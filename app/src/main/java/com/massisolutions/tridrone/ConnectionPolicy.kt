package com.massisolutions.tridrone

/** Process-local ownership prevents competing transports and stale worker updates. */
object GnssConnectionOwner {
    private var owner: String? = null
    private var cancel: (() -> Unit)? = null
    @Synchronized fun claim(id: String, onCancel: () -> Unit) {
        cancel?.invoke()
        owner = id
        cancel = onCancel
    }
    @Synchronized fun current(id: String): Boolean = owner == id
    @Synchronized fun active(): Boolean = owner != null
    @Synchronized fun withCurrent(id: String, action: () -> Unit) { if (owner == id) action() }
    @Synchronized fun release(id: String) { if (owner == id) { owner = null; cancel = null } }
}

object NmeaEpoch {
    fun seconds(value: String): Double? {
        if (!value.matches(Regex("\\d{6}(?:\\.\\d+)?"))) return null
        val hour = value.substring(0, 2).toInt()
        val minute = value.substring(2, 4).toInt()
        val second = value.substring(4).toDoubleOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59 && second >= 0 && second < 60)
            hour * 3600.0 + minute * 60.0 + second else null
    }
    fun aligned(a: Double?, b: Double?): Boolean {
        if (a == null || b == null || !a.isFinite() || !b.isFinite()) return false
        val difference = kotlin.math.abs(a - b)
        return minOf(difference, 86400 - difference) in 0.0..1.0
    }
}

/** Receipt of a frozen/replayed receiver epoch must not refresh a live indicator. */
class ReceiverEpochTracker {
    private var previous: Double? = null
    fun accept(epoch: Double): Boolean {
        val old = previous
        val delta = if (old == null) 1.0 else (epoch - old + 86400) % 86400
        if (!epoch.isFinite() || epoch < 0 || epoch >= 86400 || delta <= 0 || delta >= 43200) return false
        previous = epoch
        return true
    }
}

object ConnectionPolicy {
    const val MAX_ATTEMPTS = 3
    const val FRESH_MS = 5000L
    const val INPUT_TIMEOUT_MS = 12000L
    fun retry(attempt: Int): Boolean = attempt in 1 until MAX_ATTEMPTS
    fun age(now: Long, received: Long): Long? = if (received > 0 && now >= received) now - received else null
    fun fresh(age: Long?): Boolean = age != null && age in 0..FRESH_MS
}

object ConnectionProfile {
    /** A literal IP avoids unbounded DNS lookup on the field acquisition worker. */
    fun validIpv4(host: String): Boolean = host.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")) &&
        host.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
}

/** RFC 4180 cells. Backslash escaping commas changes the raw message and is not CSV. */
object Csv {
    fun cell(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
        "\"${value.replace("\"", "\"\"")}\"" else value
    fun row(values: List<String>): String = values.joinToString(",") { cell(it) } + "\n"
    fun parse(line: String): List<String>? {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var closed = false
        var i = 0
        while (i < line.length) {
            val char = line[i]
            when {
                quoted && char == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                quoted && char == '"' -> { quoted = false; closed = true }
                quoted -> cell.append(char)
                char == ',' -> { cells.add(cell.toString()); cell.setLength(0); closed = false }
                char == '"' && cell.isEmpty() && !closed -> quoted = true
                char == '"' || closed -> return null
                else -> cell.append(char)
            }
            i++
        }
        if (quoted) return null
        cells.add(cell.toString())
        return cells
    }
}
