package com.intentfirewall

import android.content.Context
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class Tier3Result(
    val isScam: Boolean,
    val confidence: Float,
    val latencyMs: Long
)

class Tier3Classifier(private val context: Context) {
    private var interpreter: Interpreter? = null

    // Quantization parameters — read dynamically from the TFLite model
    private var quantScale: Float = 1.0f
    private var quantZeroPoint: Int = 0
    private var inputTensorIndex: Int = 0
    private var outputTensorIndex: Int = 0

    companion object {
        private const val TAG = "IntentFirewall"
        private const val MODEL_FILE = "scam_intent_classifier.tflite"
        private const val INPUT_DIM = 768
        private const val SCAM_THRESHOLD = 0.5f
    }

    init {
        try {
            val options = Interpreter.Options().apply {
                numThreads = 2
            }
            val model = loadModelFile(MODEL_FILE)
            interpreter = Interpreter(model, options).also { interp ->
                // Read quantization parameters from the model's input tensor
                val inputTensor = interp.getInputTensor(0)
                val quantParams = inputTensor.quantizationParams()
                quantScale = quantParams.scale
                quantZeroPoint = quantParams.zeroPoint.toInt()
                inputTensorIndex = 0
                outputTensorIndex = 0

                android.util.Log.d(TAG, "Tier3 model loaded: ${MODEL_FILE} " +
                    "| scale=$quantScale zp=$quantZeroPoint " +
                    "| input=${inputTensor.shape().toList()} dtype=${inputTensor.dataType()}")

                // Warm-up inference to pre-allocate memory
                warmUp(interp)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to initialize Tier3 TFLite interpreter", e)
        }
    }

    private fun warmUp(interp: Interpreter) {
        try {
            val dummyInput = ByteBuffer.allocateDirect(INPUT_DIM).order(ByteOrder.nativeOrder())
            for (i in 0 until INPUT_DIM) dummyInput.put(0.toByte())
            dummyInput.rewind()
            val dummyOutput = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            interp.run(dummyInput, dummyOutput)
            android.util.Log.d(TAG, "Tier3 warm-up complete")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Tier3 warm-up failed (non-critical)", e)
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun analyze(features: FloatArray): Tier3Result {
        val currentInterpreter = interpreter ?: return Tier3Result(isScam = false, confidence = 0.0f, latencyMs = 0L)
        val start = SystemClock.elapsedRealtimeNanos()

        if (features.isEmpty() || features.size != INPUT_DIM) {
            android.util.Log.w(TAG, "Invalid input size: ${features.size}, expected $INPUT_DIM")
            return Tier3Result(isScam = false, confidence = 0.1f, latencyMs = 0L)
        }

        // Quantize input: int8 = round(float / scale) + zero_point, clamped to [-128, 127]
        val inputBuffer = ByteBuffer.allocateDirect(INPUT_DIM).order(ByteOrder.nativeOrder())
        for (i in 0 until INPUT_DIM) {
            var quantized = Math.round(features[i] / quantScale) + quantZeroPoint
            if (quantized < -128) quantized = -128
            if (quantized > 127) quantized = 127
            inputBuffer.put(quantized.toByte())
        }
        inputBuffer.rewind()

        // Output buffer: float32 sigmoid probability [0, 1]
        val outputBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

        try {
            currentInterpreter.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Tier3 inference failed", e)
            return Tier3Result(isScam = false, confidence = 0.0f, latencyMs = 0L)
        }

        outputBuffer.rewind()
        // The new model outputs a properly calibrated sigmoid probability
        // No score hacking needed — knowledge distillation ensures calibrated outputs
        val pScam = outputBuffer.float

        val latencyMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
        android.util.Log.d(TAG, "Tier3 result: isScam=${pScam >= SCAM_THRESHOLD} confidence=${"%.4f".format(pScam)} latency=${latencyMs}ms")

        return Tier3Result(isScam = pScam >= SCAM_THRESHOLD, confidence = pScam, latencyMs = latencyMs)
    }

    fun close() {
        try {
            interpreter?.close()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to close Tier3 resources", e)
        } finally {
            interpreter = null
        }
    }
}

