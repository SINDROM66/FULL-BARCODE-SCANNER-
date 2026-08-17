package com.ugid.scanner

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class Record(
    val name: String,
    val dob: String,
    val nin: String,
    val cardNumber: String,
    val sex: String,
    val phoneNumber: String
)

object RecordManager {
    private const val FILE_NAME = "records.json"

    fun saveRecord(context: Context, record: Record) {
        val records = getRecords(context).toMutableList()
        records.add(record)
        val file = File(context.filesDir, FILE_NAME)
        file.writeText(Gson().toJson(records))
    }

    fun getRecords(context: Context): List<Record> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        val json = file.readText()
        if (json.isBlank()) return emptyList()
        val type = object : TypeToken<List<Record>>() {}.type
        return try {
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearAllRecords(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }

    fun deleteRecord(context: Context, record: Record) {
        val records = getRecords(context).toMutableList()
        records.remove(record)
        val file = File(context.filesDir, FILE_NAME)
        file.writeText(Gson().toJson(records))
    }
}
