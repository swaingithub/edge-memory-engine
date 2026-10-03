package com.edgememory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edgememory.data.model.EventRecord
import com.edgememory.domain.retriever.CascadedRetriever
import com.edgememory.domain.inference.OnDeviceLlmEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    object Idle : SearchUiState
    object Searching : SearchUiState
    data class Success(
        val query: String,
        val latencyMs: Long,
        val primaryEvents: List<EventRecord>,
        val entityTimelines: Map<String, List<EventRecord>>,
        val promptContext: String
    ) : SearchUiState
    data class Empty(val query: String, val latencyMs: Long) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

class MemorySearchViewModel(
    private val cascadedRetriever: CascadedRetriever,
    private val llmEngine: OnDeviceLlmEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _streamedAnswer = MutableStateFlow("")
    val streamedAnswer: StateFlow<String> = _streamedAnswer.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(newQuery: String) {
        _searchQuery.value = newQuery
    }

    fun submitQuery() {
        val query = _searchQuery.value.trim()
        if (query.isNotEmpty()) {
            executeSearch(query)
            _searchQuery.value = "" // clear input after send
        }
    }

    fun executeSearch(query: String) {
        if (query.isBlank()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = SearchUiState.Searching
            _streamedAnswer.value = ""
            val startTime = System.currentTimeMillis()

            try {
                // Stage-1 + Stage-2: Run FTS5 + 1-Bit ARM NEON scan
                val result = cascadedRetriever.retrieve(query)
                val latency = System.currentTimeMillis() - startTime

                if (result.primaryEvents.isEmpty() && result.entityTimelines.isEmpty()) {
                    _uiState.value = SearchUiState.Empty(query, latency)
                } else {
                    _uiState.value = SearchUiState.Success(
                        query = query,
                        latencyMs = latency,
                        primaryEvents = result.primaryEvents,
                        entityTimelines = result.entityTimelines,
                        promptContext = result.formattedTimelinePrompt
                    )
                    
                    // Stage-3: Feed retrieved timeline into On-Device SLM
                    when (llmEngine.state.value) {
                        com.edgememory.domain.inference.LlmState.READY -> {
                            _isGenerating.value = true
                            try {
                                llmEngine.generateAnswerStream(
                                    systemContext = result.formattedTimelinePrompt,
                                    userQuery = query
                                ).collect { token ->
                                    _streamedAnswer.value += token
                                }
                            } catch (e: Exception) {
                                _streamedAnswer.value = "Failed to stream answer: ${e.message}"
                            }
                            _isGenerating.value = false
                        }
                        com.edgememory.domain.inference.LlmState.LOADING -> {
                            _streamedAnswer.value = "Model is currently loading... Showing raw timeline results below."
                        }
                        com.edgememory.domain.inference.LlmState.MISSING -> {
                            _streamedAnswer.value = "LLM Model not found! Please adb push llama-3.2-1b-it-gpu-int4.bin to /data/user/0/com.edgememory/files/models/. Showing raw timeline results below."
                        }
                        com.edgememory.domain.inference.LlmState.ERROR -> {
                            _streamedAnswer.value = "LLM Engine failed to initialize. Showing raw timeline results below."
                        }
                        else -> {
                            _streamedAnswer.value = "LLM Engine idle. Showing raw timeline results below."
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = SearchUiState.Error(e.localizedMessage ?: "Unknown retrieval error")
                _isGenerating.value = false
            }
        }
    }
}
