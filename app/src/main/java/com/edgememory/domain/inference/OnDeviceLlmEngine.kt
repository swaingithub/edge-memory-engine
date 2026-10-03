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

enum class LlmState {
    IDLE, LOADING, MISSING, READY, ERROR
}

class OnDeviceLlmEngine(private val context: Context) : AutoCloseable {

    private var llmInference: LlmInference? = null
    
    private val _state = MutableStateFlow(LlmState.IDLE)
    val state: StateFlow<LlmState> = _state.asStateFlow()

    suspend fun initialize(modelFileName: String = "llama-3.2-1b-it-gpu-int4.bin") = withContext(Dispatchers.IO) {
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
    fun generateAnswerStream(systemContext: String, userQuery: String): Flow<String> = callbackFlow {
        val inference = llmInference
        if (inference == null) {
            close(IllegalStateException("LLM Engine not initialized"))
            return@callbackFlow
        }

        val prompt = formatChatPrompt(systemContext, userQuery)

        try {
            val fullResponse = inference.generateResponse(prompt)
            trySend(fullResponse)
            channel.close()
        } catch (e: Exception) {
            close(e)
        }

        awaitClose { }
    }.flowOn(Dispatchers.Default)

    /**
     * Non-streaming direct answer fallback.
     */
    suspend fun generateAnswer(systemContext: String, userQuery: String): String = withContext(Dispatchers.Default) {
        val inference = llmInference ?: throw IllegalStateException("LLM Engine not initialized")
        val prompt = formatChatPrompt(systemContext, userQuery)
        inference.generateResponse(prompt)
    }

    private fun formatChatPrompt(timelineContext: String, userQuery: String): String {
        return """
            <|begin_of_text|><|start_header_id|>system<|end_header_id|>
            You are a private on-device memory assistant. Answer the user question accurately using ONLY the logged activity timeline below.
            
            RULES:
            1. If an event or booking was rescheduled or changed, state BOTH original and updated information clearly.
            2. If the context does not contain the answer, reply: "I do not have record of that in your logged activity."
            
            HISTORICAL TIMELINE:
            $timelineContext
            <|eot_id|><|start_header_id|>user<|end_header_id|>
            $userQuery<|eot_id|><|start_header_id|>assistant<|end_header_id|>
        """.trimIndent()
    }

    override fun close() {
        llmInference?.close()
        llmInference = null
        isInitialized = false
    }
}
