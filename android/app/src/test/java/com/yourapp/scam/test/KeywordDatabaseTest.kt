package com.yourapp.scam.test

import com.intentfirewall.ScamKeywordDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordDatabaseTest {

    @Test
    fun all_11_categories_present() {
        val ids = ScamKeywordDatabase.categories.map { it.category.id }
        assertTrue(ids.containsAll(listOf(
            "OTP_SCAM", "KYC_SCAM", "LOTTERY_SCAM", "JOB_SCAM", "LOAN_SCAM",
            "INVESTMENT_SCAM", "IMPERSONATION_SCAM", "ROMANCE_SCAM",
            "TECH_SUPPORT_SCAM", "COURIER_SCAM", "UTILITY_SCAM"
        )))
    }

    @Test
    fun no_category_has_empty_keyword_lists() {
        ScamKeywordDatabase.categories.forEach { cat ->
            assertTrue("${cat.category.id} high empty", cat.high.isNotEmpty())
            assertTrue("${cat.category.id} hinglish empty", cat.hinglish.isNotEmpty())
        }
    }

    @Test
    fun no_duplicate_keywords_within_category() {
        ScamKeywordDatabase.categories.forEach { cat ->
            val all = (cat.high + cat.medium + cat.low + cat.hinglish).toList()
            assertEquals("${cat.category.id} duplicates", all.size, all.distinct().size)
        }
    }

    @Test
    fun all_keywords_are_lowercase() {
        ScamKeywordDatabase.categories.forEach { cat ->
            (cat.high + cat.medium + cat.low + cat.hinglish).forEach { kw ->
                assertEquals(kw.lowercase(), kw)
            }
        }
    }

    @Test
    fun require_all_groups_have_non_empty_tokens() {
        ScamKeywordDatabase.categories.forEach { cat ->
            cat.requireAllGroups.forEach { group ->
                assertTrue(group.isNotBlank())
                assertTrue(cat.groups[group].orEmpty().isNotEmpty())
            }
        }
    }

    @Test
    fun otp_contains_transliteration_variants() {
        val otp = ScamKeywordDatabase.categories.first { it.category.id == "OTP_SCAM" }
        val all = otp.high + otp.hinglish
        assertTrue(all.any { it.contains("o.t.p") })
        assertTrue(all.any { it.contains("woh code") || it.contains("wo code") })
    }

    @Test
    fun tech_support_contains_remote_access_apps() {
        val tech = ScamKeywordDatabase.categories.first { it.category.id == "TECH_SUPPORT_SCAM" }
        val all = (tech.high + tech.groups.values.flatten()).map { it.lowercase() }
        assertTrue(all.any { it.contains("anydesk") })
        assertTrue(all.any { it.contains("teamviewer") })
        assertTrue(all.any { it.contains("rustdesk") })
        assertTrue(all.any { it.contains("quicksupport") })
    }
}
