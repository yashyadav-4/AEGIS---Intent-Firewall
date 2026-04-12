package com.intentfirewall

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallTranscriptStore(context: Context) {
    private val transcriptsDir = File(context.filesDir, "call_transcripts")
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    @Volatile
    private var activeFile: File? = null

    fun startSession(callerId: String?) {
        transcriptsDir.mkdirs()
        val safeCaller = callerId?.filter { it.isLetterOrDigit() }?.take(24).orEmpty().ifBlank { "unknown" }
        val fileName = "call_${timestampFormat.format(Date())}_$safeCaller.txt"
        activeFile = File(transcriptsDir, fileName).apply {
            writeText("# Aegis call transcript\n# caller=$callerId\n# started=${System.currentTimeMillis()}\n")
        }
    }

    fun appendLine(label: String, text: String) {
        val file = activeFile ?: return
        file.appendText("[$label] ${text.trim()}\n")
    }

    fun finishSession(summary: String? = null) {
        val file = activeFile ?: return
        if (!summary.isNullOrBlank()) {
            file.appendText("\n# summary=$summary\n")
        }
        file.appendText("# ended=${System.currentTimeMillis()}\n")
        activeFile = null
    }

    fun latestTranscriptFile(): File? {
        return transcriptsDir.listFiles()?.maxByOrNull { it.lastModified() }
    }
}