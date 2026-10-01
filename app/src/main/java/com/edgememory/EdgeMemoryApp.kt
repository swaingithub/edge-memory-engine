package com.edgememory

import android.app.Application
import com.edgememory.domain.embedding.OnDeviceEmbedder
import com.edgememory.domain.retriever.EventIngestionCoordinator
import com.edgememory.data.local.EventLogDao
import com.edgememory.data.local.DatabaseManager
import com.edgememory.service.worker.NightlyCompactionWorker

class EdgeMemoryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Core Dependencies
        val dbManager = DatabaseManager.getInstance(applicationContext)
        val eventLogDao = EventLogDao(dbManager)
        val embedder = OnDeviceEmbedder(applicationContext)

        // Inject dependencies into IngestionCoordinator singleton
        EventIngestionCoordinator.dao = eventLogDao
        EventIngestionCoordinator.embedder = embedder
        
        // Enqueue daily compaction under idle + charging constraints
        NightlyCompactionWorker.schedule(applicationContext)
    }
}
