package com.intentfirewall

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer

data class Tier2Result(
    val isScam: Boolean,
    val confidence: Float,
    val label: String
)

class Tier2Classifier(private val context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open("scam_classifier_int8.onnx").readBytes()
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
    }

    fun analyze(text: String): Tier2Result {
        // Fallback dummy tokenization
        val seqLen = 128
        val inputIds = LongArray(seqLen)
        val attentionMask = LongArray(seqLen)

        // Rudimentary space-based hash tokens just to feed the model
        val words = text.split(Regex("\\s+"))
        for (i in 0 until minOf(words.size, seqLen)) {
            inputIds[i] = (words[i].hashCode() % 30000).toLong().let { if (it < 0) it + 30000 else it }
            attentionMask[i] = 1L
        }
        
        val inputIdsBuffer = LongBuffer.wrap(inputIds)
        val attentionMaskBuffer = LongBuffer.wrap(attentionMask)

        return try {
            val inputIdsTensor = OnnxTensor.createTensor(env, inputIdsBuffer, longArrayOf(1, seqLen.toLong()))
            val attentionMaskTensor = OnnxTensor.createTensor(env, attentionMaskBuffer, longArrayOf(1, seqLen.toLong()))
            
            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )

            android.util.Log.d("IntentFirewall", "Starting Tier2 detection")
            val result = session.run(inputs)
            val output = result[0].value as Array<FloatArray>
            val logits = output[0] // shape [1, 2] typically
            val exp0 = Math.exp(logits[0].toDouble())
            val exp1 = Math.exp(logits[1].toDouble())
            val pScam = (exp1 / (exp0 + exp1)).toFloat()

            android.util.Log.d("IntentFirewall", "Tier2 result: isScam=${pScam >= 0.5f}, confidence=$pScam")

            // Cleanup
            inputIdsTensor.close()
            attentionMaskTensor.close()
            result.close()

            Tier2Result(isScam = pScam >= 0.5f, confidence = pScam, label = if (pScam >= 0.5f) "SCAM_SUSPECT" else "SAFE")
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("IntentFirewall", "Tier2 detection failed", e)
            // Fallback
            Tier2Result(isScam = false, confidence = 0.0f, label = "SAFE")
        }
    }
}