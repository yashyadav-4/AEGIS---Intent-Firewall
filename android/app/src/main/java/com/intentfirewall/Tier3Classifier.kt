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
    private val interpreter: Interpreter

    // Target inputs are (1, 768) raw int8 float inputs quantized.
    private val quantScale = 0.04719424247741699f
    private val quantZeroPoint = 43

    init {
        interpreter = Interpreter(loadModelFile("tier3_classifier.tflite"), Interpreter.Options())
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
        val start = SystemClock.elapsedRealtimeNanos()

        if (features.isEmpty() || features.size != 768) {
            return Tier3Result(isScam = false, confidence = 0.1f, latencyMs = 0L)
        }

        // Quantize input
        val inputBuffer = ByteBuffer.allocateDirect(768).order(ByteOrder.nativeOrder())
        for (i in 0 until 768) {
            var quantized = Math.round(features[i] / quantScale) + quantZeroPoint
            if (quantized < -128) quantized = -128
            if (quantized > 127) quantized = 127
            inputBuffer.put(quantized.toByte())
        }
        inputBuffer.rewind()

        val outputBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

        try {
            android.util.Log.d("IntentFirewall", "Starting Tier3 detection")
            interpreter.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("IntentFirewall", "Tier3 detection failed", e)
            return Tier3Result(isScam = false, confidence = 0.0f, latencyMs = 0L)
        }

        outputBuffer.rewind()
        val score = outputBuffer.float // [1, 1] output

        val pScam = 1f / (1f + Math.exp(-score.toDouble())).toFloat()
        android.util.Log.d("IntentFirewall", "Tier3 result: isScam=${pScam >= 0.5f} confidence=$pScam")

        val latencyMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
        return Tier3Result(isScam = pScam >= 0.5f, confidence = pScam, latencyMs = latencyMs)
    }
}