// Tier 3 — Distilled scam classifier (219KB TFLite INT8)
// Input: 768-dim DistilBERT CLS hidden state from Tier 2
// Output: sigmoid score >0.5 = SCAM
// RAM: ~15MB loaded, evicted after 5min idle
package com.intentfirewall

import android.content.Context
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong

class Tier3Classifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val lastUsedMs = AtomicLong(0L)
    private val IDLE_EVICT_MS = 5 * 60 * 1000L  // 5 minutes
    private val ASSET_NAME = "tier3_classifier.tflite"
    private val THRESHOLD = 0.5f

    data class Tier3Result(
        val isScam: Boolean,
        val confidence: Float,
        val latencyMs: Long
    )

    private fun loadInterpreter() {
        val loadedInterpreter = context.assets.openFd(ASSET_NAME).use { afd ->
            val modelBuffer: MappedByteBuffer = FileInputStream(afd.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
            }
            Interpreter(
                modelBuffer,
                Interpreter.Options().setNumThreads(2)
            )
        }

        loadedInterpreter.allocateTensors()
        interpreter = loadedInterpreter
    }

    private fun maybeEvict() {
        val nowMs = System.currentTimeMillis()
        if (interpreter != null && nowMs - lastUsedMs.get() > IDLE_EVICT_MS) {
            interpreter?.close()
            interpreter = null
        }
    }

    fun analyze(features: FloatArray): Tier3Result {
        val t0 = SystemClock.elapsedRealtimeNanos()

        maybeEvict()
        if (interpreter == null) {
            loadInterpreter()
        }

        lastUsedMs.set(System.currentTimeMillis())

        val input = Array(1) { features }
        val output = Array(1) { FloatArray(1) }

        interpreter!!.run(input, output)

        val score = output[0][0]
        val latencyMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L

        return Tier3Result(
            isScam = score > THRESHOLD,
            confidence = score,
            latencyMs = latencyMs
        )
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
