package com.mina.legadostudio.diagnostic

/**
 * 进程内第一次组件加载故障（[ExceptionInInitializerError] / [NoClassDefFoundError] / 其它 [LinkageError]）。
 * JVM 把初始化失败的类标成 erroneous 后，后续调用只剩 NCDFE，必须把「首个故障」留住才能定位。
 */
object RuntimeFaultStore {
    data class Snapshot(
        val atMs: Long,
        val tool: String?,
        val errorClass: String,
        val causeClass: String?,
        val chain: String,
    ) {
        fun toPayload(): Map<String, Any?> = linkedMapOf(
            "atMs" to atMs,
            "tool" to tool,
            "errorClass" to errorClass,
            "causeClass" to causeClass,
            "chain" to chain,
        )
    }

    @Volatile private var first: Snapshot? = null

    fun record(error: Throwable, tool: String? = null): Snapshot {
        val snap = Snapshot(
            atMs = System.currentTimeMillis(),
            tool = tool,
            errorClass = error.javaClass.name,
            causeClass = error.cause?.javaClass?.name,
            chain = ExceptionDigest.chain(error),
        )
        val existing = first
        if (existing != null) return existing
        synchronized(this) {
            val raced = first
            if (raced != null) return raced
            first = snap
            return snap
        }
    }

    fun firstOrNull(): Snapshot? = first

    fun clear() {
        first = null
    }
}
