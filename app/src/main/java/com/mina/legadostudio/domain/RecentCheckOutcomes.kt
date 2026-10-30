package com.mina.legadostudio.domain

import java.util.concurrent.ConcurrentHashMap

object RecentCheckOutcomes {
    data class Outcome(val hadFailure: Boolean, val atMs: Long)

    private const val CAPACITY = 500
    private val outcomes = ConcurrentHashMap<String, Outcome>()
    private val lock = Any()

    fun record(key: String, hadFailure: Boolean, atMs: Long = System.currentTimeMillis()) {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return
        synchronized(lock) {
            outcomes[normalizedKey] = Outcome(hadFailure, atMs)
            while (outcomes.size > CAPACITY) {
                val oldestKey = outcomes.entries.minByOrNull { it.value.atMs }?.key ?: break
                outcomes.remove(oldestKey)
            }
        }
    }

    fun get(key: String): Outcome? = outcomes[key.trim()]

    internal fun clear() = synchronized(lock) { outcomes.clear() }
}
