package com.massisolutions.tridrone

/** Hardware-neutral policy; denial remains latched until explicit Connect or physical detach. */
class UsbConnectionPolicy {
    private val denied = mutableSetOf<Int>()
    private var failures = 0
    private var nextAttemptMillis = 0L
    var selectedDeviceId: Int? = null
        private set
    fun select(id: Int?) { selectedDeviceId = id; denied.clear(); connected() }
    fun permissionDenied(id: Int) { denied.add(id) }
    fun detached(id: Int) { denied.remove(id); if (selectedDeviceId == id) selectedDeviceId = null; connected() }
    fun mayRequestPermission(id: Int) = id !in denied
    fun choose(ids: List<Int>): Int? = selectedDeviceId?.takeIf { it in ids }
        ?: if (selectedDeviceId == null && ids.size == 1) ids.single() else null
    fun canAttempt(nowMillis: Long) = nowMillis >= nextAttemptMillis
    fun failed(nowMillis: Long) {
        val delay = (1000L shl failures.coerceAtMost(4)).coerceAtMost(15000L)
        failures = (failures + 1).coerceAtMost(5)
        nextAttemptMillis = nowMillis + delay
    }
    fun connected() { failures = 0; nextAttemptMillis = 0 }
}
