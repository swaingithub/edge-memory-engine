package com.edgememory.domain.inference

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

class OnDeviceLlmEngine(private val context: Context) : AutoCloseable {

    private var llmInference: LlmInference? = null
    private var isInitialized = false

    suspend fun initialize(modelFileName: String = "llama-3.2-1b-it-gpu-int4.bin") = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        val modelFile = File(context.filesDir, "models/$modelFileName")
        if (!modelFile.exists()) {
            throw IllegalStateException("Model not found at: ${modelFile.absolutePath}")
        }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(512)            // Bounded generation for mobile battery
            .setTemperature(0.2f)         // Low temperature for factual precision
            .setTopK(40)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
        isInitialized = true
    }

    /**
     * Streams the answer token-by-token directly from local GPU/NPU memory.
     */
    fun generateAnswerStream(systemContext: String, userQuery: String): Flow<String> = callbackFlow {
        val inference = llmInference ?: throw IllegalStateException("LLM Engine not initialized")

        val prompt = formatChatPrompt(systemContext, userQuery)

        val streamingListener = LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(File(context.filesDir, "models/llama-3.2-1b-it-gpu-int4.bin").absolutePath)
                .setResultListener { partialResult, done ->
                    trySend(partialResult)
                    if (done) {
                        channel.close()
                    }
                }
                .setErrorListener { error ->
                    close(Exception("Inference error: ${error.message}"))
                }
                .build()
        )

        streamingListener.generateResponseAsync(prompt)

        awaitClose {
            // Clean up resources if flow cancellation occurs
        }
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
