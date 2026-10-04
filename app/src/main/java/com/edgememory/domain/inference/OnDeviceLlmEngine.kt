package com.edgememory.domain.inference

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlinx.coroutines.sync.withLock

enum class LlmState {
    IDLE, LOADING, MISSING, READY, ERROR
}

class OnDeviceLlmEngine(private val context: Context) : AutoCloseable {

    private var llmInference: LlmInference? = null
    
    private val _state = MutableStateFlow(LlmState.IDLE)
    val state: StateFlow<LlmState> = _state.asStateFlow()

    suspend fun initialize(modelFileName: String = "gemma-2b-it-cpu-int4.bin") = withContext(Dispatchers.IO) {
        if (_state.value == LlmState.READY || _state.value == LlmState.LOADING) return@withContext

        _state.value = LlmState.LOADING

        try {
            val modelDir = File(context.filesDir, "models")
            if (!modelDir.exists()) modelDir.mkdirs()

            val modelFile = File(modelDir, modelFileName)
            if (!modelFile.exists()) {
                _state.value = LlmState.MISSING
                return@withContext
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .setTemperature(0.2f)
                .setTopK(40)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            _state.value = LlmState.READY
        } catch (e: Exception) {
            _state.value = LlmState.ERROR
            android.util.Log.e("OnDeviceLlmEngine", "LLM Init Error", e)
        }
    }

    fun isReady(): Boolean = _state.value == LlmState.READY

    /**
     * Executes async streaming from the pre-allocated inference session.
     */
    private val inferenceMutex = kotlinx.coroutines.sync.Mutex()

    fun generateAnswerStream(systemContext: String, userQuery: String): Flow<String> = callbackFlow {
        val inference = llmInference
        if (inference == null) {
            close(IllegalStateException("LLM Engine not initialized"))
            return@callbackFlow
        }

        val prompt = formatChatPrompt(systemContext, userQuery)

        try {
            // Must use a lock! MediaPipe's LlmInference crashes with SIGABRT if called concurrently!
            val fullResponse = inferenceMutex.withLock {
                inference.generateResponse(prompt)
            }
            trySend(fullResponse)
            channel.close()
        } catch (e: Exception) {
            close(e)
        }

        awaitClose { }
    }.flowOn(Dispatchers.IO)

    /**
     * Non-streaming direct answer fallback.
     */
    suspend fun generateAnswer(systemContext: String, userQuery: String): String = withContext(Dispatchers.Default) {
        val inference = llmInference ?: throw IllegalStateException("LLM Engine not initialized")
        val prompt = formatChatPrompt(systemContext, userQuery)
        inference.generateResponse(prompt)
    }

    private fun formatChatPrompt(timelineContext: String, userQuery: String): String {
        // Keep it extremely simple for Gemma 2B. 
        // Complex rules cause it to hallucinate and regurgitate the context.
        return """
<start_of_turn>user
Context from my recent phone activity:
$timelineContext

Based ONLY on the context above, answer this question:
$userQuery<end_of_turn>
<start_of_turn>model
""".trimIndent()
    }

    override fun close() {
        llmInference?.close()
        llmInference = null
        _state.value = LlmState.IDLE
    }
}
