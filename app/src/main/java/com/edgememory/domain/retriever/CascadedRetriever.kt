package com.edgememory.domain.retriever

import com.edgememory.data.local.EventLogDao
import com.edgememory.data.model.EventRecord
import com.edgememory.data.model.FtsSearchResult
import com.edgememory.data.native.NativeHamming
import com.edgememory.domain.embedding.OnDeviceEmbedder
import com.edgememory.domain.quantization.BitPacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class ScoredCandidate(
    val eventId: Long,
    val entityUrn: String,
    val rrfScore: Double
)

data class RetrievalContext(
    val primaryEvents: List<EventRecord>,
    val entityTimelines: Map<String, List<EventRecord>>,
    val formattedTimelinePrompt: String
)

class CascadedRetriever(
    private val eventLogDao: EventLogDao,
    private val embedder: OnDeviceEmbedder
) {

    companion object {
        private const val RRF_K = 60.0             // Standard Cormack RRF smoothing constant
        private const val STAGE1_TOP_K = 50        // Candidate pool size per channel
        private const val FINAL_ENTITY_LIMIT = 3   // Top distinct entities to hydrate
    }

    /**
     * Executes the hybrid search cascade:
     * 1. Query Embedding & 1-Bit Binarization
     * 2. Parallel FTS5 BM25 + Native ARM NEON Hamming scan
     * 3. Reciprocal Rank Fusion (RRF)
     * 4. Entity timeline hydration for SLM context
     */
    suspend fun retrieve(queryText: String): RetrievalContext = coroutineScope {
        val queryFloats = embedder.embed(queryText)
        val queryBlob = BitPacker.pack(queryFloats) // Renamed from packFloatsTo1Bit to match actual code

        val ftsDeferred = async(Dispatchers.IO) {
            eventLogDao.searchFts(queryText, limit = STAGE1_TOP_K)
        }

        val binaryDeferred = async(Dispatchers.Default) {
            runBinaryHammingScan(queryBlob, topK = STAGE1_TOP_K)
        }

        val ftsResults = ftsDeferred.await()
        val binaryResults = binaryDeferred.await()

        val fusedCandidates = fuseRankings(ftsResults, binaryResults)

        val topEntityUrns = fusedCandidates
            .map { it.entityUrn }
            .distinct()
            .take(FINAL_ENTITY_LIMIT)

        val entityTimelines = mutableMapOf<String, List<EventRecord>>()
        for (urn in topEntityUrns) {
            val timeline = eventLogDao.getEntityTimeline(urn)
            if (timeline.isNotEmpty()) {
                entityTimelines[urn] = timeline
            }
        }

        // Inflate primary hit records
        val primaryEventIds = fusedCandidates.take(10).map { it.eventId }
        val primaryEvents = eventLogDao.getEventsByIds(primaryEventIds)

        // Format chronological timeline string for local SLM prompt
        val formattedPrompt = buildChronologicalContext(entityTimelines, primaryEvents)

        RetrievalContext(
            primaryEvents = primaryEvents,
            entityTimelines = entityTimelines,
            formattedTimelinePrompt = formattedPrompt
        )
    }

    /**
     * Executes fast native ARM NEON SIMD Hamming distance over stored 64-byte BLOBs.
     */
    private suspend fun runBinaryHammingScan(
        queryBlob: ByteArray,
        topK: Int
    ): List<Pair<Long, String>> = withContext(Dispatchers.Default) {
        val candidates = eventLogDao.loadBinaryCandidates()
        if (candidates.isEmpty()) return@withContext emptyList()

        val count = candidates.size
        val distances = IntArray(count)
        val candidateBlobs = Array(count) { candidates[it].binaryEmbedding }

        // Run batch C++ ARM NEON popcount
        NativeHamming.batchComputeDistances(queryBlob, candidateBlobs, distances)

        // Find top-K smallest Hamming distances
        return@withContext candidates.indices
            .asSequence()
            .map { idx -> Triple(candidates[idx].eventId, candidates[idx].entityUrn, distances[idx]) }
            .sortedBy { it.third } // Ascending distance (0 = identical)
            .take(topK)
            .map { Pair(it.first, it.second) }
            .toList()
    }

    /**
     * Merges FTS5 BM25 ranks and 1-Bit Hamming ranks using Reciprocal Rank Fusion.
     */
    private fun fuseRankings(
        ftsHits: List<FtsSearchResult>,
        binaryHits: List<Pair<Long, String>>
    ): List<ScoredCandidate> {
        val rrfScoreMap = mutableMapOf<Long, Double>()
        val urnMap = mutableMapOf<Long, String>()

        // Accumulate FTS ranks (1-based index)
        ftsHits.forEachIndexed { rank, hit ->
            val score = 1.0 / (RRF_K + (rank + 1))
            rrfScoreMap[hit.eventId] = (rrfScoreMap[hit.eventId] ?: 0.0) + score
            urnMap[hit.eventId] = hit.entityUrn
        }

        // Accumulate Binary Hamming ranks (1-based index)
        binaryHits.forEachIndexed { rank, hit ->
            val eventId = hit.first
            val score = 1.0 / (RRF_K + (rank + 1))
            rrfScoreMap[eventId] = (rrfScoreMap[eventId] ?: 0.0) + score
            urnMap[eventId] = hit.second
        }

        return rrfScoreMap.entries
            .asSequence()
            .map { entry ->
                ScoredCandidate(
                    eventId = entry.key,
                    entityUrn = urnMap[entry.key] ?: "unknown",
                    rrfScore = entry.value
                )
            }
            .sortedByDescending { it.rrfScore }
            .toList()
    }

    /**
     * Assembles a structured, chronological text timeline for the SLM prompt context.
     */
    private fun buildChronologicalContext(
        timelines: Map<String, List<EventRecord>>,
        standaloneEvents: List<EventRecord>
    ): String {
        val sb = StringBuilder()

        if (timelines.isNotEmpty()) {
            sb.append("HISTORICAL TIMELINES BY ENTITY:\n")
            for ((urn, events) in timelines) {
                sb.append("--- Entity: $urn ---\n")
                for (event in events) {
                    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        .format(java.util.Date(event.timestamp))
                    sb.append("  - [$dateStr] ACTION: ${event.action} | APP: ${event.sourceApp} | DETAILS: ${event.rawText}\n")
                }
            }
        } else if (standaloneEvents.isNotEmpty()) {
            sb.append("RELEVANT LOGGED EVENTS:\n")
            for (event in standaloneEvents.sortedBy { it.timestamp }) {
                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .format(java.util.Date(event.timestamp))
                sb.append("  - [$dateStr] ACTION: ${event.action} | DETAILS: ${event.rawText}\n")
            }
        } else {
            sb.append("No matching activity logs found.")
        }

        return sb.toString()
    }
}
