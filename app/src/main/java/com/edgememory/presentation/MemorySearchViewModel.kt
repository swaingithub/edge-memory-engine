package com.edgememory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edgememory.data.model.EventRecord
import com.edgememory.domain.retriever.CascadedRetriever
import com.edgememory.domain.inference.OnDeviceLlmEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val isGenerating: Boolean = false,
    val isError: Boolean = false,
    val latencyMs: Long? = null,
    val primaryEvents: List<EventRecord> = emptyList(),
    val entityTimelines: Map<String, List<EventRecord>> = emptyMap()
)

class MemorySearchViewModel(
    private val cascadedRetriever: CascadedRetriever,
    private val llmEngine: OnDeviceLlmEngine
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

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
        if (query.isBlank() || _isGenerating.value) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isGenerating.value = true
            
            // Add user message to history
            val currentMessages = _messages.value.toMutableList()
            currentMessages.add(ChatMessage(text = query, isUser = true))
            
            // Add a placeholder AI message
            val aiMsgId = UUID.randomUUID().toString()
            var currentAiMsg = ChatMessage(id = aiMsgId, text = "Thinking...", isUser = false, isGenerating = true)
            currentMessages.add(currentAiMsg)
            _messages.value = currentMessages.toList()

            val startTime = System.currentTimeMillis()
            var fullAnswer = ""

            try {
                // Stage-1 + Stage-2: Run FTS5 + 1-Bit ARM NEON scan
                val result = cascadedRetriever.retrieve(query)
                val latency = System.currentTimeMillis() - startTime

                // Update placeholder with retrieved context immediately
                currentAiMsg = currentAiMsg.copy(
                    text = "",
                    latencyMs = latency,
                    primaryEvents = result.primaryEvents,
                    entityTimelines = result.entityTimelines
                )
                updateMessage(currentAiMsg)

                // Let the AI handle the empty timeline context gracefully instead of short-circuiting!
                
                // Stage-3: Feed retrieved timeline into On-Device SLM
                when (llmEngine.state.value) {
                    com.edgememory.domain.inference.LlmState.READY -> {
                        try {
                            llmEngine.generateAnswerStream(
                                systemContext = result.formattedTimelinePrompt,
                                userQuery = query
                            ).collect { token ->
                                fullAnswer += token
                                currentAiMsg = currentAiMsg.copy(text = fullAnswer)
                                updateMessage(currentAiMsg)
                            }
                        } catch (e: Exception) {
                            currentAiMsg = currentAiMsg.copy(text = fullAnswer + "\n\nError: ${e.message}", isError = true)
                            updateMessage(currentAiMsg)
                        }
                    }
                    com.edgememory.domain.inference.LlmState.LOADING -> {
                        currentAiMsg = currentAiMsg.copy(text = "Model is currently loading... Showing raw timeline results below.", isGenerating = false)
                        updateMessage(currentAiMsg)
                    }
                    com.edgememory.domain.inference.LlmState.MISSING -> {
                        currentAiMsg = currentAiMsg.copy(text = "LLM Model not found! Please adb push gemma-2b-it-cpu-int4.bin to /data/user/0/com.edgememory/files/models/. Showing raw timeline results below.", isGenerating = false)
                        updateMessage(currentAiMsg)
                    }
                    com.edgememory.domain.inference.LlmState.ERROR -> {
                        currentAiMsg = currentAiMsg.copy(text = "LLM Engine failed to initialize. Showing raw timeline results below.", isGenerating = false)
                        updateMessage(currentAiMsg)
                    }
                    else -> {
                        currentAiMsg = currentAiMsg.copy(text = "LLM Engine idle. Showing raw timeline results below.", isGenerating = false)
                        updateMessage(currentAiMsg)
                    }
                }
                
                // Mark generation as finished
                currentAiMsg = currentAiMsg.copy(isGenerating = false)
                updateMessage(currentAiMsg)
                
            } catch (e: Exception) {
                currentAiMsg = currentAiMsg.copy(text = "Error: ${e.localizedMessage}", isError = true, isGenerating = false)
                updateMessage(currentAiMsg)
            } finally {
                _isGenerating.value = false
            }
        }
    }
    
    private fun updateMessage(updatedMsg: ChatMessage) {
        val msgs = _messages.value.toMutableList()
        val index = msgs.indexOfFirst { it.id == updatedMsg.id }
        if (index != -1) {
            msgs[index] = updatedMsg
            _messages.value = msgs.toList()
        }
    }
}
