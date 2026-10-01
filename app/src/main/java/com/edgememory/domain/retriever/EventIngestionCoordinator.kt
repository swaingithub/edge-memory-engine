package com.edgememory.domain.retriever

import android.util.Log
import com.edgememory.domain.embedding.OnDeviceEmbedder
import com.edgememory.domain.quantization.BitPacker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object EventIngestionCoordinator {
    private const val TAG = "EventIngestion"
    
    // NOTE: These must be initialized from Application class
    var embedder: OnDeviceEmbedder? = null
    var dao: com.edgememory.data.local.EventLogDao? = null

    /**
     * Entry-point called by TextAccessibilityService and NotificationTrackerService.
     */
    fun enqueueEvent(sourceApp: String, action: String, rawText: String, timestamp: Long) {
        // Log confirmation of zero-media sanitized payload
        Log.d(TAG, "Ingested [$action] from $sourceApp ($timestamp): ${rawText.take(60)}...")

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val safeEmbedder = embedder ?: return@launch
                
                // 1. Generate 512 FP32 values on-device
                val floatEmbedding = safeEmbedder.embed(rawText)

                // 2. Binarize to 64-byte packed BLOB
                val binaryBlob = BitPacker.pack(floatEmbedding)

                // 3. Write directly to encrypted SQLite table
                dao?.insertEvent(
                    com.edgememory.data.model.EventRecord(
                        entityUrn = "urn:app:$sourceApp",
                        timestamp = timestamp,
                        action = action,
                        sourceApp = sourceApp,
                        rawText = rawText,
                        binaryEmbedding = binaryBlob
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to embed and persist event: ${e.message}")
            }
        }
    }
}
