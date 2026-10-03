package com.edgememory.service.ingestion

import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

object IngestionSanitizer {

    // -------------------------------------------------------------
    // 1. Strict Package Blocklist
    // Dropped immediately at event receipt; no further checks run.
    // -------------------------------------------------------------
    private val BLOCKED_PACKAGES = hashSetOf(
        // System and OEM Galleries
        "com.google.android.apps.photos",
        "com.sec.android.gallery3d",
        "com.miui.gallery",
        "com.oneplus.gallery",
        "com.coloros.gallery",
        "com.huawei.photos",
        
        // System & Third-Party Cameras
        "com.android.camera",
        "com.google.android.GoogleCamera",
        "com.sec.android.app.camera",
        
        // Password Managers & 2FA Authenticators
        "com.onepassword.android",
        "com.bitwarden.mobile",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "org.keepassdroid",
        
        // Android System UI Keyboards / Lockscreen PINs
        "com.google.android.inputmethod.latin",
        "com.android.systemui"
    )

    // -------------------------------------------------------------
    // 2. Visual & Media Class Identifiers
    // Drops nodes designed solely for image/video rendering.
    // -------------------------------------------------------------
    private val MEDIA_CLASS_SUBSTRINGS = listOf(
        "ImageView",
        "VideoView",
        "SurfaceView",
        "TextureView",
        "GLSurfaceView"
    )

    // -------------------------------------------------------------
    // 3. Compiled Pre-Compiled PII & Secret Patterns
    // -------------------------------------------------------------
    // Credit / Debit Cards (Visa, MasterCard, Amex, RuPay, etc.)
    private val CREDIT_CARD_REGEX = Pattern.compile(
        "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|6(?:011|5[0-9]{2})[0-9]{12}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|(?:2131|1800|35\\d{3})\\d{11})\\b"
    )

    // One-Time Passwords / Verification Codes (e.g., "OTP: 482901", "code is 123456")
    private val OTP_REGEX = Pattern.compile(
        "(?i)\\b(?:otp|code|verification|pin|passcode)\\s*(?:is|:|[-=])?\\s*(\\d{4,8})\\b"
    )

    // Generic Indian Aadhaar Number (12 digits, often formatted as 4-4-4)
    private val AADHAAR_REGEX = Pattern.compile(
        "\\b[2-9]\\d{3}[ -]\\d{4}[ -]\\d{4}\\b"
    )

    // US Social Security Number (SSN: 3-2-4)
    private val SSN_REGEX = Pattern.compile(
        "\\b\\d{3}-\\d{2}-\\d{4}\\b"
    )

    // Explicit Auth Keywords / Key-Value Pairs
    private val CREDENTIAL_KEY_VALUE_REGEX = Pattern.compile(
        "(?i)(?:password|passwd|pwd|cvv|secret|token|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+"
    )

    /**
     * Determines whether an application package is permitted to be ingested.
     */
    fun isPackageAllowed(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return !BLOCKED_PACKAGES.contains(packageName.trim())
    }

    /**
     * Checks if an Accessibility Node is safe for text extraction.
     * Rejects password inputs, secret fields, and media canvas components.
     */
    fun isNodeSafe(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        // 1. Drop password and secure masked inputs at the OS framework level
        if (node.isPassword) return false

        // 2. Reject visual/video widgets
        val className = node.className?.toString() ?: ""
        for (mediaToken in MEDIA_CLASS_SUBSTRINGS) {
            if (className.contains(mediaToken, ignoreCase = true)) {
                return false
            }
        }

        // 3. Drop empty or blank text containers
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        if (text.isNullOrBlank() && contentDesc.isNullOrBlank()) {
            return false
        }

        return true
    }

    /**
     * Cleans, normalizes, and redacts PII/secrets from raw captured text.
     * Returns sanitized text or null if the entire payload consisted of a secret.
     */
    fun sanitizeText(rawInput: String?): String? {
        if (rawInput.isNullOrBlank()) return null

        var text = rawInput

        // 1. Redact direct credential assignments (e.g. "password: secret123")
        text = CREDENTIAL_KEY_VALUE_REGEX.matcher(text).replaceAll("[REDACTED_CREDENTIAL]")

        // 2. Redact Card numbers
        text = CREDIT_CARD_REGEX.matcher(text).replaceAll("[REDACTED_CARD]")

        // 3. Redact National Identification numbers (Aadhaar / SSN)
        text = AADHAAR_REGEX.matcher(text).replaceAll("[REDACTED_ID]")
        text = SSN_REGEX.matcher(text).replaceAll("[REDACTED_ID]")

        // 4. Redact OTPs and Verification codes
        val otpMatcher = OTP_REGEX.matcher(text)
        val sb = StringBuffer()
        while (otpMatcher.find()) {
            // Replace only the numeric capture group with [OTP]
            val matchedPrefix = otpMatcher.group(0)?.substringBefore(otpMatcher.group(1) ?: "") ?: ""
            otpMatcher.appendReplacement(sb, "$matchedPrefix[REDACTED_OTP]")
        }
        otpMatcher.appendTail(sb)
        text = sb.toString()

        // 5. Normalize consecutive whitespaces
        val cleaned = text.replace(Regex("\\s+"), " ").trim()

        return cleaned.ifBlank { null }
    }
}
