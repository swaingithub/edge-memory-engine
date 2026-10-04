package com.edgememory.service.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.edgememory.data.local.DatabaseManager
import com.edgememory.domain.embedding.OnDeviceEmbedder
import com.edgememory.domain.quantization.BitPacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class NightlyCompactionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "NightlyCompaction"
        const val WORK_NAME = "edge_memory_nightly_compaction"

        /**
         * Schedules the worker to run periodically once per day,
         * exclusively when the phone is charging and idle.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build()

            val dailyWorkRequest = PeriodicWorkRequestBuilder<NightlyCompactionWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 2,
                flexTimeIntervalUnit = TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                dailyWorkRequest
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting nightly compaction job under idle/charging constraints.")

        val dbManager = DatabaseManager.getInstance(applicationContext)
        val db = dbManager.getWritableDatabase()
        val embedder = OnDeviceEmbedder(applicationContext)

        try {
            embedder.initialize()

            // 1. Calculate boundaries for the previous calendar day
            val calendar = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfDay = calendar.timeInMillis
            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)

            calendar.set(Calendar.HOUR_OF_DAY, 23)
            calendar.set(Calendar.MINUTE, 59)
            calendar.set(Calendar.SECOND, 59)
            calendar.set(Calendar.MILLISECOND, 999)
            val endOfDay = calendar.timeInMillis

            // 2. Check if day is already compacted
            var alreadyCompacted = false
            db.rawQuery("SELECT 1 FROM daily_summaries WHERE day_date = ?", arrayOf(dateKey)).use { cursor ->
                if (cursor.moveToFirst()) alreadyCompacted = true
            }

            if (alreadyCompacted) {
                Log.i(TAG, "Day $dateKey is already compacted. Skipping summary extraction.")
                return@withContext Result.success()
            }

            // 3. Extract raw events for that 24-hour window
            val rawEvents = mutableListOf<String>()
            val fetchQuery = """
                SELECT timestamp, action, source_app, raw_text 
                FROM event_log 
                WHERE timestamp BETWEEN ? AND ? 
                ORDER BY timestamp ASC;
            """.trimIndent()

            db.rawQuery(fetchQuery, arrayOf(startOfDay.toString(), endOfDay.toString())).use { cursor ->
                val timeCol = cursor.getColumnIndexOrThrow("timestamp")
                val actionCol = cursor.getColumnIndexOrThrow("action")
                val appCol = cursor.getColumnIndexOrThrow("source_app")
                val textCol = cursor.getColumnIndexOrThrow("raw_text")

                val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
                while (cursor.moveToNext()) {
                    val timeStr = timeFmt.format(cursor.getLong(timeCol))
                    val action = cursor.getString(actionCol)
                    val app = cursor.getString(appCol)
                    val text = cursor.getString(textCol)
                    rawEvents.add("[$timeStr] ($app) $action: $text")
                }
            }

            if (rawEvents.isEmpty()) {
                Log.i(TAG, "No activity logged for $dateKey. Inserting empty marker.")
                insertDailySummary(db, dateKey, "No notable activity.", ByteArray(48))
                return@withContext Result.success()
            }

            // 4. Compact into structured micro-facts
            val compactedTriplets = extractMicroFacts(rawEvents)

            // 5. Generate 1-bit binary embedding of the daily digest
            val floatEmbedding = embedder.embed(compactedTriplets)
            val binaryBlob = BitPacker.pack(floatEmbedding)

            // 6. Write to daily_summaries L1 index
            insertDailySummary(db, dateKey, compactedTriplets, binaryBlob)
            Log.i(TAG, "Successfully compacted $dateKey (${rawEvents.size} events -> ${compactedTriplets.length} chars).")

            // 7. Reclaim SQLite WAL storage
            db.execSQL("PRAGMA wal_checkpoint(TRUNCATE);")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Compaction failed: ${e.message}", e)
            Result.retry()
        } finally {
            embedder.close()
        }
    }

    /**
     * Synthesizes chronological raw events into structured micro-fact triplets.
     * When an on-device SLM runtime (e.g. llama.cpp) is bound, prompt it with this template.
     */
    private fun extractMicroFacts(events: List<String>): String {
        val eventContext = events.joinToString("\n")

        // Structured prompt template for local SLM compaction
        val prompt = """
            Extract structured facts from the raw logs below.
            Format as compact triplets: [DATE_TIME | ACTION | DETAILS]
            Focus exclusively on bookings, transactions, key communications, and updates.
            Omit repetitive UI noise.
            
            RAW LOGS:
            $eventContext
            
            MICRO-FACT TRIPLETS:
        """.trimIndent()

        // Fallback rule-based compactor if SLM is not active in background
        return ruleBasedCompactor(events)
    }

    private fun ruleBasedCompactor(events: List<String>): String {
        val keywords = listOf("book", "ticket", "order", "paid", "flight", "train", "cancel", "meet", "reschedul")
        val significant = events.filter { event ->
            keywords.any { kw -> event.contains(kw, ignoreCase = true) }
        }

        return if (significant.isNotEmpty()) {
            significant.take(15).joinToString(" | ")
        } else {
            events.take(5).joinToString(" | ")
        }
    }

    private fun insertDailySummary(
        db: net.sqlcipher.database.SQLiteDatabase,
        dateKey: String,
        summaryText: String,
        binaryBlob: ByteArray
    ) {
        val sql = """
            INSERT OR REPLACE INTO daily_summaries (day_date, summary_text, summary_embedding)
            VALUES (?, ?, ?);
        """.trimIndent()

        val stmt = db.compileStatement(sql)
        stmt.bindString(1, dateKey)
        stmt.bindString(2, summaryText)
        stmt.bindBlob(3, binaryBlob)
        stmt.executeInsert()
    }
}
