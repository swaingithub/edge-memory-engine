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
        embedder = OnDeviceEmbedder(applicationContext)
        llmEngine = com.edgememory.domain.inference.OnDeviceLlmEngine(applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            embedder.initialize()
            // Optionally initialize llmEngine here if the model exists, though normally done when ready.
        }

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
        embedder.close()
        llmEngine.close()
    }
}
