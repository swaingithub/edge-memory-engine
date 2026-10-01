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

        // 3. Initialize OpenHelper with WAL and SQLite configuration
        dbHelper = EncryptedOpenHelper(context, passphrase)
    }

    /**
     * Provides an active thread-safe read/write SQLCipher database connection.
     */
    fun getWritableDatabase(): SQLiteDatabase {
        return dbHelper.writableDatabase
    }

    /**
     * Provides an active thread-safe read-only SQLCipher database connection.
     */
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

    /**
     * Internal SQLiteOpenHelper implementation targeting SQLCipher.
     */
    private class EncryptedOpenHelper(
        private val appContext: Context,
        private val passphraseChars: CharArray
    ) : SQLiteOpenHelper(appContext, DB_NAME, null, DB_VERSION) {

        val writableDatabase: SQLiteDatabase
            get() = getWritableDatabase(passphraseChars)

        val readableDatabase: SQLiteDatabase
            get() = getReadableDatabase(passphraseChars)

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            // Enable Write-Ahead Logging for non-blocking concurrent reads and writes
            db.enableWriteAheadLogging()
            db.execSQL("PRAGMA foreign_keys = ON;")
            db.execSQL("PRAGMA synchronous = NORMAL;")
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.beginTransaction()
            try {
                // Read and execute initial DDL schema from assets/schema.sql
                val schemaDdl = appContext.assets.open("schema.sql").bufferedReader().use { it.readText() }
                
                // Split multi-statement DDL by semicolon
                schemaDdl.split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { sqlStatement ->
                        db.execSQL(sqlStatement)
                    }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Append-only invariant: Schema migrations strictly append new columns or indices
        }
    }

    /**
     * Manages hardware-backed KeyStore envelope encryption for the database passphrase.
     */
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
                // Decrypt existing stored passphrase using hardware TEE key
                val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
                val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
                cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)

                val encryptedBytes = Base64.decode(encPassphraseBase64, Base64.NO_WRAP)
                val rawPassphraseBytes = cipher.doFinal(encryptedBytes)

                val chars = String(rawPassphraseBytes, Charsets.UTF_8).toCharArray()
                rawPassphraseBytes.fill(0) // Clean sensitive bytes from memory
                chars
            } else {
                // Generate a fresh cryptographically secure 64-byte passphrase
                val rawPassphraseBytes = ByteArray(64)
                SecureRandom().nextBytes(rawPassphraseBytes)

                val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
                cipher.init(Cipher.ENCRYPT_MODE, masterKey)
                val iv = cipher.iv
                val encryptedBytes = cipher.doFinal(rawPassphraseBytes)

                // Persist encrypted envelope in private SharedPreferences
                prefs.edit()
                    .putString(PREF_ENCRYPTED_PASSPHRASE, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                    .putString(PREF_GCM_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .apply()

                val chars = String(rawPassphraseBytes, Charsets.UTF_8).toCharArray()
                rawPassphraseBytes.fill(0) // Clean sensitive bytes from memory
                chars
            }
        }

        private fun getOrCreateMasterKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                val entry = keyStore.getEntry(MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry
                return entry.secretKey
            }

            // Generate hardware-isolated master AES key inside TEE / StrongBox
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenSpec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false) // Allows background workers to process without user unlock
                .build()

            keyGenerator.init(keyGenSpec)
            return keyGenerator.generateKey()
        }
    }
}
