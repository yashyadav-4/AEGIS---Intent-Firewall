package com.intentfirewall

// Tier 2 — DistilBERT scam classifier (64MB ONNX INT8)
// Input: text string (context + response, max 256 tokens)
// Output: Tier2Result with score and label
// RAM: ~75MB loaded, evicted after 5min idle

import android.content.Context
import android.os.SystemClock
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicLong

class Tier2Classifier(private val context: Context) {

    data class Tier2Result(
        val isScam: Boolean,
        val confidence: Float,
        val label: String,
        val latencyMs: Long,
        val shouldEscalate: Boolean
    )

    companion object {
        const val IS_STUB = false
        private const val ASSET_NAME = "scam_classifier_int8.onnx"
        private const val THRESHOLD = 0.5f
        private const val ESCALATE_MIN = 0.6f
        private const val ESCALATE_MAX = 0.9f
        private const val IDLE_EVICT_MS = 5 * 60 * 1000L
        private const val MAX_LENGTH = 256
        private val TAG = "AegisT2"
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val lastUsedMs = AtomicLong(0L)
    private val tokenizer: WordPieceTokenizer by lazy { WordPieceTokenizer(context) }

    private fun loadSession() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(ASSET_NAME).readBytes()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }
            ortSession = ortEnv!!.createSession(modelBytes, opts)
            Log.d(TAG, "ONNX session loaded: $ASSET_NAME")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX session: ${e.message}")
        }
    }

    private fun maybeEvict() {
        if (ortSession != null &&
            System.currentTimeMillis() - lastUsedMs.get() > IDLE_EVICT_MS) {
            ortSession?.close()
            ortEnv?.close()
            ortSession = null
            ortEnv = null
            Log.d(TAG, "ONNX session evicted after idle")
        }
    }

    fun analyze(text: String): Tier2Result {
        val t0 = SystemClock.elapsedRealtimeNanos()

        maybeEvict()
        if (ortSession == null) loadSession()
        lastUsedMs.set(System.currentTimeMillis())

        // Fallback to keyword scoring if session failed to load
        if (ortSession == null) {
            return keywordFallback(text, t0)
        }

        return try {
            val inputIds = tokenizer.tokenize(text, maxLen = 128)
            val attentionMask = LongArray(128) { i ->
                if (inputIds[i] != 0L) 1L else 0L
            }

            val env = ortEnv!!
            val inputIdsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(inputIds),
                longArrayOf(1, 128L)
            )
            val attentionTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(attentionMask),
                longArrayOf(1, 128L)
            )

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionTensor
            )

            val output = ortSession!!.run(inputs)
            val logits = (output[0].value as Array<*>)[0] as FloatArray

            inputIdsTensor.close()
            attentionTensor.close()
            output.close()

            // Softmax over 2 logits -> scam probability
            val expScam = Math.exp(logits[1].toDouble())
            val expBenign = Math.exp(logits[0].toDouble())
            val score = (expScam / (expScam + expBenign)).toFloat()

            val latencyMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L
            Log.d(TAG, "score=$score latency=${latencyMs}ms")

            Tier2Result(
                isScam = score >= THRESHOLD,
                confidence = score,
                label = if (score >= THRESHOLD) "SCAM" else "BENIGN",
                latencyMs = latencyMs,
                shouldEscalate = score in ESCALATE_MIN..ESCALATE_MAX
            )

        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference failed: ${e.message}")
            keywordFallback(text, t0)
        }
    }

    private fun keywordFallback(text: String, t0: Long): Tier2Result {
        val lower = text.lowercase()
        val keywords = listOf(
            "otp", "kyc", "blocked", "suspended", "urgent",
            "send money", "upi", "verify", "arrest", "warrant",
            "gift card", "lottery", "won", "prize", "bit.ly"
        )
        val hits = keywords.count { lower.contains(it) }
        val score = (hits * 0.15f).coerceIn(0f, 1f)
        val latencyMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L
        return Tier2Result(
            isScam = score >= THRESHOLD,
            confidence = score,
            label = if (score >= THRESHOLD) "SCAM" else "BENIGN",
            latencyMs = latencyMs,
            shouldEscalate = score in ESCALATE_MIN..ESCALATE_MAX
        )
    }

    fun close() {
        ortSession?.close()
        ortEnv?.close()
        ortSession = null
        ortEnv = null
    }

    private inner class WordPieceTokenizer {
        private val vocab: Map<String, Int>
        private val unkId = 100
        private val clsId = 101
        private val sepId = 102
        private val maxInputChars = 100

        init {
            val vocabMap = mutableMapOf<String, Int>()
            context.assets.open("vocab.txt")
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines ->
                    lines.forEachIndexed { idx, token ->
                        vocabMap[token.trim()] = idx
                    }
                }
            vocab = vocabMap
            Log.d("AegisT2", "WordPiece vocab loaded: ${vocab.size} tokens")
        }

        fun tokenize(text: String, maxLen: Int = 128): LongArray {
            val tokens = mutableListOf<Int>()
            tokens.add(clsId)

            val cleaned = text.lowercase()
                .replace(Regex("[^a-z0-9\\s.,!?'\\-]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            for (word in cleaned.split(" ")) {
                if (word.isEmpty()) continue
                val wordTokens = tokenizeWord(word)
                if (tokens.size + wordTokens.size + 1 > maxLen) break
                tokens.addAll(wordTokens)
            }

            tokens.add(sepId)

            val result = LongArray(maxLen) { 0L }
            tokens.forEachIndexed { i, id ->
                if (i < maxLen) result[i] = id.toLong()
            }
            return result
        }

        private fun tokenizeWord(word: String): List<Int> {
            if (word.length > maxInputChars) return listOf(unkId)
            val subTokens = mutableListOf<Int>()
            var start = 0
            var isBad = false

            while (start < word.length) {
                var end = word.length
                var foundId = -1

                while (start < end) {
                    val substr = if (start == 0) {
                        word.substring(start, end)
                    } else {
                        "##" + word.substring(start, end)
                    }
                    val id = vocab[substr]
                    if (id != null) {
                        foundId = id
                        break
                    }
                    end--
                }

                if (foundId == -1) {
                    isBad = true
                    break
                }
                subTokens.add(foundId)
                start = end
            }

            return if (isBad) listOf(unkId) else subTokens
        }
    }
}
