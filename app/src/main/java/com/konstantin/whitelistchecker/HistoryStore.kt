package com.konstantin.whitelistchecker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryRecord(
    val timestamp: Long,
    val whitelistReachable: Int,
    val nonWhitelistReachable: Int,
    val status: String // "on" / "off" / "unavailable" / "unknown"
) {
    val verdict: Verdict? get() = Verdict.fromCode(status)
}

/**
 * История проверок в SQLite.
 *
 * Раньше на каждую запись создавался новый SQLiteOpenHelper и никогда не
 * закрывался — держим один экземпляр на процесс.
 */
class HistoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "history.db"
        private const val DB_VERSION = 1
        private const val RETENTION_DAYS = 45L

        @Volatile
        private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) {
                instance ?: HistoryStore(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                whitelist_reachable INTEGER NOT NULL,
                nonwhitelist_reachable INTEGER NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_history_timestamp ON history(timestamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS history")
        onCreate(db)
    }

    fun record(whitelistReachable: Int, nonWhitelistReachable: Int, status: String) {
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("whitelist_reachable", whitelistReachable)
            put("nonwhitelist_reachable", nonWhitelistReachable)
            put("status", status)
        }
        writableDatabase.insert("history", null, values)

        // Не даём базе расти бесконечно — храним только последние 45 дней
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24 * 60 * 60 * 1000
        writableDatabase.delete("history", "timestamp < ?", arrayOf(cutoff.toString()))
    }

    fun record(whitelistReachable: Int, nonWhitelistReachable: Int, verdict: Verdict) {
        record(whitelistReachable, nonWhitelistReachable, verdict.code)
    }

    fun getRecordsBetween(startMillis: Long, endMillis: Long): List<HistoryRecord> {
        val results = mutableListOf<HistoryRecord>()
        val cursor = readableDatabase.rawQuery(
            "SELECT timestamp, whitelist_reachable, nonwhitelist_reachable, status FROM history " +
                "WHERE timestamp >= ? AND timestamp < ? ORDER BY timestamp ASC",
            arrayOf(startMillis.toString(), endMillis.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    HistoryRecord(
                        timestamp = it.getLong(0),
                        whitelistReachable = it.getInt(1),
                        nonWhitelistReachable = it.getInt(2),
                        status = it.getString(3)
                    )
                )
            }
        }
        return results
    }
}
