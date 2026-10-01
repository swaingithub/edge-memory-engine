package com.edgememory.data.model

data class EventRecord(
    val eventId: Long = 0L,
    val entityUrn: String,
    val timestamp: Long,
    val action: String,
    val sourceApp: String,
    val rawText: String,
    val binaryEmbedding: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EventRecord
        return eventId == other.eventId && binaryEmbedding.contentEquals(other.binaryEmbedding)
    }

    override fun hashCode(): Int {
        var result = eventId.hashCode()
        result = 31 * result + binaryEmbedding.contentHashCode()
        return result
    }
}

data class BinaryCandidate(
    val eventId: Long,
    val entityUrn: String,
    val binaryEmbedding: ByteArray
)

data class FtsSearchResult(
    val eventId: Long,
    val entityUrn: String,
    val rawText: String,
    val timestamp: Long,
    val bm25Score: Double
)
