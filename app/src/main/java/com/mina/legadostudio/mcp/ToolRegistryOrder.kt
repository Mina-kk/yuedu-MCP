package com.mina.legadostudio.mcp

/**
 * 工具注册顺序的纯逻辑（JVM 可测，不依赖 Android）。
 *
 * `StudioMcpServer` 的 `fullToolSpecs` / `localHandlers` 是 ConcurrentHashMap：哈希顺序
 * 与注册顺序无关。前端 `tools.list` 按顺序渲染，因此这里维护一份注册顺序快照，
 * 并按它重排目录。
 */
internal object ToolRegistryOrder {

    /** 登记一个工具名：第一次出现的位置即顺序，重复注册不移动。 */
    fun register(order: MutableList<String>, name: String) {
        if (name.isEmpty()) return
        if (!order.contains(name)) order += name
    }

    /**
     * 按 [order] 重排 [registry]；快照里没有的名字追加在末尾（绝不丢工具）。
     * [key] 取条目的名字，避免调用方再建一份映射。
     */
    fun <T> arrange(order: List<String>, registry: Map<String, T>, key: (T) -> String): List<T> {
        val result = ArrayList<T>(registry.size)
        order.forEach { name -> registry[name]?.let { result += it } }
        val seen = result.mapTo(HashSet()) { key(it) }
        registry.values.forEach { value -> if (seen.add(key(value))) result += value }
        return result
    }
}
