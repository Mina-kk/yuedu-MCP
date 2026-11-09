package com.mina.legadostudio.web

/** Atomic cross-picker ownership. Cancelled callers retain ownership until the picker returns. */
internal class SingleOperationGate {
    private var pending: Any? = null
    private var closed = false
    @Synchronized fun reserve(operation: Any): Boolean {
        if (closed || pending != null) return false
        pending = operation
        return true
    }
    @Synchronized fun current(): Any? = pending
    @Synchronized fun finish(operation: Any) {
        if (pending === operation) pending = null
    }
    @Synchronized fun close(): Any? {
        closed = true
        return pending.also { pending = null }
    }
}
