package com.mina.legadostudio.web

/** Main-thread state machine: cancelled acquisition must settle before a new owner can start. */
internal class VerificationLifecycle<T : Any> {
    private var owner: Any? = null
    private var cancelled = false
    var resource: T? = null
        private set
    val busy: Boolean get() = owner != null

    fun begin(): Any {
        check(!busy) { "请先关闭当前验证页或等待网络切换完成" }
        return Any().also { owner = it; cancelled = false }
    }

    fun canAttach(requester: Any): Boolean = owner === requester && !cancelled

    fun attach(requester: Any, value: T) {
        check(canAttach(requester) && resource == null) { "验证页打开已取消" }
        resource = value
    }

    fun cancel(destroy: (T) -> Boolean, release: (Any) -> Unit): Boolean {
        val current = owner ?: return true
        cancelled = true
        // acquire() may still install the proxy: the acquiring coroutine must finish its own lease.
        if (resource == null) return false
        return finish(current, destroy, release)
    }

    fun finish(requester: Any, destroy: (T) -> Boolean, release: (Any) -> Unit): Boolean {
        if (owner !== requester) return false
        cancelled = true
        val view = resource
        if (view != null && !runCatching { destroy(view) }.getOrDefault(false)) return false
        // A failed destroy retains owner/resource and therefore retains the network boundary.
        release(requester)
        resource = null
        owner = null
        return true
    }
}
