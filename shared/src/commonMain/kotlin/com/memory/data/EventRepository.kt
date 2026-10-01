package com.memory.data

import android.content.ContentValues
import android.content.Context
import net.sqlcipher.database.SQLiteDatabase

class EventRepository(context: Context) {
    private val dbHelper = AppDatabase(context)
    // NOTE: In production SQLCipher, writableDatabase requires a password payload.
    // e.g., dbHelper.getWritableDatabase("SecurePassphrase")

    fun insertEvent(
        entityUrn: String,
        timestamp: Long,
        action: String,
        sourceApp: String,
        rawText: String,
        binaryEmbedding: ByteArray
    ): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("entity_urn", entityUrn)
            put("timestamp", timestamp)
            put("action", action)
            put("source_app", sourceApp)
            put("raw_text", rawText)
            put("binary_embedding", binaryEmbedding)
        }
        return db.insert("event_log", null, values)
    }

    /**
     * Extracts all candidate BLOBs for Stage 1 (Fast Hamming Filter).
     */
    fun getAllEmbeddings(): List<Pair<Long, ByteArray>> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT event_id, binary_embedding FROM event_log", null)
        val results = mutableListOf<Pair<Long, ByteArray>>()
        while (cursor.moveToNext()) {
            results.add(Pair(cursor.getLong(0), cursor.getBlob(1)))
        }
        cursor.close()
        return results
    }

    /**
     * Stage 2: Fine Resolution using SQLite FTS5.
     * Joins top candidate event IDs with FTS table for BM25 ranking.
     */
    fun searchFtsForCandidates(candidateIds: List<Long>, queryText: String): List<String> {
        if (candidateIds.isEmpty()) return emptyList()

        val db = dbHelper.readableDatabase
        val idsString = candidateIds.joinToString(",")
        
        // Match queryText in FTS table among candidate IDs, ordered by BM25 rank
        val cursor = db.rawQuery("""
            SELECT e.raw_text 
            FROM event_fts f
            JOIN event_log e ON f.rowid = e.event_id
            WHERE event_fts MATCH ? AND f.rowid IN ($idsString)
            ORDER BY rank
            LIMIT 20
        """, arrayOf(queryText))
        
        val results = mutableListOf<String>()
        while (cursor.moveToNext()) {
            results.add(cursor.getString(0))
        }
        cursor.close()
        return results
    }

    /**
     * Entity Linking: Fetch all events chronologically to construct the SLM timeline.
     */
    fun getChronologicalEvents(entityUrn: String): List<String> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("""
            SELECT raw_text 
            FROM event_log 
            WHERE entity_urn = ? 
            ORDER BY timestamp ASC
        """, arrayOf(entityUrn))
        
        val results = mutableListOf<String>()
        while (cursor.moveToNext()) {
            results.add(cursor.getString(0))
        }
        cursor.close()
        return results
    }
}
