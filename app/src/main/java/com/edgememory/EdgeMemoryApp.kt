package com.edgememory

import android.app.Application
import android.util.Log
import com.edgememory.domain.embedding.OnDeviceEmbedder
import com.edgememory.domain.retriever.EventIngestionCoordinator
import com.edgememory.data.local.EventLogDao
import com.edgememory.data.local.DatabaseManager
import com.edgememory.service.worker.NightlyCompactionWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EdgeMemoryApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Core Dependencies
        val dbManager = DatabaseManager.getInstance(applicationContext)
        val eventLogDao = EventLogDao(dbManager)
        val embedder = OnDeviceEmbedder(applicationContext)

        // Inject dependencies into IngestionCoordinator singleton
        EventIngestionCoordinator.dao = eventLogDao
        EventIngestionCoordinator.embedder = embedder
        
        // Initialize ONNX runtime & Vocab asynchronously
        appScope.launch {
            try {
                embedder.initialize()
            } catch (e: Exception) {
                Log.e("EdgeMemoryApp", "Failed to initialize embedder: ${e.message}")
            }
        }
        
        // Enqueue daily compaction under idle + charging constraints
        NightlyCompactionWorker.schedule(applicationContext)
    }
}
