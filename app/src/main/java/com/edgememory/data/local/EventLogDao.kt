package com.edgememory.data.local

import android.database.Cursor
import com.edgememory.data.model.BinaryCandidate
import com.edgememory.data.model.EventRecord
import com.edgememory.data.model.FtsSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteStatement

class EventLogDao(private val dbManager: DatabaseManager) {

    // Precompiled reusable insert statement for maximum write throughput
    private var insertStatement: SQLiteStatement? = null

    private fun getOrCompileInsertStatement(db: SQLiteDatabase): SQLiteStatement {
        return insertStatement ?: run {
            val sql = """
                INSERT INTO event_log (entity_urn, timestamp, action, source_app, raw_text, binary_embedding)
                VALUES (?, ?, ?, ?, ?, ?);
            """.trimIndent()
            db.compileStatement(sql).also { insertStatement = it }
        }
    }

    /**
     * 1. Append-Only Insert
     * Invariant: Never mutates existing rows. Inserts fresh chronological events.
     */
    suspend fun insertEvent(event: EventRecord): Long = withContext(Dispatchers.IO) {
        val db = dbManager.getWritableDatabase()
        db.beginTransaction()
        try {
            val stmt = getOrCompileInsertStatement(db)
            stmt.bindString(1, event.entityUrn)
            stmt.bindLong(2, event.timestamp)
            stmt.bindString(3, event.action)
            stmt.bindString(4, event.sourceApp)
            stmt.bindString(5, event.rawText)
            stmt.bindBlob(6, event.binaryEmbedding)

            val rowId = stmt.executeInsert()
            db.setTransactionSuccessful()
            rowId
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 2. FTS5 BM25 Keyword Search
     * Uses SQLite FTS5 rank function for exact token matching (PNRs, numbers, specific names).
     */
    suspend fun searchFts(query: String, limit: Int = 30): List<FtsSearchResult> = withContext(Dispatchers.IO) {
        val db = dbManager.getReadableDatabase()
        val results = mutableListOf<FtsSearchResult>()

        // Sanitize query to avoid FTS5 syntax errors with special punctuation
        val sanitizedQuery = query.replace("\"", "").trim()
        if (sanitizedQuery.isBlank()) return@withContext emptyList()

        val sql = """
            SELECT 
                e.event_id, 
                e.entity_urn, 
                e.raw_text, 
                e.timestamp,
                fts.rank AS bm25_rank
            FROM event_fts fts
            JOIN event_log e ON fts.rowid = e.event_id
            WHERE event_fts MATCH ?
            ORDER BY rank
            LIMIT ?;
        """.trimIndent()

        // Match query with prefix support for the final word
        val ftsPattern = "$sanitizedQuery*"

        db.rawQuery(sql, arrayOf(ftsPattern, limit.toString())).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow("event_id")
            val urnCol = cursor.getColumnIndexOrThrow("entity_urn")
            val textCol = cursor.getColumnIndexOrThrow("raw_text")
            val timeCol = cursor.getColumnIndexOrThrow("timestamp")
            val rankCol = cursor.getColumnIndexOrThrow("bm25_rank")

            while (cursor.moveToNext()) {
                results.add(
                    FtsSearchResult(
                        eventId = cursor.getLong(idCol),
                        entityUrn = cursor.getString(urnCol),
                        rawText = cursor.getString(textCol),
                        timestamp = cursor.getLong(timeCol),
                        bm25Score = cursor.getDouble(rankCol)
                    )
                )
            }
        }
        results
    }

    /**
     * 3. Streamed Binary BLOB Candidate Extractor
     * Pulls 64-byte vectors for the Stage-1 ARM NEON Hamming scan.
     * Only extracts IDs, URNs, and BLOBs (omits bulky text to minimize memory allocation).
     */
    suspend fun loadBinaryCandidates(sinceTimestamp: Long = 0L): List<BinaryCandidate> = withContext(Dispatchers.IO) {
        val db = dbManager.getReadableDatabase()
        val candidates = mutableListOf<BinaryCandidate>()

        val sql = """
            SELECT event_id, entity_urn, binary_embedding 
            FROM event_log 
            WHERE timestamp >= ?
            ORDER BY timestamp DESC;
        """.trimIndent()

        db.rawQuery(sql, arrayOf(sinceTimestamp.toString())).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow("event_id")
            val urnCol = cursor.getColumnIndexOrThrow("entity_urn")
            val blobCol = cursor.getColumnIndexOrThrow("binary_embedding")

            while (cursor.moveToNext()) {
                candidates.add(
                    BinaryCandidate(
                        eventId = cursor.getLong(idCol),
                        entityUrn = cursor.getString(urnCol),
                        binaryEmbedding = cursor.getBlob(blobCol)
                    )
                )
            }
        }
        candidates
    }

    /**
     * 4. Chronological Entity History Fetcher
     * Fetches the entire lifecycle of an entity (e.g. BOOKED -> RESCHEDULED -> CANCELLED).
     */
    suspend fun getEntityTimeline(entityUrn: String): List<EventRecord> = withContext(Dispatchers.IO) {
        val db = dbManager.getReadableDatabase()
        val timeline = mutableListOf<EventRecord>()

        val sql = """
            SELECT event_id, entity_urn, timestamp, action, source_app, raw_text, binary_embedding 
            FROM event_log 
            WHERE entity_urn = ? 
            ORDER BY timestamp ASC;
        """.trimIndent()

        db.rawQuery(sql, arrayOf(entityUrn)).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow("event_id")
            val urnCol = cursor.getColumnIndexOrThrow("entity_urn")
            val timeCol = cursor.getColumnIndexOrThrow("timestamp")
            val actionCol = cursor.getColumnIndexOrThrow("action")
            val appCol = cursor.getColumnIndexOrThrow("source_app")
            val textCol = cursor.getColumnIndexOrThrow("raw_text")
            val blobCol = cursor.getColumnIndexOrThrow("binary_embedding")

            while (cursor.moveToNext()) {
                timeline.add(
                    EventRecord(
                        eventId = cursor.getLong(idCol),
                        entityUrn = cursor.getString(urnCol),
                        timestamp = cursor.getLong(timeCol),
                        action = cursor.getString(actionCol),
                        sourceApp = cursor.getString(appCol),
                        rawText = cursor.getString(textCol),
                        binaryEmbedding = cursor.getBlob(blobCol)
                    )
                )
            }
        }
        timeline
    }

    /**
     * Fetches specific event records by their IDs (used to inflate top candidates).
     */
    suspend fun getEventsByIds(eventIds: List<Long>): List<EventRecord> = withContext(Dispatchers.IO) {
        if (eventIds.isEmpty()) return@withContext emptyList()

        val db = dbManager.getReadableDatabase()
        val records = mutableListOf<EventRecord>()
        val placeholders = eventIds.joinToString(",") { "?" }
        val args = eventIds.map { it.toString() }.toTypedArray()

        val sql = """
            SELECT event_id, entity_urn, timestamp, action, source_app, raw_text, binary_embedding 
            FROM event_log 
            WHERE event_id IN ($placeholders)
            ORDER BY timestamp ASC;
        """.trimIndent()

        db.rawQuery(sql, args).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow("event_id")
            val urnCol = cursor.getColumnIndexOrThrow("entity_urn")
            val timeCol = cursor.getColumnIndexOrThrow("timestamp")
            val actionCol = cursor.getColumnIndexOrThrow("action")
            val appCol = cursor.getColumnIndexOrThrow("source_app")
            val textCol = cursor.getColumnIndexOrThrow("raw_text")
            val blobCol = cursor.getColumnIndexOrThrow("binary_embedding")

            while (cursor.moveToNext()) {
                records.add(
                    EventRecord(
                        eventId = cursor.getLong(idCol),
                        entityUrn = cursor.getString(urnCol),
                        timestamp = cursor.getLong(timeCol),
                        action = cursor.getString(actionCol),
                        sourceApp = cursor.getString(appCol),
                        rawText = cursor.getString(textCol),
                        binaryEmbedding = cursor.getBlob(blobCol)
                    )
                )
            }
        }
        records
    }
}
