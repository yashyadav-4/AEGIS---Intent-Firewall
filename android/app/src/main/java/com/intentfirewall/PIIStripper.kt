package com.intentfirewall

object PIIStripper {
    
    // Regex patterns for common PII
    private val PHONE_PATTERN = Regex("""(\+?\d{1,3}[\s-]?)?\(?\d{3}\)?[\s-]?\d{3}[\s-]?\d{4}""")
    private val EMAIL_PATTERN = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
    private val UPI_PATTERN = Regex("""[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z]{2,64}""") // e.g., username@bank
    
    fun stripPII(text: String): String {
        if (text.isEmpty()) return text
        
        var sanitizedText = text
        
        // Redact emails
        sanitizedText = sanitizedText.replace(EMAIL_PATTERN, "[EMAIL_REDACTED]")
        
        // Redact UPI IDs (Note: UPI regex is somewhat generic, applying after email is safer)
        sanitizedText = sanitizedText.replace(UPI_PATTERN, "[UPI_REDACTED]")
        
        // Redact phone numbers
        sanitizedText = sanitizedText.replace(PHONE_PATTERN, "[PHONE_REDACTED]")
        
        return sanitizedText
    }
}
