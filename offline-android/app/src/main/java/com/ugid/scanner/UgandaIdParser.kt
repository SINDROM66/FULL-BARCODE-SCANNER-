package com.ugid.scanner

import android.util.Base64

data class ParsedRecord(
    val name: String,
    val dob: String,
    val nin: String,
    val cardNumber: String,
    val sex: String
)

object UgandaIdParser {
    fun parse(payload: String): ParsedRecord? {
        try {
            val parts = payload.split("[FNG]")
            if (parts.isEmpty()) return null
            val firstPart = parts[0]
            val fields = firstPart.split(";")
            if (fields.size <= 7) return null

            val surname = try { String(Base64.decode(fields[0], Base64.DEFAULT)).trim() } catch(e:Exception){""}
            val given = try { String(Base64.decode(fields[1], Base64.DEFAULT)).trim() } catch(e:Exception){""}
            val other = try { String(Base64.decode(fields[2], Base64.DEFAULT)).trim() } catch(e:Exception){""}
            val name = listOf(surname, given, other).filter { it.isNotEmpty() }.joinToString(" ")

            val dob = fields[3]
            var sex = ""
            // Attempt to find sex in index 4 or 5
            try {
                val s1 = String(Base64.decode(fields[4], Base64.DEFAULT)).trim()
                if (s1 == "M" || s1 == "F") sex = s1
            } catch(e:Exception){}
            try {
                if (sex.isEmpty() && fields.size > 5) {
                    val s2 = String(Base64.decode(fields[5], Base64.DEFAULT)).trim()
                    if (s2 == "M" || s2 == "F") sex = s2
                }
            } catch(e:Exception){}

            val nin = fields[6]
            val cardNumber = fields[7]

            return ParsedRecord(name, dob, nin, cardNumber, sex)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
