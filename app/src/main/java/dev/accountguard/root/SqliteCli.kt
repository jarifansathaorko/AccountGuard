package dev.accountguard.root

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Privileged SQLite CLI helper.
 * Executed via `/system/bin/app_process` in a root shell context (`su`).
 * This acts as a reliable fallback when native `sqlite3` CLI is unavailable.
 */
object SqliteCli {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: SqliteCli <dbPath> <base64SqlOrRawSql>")
            System.exit(1)
        }

        val dbPath = args[0]
        if (args.size < 2) {
            testDbAccess(dbPath)
            return
        }

        val sqlPayload = args[1]
        val sql = decodeSql(sqlPayload)

        var database: SQLiteDatabase? = null
        try {
            val upper = sql.trim().uppercase()
            val isQuery = upper.startsWith("SELECT") || upper.startsWith("PRAGMA")

            database = try {
                if (isQuery) {
                    SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
                } else {
                    SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE)
                }
            } catch (openEx: Throwable) {
                // If readwrite failed on query or write, attempt fallback
                if (isQuery) throw openEx
                SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE)
            }

            if (isQuery) {
                database.rawQuery(sql, null).use { cursor ->
                    val colCount = cursor.columnCount
                    while (cursor.moveToNext()) {
                        val sb = StringBuilder()
                        for (i in 0 until colCount) {
                            if (i > 0) sb.append("|")
                            sb.append(cursor.getString(i) ?: "")
                        }
                        println(sb.toString())
                    }
                }
            } else {
                database.execSQL(sql)
            }
            System.exit(0)
        } catch (e: Throwable) {
            System.err.println("SQLite error: ${e.message}")
            System.exit(3)
        } finally {
            try {
                database?.close()
            } catch (_: Throwable) {}
        }
    }

    private fun testDbAccess(dbPath: String) {
        var database: SQLiteDatabase? = null
        try {
            database = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
            database.rawQuery("SELECT COUNT(*) FROM accounts;", null).use { cursor ->
                if (cursor.moveToNext()) {
                    val count = cursor.getInt(0)
                    println(count.toString())
                }
            }
            System.exit(0)
        } catch (e: Throwable) {
            System.err.println("DB test failed: ${e.message}")
            System.exit(3)
        } finally {
            try {
                database?.close()
            } catch (_: Throwable) {}
        }
    }

    private fun decodeSql(payload: String): String {
        return try {
            val bytes = try {
                android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            } catch (_: Throwable) {
                java.util.Base64.getDecoder().decode(payload)
            }
            String(bytes, Charsets.UTF_8).trim()
        } catch (_: Throwable) {
            payload.trim()
        }
    }
}
