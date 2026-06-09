package com.fireflisinfotech.ffitbt

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PrintLog(
    val id: Long,
    val systemJobId: String,
    val title: String,
    val printerType: String,
    val printerTarget: String,
    val status: String, // "queued", "printing", "completed", "failed", "cancelled"
    val timestamp: Long,
    val errorMsg: String?
)

class PrintDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "print_logs.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_LOGS = "print_logs"

        private const val KEY_ID = "id"
        private const val KEY_SYSTEM_JOB_ID = "system_job_id"
        private const val KEY_TITLE = "title"
        private const val KEY_PRINTER_TYPE = "printer_type"
        private const val KEY_PRINTER_TARGET = "printer_target"
        private const val KEY_STATUS = "status"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_ERROR_MSG = "error_msg"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE " + TABLE_LOGS + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_SYSTEM_JOB_ID + " TEXT,"
                + KEY_TITLE + " TEXT,"
                + KEY_PRINTER_TYPE + " TEXT,"
                + KEY_PRINTER_TARGET + " TEXT,"
                + KEY_STATUS + " TEXT,"
                + KEY_TIMESTAMP + " INTEGER,"
                + KEY_ERROR_MSG + " TEXT" + ")")
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        onCreate(db)
    }

    fun insertLog(systemJobId: String, title: String, printerType: String, printerTarget: String, status: String): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(KEY_SYSTEM_JOB_ID, systemJobId)
            put(KEY_TITLE, title)
            put(KEY_PRINTER_TYPE, printerType)
            put(KEY_PRINTER_TARGET, printerTarget)
            put(KEY_STATUS, status)
            put(KEY_TIMESTAMP, System.currentTimeMillis())
            put(KEY_ERROR_MSG, "")
        }
        return db.insert(TABLE_LOGS, null, values)
    }

    fun updateStatus(systemJobId: String, status: String, errorMsg: String = "") {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(KEY_STATUS, status)
            put(KEY_ERROR_MSG, errorMsg)
        }
        db.update(TABLE_LOGS, values, "$KEY_SYSTEM_JOB_ID = ?", arrayOf(systemJobId))
    }

    fun getAllLogs(): List<PrintLog> {
        val list = mutableListOf<PrintLog>()
        val db = this.readableDatabase
        var cursor: android.database.Cursor? = null
        try {
            cursor = db.rawQuery("SELECT * FROM $TABLE_LOGS ORDER BY $KEY_TIMESTAMP DESC", null)
            if (cursor.moveToFirst()) {
                do {
                    val log = PrintLog(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ID)),
                        systemJobId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_SYSTEM_JOB_ID)),
                        title = cursor.getString(cursor.getColumnIndexOrThrow(KEY_TITLE)),
                        printerType = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PRINTER_TYPE)),
                        printerTarget = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PRINTER_TARGET)),
                        status = cursor.getString(cursor.getColumnIndexOrThrow(KEY_STATUS)),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_TIMESTAMP)),
                        errorMsg = cursor.getString(cursor.getColumnIndexOrThrow(KEY_ERROR_MSG))
                    )
                    list.add(log)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return list
    }

    fun clearAllLogs() {
        val db = this.writableDatabase
        db.delete(TABLE_LOGS, null, null)
    }
}
