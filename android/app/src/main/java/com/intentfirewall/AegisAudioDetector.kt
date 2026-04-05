package com.intentfirewall

// Gradle dependencies:
// implementation 'org.tensorflow:tensorflow-lite:2.14.0'
// implementation 'org.tensorflow:tensorflow-lite-gpu:2.14.0'
// implementation 'com.github.wendykierp:JTransforms:3.1'

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import org.jtransforms.fft.DoubleFFT_1D
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Deployment assets (WavLM path):
// - wavlm_student.tflite: INT8 input raw waveform (1, 32000) -> FLOAT32 features (1, 128)
// - detector_wavlm.tflite: INT8 input scaled 128-dim features -> INT8 score output

private const val SAMPLE_RATE = 16000
private const val WAVEFORM_SIZE = 32000
private const val FLAT_PITCH_THRESHOLD = 0.15f

data class ScalerParams(
    val mean: FloatArray,
    val scale: FloatArray
)

data class DetectionResult(
    val isSynthetic: Boolean,
    val confidence: Float,
    val phaseScore: Float,
    val glottalScore: Float,
    val wavlmScore: Float,
    val latencyMs: Long
)

class AegisAudioDetector(private val context: Context) {

    private val delegates = mutableListOf<NnApiDelegate>()

    private val phaseInterpreter: Interpreter
    private val glottalInterpreter: Interpreter
    private val wavlmStudentInterpreter: Interpreter
    private val wavlmInterpreter: Interpreter
    private val ensembleInterpreter: Interpreter

    private var metadataHeuristicFired = false
    private var spectralEnergyFired = false

    private lateinit var phaseScaler: ScalerParams
    private lateinit var glottalScaler: ScalerParams
    private lateinit var wavlmScaler: ScalerParams

    init {
        phaseInterpreter = loadInterpreter("detector_phase.tflite", createOptions())
        phaseInterpreter.allocateTensors()

        glottalInterpreter = loadInterpreter("detector_glottal.tflite", createOptions())
        glottalInterpreter.allocateTensors()

        // Student model is tiny (~0.15MB), so keep it eagerly loaded.
        wavlmStudentInterpreter = loadInterpreter("wavlm_student.tflite", createOptions())
        wavlmStudentInterpreter.allocateTensors()

        wavlmInterpreter = loadInterpreter("detector_wavlm.tflite", createOptions())
        wavlmInterpreter.allocateTensors()

        ensembleInterpreter = loadInterpreter("ensemble.tflite", createOptions())
        ensembleInterpreter.allocateTensors()

        // Load scalers (from assets or hardcoded defaults)
        phaseScaler = loadScaler("phase_scaler.json") ?: getDefaultPhaseScaler()
        glottalScaler = loadScaler("glottal_scaler.json") ?: getDefaultGlottalScaler()
        wavlmScaler = loadScaler("wavlm_scaler.json") ?: getDefaultWavLMScaler()
    }

    /**
     * Updates metadata-trigger state from call context.
     * Signal fires when either unknown caller or video-call context is present.
     */
    fun onCallMetadataUpdate(isUnknownNumber: Boolean, isVideoCall: Boolean) {
        metadataHeuristicFired = isUnknownNumber || isVideoCall
    }

    /**
     * Updates spectral trigger state from passive pitch-variance monitoring.
     * Signal fires when pitch variance is flatter than the threshold.
     */
    fun onSpectralEnergyUpdate(pitchVariance: Float) {
        spectralEnergyFired = pitchVariance < FLAT_PITCH_THRESHOLD
    }

    /**
     * Returns true only when both metadata and spectral trigger signals are active.
     */
    fun shouldActivate(): Boolean {
        return metadataHeuristicFired && spectralEnergyFired
    }

    private fun loadScaler(fileName: String): ScalerParams? {
        return try {
            val inputStream = context.assets.open(fileName)
            val json = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()
            parseScalerJson(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseScalerJson(json: String): ScalerParams? {
        return try {
            val obj = JSONObject(json)
            val meanArray = obj.getJSONArray("mean")
            val scaleArray = obj.getJSONArray("scale")

            val mean = FloatArray(meanArray.length()) { i ->
                meanArray.getDouble(i).toFloat()
            }
            val scale = FloatArray(scaleArray.length()) { i ->
                scaleArray.getDouble(i).toFloat()
            }

            if (mean.isNotEmpty() && scale.isNotEmpty() && mean.size == scale.size) {
                ScalerParams(mean, scale)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getDefaultPhaseScaler(): ScalerParams {
        // Fallback: identity scaler (mean=0, scale=1)
        return ScalerParams(
            FloatArray(64) { 0f },
            FloatArray(64) { 1f }
        )
    }

    private fun getDefaultGlottalScaler(): ScalerParams {
        // Fallback: identity scaler (mean=0, scale=1)
        return ScalerParams(
            FloatArray(12) { 0f },
            FloatArray(12) { 1f }
        )
    }

    private fun getDefaultWavLMScaler(): ScalerParams {
        // Fallback: identity scaler (mean=0, scale=1)
        return ScalerParams(
            FloatArray(128) { 0f },
            FloatArray(128) { 1f }
        )
    }

    private fun applyScaler(features: FloatArray, scaler: ScalerParams): FloatArray {
        return FloatArray(features.size) { i ->
            val scale = if (abs(scaler.scale[i]) < 1e-9f) 1f else scaler.scale[i]
            (features[i] - scaler.mean[i]) / scale
        }
    }

    /**
     * Runs full three-detector + ensemble inference on a 2-second normalized waveform.
     * Call this from a background thread (Dispatchers.Default), never on Main.
     */
    fun analyze(waveform: FloatArray): DetectionResult {
        val t0 = SystemClock.elapsedRealtimeNanos()

        val normalized = normalizeWaveform(waveform)

        val phaseFeatures = extractLfccFeatures(normalized)
        val phaseScaled = applyScaler(phaseFeatures, phaseScaler)
        
        val glottalFeatures = extractGlottalFeatures(normalized)
        val glottalScaled = applyScaler(glottalFeatures, glottalScaler)

        val phaseScore = runInt8SingleOutput(phaseInterpreter, phaseScaled)
        val glottalScore = runInt8SingleOutput(glottalInterpreter, glottalScaled)

        // Student: raw waveform -> 128-dim WavLM approximation
        val wavlmRaw = extractWavLMStudentFeatures(normalized)

        // Apply scaler to student output
        val wavlmScaled = applyScaler(wavlmRaw, wavlmScaler)

        // Linear probe: 128-dim -> single score
        val wavlmScore = runInt8SingleOutput(wavlmInterpreter, wavlmScaled)

        val fusionInput = floatArrayOf(
            phaseScore, glottalScore, wavlmScore,
            phaseScore * glottalScore,
            phaseScore * wavlmScore,
            glottalScore * wavlmScore
        )
        val finalScore = runInt8SingleOutput(ensembleInterpreter, fusionInput)

        val latencyMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L
        return DetectionResult(
            isSynthetic = finalScore > 0.5f,
            confidence = finalScore,
            phaseScore = phaseScore,
            glottalScore = glottalScore,
            wavlmScore = wavlmScore,
            latencyMs = latencyMs
        )
    }

    /**
     * Closes interpreters and delegates. Call when detection module is no longer needed.
     */
    fun close() {
        phaseInterpreter.close()
        glottalInterpreter.close()
        wavlmStudentInterpreter.close()
        wavlmInterpreter.close()
        ensembleInterpreter.close()

        delegates.forEach { delegate ->
            try {
                delegate.close()
            } catch (_: Exception) {
            }
        }
        delegates.clear()
    }

    private fun extractWavLMStudentFeatures(waveform: FloatArray): FloatArray {
        val inputTensor = wavlmStudentInterpreter.getInputTensor(0)
        val outputTensor = wavlmStudentInterpreter.getOutputTensor(0)

        val inScale = quantScale(inputTensor)
        val inZero = quantZeroPoint(inputTensor)

        val quantizedWaveform = ByteArray(WAVEFORM_SIZE)
        for (i in 0 until WAVEFORM_SIZE) {
            val q = (waveform[i] / inScale + inZero).roundToInt().coerceIn(-128, 127)
            quantizedWaveform[i] = q.toByte()
        }

        val inputBuffer = ByteBuffer.allocateDirect(WAVEFORM_SIZE).order(ByteOrder.nativeOrder())
        inputBuffer.put(quantizedWaveform)
        inputBuffer.rewind()

        val outputBuffer = ByteBuffer.allocateDirect(128 * 4).order(ByteOrder.nativeOrder())
        wavlmStudentInterpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val features = FloatArray(128)
        outputBuffer.asFloatBuffer().get(features)
        return features
    }

    private fun createOptions(): Interpreter.Options {
        val options = Interpreter.Options().apply {
            setNumThreads(2)
        }

        val nnApiDelegate = try {
            NnApiDelegate()
        } catch (_: Exception) {
            null
        }

        if (nnApiDelegate != null) {
            options.addDelegate(nnApiDelegate)
            delegates.add(nnApiDelegate)
        }

        return options
    }

    private fun runInt8SingleOutput(interpreter: Interpreter, input: FloatArray): Float {
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)

        val inScale = quantScale(inputTensor)
        val inZero = quantZeroPoint(inputTensor)
        val outScale = quantScale(outputTensor)
        val outZero = quantZeroPoint(outputTensor)

        val quantizedInput = quantizeInput(input, inScale, inZero)
        val inputBuffer = ByteBuffer.allocateDirect(quantizedInput.size).order(ByteOrder.nativeOrder())
        inputBuffer.put(quantizedInput)
        inputBuffer.rewind()

        val rawOutput = ByteArray(1)
        val outputBuffer = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())

        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()
        outputBuffer.get(rawOutput)

        return dequantize(rawOutput, outScale, outZero)
    }

    private fun quantScale(tensor: Tensor): Float {
        val scale = tensor.quantizationParams().scale
        return if (scale == 0.0f) 1.0f else scale
    }

    private fun quantZeroPoint(tensor: Tensor): Int {
        return tensor.quantizationParams().zeroPoint
    }

    private fun normalizeWaveform(waveform: FloatArray): FloatArray {
        val output = FloatArray(WAVEFORM_SIZE)
        val copyLen = min(waveform.size, WAVEFORM_SIZE)
        for (i in 0 until copyLen) {
            output[i] = waveform[i]
        }

        var maxAbs = 0.0f
        for (v in output) {
            val av = abs(v)
            if (av > maxAbs) maxAbs = av
        }
        if (maxAbs > 0f) {
            for (i in output.indices) {
                output[i] /= maxAbs
            }
        }
        return output
    }

    private fun extractLfccFeatures(waveform: FloatArray): FloatArray {
        val sr = 16000
        val nFft = 512
        val hop = 160
        val nFilters = 20
        val nCoeffs = 20

        // Pre-emphasis
        val pre = FloatArray(waveform.size)
        pre[0] = waveform[0]
        for (i in 1 until waveform.size) {
            pre[i] = waveform[i] - 0.97f * waveform[i - 1]
        }

        // Frame the signal with Hamming window
        val frames = mutableListOf<DoubleArray>()
        var start = 0
        while (start + nFft <= pre.size) {
            val frame = DoubleArray(nFft)
            for (i in 0 until nFft) {
                val win = 0.54 - 0.46 * kotlin.math.cos(
                    2.0 * Math.PI * i / (nFft - 1))
                frame[i] = pre[start + i].toDouble() * win
            }
            frames.add(frame)
            start += hop
        }

        if (frames.isEmpty()) return FloatArray(64) { 0f }

        // Power spectrum via JTransforms FFT
        val fft = DoubleFFT_1D(nFft.toLong())
        val powerSpectra = Array(frames.size) { DoubleArray(nFft / 2 + 1) }
        for ((idx, frame) in frames.withIndex()) {
            val full = DoubleArray(2 * nFft)
            for (i in frame.indices) full[i] = frame[i]
            fft.realForwardFull(full)
            for (k in 0..nFft / 2) {
                val re = full[2 * k]
                val im = full[2 * k + 1]
                powerSpectra[idx][k] = re * re + im * im
            }
        }

        // Linear filterbank
        val freqBins = DoubleArray(nFft / 2 + 1) { k ->
            k.toDouble() * sr / nFft }
        val centers = DoubleArray(nFilters + 2) { m ->
            m.toDouble() * (sr / 2.0) / (nFilters + 1) }
        val filterbank = Array(nFilters) { m ->
            DoubleArray(nFft / 2 + 1) { k ->
                val f = freqBins[k]
                when {
                    f in centers[m]..centers[m + 1] ->
                        (f - centers[m]) / (centers[m + 1] - centers[m])
                    f in centers[m + 1]..centers[m + 2] ->
                        (centers[m + 2] - f) / (centers[m + 2] - centers[m + 1])
                    else -> 0.0
                }
            }
        }

        // Apply filterbank and log
        val logEnergies = Array(frames.size) { t ->
            DoubleArray(nFilters) { m ->
                val energy = powerSpectra[t].zip(filterbank[m].toList())
                    .sumOf { (p, h) -> p * h }
                kotlin.math.ln(energy + 1e-8)
            }
        }

        // DCT (Type-II) for cepstral coefficients
        val T = frames.size
        val lfcc = Array(T) { t ->
            DoubleArray(nCoeffs) { n ->
                var sum = 0.0
                for (m in 0 until nFilters) {
                    sum += logEnergies[t][m] *
                        kotlin.math.cos(Math.PI * n * (m + 0.5) / nFilters)
                }
                sum * kotlin.math.sqrt(2.0 / nFilters)
            }
        }

        // Delta computation helper
        fun delta(feat: Array<DoubleArray>, N: Int = 2): Array<DoubleArray> {
            val denom = 2.0 * (1..N).sumOf { it * it }
            return Array(feat.size) { t ->
                DoubleArray(feat[0].size) { c ->
                    var num = 0.0
                    for (n in 1..N) {
                        val ahead = minOf(t + n, feat.size - 1)
                        val behind = maxOf(t - n, 0)
                        num += n * (feat[ahead][c] - feat[behind][c])
                    }
                    num / denom
                }
            }
        }

        val delta1 = delta(lfcc)
        val delta2 = delta(delta1)

        // Aggregate: mean of static + delta + delta2 = 60 dims
        // Plus 4 global energy stats = 64 dims total
        val mean = DoubleArray(60)
        for (t in 0 until T) {
            for (c in 0 until nCoeffs) {
                mean[c]            += lfcc[t][c]
                mean[c + nCoeffs]  += delta1[t][c]
                mean[c + 2*nCoeffs]+= delta2[t][c]
            }
        }
        for (i in mean.indices) mean[i] /= T

        // Global energy stats from power spectra
        val frameEnergies = DoubleArray(frames.size) { t ->
            powerSpectra[t].average()
        }
        val eMean = frameEnergies.average()
        val eStd  = kotlin.math.sqrt(
            frameEnergies.map { (it - eMean).pow(2.0) }.average())
        val eMax  = frameEnergies.max()
        val eMin  = frameEnergies.min()

        // Final 64-dim output: first 30 of mean + first 30 of std + 4 energy stats
        val std = DoubleArray(60)
        for (t in 0 until T) {
            for (c in 0 until nCoeffs) {
                std[c]            += (lfcc[t][c]    - mean[c]).pow(2.0)
                std[c + nCoeffs]  += (delta1[t][c]  - mean[c+nCoeffs]).pow(2.0)
                std[c + 2*nCoeffs]+= (delta2[t][c]  - mean[c+2*nCoeffs]).pow(2.0)
            }
        }
        for (i in std.indices) std[i] = kotlin.math.sqrt(std[i] / T)

        val out = FloatArray(64)
        for (i in 0 until 30) out[i]      = mean[i].toFloat()
        for (i in 0 until 30) out[i + 30] = std[i].toFloat()
        out[60] = eMean.toFloat()
        out[61] = eStd.toFloat()
        out[62] = eMax.toFloat()
        out[63] = eMin.toFloat()

        return out
    }

    private fun estimateF0Autocorr(waveform: FloatArray): Double {
        val centered = DoubleArray(waveform.size)
        var mean = 0.0
        for (v in waveform) mean += v
        mean /= waveform.size.coerceAtLeast(1)

        var maxAbs = 0.0
        for (i in waveform.indices) {
            centered[i] = waveform[i] - mean
            maxAbs = max(maxAbs, abs(centered[i]))
        }
        if (maxAbs < 1e-8) return 120.0

        val minF0 = 50.0
        val maxF0 = 500.0
        val minLag = (SAMPLE_RATE / maxF0).toInt()
        val maxLag = (SAMPLE_RATE / minF0).toInt()

        var bestLag = minLag
        var bestCorr = Double.NEGATIVE_INFINITY

        for (lag in minLag..maxLag) {
            var corr = 0.0
            var i = 0
            while (i + lag < centered.size) {
                corr += centered[i] * centered[i + lag]
                i++
            }
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        return SAMPLE_RATE.toDouble() / bestLag.toDouble()
    }

    private fun extractGlottalFeatures(waveform: FloatArray): FloatArray {
        val gcis = detectApproxGci(waveform)
        val gciIndices = if (gcis.size < 4) {
            fallbackPeriodicGci(waveform)
        } else {
            gcis
        }

        if (gciIndices.size < 4) {
            return FloatArray(12) { 0f }
        }

        val intervals = FloatArray(gciIndices.size - 1)
        for (i in 1 until gciIndices.size) {
            intervals[i - 1] = (gciIndices[i] - gciIndices[i - 1]).toFloat() / SAMPLE_RATE.toFloat()
        }

        val mean = mean(intervals)
        val variance = variance(intervals, mean)
        val skew = skewness(intervals, mean, variance)
        val kurt = kurtosis(intervals, mean, variance)

        val ac1 = lagAutocorr(intervals, 1)
        val ac2 = lagAutocorr(intervals, 2)
        val ac3 = lagAutocorr(intervals, 3)

        val dfa = dfaAlpha(intervals)
        val apEn = approximateEntropy(intervals)
        val shimmer = shimmerAtGci(waveform, gciIndices)

        val sorted = intervals.sortedArray()
        val q25 = sorted[(0.25f * (sorted.size - 1)).roundToInt()]
        val q75 = sorted[(0.75f * (sorted.size - 1)).roundToInt()]
        val iqr = q75 - q25
        val gciRate = gciIndices.size.toFloat() / (waveform.size.toFloat() / SAMPLE_RATE.toFloat())

        return floatArrayOf(
            mean,
            variance,
            skew,
            kurt,
            ac1,
            ac2,
            ac3,
            dfa,
            apEn,
            shimmer,
            iqr,
            gciRate
        )
    }

    private fun detectApproxGci(waveform: FloatArray): IntArray {
        val frame = 128
        val hop = 64
        if (waveform.size < frame) return IntArray(0)

        val energy = ArrayList<Float>()
        var start = 0
        while (start + frame <= waveform.size) {
            var e = 0f
            for (i in 0 until frame) {
                val v = waveform[start + i]
                e += v * v
            }
            energy.add(e / frame)
            start += hop
        }

        if (energy.size < 5) return IntArray(0)

        val minima = ArrayList<Int>()
        for (i in 1 until energy.size - 1) {
            if (energy[i] <= energy[i - 1] && energy[i] <= energy[i + 1]) {
                minima.add(i * hop + frame / 2)
            }
        }

        return minima.toIntArray()
    }

    private fun fallbackPeriodicGci(waveform: FloatArray): IntArray {
        val f0 = estimateF0Autocorr(waveform)
        val period = max(1, (SAMPLE_RATE / f0).roundToInt())
        val indices = ArrayList<Int>()
        var p = period
        while (p < waveform.size) {
            indices.add(p)
            p += period
        }
        return indices.toIntArray()
    }

    private fun mean(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var s = 0f
        for (v in x) s += v
        return s / x.size
    }

    private fun variance(x: FloatArray, m: Float): Float {
        if (x.isEmpty()) return 0f
        var s = 0f
        for (v in x) {
            val d = v - m
            s += d * d
        }
        return s / x.size
    }

    private fun skewness(x: FloatArray, m: Float, varValue: Float): Float {
        if (x.size < 3 || varValue <= 1e-12f) return 0f
        val std = sqrt(varValue)
        var s = 0f
        for (v in x) {
            val z = (v - m) / std
            s += z * z * z
        }
        return s / x.size
    }

    private fun kurtosis(x: FloatArray, m: Float, varValue: Float): Float {
        if (x.size < 4 || varValue <= 1e-12f) return 0f
        val std = sqrt(varValue)
        var s = 0f
        for (v in x) {
            val z = (v - m) / std
            s += z * z * z * z
        }
        return s / x.size - 3f
    }

    private fun lagAutocorr(x: FloatArray, lag: Int): Float {
        if (x.size <= lag) return 0f
        val m = mean(x)
        var denom = 0f
        for (v in x) {
            val d = v - m
            denom += d * d
        }
        if (denom <= 1e-12f) return 0f

        var num = 0f
        for (i in 0 until x.size - lag) {
            num += (x[i] - m) * (x[i + lag] - m)
        }
        return num / denom
    }

    private fun dfaAlpha(x: FloatArray): Float {
        if (x.size < 16) return 0.5f
        val m = mean(x)
        val y = FloatArray(x.size)
        var csum = 0f
        for (i in x.indices) {
            csum += x[i] - m
            y[i] = csum
        }

        val scales = intArrayOf(4, 6, 8, 12, 16, 24, 32)
        val logS = ArrayList<Float>()
        val logF = ArrayList<Float>()

        for (s in scales) {
            if (s >= y.size) continue
            val nSeg = y.size / s
            if (nSeg < 2) continue

            var rmsAccum = 0.0
            for (seg in 0 until nSeg) {
                val start = seg * s
                var sumT = 0.0
                var sumY = 0.0
                var sumTT = 0.0
                var sumTY = 0.0
                for (i in 0 until s) {
                    val t = i.toDouble()
                    val yy = y[start + i].toDouble()
                    sumT += t
                    sumY += yy
                    sumTT += t * t
                    sumTY += t * yy
                }
                val denom = s * sumTT - sumT * sumT
                val a = if (abs(denom) < 1e-12) 0.0 else (s * sumTY - sumT * sumY) / denom
                val b = (sumY - a * sumT) / s

                var mse = 0.0
                for (i in 0 until s) {
                    val t = i.toDouble()
                    val trend = a * t + b
                    val diff = y[start + i] - trend
                    mse += diff * diff
                }
                rmsAccum += kotlin.math.sqrt(mse / s)
            }

            val f = (rmsAccum / nSeg).toFloat()
            if (f > 0f) {
                logS.add(ln(s.toFloat()))
                logF.add(ln(f))
            }
        }

        if (logS.size < 2) return 0.5f

        val ms = mean(logS.toFloatArray())
        val mf = mean(logF.toFloatArray())
        var num = 0f
        var den = 0f
        for (i in logS.indices) {
            val ds = logS[i] - ms
            num += ds * (logF[i] - mf)
            den += ds * ds
        }
        return if (den <= 1e-12f) 0.5f else num / den
    }

    private fun approximateEntropy(x: FloatArray): Float {
        if (x.size < 4) return 0f
        val sorted = x.copyOf()
        sorted.sort()
        var entropy = 0f
        val n = sorted.size
        for (i in 1 until n) {
            val diff = sorted[i] - sorted[i - 1]
            if (diff > 1e-8f) entropy += diff * ln(diff)
        }
        return -entropy
    }

    private fun shimmerAtGci(waveform: FloatArray, gciIndices: IntArray): Float {
        if (gciIndices.size < 2) return 0f

        val amps = FloatArray(gciIndices.size)
        for (i in gciIndices.indices) {
            val idx = gciIndices[i].coerceIn(0, waveform.size - 1)
            amps[i] = abs(waveform[idx])
        }

        val meanAmp = mean(amps)
        if (meanAmp <= 1e-8f) return 0f

        var diffMean = 0f
        for (i in 1 until amps.size) {
            diffMean += abs(amps[i] - amps[i - 1])
        }
        diffMean /= (amps.size - 1)
        return diffMean / meanAmp
    }

    private fun loadInterpreter(assetName: String, options: Interpreter.Options): Interpreter {
        val mapped = loadModelFile(assetName)
        return Interpreter(mapped, options)
    }

    private fun loadModelFile(assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { inputStream ->
            val channel = inputStream.channel
            return channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        }
    }

    private fun dequantize(rawOutput: ByteArray, scale: Float, zeroPoint: Int): Float {
        return (rawOutput[0].toInt() - zeroPoint) * scale
    }

    private fun quantizeInput(floatArray: FloatArray, scale: Float, zeroPoint: Int): ByteArray {
        val safeScale = if (abs(scale) < 1e-12f) 1.0f else scale
        return ByteArray(floatArray.size) { i ->
            val q = (floatArray[i] / safeScale + zeroPoint.toFloat())
                .roundToInt()
                .coerceIn(-128, 127)
            q.toByte()
        }
    }
}
