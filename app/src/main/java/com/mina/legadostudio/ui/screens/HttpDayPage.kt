package com.mina.legadostudio.ui.screens

import com.mina.legadostudio.data.db.HttpLogSummary
import java.time.LocalDate
import java.time.ZoneId

/**
 * 普通 HTTP 按天滚动窗口：一页 [HTTP_DAY_PAGE_SIZE]，keyset（id<beforeId）向下翻，无固定总上限。
 *
 * 纯状态机（无 Compose / 无 DAO 依赖），从旧 Compose 日志页原样提取，行为不变：
 * mergeBelow/mergeNewer/removeIds/beginLoadMore/foldNextPage 由 HttpDayPageStateTest 锁住。
 * 前端（Rust/Vue）按同一语义实现分页窗口，本类型保留为语义基准与回归锁。
 */
internal data class HttpDayPage(
    /** 本页归属的日期（yyyy-MM-dd 本地时区）：异步回填必须对齐此键，换日期即整页作废。 */
    val dateKey: String,
    /** 该日普通 HTTP（SQL 层排除 cap:%）真实总数：桶计数/导出文案只以它为准，禁用窗口长度伪称全日。 */
    val total: Int,
    /** 加载窗口打开瞬间的最大 id：高于它的行是「新插入」，走增量通道并入页头，不占本窗口额度。 */
    val baselineId: Long,
    val entries: List<HttpLogSummary> = emptyList(),
    /** 已取到的最小 id：下一页游标。null/0 = 尚未首载。 */
    val minId: Long? = null,
    val loading: Boolean = false,
    /** 本日已翻到底（最后一页不足页大小）。 */
    val exhausted: Boolean = false,
    val loadError: String? = null,
    /** 窗口 epoch：每次换日期归零重建后自增；page 实例引用 + epoch 双判据确保只有「本窗口本请求」可回填。 */
    val epoch: Int = 0,
) {
    /** 追加一页（dedupe by id），保持 DESC。 */
    fun mergeBelow(page: List<HttpLogSummary>): List<HttpLogSummary> {
        if (page.isEmpty()) return entries
        val seen = entries.mapTo(HashSet()) { it.id }
        return entries + page.filter { it.id !in seen }
    }

    /** 新插入增量并入页头（仍 DESC，新行 id 更大）。 */
    fun mergeNewer(page: List<HttpLogSummary>): List<HttpLogSummary> {
        if (page.isEmpty()) return entries
        val seen = entries.mapTo(HashSet()) { it.id }
        return page.sortedByDescending { it.id }.filter { it.id !in seen } + entries
    }

    /** 从已加载行中剔除已删 id 并校正日总数（不依赖 MAX(id) 失效，删除后列表立即收敛）。 */
    fun removeIds(ids: Set<Long>): HttpDayPage {
        if (ids.isEmpty()) return this
        val kept = entries.filter { it.id !in ids }
        return copy(entries = kept, total = (total - (entries.size - kept.size)).coerceAtLeast(0))
    }

    /** 发出一次「加载更多」请求：把本页置 loading 并换身份（page+epoch 都新），只有拿着这个 loading 页实例的协程可回填。 */
    fun beginLoadMore(): HttpDayPage = copy(loading = true, loadError = null, epoch = epoch + 1)

    /**
     * 把一页 DB 结果并入「当前窗口 cur」：entries/minId/exhausted 只认本页请求的 keyset 结果，
     * 但 total/baselineId 保留 cur 的更新值（飞行期间新插入/删除的修正不丢）。cur 必须是发请求时的那个 loading 实例。
     */
    fun foldNextPage(cur: HttpDayPage, nextEntries: List<HttpLogSummary>, exhausted: Boolean): HttpDayPage {
        val merged = cur.mergeBelow(nextEntries)
        return cur.copy(
            entries = merged,
            minId = merged.lastOrNull()?.id ?: cur.minId,
            loading = false,
            exhausted = exhausted || nextEntries.size < HTTP_DAY_PAGE_SIZE,
            loadError = null,
        )
    }
}

/** 单页条数：不足一页即视为本日到底。 */
internal const val HTTP_DAY_PAGE_SIZE: Int = 200

/**
 * 某日（yyyy-MM-dd 本地时区）的 [start, end] 毫秒闭区间；日期串非法时返回 null（调用方按空窗口处理）。
 * 纯函数，无 Android 依赖。
 */
internal fun httpDayRange(dateKey: String): Pair<Long, Long>? = runCatching {
    val zone = ZoneId.systemDefault()
    val start = LocalDate.parse(dateKey).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = LocalDate.parse(dateKey).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    start to end
}.getOrNull()
