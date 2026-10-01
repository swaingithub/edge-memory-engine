package com.edgememory.service.ingestion

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.edgememory.domain.retriever.EventIngestionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.zip.CRC32

class TextAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastScreenHash: Long = 0L
    private var lastEventTime: Long = 0L

    companion object {
        private const val TAG = "TextAccessibility"
        private const val DEBOUNCE_INTERVAL_MS = 1000L // 1 second screen debounce
        private const val MAX_CHARS_PER_SCREEN = 4000  // Truncation limit for noisy UI trees
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val eventType = event.eventType

        // Only process Window Content Changes or State Changes
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }

        // Fast boundary check: Drop camera, gallery, banking authenticators immediately
        if (!IngestionSanitizer.isPackageAllowed(packageName)) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventTime < DEBOUNCE_INTERVAL_MS) {
            return
        }

        // Safely extract window hierarchy root
        val rootNode = rootInActiveWindow ?: return

        serviceScope.launch {
            try {
                val rawCollector = StringBuilder()
                extractTextFromNode(rootNode, rawCollector)

                val rawText = rawCollector.toString().trim()
                if (rawText.length < 15) return@launch // Skip trivial labels ("OK", "Back")

                // Compute CRC32 to avoid re-embedding identical screens
                val crc = CRC32().apply { update(rawText.toByteArray()) }
                val currentHash = crc.value

                if (currentHash == lastScreenHash) {
                    return@launch
                }
                lastScreenHash = currentHash
                lastEventTime = currentTime

                // Sanitize and redact PII / credentials
                val sanitizedText = IngestionSanitizer.sanitizeText(rawText) ?: return@launch
                val boundedText = if (sanitizedText.length > MAX_CHARS_PER_SCREEN) {
                    sanitizedText.take(MAX_CHARS_PER_SCREEN)
                } else {
                    sanitizedText
                }

                // Enqueue to 1-Bit quantization & append-only SQLite store
                EventIngestionCoordinator.enqueueEvent(
                    sourceApp = packageName,
                    action = "SCREEN_TEXT",
                    rawText = boundedText,
                    timestamp = currentTime
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing accessibility tree: ${e.message}")
            } finally {
                rootNode.recycle()
            }
        }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo?, collector: StringBuilder) {
        if (node == null) return

        // Drop passwords, ImageView, VideoView, and empty containers
        if (!IngestionSanitizer.isNodeSafe(node)) {
            return
        }

        // Extract visible text or content descriptions
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        if (!text.isNullOrBlank()) {
            collector.append(text).append(" ")
        } else if (!desc.isNullOrBlank()) {
            collector.append(desc).append(" ")
        }

        // Traverse layout children
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                extractTextFromNode(child, collector)
                child.recycle()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted by OS")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
