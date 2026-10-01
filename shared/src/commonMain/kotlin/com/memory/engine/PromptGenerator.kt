package com.memory.engine

object PromptGenerator {
    /**
     * Constructs a chronologically ordered context prompt for the local SLM using the timeline format.
     */
    fun buildTimelinePrompt(question: String, chronologicalEvents: List<String>): String {
        val timelineText = chronologicalEvents.joinToString(separator = "\n") { "- $it" }
        
        return """
            <|im_start|>system
            You are an on-device personal memory assistant. Use ONLY the historical event timeline below.
            If an event was modified, rescheduled, or cancelled, state the complete progression (both original and final states).
            
            TIMELINE:
            $timelineText
            <|im_end|>
            <|im_start|>user
            $question
            <|im_end|>
        """.trimIndent()
    }
}
