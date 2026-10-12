package com.massisolutions.tridrone

object NmeaGga {
    fun valid(sentence: String): Boolean {
        if (!sentence.startsWith('$') || sentence.length !in 7..256 || sentence.any { it.code !in 32..126 }) return false
        val star = sentence.indexOf('*')
        if (star < 1 || star + 3 != sentence.length) return false
        val expected = sentence.substring(star + 1).toIntOrNull(16) ?: return false
        var checksum = 0
        for (i in 1 until star) checksum = checksum xor sentence[i].code
        return checksum == expected
    }
    /** Validated receiver fields; no inferred datum or survey-grade coordinates. */
    fun parse(sentence: String): List<String>? {
        if (!valid(sentence)) return null
        val f = sentence.substringBefore('*').removePrefix("$").split(',')
        if (!f.firstOrNull().orEmpty().matches(Regex("[A-Z]{2}GGA")) || f.size != 15) return null
        if (NmeaEpoch.seconds(f[1]) == null) return null
        fun degrees(raw: String, hemisphere: String, latitude: Boolean): String? {
            if (hemisphere !in if (latitude) listOf("N", "S") else listOf("E", "W")) return null
            if (!raw.matches(Regex(if (latitude) "\\d{4}(?:\\.\\d+)?" else "\\d{5}(?:\\.\\d+)?"))) return null
            val value = raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            val degree = kotlin.math.floor(value / 100)
            val minute = value - degree * 100
            val maximum = if (latitude) 90 else 180
            if (minute < 0 || minute >= 60 || degree > maximum || degree == maximum.toDouble() && minute != 0.0) return null
            val decimal = degree + minute / 60.0
            return (if (hemisphere == "S" || hemisphere == "W") -decimal else decimal).toString()
        }
        val lat = degrees(f[2], f[3], true) ?: return null
        val lon = degrees(f[4], f[5], false) ?: return null
        if (f[6].toIntOrNull() !in 0..8 || f[7].toIntOrNull() !in 0..99) return null
        for (index in listOf(8, 9, 11, 13)) {
            if (f[index].isNotBlank()) {
                val value = f[index].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
                if (index in listOf(8, 13) && value < 0) return null
            }
        }
        if (f[9].isNotBlank() && f[10] != "M" || f[11].isNotBlank() && f[12] != "M") return null
        return listOf(f[1], lat, lon, f[6], f[7], f[8], f[9], f[11], f[13], f[14])
    }
}

object NmeaGst {
    fun parse(sentence: String): Pair<Double, Double>? {
        if (!NmeaGga.valid(sentence)) return null
        val f = sentence.substringBefore('*').removePrefix("$").split(',')
        if (!f.firstOrNull().orEmpty().matches(Regex("[A-Z]{2}GST")) || f.size != 9) return null
        if (NmeaEpoch.seconds(f[1]) == null) return null
        val values = listOf(6, 7, 8).map { f[it].toDoubleOrNull()?.takeIf { value -> value.isFinite() && value >= 0 } ?: return null }
        val horizontal = kotlin.math.hypot(values[0], values[1])
        return if (horizontal.isFinite()) Pair(horizontal, values[2]) else null
    }
}
