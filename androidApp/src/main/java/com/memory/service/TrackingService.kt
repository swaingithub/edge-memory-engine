package com.memory.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Text-only ingestion service using Accessibility.
 * Implements a zero-media permission posture.
 */
class TrackingService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val rootNode = rootInActiveWindow ?: return

        val packageName = event.packageName?.toString() ?: ""
        
        // Exclude camera and gallery packages
        if (isBlockedPackage(packageName)) {
            return
        }

        processNode(rootNode, packageName)
    }

    private fun isBlockedPackage(packageName: String): Boolean {
        val lowerCase = packageName.lowercase()
        return lowerCase.contains("camera") || lowerCase.contains("gallery") || lowerCase.contains("photos")
    }

    private fun processNode(node: AccessibilityNodeInfo, sourceApp: String) {
        // 1. Privacy filtering
        if (node.isPassword) return
        
        val className = node.className?.toString() ?: ""
        if (className.contains("ImageView") || className.contains("VideoView")) return

        // 2. Text extraction
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        
        val extractedText = text ?: contentDesc
        
        if (!extractedText.isNullOrBlank()) {
            // Pass to ingestion pipeline via Kotlin Flow
            com.memory.engine.MemoryEngine.ingestEvent(
                sourceApp = sourceApp,
                rawText = extractedText,
                action = "VIEW",
                entityUrn = "app:${sourceApp}:view" // Simplified fallback URN
            )
        }

        // 3. Traverse children
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { processNode(it, sourceApp) }
        }
    }

    override fun onInterrupt() {
        // Service interrupted
    }
}
