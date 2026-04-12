package com.intentfirewall

import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object ScamAssistBridgeUploader {
    private const val TAG = "ScamAssistBridge"

    fun uploadSession(
        settings: ScamAssistBridgeConfig.Settings,
        wavFile: File,
        transcriptFile: File?,
        callerId: String?,
        startedAtMs: Long,
        endedAtMs: Long,
        captureSource: String?
    ): Boolean {
        if (!settings.enabled || settings.endpoint.isBlank()) {
            return false
        }
        if (!wavFile.exists() || wavFile.length() <= 44L) {
            Log.w(TAG, "Skipping upload; WAV file missing or too small")
            return false
        }

        val boundary = "----AegisBoundary${System.currentTimeMillis()}"
        val lineEnd = "\r\n"

        return try {
            val url = URL(resolveUploadEndpoint(settings.endpoint))
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                connectTimeout = 15000
                readTimeout = 45000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
                if (!settings.authToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer ${settings.authToken}")
                }
            }

            DataOutputStream(connection.outputStream).use { out ->
                writeTextField(out, boundary, "caller_id", callerId ?: "")
                writeTextField(out, boundary, "started_at_ms", startedAtMs.toString())
                writeTextField(out, boundary, "ended_at_ms", endedAtMs.toString())
                writeTextField(out, boundary, "capture_source", captureSource ?: "unknown")
                writeTextField(out, boundary, "app_package", "com.intentfirewall")

                val transcript = transcriptFile?.takeIf { it.exists() }?.readText().orEmpty()
                writeTextField(out, boundary, "transcript", transcript)

                writeFileField(
                    out,
                    boundary,
                    fieldName = "audio_file",
                    fileName = wavFile.name,
                    mimeType = "audio/wav",
                    file = wavFile
                )

                out.writeBytes("--$boundary--$lineEnd")
                out.flush()
            }

            val responseCode = connection.responseCode
            val responseBody = runCatching {
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")

            val success = responseCode in 200..299
            if (success) {
                Log.i(TAG, "Bridge upload success code=$responseCode")
            } else {
                Log.w(TAG, "Bridge upload failed code=$responseCode body=${responseBody.take(300)}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Bridge upload exception", e)
            false
        }
    }

    fun sendScreenEvent(
        settings: ScamAssistBridgeConfig.Settings,
        callerId: String,
        callDirection: String,
        eventTimeMs: Long
    ): Boolean {
        if (!settings.enabled || settings.endpoint.isBlank()) {
            return false
        }

        return try {
            val screenUrl = URL(resolveScreenEventEndpoint(settings.endpoint))
            val connection = (screenUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                connectTimeout = 10000
                readTimeout = 20000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (!settings.authToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer ${settings.authToken}")
                }
            }

            val payload = JSONObject().apply {
                put("caller_id", callerId)
                put("call_direction", callDirection)
                put("event_time_ms", eventTimeMs)
                put("app_package", "com.intentfirewall")
            }

            connection.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val code = connection.responseCode
            val success = code in 200..299
            if (!success) {
                Log.w(TAG, "Screen event upload failed code=$code")
            }
            success
        } catch (e: Exception) {
            Log.w(TAG, "Screen event upload exception", e)
            false
        }
    }

    private fun writeTextField(
        out: DataOutputStream,
        boundary: String,
        fieldName: String,
        value: String
    ) {
        val lineEnd = "\r\n"
        out.writeBytes("--$boundary$lineEnd")
        out.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"$lineEnd")
        out.writeBytes("Content-Type: text/plain; charset=UTF-8$lineEnd$lineEnd")
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes(lineEnd)
    }

    private fun writeFileField(
        out: DataOutputStream,
        boundary: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        file: File
    ) {
        val lineEnd = "\r\n"
        out.writeBytes("--$boundary$lineEnd")
        out.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"$lineEnd")
        out.writeBytes("Content-Type: $mimeType$lineEnd$lineEnd")
        file.inputStream().use { input ->
            input.copyTo(out)
        }
        out.writeBytes(lineEnd)
    }

    private fun resolveUploadEndpoint(endpoint: String): String {
        val normalized = endpoint.trim().trimEnd('/')
        return if (normalized.endsWith("/upload")) {
            normalized
        } else {
            "$normalized/upload"
        }
    }

    private fun resolveScreenEventEndpoint(endpoint: String): String {
        val normalized = endpoint.trim().trimEnd('/')
        return when {
            normalized.endsWith("/upload") -> normalized.removeSuffix("/upload") + "/screen-event"
            normalized.endsWith("/screen-event") -> normalized
            else -> "$normalized/screen-event"
        }
    }
}
