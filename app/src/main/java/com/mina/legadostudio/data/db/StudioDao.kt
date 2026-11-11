package com.mina.legadostudio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface StudioDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") fun observeProjects(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") suspend fun allProjects(): List<ProjectEntity>
    @Query("SELECT * FROM projects WHERE id=:id") fun observeProject(id: String): Flow<ProjectEntity?>
    @Query("SELECT * FROM projects WHERE id=:id") suspend fun project(id: String): ProjectEntity?
    @Query("SELECT * FROM projects WHERE siteUrl=:url ORDER BY updatedAt DESC LIMIT 1") suspend fun projectBySiteUrl(url: String): ProjectEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProject(value: ProjectEntity)
    @Query("DELETE FROM projects WHERE id IN (:ids)") suspend fun deleteProjects(ids: List<String>): Int

    /** Room holds the database write transaction across comparison, exact deletion and readback.
     * All writers (including REPLACE) serialize at SQLite, not a UI-only mutex.
     * Any whole-library change rejects the entire operation; never retry with fresher IDs.
     */
    @Transaction
    suspend fun deleteProjectsFromSnapshot(expected: List<ProjectEntity>, ids: List<String>): Int {
        val expectedById = expected.associateBy { it.id }
        require(expectedById.size == expected.size && ids.distinct().size == ids.size && ids.all { it in expectedById }) {
            "删除范围不属于确认快照"
        }
        check(allProjects().associateBy { it.id } == expectedById) {
            "书源库已变化，请重新分析；未删除任何书源"
        }
        // Stay below SQLite's bind limit even for large libraries, within ONE transaction.
        val deleted = ids.chunked(900).sumOf { deleteProjects(it) }
        check(deleted == ids.size) { "删除数量不一致，事务已回滚" }
        val remaining = expectedById - ids.toSet()
        check(allProjects().associateBy { it.id } == remaining) { "删除回读不一致，事务已回滚" }
        return deleted
    }

    @Query("SELECT * FROM source_revisions WHERE projectId=:projectId ORDER BY id DESC") fun observeRevisions(projectId: String): Flow<List<SourceRevisionEntity>>
    @Query("SELECT * FROM source_revisions WHERE id=:id") suspend fun revision(id: Long): SourceRevisionEntity?
    @Insert suspend fun addRevision(value: SourceRevisionEntity)

    @Query("SELECT * FROM http_logs ORDER BY id DESC LIMIT :limit") fun observeHttpLogs(limit: Int): Flow<List<HttpLogEntity>>
    @Query("SELECT id, method, url, finalUrl, statusCode, durationMs, error, createdAt, sourceAnchor, contextId, originKind, requestHeaders, redirectChain FROM http_logs ORDER BY id DESC LIMIT :limit") fun observeHttpLogSummaries(limit: Int): Flow<List<HttpLogSummary>>
    /**
     * 同 observeHttpLogSummaries 但 SQL 层排除逐次抓包（cap:% contextId）事务：
     * 抓包高频记录不占普通 HTTP 最近 N 条窗口；NULL contextId（无上下文普通请求）必须保留，
     * 所以谓词是 (contextId IS NULL OR contextId NOT LIKE 'cap:%') 而不是 NOT LIKE——后者会把 NULL 滤掉。
     * 只排除 cap: 前缀，不误伤 webview_resource 等其他 contextId 种类。
     */
    @Query("SELECT id, method, url, finalUrl, statusCode, durationMs, error, createdAt, sourceAnchor, contextId, originKind, requestHeaders, redirectChain FROM http_logs WHERE (contextId IS NULL OR contextId NOT LIKE 'cap:%') ORDER BY id DESC LIMIT :limit") fun observeHttpLogSummariesNoCapture(limit: Int): Flow<List<HttpLogSummary>>

    /**
     * 普通 HTTP 选中日 keyset 页：游标是 id<:beforeId（取已加载最小 id 继续向下翻），
     * 不用 OFFSET——新插入行只落在页头方向，不会让后续页错位/漏行；cap:% 排除、NULL contextId 保留。
     */
    @Query("SELECT id, method, url, finalUrl, statusCode, durationMs, error, createdAt, sourceAnchor, contextId, originKind, requestHeaders, redirectChain FROM http_logs WHERE createdAt BETWEEN :dayStart AND :dayEnd AND (contextId IS NULL OR contextId NOT LIKE 'cap:%') AND id < :beforeId ORDER BY id DESC LIMIT :limit")
    suspend fun httpLogSummariesByDayBeforeIdNoCapture(dayStart: Long, dayEnd: Long, beforeId: Long, limit: Int): List<HttpLogSummary>

    /**
     * 普通 HTTP 全库本地日期导航（yyyy-MM-dd DESC）：日期列表不依赖任何已加载窗口，
     * 任意历史天都可直达；Room 表失效会自动推新日期（新日志落到新一天时列表自动出现）。
     */
    @Query("SELECT DISTINCT date(createdAt/1000,'unixepoch','localtime') AS dayKey FROM http_logs WHERE (contextId IS NULL OR contextId NOT LIKE 'cap:%') ORDER BY dayKey DESC")
    fun observeHttpLogDaysNoCapture(): Flow<List<String>>

    /** 普通 HTTP 最新行 id 流：新插入即推新值，UI 据此把增量并入当前日期页头而不整页重拉。 */
    @Query("SELECT MAX(id) FROM http_logs WHERE (contextId IS NULL OR contextId NOT LIKE 'cap:%')")
    fun observeLatestHttpLogIdNoCapture(): Flow<Long?>

    /** 同 observeLatestHttpLogIdNoCapture 的同步版：首载前取基线，区分「新插入」与「历史行」。 */
    @Query("SELECT MAX(id) FROM http_logs WHERE (contextId IS NULL OR contextId NOT LIKE 'cap:%')")
    suspend fun latestHttpLogIdNoCapture(): Long?

    /** 取 id>:afterId 的普通 HTTP 增量（ASC 入库序）：新日志并入当日页头用，调用方按日期过滤错位行。 */
    @Query("SELECT id, method, url, finalUrl, statusCode, durationMs, error, createdAt, sourceAnchor, contextId, originKind, requestHeaders, redirectChain FROM http_logs WHERE (contextId IS NULL OR contextId NOT LIKE 'cap:%') AND id > :afterId ORDER BY id ASC")
    suspend fun httpLogSummariesAfterIdNoCapture(afterId: Long): List<HttpLogSummary>

    @Query("SELECT * FROM http_logs WHERE id=:id") suspend fun httpLog(id: Long): HttpLogEntity?
    @Query("SELECT * FROM http_logs WHERE id IN (:ids)") suspend fun httpLogsByIds(ids: List<Long>): List<HttpLogEntity>
    /** 逐次抓包按 cap:contextId 精确取回本批日志：并发与其他任务互不影响，也不受最近500条窗口限制。 */
    @Query("SELECT * FROM http_logs WHERE contextId=:contextId ORDER BY id ASC") suspend fun httpLogsByContextId(contextId: String): List<HttpLogEntity>
    /** 同上但流式：异步写入（拦截器/WebView 记录）落库后自动推新列表，供 UI 实时刷新抓包结果。 */
    @Query("SELECT * FROM http_logs WHERE contextId=:contextId ORDER BY id ASC") fun observeHttpLogsByContextId(contextId: String): Flow<List<HttpLogEntity>>
    /**
     * 同一 contextId 的轻量投影流（不含正文大字段），只取最近 :limit 条、新→旧：
     * 浏览器抓包事务面板实时预览用——旧版 observeHttpLogsByContextId 每次变化全量回读含
     * request/responseBody 的整行再 takeLast(300)，高流量会话下 IO/内存随会话行数线性膨胀；
     * 这里 SQL 层 LIMIT + 投影列，一次失效只回读 300 行小投影。
     */
    @Query("SELECT id, method, url, finalUrl, statusCode, durationMs, error, createdAt, sourceAnchor, contextId, originKind, requestHeaders, redirectChain FROM http_logs WHERE contextId=:contextId ORDER BY id DESC LIMIT :limit")
    fun observeHttpLogSummariesByContextIdDesc(contextId: String, limit: Int): Flow<List<HttpLogSummary>>
    /** 同一 contextId 的全量实体分页（含正文大字段）：会话导出用——逐页写盘，避免一次读全行撑爆内存。 */
    @Query("SELECT * FROM http_logs WHERE contextId=:contextId ORDER BY id ASC LIMIT :limit OFFSET :offset")
    suspend fun httpLogsByContextIdPage(contextId: String, limit: Int, offset: Int): List<HttpLogEntity>
    /** 同一 contextId 的轻量投影分页（不含正文大字段）：MCP get_capture 用，避免每次回看全量实体。 */
    @Query("SELECT id, method, url, finalUrl, statusCode, durationMs, error, createdAt, sourceAnchor, contextId, originKind, requestHeaders, redirectChain FROM http_logs WHERE contextId=:contextId ORDER BY id ASC LIMIT :limit OFFSET :offset")
    suspend fun httpLogSummariesByContextIdPage(contextId: String, limit: Int, offset: Int): List<HttpLogSummary>
    /** 同一 contextId 的总数（分页总额外信息）。 */
    @Query("SELECT COUNT(*) FROM http_logs WHERE contextId=:contextId") suspend fun countHttpLogsByContextId(contextId: String): Int
    /**
     * 抓包会话摘要：按 contextId LIKE 'cap:%' 前缀分组（逐次抓包/可见浏览器/无头 WebView 都落这里）。
     * MIN(id)/MAX(id) 是该会话首/末条事务的稳定游标；MAX(createdAt) 用于新→旧分页。
     * lastStatus 取「入库序末行」的状态码（ORDER BY id DESC LIMIT 1）而非 MAX(statusCode)——
     * 后者对浏览器多资源会话会拿任意资源的最大码冒充「最终状态」，语义错误。
     * hasOnce/webviewKinds/summaryBody 供调用方做来源识别：缺可靠 originKind 的旧会话如实报「未知」。
     */
    @Query("SELECT contextId AS contextId, MIN(id) AS firstLogId, MAX(id) AS latestLogId, COUNT(*) AS totalCount, MAX(createdAt) AS lastAt, MIN(createdAt) AS firstAt, SUM(CASE WHEN originKind='capture_hop' THEN 1 ELSE 0 END) AS hopCount, (SELECT h.statusCode FROM http_logs h WHERE h.contextId=http_logs.contextId ORDER BY h.id DESC LIMIT 1) AS lastStatus, MAX(CASE WHEN originKind IN ('capture_once','capture_hop') THEN 1 ELSE 0 END) AS hasOnce, GROUP_CONCAT(DISTINCT originKind) AS webviewKinds, (SELECT h.responseBody FROM http_logs h WHERE h.contextId=http_logs.contextId AND h.originKind='webview_capture' ORDER BY h.id DESC LIMIT 1) AS summaryBody FROM http_logs WHERE contextId LIKE 'cap:%' GROUP BY contextId ORDER BY MAX(createdAt) DESC LIMIT :limit OFFSET :offset")
    suspend fun captureSessionSummaries(limit: Int, offset: Int): List<CaptureSessionSummary>
    /** 抓包会话总数（list_captures 分页 total，cap:% 全口径）。 */
    @Query("SELECT COUNT(DISTINCT contextId) FROM http_logs WHERE contextId LIKE 'cap:%'") suspend fun countCaptureSessions(): Int
    /**
     * 抓包会话 keyset 页（新→旧）：游标是上一页末行的 (lastAt, latestLogId)，
     * `WHERE (MAX(createdAt), MAX(id)) < (:beforeLastAt, :beforeLatestId)` 按聚合键向下翻。
     * 不用 OFFSET——会话删除/新会话插入只影响游标之前的页，后续页不漏行、不重行
     * （删除后 OFFSET 翻页会整体错位跳过紧邻会话，keyset 只认锚点不受影响）。
     * lastAt DESC 为序内会话 lastAt 相同（并发同日同毫秒）时用 latestLogId 定胜负。
     */
    @Query("SELECT contextId AS contextId, MIN(id) AS firstLogId, MAX(id) AS latestLogId, COUNT(*) AS totalCount, MAX(createdAt) AS lastAt, MIN(createdAt) AS firstAt, SUM(CASE WHEN originKind='capture_hop' THEN 1 ELSE 0 END) AS hopCount, (SELECT h.statusCode FROM http_logs h WHERE h.contextId=http_logs.contextId ORDER BY h.id DESC LIMIT 1) AS lastStatus, MAX(CASE WHEN originKind IN ('capture_once','capture_hop') THEN 1 ELSE 0 END) AS hasOnce, GROUP_CONCAT(DISTINCT originKind) AS webviewKinds, (SELECT h.responseBody FROM http_logs h WHERE h.contextId=http_logs.contextId AND h.originKind='webview_capture' ORDER BY h.id DESC LIMIT 1) AS summaryBody FROM http_logs WHERE contextId LIKE 'cap:%' GROUP BY contextId HAVING (MAX(createdAt) < :beforeLastAt OR (MAX(createdAt) = :beforeLastAt AND MAX(id) < :beforeLatestId)) ORDER BY MAX(createdAt) DESC, MAX(id) DESC LIMIT :limit")
    suspend fun captureSessionSummariesBefore(beforeLastAt: Long, beforeLatestId: Long, limit: Int): List<CaptureSessionSummary>
    /** 会话当前末行 id：poll_capture 增量游标基线，MAX(id) 而非 createdAt（并发写入不乱序）。 */
    @Query("SELECT MAX(id) FROM http_logs WHERE contextId=:contextId") suspend fun latestHttpLogIdByContextId(contextId: String): Long?
    /**
     * 会话内 id > :afterId 的增量页（ASC 入库序，LIMIT 截断）：
     * poll_capture 专用——空页不推进游标，跨次轮询不漏活会话新事务（id 自增即入序）。
     */
    @Query("SELECT id, method, url, finalUrl, statusCode, durationMs, error, createdAt, sourceAnchor, contextId, originKind, requestHeaders, redirectChain FROM http_logs WHERE contextId=:contextId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun httpLogSummariesByContextIdAfterId(contextId: String, afterId: Long, limit: Int): List<HttpLogSummary>
    /**
     * 单会话的结束证据聚合（poll_capture 的 sessionState）：
     * 逐次抓包（hasOnce）与无头 webview_capture 一次写完即结束，不看结算行；
     * 只有可见浏览器会话（有 webview_capture* 行但还没结算行）会持续追加，
     * 直到用户「结束并保存」写出 hasSettlement=1。
     */
    @Query("SELECT MAX(CASE WHEN originKind IN ('capture_once','capture_hop') THEN 1 ELSE 0 END) AS hasOnce, MAX(CASE WHEN originKind LIKE 'webview_capture%' THEN 1 ELSE 0 END) AS hasWebView, MAX(CASE WHEN originKind='webview_capture' THEN 1 ELSE 0 END) AS hasSettlement FROM http_logs WHERE contextId=:contextId")
    suspend fun captureSessionEndState(contextId: String): CaptureSessionEndState?
    @Insert suspend fun addHttpLog(value: HttpLogEntity)
    @Query("DELETE FROM http_logs") suspend fun clearHttpLogs()
    @Query("DELETE FROM http_logs WHERE id IN (:ids)") suspend fun deleteHttpLogs(ids: List<Long>): Int
    @Query("DELETE FROM http_logs WHERE createdAt BETWEEN :start AND :end") suspend fun deleteHttpLogsInRange(start: Long, end: Long): Int
    /**
     * 按天删除普通 HTTP 日志（UI「删除该日全部」入口）：谓词 (contextId IS NULL OR contextId NOT LIKE 'cap:%')
     * 与 observeHttpLogDaysNoCapture/countHttpLogsByDayNoCapture 同一口径——只排 cap:% 前缀、NULL contextId
     * 保留并删除；其他非 cap: 的 contextId（如 webview_resource）也归普通 HTTP 一同删除。不能用 deleteHttpLogsInRange：它会把落在
     * 该日 createdAt 的逐次/浏览器/MCP 抓包事务一并删掉，破坏抓包隔离。条数上限无限制（不经过 UI 加载窗口）。
     */
    @Query("DELETE FROM http_logs WHERE createdAt BETWEEN :start AND :end AND (contextId IS NULL OR contextId NOT LIKE 'cap:%')") suspend fun deleteHttpLogsByDayNoCapture(start: Long, end: Long): Int

    /**
     * 按 cap:contextId 精确删除一个完整抓包会话（抓包会话历史页逐行删除入口）。
     * 双谓词缺一不可：
     * - `contextId = :contextId` 精确等值：只动这一个会话，其它 cap: 会话/普通 HTTP/NULL contextId 天然免疫；
     * - `contextId LIKE 'cap:%'` 前缀校验：传入值不是 cap: 前缀时 SQL 一行不命中（0 删除），
     *   即使调用方传错普通 contextId（如 webview_resource）也不会被误删。
     * 与 deleteHttpLogsByDayNoCapture 方向相反、互不替代：那条按天删普通 HTTP 且故意排除 cap:%，
     * 绝不能拿来删抓包会话。
     */
    @Query("DELETE FROM http_logs WHERE contextId = :contextId AND contextId LIKE 'cap:%'") suspend fun deleteCaptureSessionByContextId(contextId: String): Int

    /** 按天全量取 HTTP 日志（含锚点字段），游标分页避免 >500 截断。仅供导出用（要 responseBody）。 */
    @Query("SELECT * FROM http_logs WHERE createdAt BETWEEN :dayStart AND :dayEnd ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun httpLogsByDayPage(dayStart: Long, dayEnd: Long, limit: Int, offset: Int): List<HttpLogEntity>

    /** 同 httpLogsByDayPage 但排除 cap:% 抓包事务（NULL contextId 保留）：HTTP 导出默认不混抓包记录。 */
    @Query("SELECT * FROM http_logs WHERE createdAt BETWEEN :dayStart AND :dayEnd AND (contextId IS NULL OR contextId NOT LIKE 'cap:%') ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun httpLogsByDayPageNoCapture(dayStart: Long, dayEnd: Long, limit: Int, offset: Int): List<HttpLogEntity>

    /**
     * 全库轻量投影 keyset 页（id<:beforeId）：「全部日期」导出的第一趟归属扫描用——
     * 锚点/Referer 证据可能在任意日期的老日志上，须扫全表；投影不含正文大字段。
     * keyset 代替 OFFSET：扫描期间新插入只落页头，后续页不错位/漏行。cap:% 排除、NULL contextId 保留。
     */
    @Query("SELECT id, method, url, finalUrl, statusCode, durationMs, error, createdAt, sourceAnchor, contextId, originKind, requestHeaders, redirectChain FROM http_logs WHERE (contextId IS NULL OR contextId NOT LIKE 'cap:%') AND id < :beforeId ORDER BY id DESC LIMIT :limit")
    suspend fun httpLogSummariesAllBeforeIdNoCapture(beforeId: Long, limit: Int): List<HttpLogSummary>

    /** 按天取轻量投影（含归属证据列，无 responseBody）：MCP/UI 归属筛选用，避免大响应体占内存。 */
    @Query("SELECT id, method, url, finalUrl, statusCode, durationMs, error, createdAt, sourceAnchor, contextId, originKind, requestHeaders, redirectChain FROM http_logs WHERE createdAt BETWEEN :dayStart AND :dayEnd ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun httpLogSummariesByDayPage(dayStart: Long, dayEnd: Long, limit: Int, offset: Int): List<HttpLogSummary>

    /** 同 httpLogSummariesByDayPage 但排除 cap:% 抓包事务（NULL contextId 保留）：MCP get_http_logs 默认过滤抓包记录。 */
    @Query("SELECT id, method, url, finalUrl, statusCode, durationMs, error, createdAt, sourceAnchor, contextId, originKind, requestHeaders, redirectChain FROM http_logs WHERE createdAt BETWEEN :dayStart AND :dayEnd AND (contextId IS NULL OR contextId NOT LIKE 'cap:%') ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun httpLogSummariesByDayPageNoCapture(dayStart: Long, dayEnd: Long, limit: Int, offset: Int): List<HttpLogSummary>

    /** 全库轻量投影游标分页：MCP 无日期归属筛选需扫全表（锚点/Referer 证据在任意日期的老日志上都要生效）。 */
    @Query("SELECT id, method, url, finalUrl, statusCode, durationMs, error, createdAt, sourceAnchor, contextId, originKind, requestHeaders, redirectChain FROM http_logs ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun httpLogSummariesPage(limit: Int, offset: Int): List<HttpLogSummary>

    /** 同 httpLogSummariesPage 但排除 cap:% 抓包事务（NULL contextId 保留）：get_http_logs 默认过滤抓包记录，includeCapture=true 时仍走无过滤版。 */
    @Query("SELECT id, method, url, finalUrl, statusCode, durationMs, error, createdAt, sourceAnchor, contextId, originKind, requestHeaders, redirectChain FROM http_logs WHERE (contextId IS NULL OR contextId NOT LIKE 'cap:%') ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun httpLogSummariesPageNoCapture(limit: Int, offset: Int): List<HttpLogSummary>

    /** 按锚点筛选一天日志：显式 sourceAnchor 命中即可（NULL 不匹配）。 */
    @Query("SELECT * FROM http_logs WHERE createdAt BETWEEN :dayStart AND :dayEnd AND sourceAnchor = :anchor ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun httpLogsByDayAndAnchorPage(dayStart: Long, dayEnd: Long, anchor: String, limit: Int, offset: Int): List<HttpLogEntity>

    /** 统计一天日志总数（用于 UI/MCP 显示未截断）。 */
    @Query("SELECT COUNT(*) FROM http_logs WHERE createdAt BETWEEN :dayStart AND :dayEnd")
    suspend fun countHttpLogsByDay(dayStart: Long, dayEnd: Long): Int

    /** 同 countHttpLogsByDay 但排除 cap:% 抓包事务（NULL contextId 保留）。 */
    @Query("SELECT COUNT(*) FROM http_logs WHERE createdAt BETWEEN :dayStart AND :dayEnd AND (contextId IS NULL OR contextId NOT LIKE 'cap:%')")
    suspend fun countHttpLogsByDayNoCapture(dayStart: Long, dayEnd: Long): Int

    @Query("SELECT * FROM operation_logs ORDER BY id DESC LIMIT :limit") fun observeOperationLogs(limit: Int): Flow<List<OperationLogEntity>>
    @Query("SELECT * FROM operation_logs ORDER BY id DESC LIMIT :limit") suspend fun latestOperationLogs(limit: Int): List<OperationLogEntity>
    @Query("SELECT * FROM operation_logs WHERE id=:id") suspend fun operationLog(id: Long): OperationLogEntity?
    @Query("SELECT * FROM operation_logs WHERE id IN (:ids)") suspend fun operationLogsByIds(ids: List<Long>): List<OperationLogEntity>
    @Insert suspend fun addOperationLog(value: OperationLogEntity)
    @Query("DELETE FROM operation_logs WHERE id IN (:ids)") suspend fun deleteOperationLogs(ids: List<Long>): Int
    /**
     * 操作日志全库本地日期导航（yyyy-MM-dd DESC）：与 HTTP 侧的 observeHttpLogDaysNoCapture 同构——
     * 500 条 UI 窗口截断前的历史天（被 trimOperationLogs 裁剪掉的除外）都可直达/可整删。
     */
    @Query("SELECT DISTINCT date(createdAt/1000,'unixepoch','localtime') AS dayKey FROM operation_logs ORDER BY dayKey DESC")
    fun observeOperationLogDays(): Flow<List<String>>
    /** 操作日志按天全库计数：UI 日期弹窗逐日计数与「删除该日」二次确认都以此为准，不用窗口内条数。 */
    @Query("SELECT COUNT(*) FROM operation_logs WHERE createdAt BETWEEN :start AND :end") suspend fun countOperationLogsByDay(start: Long, end: Long): Int
    /** 按天删除操作日志（UI「删除该日全部」入口）：真实数据库日期范围，不受 500 条窗口限制。 */
    @Query("DELETE FROM operation_logs WHERE createdAt BETWEEN :start AND :end") suspend fun deleteOperationLogsByDay(start: Long, end: Long): Int
    @Query("DELETE FROM operation_logs WHERE id NOT IN (SELECT id FROM operation_logs ORDER BY id DESC LIMIT :keep)") suspend fun trimOperationLogs(keep: Int)

    @Query("SELECT * FROM diagnostic_snapshots ORDER BY createdAt DESC") fun observeDiagnosticSnapshots(): Flow<List<DiagnosticSnapshotEntity>>
    @Query("SELECT * FROM diagnostic_snapshots WHERE id=:id") suspend fun diagnosticSnapshot(id: String): DiagnosticSnapshotEntity?
    @Query("SELECT * FROM diagnostic_snapshots WHERE id IN (:ids)") suspend fun diagnosticSnapshotsByIds(ids: List<String>): List<DiagnosticSnapshotEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDiagnosticSnapshot(value: DiagnosticSnapshotEntity)
    @Query("DELETE FROM diagnostic_snapshots WHERE id IN (:ids)") suspend fun deleteDiagnosticSnapshots(ids: List<String>): Int

    @Query("SELECT * FROM verification_sessions ORDER BY updatedAt DESC") fun observeVerificationSessions(): Flow<List<VerificationSessionEntity>>
    @Query("SELECT * FROM verification_sessions WHERE id=:id") suspend fun verificationSession(id: String): VerificationSessionEntity?
    @Query("SELECT * FROM verification_sessions WHERE jobId=:jobId AND domain=:domain AND status='WAITING' LIMIT 1") suspend fun waitingVerification(jobId: String, domain: String): VerificationSessionEntity?
    @Query("SELECT * FROM verification_sessions WHERE jobId=:jobId AND domain=:domain ORDER BY updatedAt DESC LIMIT 1") suspend fun latestVerification(jobId: String, domain: String): VerificationSessionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveVerificationSession(value: VerificationSessionEntity)
    @Query("DELETE FROM verification_sessions WHERE id=:id") suspend fun deleteVerificationSession(id: String)
}