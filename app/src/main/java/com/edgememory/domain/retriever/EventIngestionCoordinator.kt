package com.edgememory.domain.retriever

import android.util.Log
import com.edgememory.data.local.EventLogDao
import com.edgememory.domain.embedding.OnDeviceEmbedder
import com.edgememory.domain.quantization.BitPacker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object EventIngestionCoordinator {
    private const val TAG = "EventIngestion"
    
    // Persistent managed scope for background ingestion jobs
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var embedder: OnDeviceEmbedder? = null
    var dao: EventLogDao? = null

    fun enqueueEvent(sourceApp: String, action: String, rawText: String, timestamp: Long) {
        Log.d(TAG, "Ingested [$action] from $sourceApp ($timestamp): ${rawText.take(60)}...")

        coordinatorScope.launch {
            try {
                val safeEmbedder = embedder ?: run {
                    Log.w(TAG, "Embedder is null. Dropping event.")
                    return@launch
                }
                
                val safeDao = dao ?: run {
                    Log.w(TAG, "Dao is null. Dropping event.")
                    return@launch
                }

                // 1. Generate 512 FP32 values on-device
                val floatEmbedding = safeEmbedder.embed(rawText)

                // 2. Binarize to 64-byte packed BLOB
                val binaryBlob = BitPacker.pack(floatEmbedding)

                // 3. Write directly to encrypted SQLite table
                safeDao.insertEvent(
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
                Log.e(TAG, "Failed to embed and persist event: ${e.message}", e)
            }
        }
    }
}
