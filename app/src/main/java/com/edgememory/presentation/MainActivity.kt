package com.edgememory.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.edgememory.data.local.DatabaseManager
import com.edgememory.data.local.EventLogDao
import com.edgememory.domain.embedding.OnDeviceEmbedder
import com.edgememory.domain.retriever.CascadedRetriever
import com.edgememory.domain.retriever.EventIngestionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {

    private lateinit var embedder: OnDeviceEmbedder
    private lateinit var llmEngine: com.edgememory.domain.inference.OnDeviceLlmEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize dependencies
        val dbManager = DatabaseManager.getInstance(applicationContext)
        val eventLogDao = EventLogDao(dbManager)
        embedder = EventIngestionCoordinator.embedder ?: OnDeviceEmbedder(applicationContext).also {
            CoroutineScope(Dispatchers.IO).launch { it.initialize() }
        }
        llmEngine = com.edgememory.domain.inference.OnDeviceLlmEngine(applicationContext)

        val retriever = CascadedRetriever(eventLogDao, embedder)

        val viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MemorySearchViewModel(retriever, llmEngine) as T
                }
            }
        )[MemorySearchViewModel::class.java]

        setContent {
            MaterialTheme {
                MemorySearchScreen(viewModel = viewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        llmEngine.close()
        // Do NOT call embedder.close() here as the background ingestion service still uses it!
    }
}
