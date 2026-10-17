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

    init {
        // Purge any previously polluted self-app records from database
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                cascadedRetriever.purgeSelfEvents()
            } catch (e: Exception) {
                android.util.Log.e("MemorySearchVM", "Purge self-events error", e)
            }
        }
    }

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

                // Stage-3: Feed retrieved context into On-Device SLM or extract direct answer
                if (result.primaryEvents.isEmpty()) {
                    currentAiMsg = currentAiMsg.copy(
                        text = "I couldn't find any relevant recorded memory for your query.",
                        isGenerating = false
                    )
                    updateMessage(currentAiMsg)
                } else if (llmEngine.state.value == com.edgememory.domain.inference.LlmState.READY) {
                    try {
                        llmEngine.generateAnswerStream(
                            systemContext = result.formattedTimelinePrompt,
                            userQuery = query
                        ).collect { token ->
                            fullAnswer += token
                            // Strip leading ': ' or formatting artifacts that small models occasionally prefix
                            val cleanAnswer = fullAnswer.trimStart().removePrefix(":").trimStart()
                            currentAiMsg = currentAiMsg.copy(text = cleanAnswer)
                            updateMessage(currentAiMsg)
                        }

                        // If the SLM gave a canned refusal despite the context, synthesize directly from top match
                        val lower = fullAnswer.lowercase()
                        val isRefusal = lower.contains("do not have access") ||
                                        lower.contains("cannot access") ||
                                        lower.contains("cannot answer") ||
                                        lower.contains("as an ai")
                        if (isRefusal) {
                            val bestMatch = result.primaryEvents.first()
                            val dateStr = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.US)
                                .format(java.util.Date(bestMatch.timestamp))
                            val fallback = "From **${bestMatch.sourceApp}** ($dateStr):\n\n${bestMatch.rawText.trim()}"
                            currentAiMsg = currentAiMsg.copy(text = fallback)
                            updateMessage(currentAiMsg)
                        }
                    } catch (e: Exception) {
                        currentAiMsg = currentAiMsg.copy(text = fullAnswer + "\n\nError: ${e.message}", isError = true)
                        updateMessage(currentAiMsg)
                    }
                } else {
                    // LLM model is not ready/downloaded on device: Synthesize direct answer from the top matching event
                    val bestMatch = result.primaryEvents.first()
                    val dateStr = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.US)
                        .format(java.util.Date(bestMatch.timestamp))
                    
                    val synthesized = buildString {
                        append("Based on your recent activity from **${bestMatch.sourceApp}** ($dateStr):\n\n")
                        append(bestMatch.rawText.trim())
                    }
                    currentAiMsg = currentAiMsg.copy(text = synthesized, isGenerating = false)
                    updateMessage(currentAiMsg)
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
