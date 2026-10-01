package com.memory.data

import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper

/**
 * SQLCipher implementation of the append-only event store with FTS5.
 */
class AppDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "memory_engine.db"
        private const val DATABASE_VERSION = 1
    }

    init {
        SQLiteDatabase.loadLibs(context)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("PRAGMA journal_mode = WAL;")
        db.execSQL("PRAGMA synchronous = NORMAL;")

        // Append-Only Event Store
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS event_log (
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                entity_urn TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                action TEXT NOT NULL,
                source_app TEXT NOT NULL,
                raw_text TEXT NOT NULL,
                binary_embedding BLOB NOT NULL
            );
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entity_time ON event_log(entity_urn, timestamp ASC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_time ON event_log(timestamp DESC);")

        // Exact Keyword Matching Table
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS event_fts USING fts5(
                raw_text,
                content='event_log',
                content_rowid='event_id',
                tokenize='porter unicode61'
            );
        """)

        // Triggers for FTS sync
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS trg_event_ai AFTER INSERT ON event_log BEGIN
                INSERT INTO event_fts(rowid, raw_text) VALUES (new.event_id, new.raw_text);
            END;
        """)

        // L1 Daily Digest Table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS daily_summaries (
                day_date TEXT PRIMARY KEY,
                summary_text TEXT NOT NULL,
                summary_embedding BLOB NOT NULL
            );
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations should maintain the append-only invariant
    }
}
