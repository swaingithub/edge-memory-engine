package com.edgememory.service.ingestion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.edgememory.domain.retriever.EventIngestionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotificationTrackerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "NotificationTracker"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName ?: return

        // 1. Package blocklist filter
        if (!IngestionSanitizer.isPackageAllowed(packageName)) {
            return
        }

        val notification = sbn.notification ?: return

        // 2. Ignore non-content ongoing foreground notifications (e.g., active pedometer, music player)
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            return
        }

        serviceScope.launch {
            try {
                val extras = notification.extras ?: return@launch
                
                // Extract Notification Title, Body, and Expanded BigText
                val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

                val bodyContent = if (bigText.isNotBlank()) bigText else text
                if (title.isBlank() && bodyContent.isBlank()) return@launch

                val rawPayload = "[$packageName] $title: $bodyContent"

                // 3. PII and OTP Redaction
                val sanitizedPayload = IngestionSanitizer.sanitizeText(rawPayload) ?: return@launch

                // 4. Send to Ingestion Coordinator
                EventIngestionCoordinator.enqueueEvent(
                    sourceApp = packageName,
                    action = "NOTIFICATION",
                    rawText = sanitizedPayload,
                    timestamp = sbn.postTime
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to ingest notification: ${e.message}")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op: Append-only ledger preserves history; we never remove past events
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
