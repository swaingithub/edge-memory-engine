package com.edgememory.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DatabaseManager private constructor(private val context: Context) {

    private val dbHelper: EncryptedOpenHelper

    init {
        // 1. Initialize native SQLCipher binary libraries
        SQLiteDatabase.loadLibs(context)

        // 2. Resolve or generate hardware-backed database passphrase
        val passphrase = KeyStoreHelper.getOrCreateDatabasePassphrase(context)

        // 3. Initialize OpenHelper
        dbHelper = EncryptedOpenHelper(context, passphrase)
    }

    fun getWritableDatabase(): SQLiteDatabase {
        return dbHelper.writableDatabase
    }

    fun getReadableDatabase(): SQLiteDatabase {
        return dbHelper.readableDatabase
    }

    companion object {
        private const val DB_NAME = "edge_memory_encrypted.db"
        private const val DB_VERSION = 1

        @Volatile
        private var INSTANCE: DatabaseManager? = null

        fun getInstance(context: Context): DatabaseManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private class EncryptedOpenHelper(
        context: Context,
        private val passphraseChars: CharArray
    ) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        val writableDatabase: SQLiteDatabase
            get() = getWritableDatabase(passphraseChars)

        val readableDatabase: SQLiteDatabase
            get() = getWritableDatabase(passphraseChars) // Workaround SQLCipher bug where read-only creation fails

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.enableWriteAheadLogging()
            db.execSQL("PRAGMA foreign_keys = ON;")
            db.execSQL("PRAGMA synchronous = NORMAL;")
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.beginTransaction()
            try {
                // 1. Event Log table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS event_log (
                        event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        entity_urn TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        action TEXT NOT NULL,
                        source_app TEXT NOT NULL,
                        raw_text TEXT NOT NULL,
                        binary_embedding BLOB NOT NULL
                    )
                """.trimIndent())

                // 2. Indices
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_entity_time ON event_log(entity_urn, timestamp ASC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_time ON event_log(timestamp DESC)")

                // 3. FTS5 Virtual Table
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS event_fts USING fts5(
                        raw_text,
                        content='event_log',
                        content_rowid='event_id',
                        tokenize='porter unicode61'
                    )
                """.trimIndent())

                // 4. Synchronization Trigger (Keeps complete BEGIN ... END intact)
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS trg_event_ai AFTER INSERT ON event_log BEGIN
                        INSERT INTO event_fts(rowid, raw_text) VALUES (new.event_id, new.raw_text);
                    END
                """.trimIndent())

                // 5. Daily Summaries Table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS daily_summaries (
                        day_date TEXT PRIMARY KEY,
                        summary_text TEXT NOT NULL,
                        summary_embedding BLOB NOT NULL
                    )
                """.trimIndent())

                // 6. Seed a sample test event
                db.execSQL("""
                    INSERT INTO event_log (entity_urn, timestamp, action, source_app, raw_text, binary_embedding)
                    VALUES (
                        'urn:app:irctc',
                        ${System.currentTimeMillis()},
                        'BOOKED',
                        'com.irctc',
                        'Train 12004 Lucknow Shatabdi booked to Kanpur Central on 30 Sept. PNR 2849103948.',
                        zeroblob(64)
                    );
                """.trimIndent())

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Append-only invariant
        }
    }

    private object KeyStoreHelper {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "edge_memory_master_key"
        private const val PREFS_FILE = "edge_memory_vault_prefs"
        private const val PREF_ENCRYPTED_PASSPHRASE = "enc_db_pass"
        private const val PREF_GCM_IV = "enc_gcm_iv"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"

        fun getOrCreateDatabasePassphrase(context: Context): CharArray {
            val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            val encPassphraseBase64 = prefs.getString(PREF_ENCRYPTED_PASSPHRASE, null)
            val ivBase64 = prefs.getString(PREF_GCM_IV, null)

            val masterKey = getOrCreateMasterKey()

            return if (encPassphraseBase64 != null && ivBase64 != null) {
                val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
                val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
                cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)

                val encryptedBytes = Base64.decode(encPassphraseBase64, Base64.NO_WRAP)
                val rawPassphraseBytes = cipher.doFinal(encryptedBytes)

                val chars = String(rawPassphraseBytes, Charsets.UTF_8).toCharArray()
                rawPassphraseBytes.fill(0)
                chars
            } else {
                val rawPassphraseBytes = ByteArray(64)
                SecureRandom().nextBytes(rawPassphraseBytes)

                val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
                cipher.init(Cipher.ENCRYPT_MODE, masterKey)
                val iv = cipher.iv
                val encryptedBytes = cipher.doFinal(rawPassphraseBytes)

                prefs.edit()
                    .putString(PREF_ENCRYPTED_PASSPHRASE, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                    .putString(PREF_GCM_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .apply()

                val chars = String(rawPassphraseBytes, Charsets.UTF_8).toCharArray()
                rawPassphraseBytes.fill(0)
                chars
            }
        }

        private fun getOrCreateMasterKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                val entry = keyStore.getEntry(MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry
                return entry.secretKey
            }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenSpec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()

            keyGenerator.init(keyGenSpec)
            return keyGenerator.generateKey()
        }
    }
}
