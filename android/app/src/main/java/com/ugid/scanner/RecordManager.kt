package com.ugid.scanner

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileReader
import java.io.FileWriter

data class ScannedRecord(
    val response: ParseResponse,
    val phoneNumber: String
)

class RecordManager(private val context: Context) {
    private val file = File(context.filesDir, "records.json")
    private val gson = Gson()

    fun saveRecord(record: ScannedRecord) {
        val records = loadRecords().toMutableList()
        records.add(record)
        FileWriter(file).use { writer ->
            gson.toJson(records, writer)
        }
    }

    fun loadRecords(): List<ScannedRecord> {
        if (!file.exists()) return emptyList()
        return try {
            FileReader(file).use { reader ->
                val type = object : TypeToken<List<ScannedRecord>>() {}.type
                gson.fromJson(reader, type) ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearAllRecords() {
        if (file.exists()) {
            file.delete()
        }
    }

    fun deleteRecord(record: ScannedRecord) {
        val records = loadRecords().toMutableList()
        records.remove(record)
        FileWriter(file).use { writer ->
            gson.toJson(records, writer)
        }
    }
}
