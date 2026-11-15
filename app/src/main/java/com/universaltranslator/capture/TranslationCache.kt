package com.universaltranslator.capture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/**
 * Persistent translation cache. Cache keys include normalized source text,
 * detected source language, target language and engine mode.
 */
class TranslationCache(context: Context) :
    SQLiteOpenHelper(context, "translation_cache.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE translations (
                cache_key TEXT PRIMARY KEY,
                translated_text TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No destructive migration in the initial release.
    }

    fun get(text: String, source: String, target: String, mode: String): String? {
        val key = key(text, source, target, mode)
        readableDatabase.query(
            "translations", arrayOf("translated_text"), "cache_key=?",
            arrayOf(key), null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun put(text: String, source: String, target: String, mode: String, translated: String) {
        if (translated.isBlank()) return
        val values = ContentValues().apply {
            put("cache_key", key(text, source, target, mode))
            put("translated_text", translated)
            put("updated_at", System.currentTimeMillis())
        }
        val db = writableDatabase
        db.insertWithOnConflict("translations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        // Keep the on-device cache bounded so long-running use cannot grow it forever.
        db.execSQL(
            "DELETE FROM translations WHERE cache_key IN (" +
                "SELECT cache_key FROM translations ORDER BY updated_at DESC LIMIT -1 OFFSET 5000)"
        )
    }

    private fun key(text: String, source: String, target: String, mode: String): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        val raw = "$source|$target|$mode|$normalized"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
