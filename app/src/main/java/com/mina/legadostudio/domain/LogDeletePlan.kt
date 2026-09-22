package com.mina.legadostudio.domain

/**
 * 日志页“全选 → 删除”的删除目标解析器（纯函数，无 Android 依赖，便于单测）。
 *
 * 四个 Tab 的删除目标统一收敛到这里，保证：
 * - OPERATION/HTTP：选中项先过 [toLongOrNull]，非数字（例如误留的崩溃文件名、
 *   其他 Tab 的陈旧选中项）一律剔除，再与当前视图可见 id 取交集——只删用户眼前看到的记录，
 *   不会因为跨 Tab / 跨日期的陈旧选中误删视图外数据，也不可能把非数字喂进 [String.toLong] 抛异常。
 * - CRASH/SNAPSHOT：只保留当前视图可见的文件名 / 快照 id，天然挡住路径穿越类输入。
 * - 长列表按 [MAX_BATCH] 切批调用 DAO，远离 SQLite 999 宿主变量上限（[chunk] 由调用方执行）。
 */
object LogDeletePlan {

    /** 单批 IN (:ids) 的容量上限：远低于 SQLite 999 变量上限，留足余量 */
    const val MAX_BATCH = 900

    /**
     * 解析数字 id 通道（操作日志 / HTTP 日志）。
     * @param selected 当前勾选的 id 字符串集合
     * @param visibleIds 当前 Tab 当前日期筛选下可见的 id 字符串（顺序即展示顺序）
     * @return 可安全删除的 Long id，保持可见列表顺序；空列表表示无需调用 DAO
     */
    fun resolveLogIds(selected: Set<String>, visibleIds: List<String>): List<Long> =
        visibleIds.asSequence()
            .filter { it in selected }
            .mapNotNull { it.toLongOrNull() }
            .toList()

    /**
     * 解析文件名 / 快照 id 通道（崩溃记录 / 诊断快照）。
     * 同样只认当前视图可见项，空列表表示无需调用删除。
     */
    fun resolveNames(selected: Set<String>, visibleIds: List<String>): List<String> =
        visibleIds.filter { it in selected }

    /**
     * 按 [MAX_BATCH] 切批，逐批执行 DAO 删除，避免单条 SQL 变量过多。
     * 空输入返回空批次列表（调用方据此跳过 DAO 调用）。
     */
    fun chunk(ids: List<Long>): List<List<Long>> = ids.chunked(MAX_BATCH)

    /** 字符串通道（快照 id）切批，语义同 [chunk]。 */
    fun chunkNames(ids: List<String>): List<List<String>> = ids.chunked(MAX_BATCH)
}
