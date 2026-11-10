package com.mina.legadostudio.network

/** Process-wide ownership shared by both capture producers and exact history deletion. */
object CaptureActivity {
    private val monitor = Any()
    private val active = mutableSetOf<String>()
    private val deleting = mutableSetOf<String>()

    fun start(contextId: String) = synchronized(monitor) {
        check(contextId !in deleting && active.add(contextId)) { "capture already owned" }
    }

    fun finish(contextId: String) = synchronized(monitor) { active.remove(contextId); Unit }
    fun isActive(contextId: String): Boolean = synchronized(monitor) { contextId in active }

    /** Reserve atomically against producer registration; no monitor is held across Room suspension. */
    suspend fun deleteIfInactive(contextId: String, delete: suspend () -> Unit): Boolean {
        val reserved = synchronized(monitor) {
            contextId !in active && deleting.add(contextId)
        }
        if (!reserved) return false
        try {
            delete()
            return true
        } finally {
            synchronized(monitor) { deleting.remove(contextId) }
        }
    }
}
