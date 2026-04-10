package com.intentfirewall

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer

data class Tier2Input(
    val message: String,
    val conversationContext: List<String>,
    val senderPhone: String,
    val senderKnown: Boolean,
    val contactName: String?,
    val messageCount: Int,
    val ruleEngineScore: Float,
    val appSource: String,
    val timestamp: Long,
)

data class Tier2Result(
    val isScam: Boolean,
    val score: Float,
    val confidence: Float,
    val reason: String,
    val threatType: String,
    val label: String,
)

class Tier2Classifier(private val context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open("scam_classifier_int8.onnx").readBytes()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        session = env.createSession(modelBytes, opts)
        Log.i("IntentFirewall|Tier2", "Tier2 model loaded (${modelBytes.size} bytes)")
    }

    fun close() {
        session.close()
        Log.i("IntentFirewall|Tier2", "Tier2 model unloaded")
    }

    fun analyze(input: Tier2Input): Tier2Result {
        val message = buildModelText(input)
        val messageSnippet = snippetForLog(input.message)

        // Fallback dummy tokenization
        val seqLen = 128
        val inputIds = LongArray(seqLen)
        val attentionMask = LongArray(seqLen)

        // Rudimentary space-based hash tokens just to feed the model
        val words = message.split(Regex("\\s+"))
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

            android.util.Log.d(
                "IntentFirewall",
                "Starting Tier2 detection for message=\"$messageSnippet\""
            )
            val result = session.run(inputs)
            val output = result[0].value as Array<FloatArray>
            val logits = output[0] // shape [1, 2] typically
            val exp0 = Math.exp(logits[0].toDouble())
            val exp1 = Math.exp(logits[1].toDouble())
            val pScam = (exp1 / (exp0 + exp1)).toFloat()

            android.util.Log.d(
                "IntentFirewall",
                "Tier2 result: isScam=${pScam >= 0.5f}, confidence=$pScam, message=\"$messageSnippet\""
            )

            // Cleanup
            inputIdsTensor.close()
            attentionMaskTensor.close()
            result.close()

            val reason = when {
                pScam >= 0.8f -> "High scam probability from on-device model"
                pScam >= 0.6f -> "Multiple suspicious linguistic cues"
                pScam >= 0.4f -> "Mixed benign and suspicious signals"
                else -> "Mostly benign linguistic profile"
            }

            val threatType = when {
                pScam >= 0.75f && message.contains("otp", ignoreCase = true) -> "OTP_FRAUD"
                pScam >= 0.75f && message.contains("kyc", ignoreCase = true) -> "KYC_SCAM"
                pScam >= 0.75f && message.contains("upi", ignoreCase = true) -> "UPI_FRAUD"
                pScam >= 0.6f -> "SOCIAL_ENGINEERING"
                else -> "NONE"
            }

            Tier2Result(
                isScam = pScam >= 0.5f,
                score = pScam,
                confidence = pScam,
                reason = reason,
                threatType = threatType,
                label = if (pScam >= 0.5f) "SCAM_SUSPECT" else "SAFE",
            )
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("IntentFirewall", "Tier2 detection failed", e)
            // Fallback
            Tier2Result(
                isScam = false,
                score = 0.0f,
                confidence = 0.0f,
                reason = "Tier2 inference failed; fallback to safe",
                threatType = "NONE",
                label = "SAFE",
            )
        }
    }

    private fun buildModelText(input: Tier2Input): String {
        val contextSlice = input.conversationContext.takeLast(5).joinToString(" ")
        val knownToken = if (input.senderKnown) "known_sender" else "unknown_sender"
        val appToken = input.appSource.lowercase().replace("\\s+".toRegex(), "_")
        return "${input.message} [ctx] $contextSlice [meta] $knownToken app_$appToken count_${input.messageCount} rule_${"%.2f".format(input.ruleEngineScore)}"
    }

    private fun snippetForLog(text: String): String {
        val normalized = text
            .replace("\\s+".toRegex(), " ")
            .trim()
        if (normalized.isEmpty()) return "[empty]"
        return if (normalized.length <= 140) {
            normalized
        } else {
            normalized.substring(0, 140) + "..."
        }
    }
}