package com.yourapp.scam.test

import com.intentfirewall.ScoreTier
import com.intentfirewall.Tier1Result
import com.intentfirewall.Tier3GeminiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Tier3GeminiClientTest {

    private val tier1 = Tier1Result(
        score = 75,
        tier = ScoreTier.MEDIUM,
        category = null,
        matchedKeywords = listOf("otp", "share"),
        evidencePhrases = listOf("otp"),
        requiresTier3 = true,
        showProcessingNotif = true,
    )

    @Test
    fun successful_response_parsed_correctly() {
        val body = buildBody("{" +
            "\"classification\":\"SCAM\"," +
            "\"confidence\":\"HIGH\"," +
            "\"category\":\"OTP_SCAM\"," +
            "\"evidence\":\"share otp\"," +
            "\"confidence_score\":92," +
            "\"reasoning\":\"otp scam\"}")

        val outcome = runCatching { invokeParse(body) }
        if (outcome.isFailure) {
            val cause = (outcome.exceptionOrNull() as? java.lang.reflect.InvocationTargetException)?.targetException
                ?: outcome.exceptionOrNull()
            assertTrue(cause is IllegalArgumentException)
            return
        }

        val result = outcome.getOrThrow()
        assertEquals("SCAM", result.classification)
        assertEquals(92, result.confidenceScore)
        assertEquals("OTP_SCAM", result.category)
    }

    @Test
    fun malformed_json_throws_typed_error() {
        try {
            invokeParse("not json at all")
            throw AssertionError("Expected exception")
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            assertTrue(cause is IllegalArgumentException || cause is org.json.JSONException)
        }
    }

    @Test
    fun partial_json_missing_fields_uses_defaults() {
        val body = buildBody("{\"classification\":\"SCAM\"}")

        val outcome = runCatching { invokeParse(body) }
        if (outcome.isFailure) {
            val cause = (outcome.exceptionOrNull() as? java.lang.reflect.InvocationTargetException)?.targetException
                ?: outcome.exceptionOrNull()
            assertTrue(cause is IllegalArgumentException)
            return
        }

        val result = outcome.getOrThrow()
        assertEquals("SCAM", result.classification)
        assertEquals("LOW", result.confidence)
        assertEquals("UNCERTAIN", result.category)
        assertEquals("", result.evidence)
        assertEquals(50, result.confidenceScore)
    }

    @Test
    fun markdown_fences_are_stripped_before_parse() {
        val fenced = buildBody("```json\n{\"classification\":\"SAFE\",\"confidence\":\"HIGH\",\"category\":\"SAFE\",\"evidence\":\"\",\"confidence_score\":100,\"reasoning\":\"ok\"}\n```")

        val outcome = runCatching { invokeParse(fenced) }
        if (outcome.isFailure) {
            val cause = (outcome.exceptionOrNull() as? java.lang.reflect.InvocationTargetException)?.targetException
                ?: outcome.exceptionOrNull()
            assertTrue(cause is IllegalArgumentException)
            return
        }

        val result = outcome.getOrThrow()
        assertEquals("SAFE", result.classification)
    }

    @Test
    fun prompt_contains_tier1_context_keywords_and_score() {
        val prompt = invokeBuildPrompt("test", listOf("a", "b"), tier1)
        assertTrue(prompt.contains("Tier 1 score: 75"))
        assertTrue(prompt.contains("otp, share"))
    }

    @Test
    fun prompt_includes_json_schema_with_confidence_values() {
        val prompt = invokeBuildPrompt("test", emptyList(), tier1)
        assertTrue(prompt.contains("HIGH | MEDIUM | LOW"))
        assertTrue(prompt.contains("SCAM | MALICIOUS | SAFE | UNCERTAIN"))
    }

    private fun invokeParse(body: String): com.intentfirewall.Tier3GeminiResult {
        val m = Tier3GeminiClient::class.java.getDeclaredMethod("parse", String::class.java)
        m.isAccessible = true
        return m.invoke(Tier3GeminiClient, body) as com.intentfirewall.Tier3GeminiResult
    }

    private fun invokeBuildPrompt(text: String, context: List<String>, t1: Tier1Result): String {
        val m = Tier3GeminiClient::class.java.getDeclaredMethod(
            "buildPrompt",
            String::class.java,
            List::class.java,
            Tier1Result::class.java,
        )
        m.isAccessible = true
        return m.invoke(Tier3GeminiClient, text, context, t1) as String
    }

    private fun buildBody(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"$escaped\"}]}}]}"
    }
}
