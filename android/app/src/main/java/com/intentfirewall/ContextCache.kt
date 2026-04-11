package com.intentfirewall

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object ContextCache {
    private const val TAG = "SCAM_ContextCache"
    private const val PREF_NAME = "scam_context_cache"
    private const val MAX_ENTRIES = 5
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    const val SNAPSHOT_KEY = "ctx_cache"

    data class CacheEntry(
        val text: String,
        val timestamp: Long,
    )

    /**
     * Writes a text entry to package context cache.
     */
    fun write(context: Context, packageName: String, text: String, timestamp: Long = System.currentTimeMillis()) {
        try {
            if (packageName.isBlank() || text.isBlank()) return
            val key = buildKey(packageName)
            val current = readEntries(context, packageName).toMutableList()
            current.add(CacheEntry(text = text, timestamp = timestamp))
            val sanitized = current
                .filter { timestamp - it.timestamp <= MAX_AGE_MS }
                .takeLast(MAX_ENTRIES)
            val arr = JSONArray()
            sanitized.forEach {
                arr.put(
                    JSONObject()
                        .put("text", it.text)
                        .put("timestamp", it.timestamp),
                )
            }
            prefs(context).edit().putString(key, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
        }
    }

    /**
     * Reads non-expired entries for a package, ordered oldest-to-newest.
     */
    fun read(context: Context, packageName: String): List<String> {
        return readEntries(context, packageName).map { it.text }
    }

    /**
     * Restores all package caches from persisted snapshot key.
     */
    fun restoreSnapshot(context: Context): Map<String, List<String>> {
        val out = mutableMapOf<String, List<String>>()
        return try {
            val raw = prefs(context).getString(SNAPSHOT_KEY, "[]").orEmpty()
            val arr = JSONArray(raw)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val pkg = row.optString("pkg", "")
                val values = row.optJSONArray("items") ?: JSONArray()
                val parsed = mutableListOf<CacheEntry>()
                for (j in 0 until values.length()) {
                    val item = values.optJSONObject(j) ?: continue
                    val text = item.optString("text", "").trim()
                    val ts = item.optLong("timestamp", 0L)
                    if (text.isNotBlank() && now - ts <= MAX_AGE_MS) {
                        parsed.add(CacheEntry(text, ts))
                    }
                }
                if (pkg.isNotBlank() && parsed.isNotEmpty()) {
                    val filtered = parsed.takeLast(MAX_ENTRIES)
                    out[pkg] = filtered.map { it.text }
                    val serialized = JSONArray()
                    filtered.forEach {
                        serialized.put(JSONObject().put("text", it.text).put("timestamp", it.timestamp))
                    }
                    prefs(context).edit().putString(buildKey(pkg), serialized.toString()).apply()
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "restoreSnapshot failed", e)
            emptyMap()
        }
    }

    /**
     * Flushes all package cache keys into snapshot key for fast restore.
     */
    fun flushSnapshot(context: Context) {
        try {
            val now = System.currentTimeMillis()
            val all = prefs(context).all
            val rows = JSONArray()
            all.forEach { (key, value) ->
                if (!key.startsWith("ctx_")) return@forEach
                val arr = runCatching { JSONArray(value?.toString().orEmpty()) }.getOrNull() ?: JSONArray()
                val cleaned = JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val text = item.optString("text", "").trim()
                    val ts = item.optLong("timestamp", 0L)
                    if (text.isNotBlank() && now - ts <= MAX_AGE_MS) {
                        cleaned.put(JSONObject().put("text", text).put("timestamp", ts))
                    }
                }
                if (cleaned.length() > 0) {
                    rows.put(
                        JSONObject()
                            .put("pkg", key.removePrefix("ctx_"))
                            .put("items", cleaned),
                    )
                }
            }
            prefs(context).edit().putString(SNAPSHOT_KEY, rows.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "flushSnapshot failed", e)
        }
    }

    private fun readEntries(context: Context, packageName: String): List<CacheEntry> {
        return try {
            val now = System.currentTimeMillis()
            val raw = prefs(context).getString(buildKey(packageName), "[]").orEmpty()
            val arr = JSONArray(raw)
            val out = mutableListOf<CacheEntry>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val text = item.optString("text", "").trim()
                val ts = item.optLong("timestamp", 0L)
                if (text.isNotBlank() && now - ts <= MAX_AGE_MS) {
                    out.add(CacheEntry(text, ts))
                }
            }
            val trimmed = out.takeLast(MAX_ENTRIES)
            val cleaned = JSONArray()
            trimmed.forEach {
                cleaned.put(JSONObject().put("text", it.text).put("timestamp", it.timestamp))
            }
            prefs(context).edit().putString(buildKey(packageName), cleaned.toString()).apply()
            trimmed
        } catch (e: Exception) {
            Log.e(TAG, "readEntries failed", e)
            emptyList()
        }
    }

    private fun buildKey(packageName: String): String = "ctx_$packageName"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
