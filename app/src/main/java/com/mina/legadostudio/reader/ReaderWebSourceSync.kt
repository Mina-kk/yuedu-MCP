package com.mina.legadostudio.reader

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * `reader_web_sync_source`：把本 App 里**已保存的确切成品**写入用户自己的阅读 App。
 *
 * 写入语义（不靠版本号，只看阅读的实际响应）：
 * 1. 从项目 `sourceJson` 取原文，逐字符解析出 `bookSourceUrl`；
 * 2. 固定同源 `GET /getBookSource?url=` 精确预检：
 *    - 阅读**明确**回答不存在（`isSuccess=false` + 官方未找到措辞）→ `POST /saveBookSource` **新增**；
 *    - 存在且实质内容相同 → `already_present`，不写；
 *    - 存在但内容不同且 `overwrite=false` → `conflict`，只回两侧哈希与不同的顶层字段名；
 *    - `overwrite=true` 才 `POST` 覆盖（唯一会改写阅读已有书源的路径）；
 *    - 预检形状读不懂（`data=null`、空对象、缺/错 `bookSourceUrl`）→ 停，**零写入**；
 * 3. HTTP 200 不算成功：先检查 `ReturnData.isSuccess`，再 `GET /getBookSource` 回读，
 *    对用户提交的**全部非元数据顶层字段**逐个做实质比较（含整棵嵌套规则）；
 *    只有回读完全一致才 `confirmed=true`，也只有那时才给出 `nextAction=reader_web_verify`。
 *
 * 安全边界：
 * - 目标 origin 只来自用户保存的配置；路径是固定 allowlist，工具不接受任意 URL/路径；
 * - 请求体只来自项目 `sourceJson`，不接受模型另传书源；
 * - 覆盖阅读现有书源只能由调用方显式传 `overwrite=true`，默认绝不改写；
 * - 冲突/回读失败的输出只有哈希与字段名，绝不带 `header`/`jsLib`/规则原文等敏感值；
 * - 整次调用固定同一份地址+令牌快照，中途改界面不会换主机。
 *
 * 请求可追溯（避免模型再猜「同步是怎么写的」）：
 * - 每次调用把**实际发出**的固定路由请求按顺序放进 `requests`（lookup → save → readback），
 *   每条都带方法/固定路径/阶段/HTTP 状态；成功回包也列，模型不必再猜 `GET /saveBookSource` 之类；
 * - 失败那次请求的 `diagnostic` 与 `requests` 一起回包，`stage` 直接区分预检/写入/回读；
 * - POST 网络异常**不重放**，也**不谎称肯定没写**：`writeAttempted=true` + `writeOutcome=unknown`；
 *   只有连接都没建立（DNS/拒绝/TLS）才可确认「没送到」；阅读接受写入但回读失败标 accepted/unconfirmed。
 */
class ReaderWebSourceSync(
    private val client: ReaderWebClient,
    private val sourceProvider: suspend (String) -> String?,
) {

    /**
     * 阅读自身会维护、不属于「书源内容」的字段：只在**顶层**不参与实质比较，
     * 也不会出现在冲突字段名里。这些值由阅读端按使用情况更新（最后更新时间、响应耗时、分组、启用状态等）。
     *
     * 注意：嵌套对象（如 `ruleSearch.enabled`、`ruleToc.group`）里的同名键是书源规则的一部分，
     * 必须严格比较——否则两份不同的规则会被误判成相同而跳过写入。
     */
    private val READER_METADATA = setOf(
        "lastUpdateTime", "respondTime", "weight", "customOrder",
        "enabled", "enabledExplore", "bookSourceGroup", "bookSourceComment",
        "variableComment",
    )

    /**
     * @property writeAttempted 本次是否**尝试过** `POST /saveBookSource`（只看有没有走写入这一步，不代表已送达）
     * @property writeOutcome `not_attempted` / `not_sent` / `unknown` / `rejected` / `accepted`：
     *   - `not_attempted`：预检失败、已存在、冲突拒写，根本没走到写入；
     *   - `not_sent`：尝试了写入但连接都没建立（DNS/拒绝/不可达/TLS），可确认阅读**没收到**；
     *   - `unknown`：连接已建立后断开/超时，POST **可能已到达**阅读；不重放，也不能说没写；
     *   - `rejected`：阅读明确回了 HTTP 错误或 `isSuccess=false`，写入未被接受；
     *   - `accepted`：阅读接受了写入（`isSuccess=true`）；是否生效仍只看回读，见 [confirmed]
     * @property diagnostic 失败那次请求的脱敏诊断（成功时为 null）；`stage` 区分 lookup/save/readback
     * @property requests 本次调用**实际发出**的请求（按顺序、成功也列），供模型核对方法与阶段
     */
    data class Result(
        val status: String,
        val bookSourceUrl: String?,
        val written: Boolean,
        val confirmed: Boolean,
        val presentInReader: Boolean = false,
        val normalizedFields: List<String> = emptyList(),
        val localHash: String?,
        val remoteHash: String?,
        val differentTopLevelFields: List<String>,
        val remoteOnlyTopLevelFields: List<String>,
        val mismatchedFields: List<String>,
        val projectId: String?,
        val message: String,
        val errorCode: String? = null,
        val writeAttempted: Boolean = false,
        val writeOutcome: String? = null,
        val diagnostic: ReaderWebRequestDiagnostic? = null,
        val requests: List<Map<String, Any?>> = emptyList(),
    ) {
        fun toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "status" to status,
            "projectId" to projectId,
            "bookSourceUrl" to bookSourceUrl,
            "written" to written,
            "confirmed" to confirmed,
            "presentInReader" to presentInReader,
            "normalizedFields" to normalizedFields,
            "writeAttempted" to writeAttempted,
            "writeOutcome" to writeOutcome,
            "localHash" to localHash,
            "remoteHash" to remoteHash,
            "differentTopLevelFields" to differentTopLevelFields,
            "remoteOnlyTopLevelFields" to remoteOnlyTopLevelFields,
            "mismatchedFields" to mismatchedFields,
            "nextAction" to if (confirmed) "reader_web_verify" else null,
            "message" to message,
            "diagnostic" to diagnostic?.toPayload(),
            // 成功回包也列出实际请求顺序：模型不再需要猜「同步是不是 GET /saveBookSource」
            "requests" to requests,
            "trace" to traceOf(requests),
            "evidenceScope" to "reader_web_sync",
            "evidenceNote" to "只把本 App 已保存的书源原文写入阅读并回读核对；不产生阅读端检验结论，也不登记为本实例 check_source 的证据",
        ).apply { if (errorCode != null) put("errorCode", errorCode) }
            .filterValues { it != null }
    }

    /** 失败时按同步阶段保留诊断与「写入到哪一步」的诚实状态。 */
    class SyncException(
        val code: String,
        message: String,
        val diagnostic: ReaderWebRequestDiagnostic? = null,
        val writeAttempted: Boolean = false,
        val writeOutcome: String? = null,
        val requests: List<Map<String, Any?>> = emptyList(),
    ) : Exception(message)

    /** 写入结局（见 [Result.writeOutcome]）：供回包与 MCP 错误模板共用。 */
    object WriteOutcome {
        const val NOT_ATTEMPTED = "not_attempted"
        const val NOT_SENT = "not_sent"
        const val UNKNOWN = "unknown"
        const val REJECTED = "rejected"
        const val ACCEPTED = "accepted"
    }

    companion object {
        /**
         * 同步失败时的统一回包（MCP 工具与测试共用，避免两处各拼一份）。
         *
         * `written` 只有「阅读已接受写入」（writeOutcome=accepted）才为 true；
         * `confirmed` 恒为 false —— 失败路径上没有回读证据就不可能确认生效。
         */
        fun failurePayload(projectId: String?, error: SyncException): Map<String, Any?> = Result(
            status = "error",
            bookSourceUrl = null,
            written = error.writeOutcome == WriteOutcome.ACCEPTED,
            confirmed = false,
            localHash = null,
            remoteHash = null,
            differentTopLevelFields = emptyList(),
            remoteOnlyTopLevelFields = emptyList(),
            mismatchedFields = emptyList(),
            projectId = projectId,
            message = error.message.orEmpty(),
            errorCode = error.code,
            writeAttempted = error.writeAttempted,
            writeOutcome = error.writeOutcome,
            diagnostic = error.diagnostic,
            requests = error.requests,
        ).toPayload()

        /** 请求顺序的短标签（`GET /getBookSource [lookup] HTTP 200`）：不含 host/端口/查询串/令牌。 */
        fun traceOf(requests: List<Map<String, Any?>>): List<String> = requests.map { row ->
            val status = (row["httpStatus"] as? Number)?.let { "HTTP $it" }
                ?: (row["exceptionCategory"] as? String)?.let { "no-response $it" }
                ?: "no-response"
            "${row["method"]} ${row["path"]} [${row["stage"]}] $status"
        }
    }

    /**
     * @param projectId 本地项目 ID（sourceJson 来自项目库，模型不能另传书源）
     * @param overwrite false 时绝不在内容不同的情况下覆盖阅读里的书源
     */
    suspend fun sync(projectId: String, overwrite: Boolean): Result = withContext(Dispatchers.IO) {
        require(projectId.isNotBlank()) { "projectId 不能为空" }
        val raw = sourceProvider(projectId)
            ?: throw SyncException("PROJECT_NOT_FOUND", "项目不存在：请先确认 projectId（list_projects 可查）")
        val local = runCatching { JsonParser.parseString(raw) as? JsonObject }.getOrNull()
            ?: throw SyncException("INVALID_SOURCE", "项目里的 sourceJson 不是合法 JSON 对象，无法同步")
        val url = local.stringOrNull("bookSourceUrl")?.trim().orEmpty()
        if (url.isEmpty()) throw SyncException("MISSING_BOOK_SOURCE_URL", "项目书源缺少 bookSourceUrl，无法在阅读里定位该书源")
        if (url.length > 4_096) throw SyncException("INVALID_SOURCE", "bookSourceUrl 过长")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw SyncException("INVALID_SOURCE", "bookSourceUrl 必须是 http/https 书源地址")
        }

        // 整次同步固定同一份地址+令牌快照
        val pinned = client.pinConfig()
        val localHash = hashOf(local)
        // 每次调用新建：只收集**本次**实际发出的固定路由请求（成功也记），不跨调用累积
        val requests = mutableListOf<Map<String, Any?>>()
        val remote = try {
            pinned.fetchSource(url, ReaderWebClient.Stage.LOOKUP)
        } catch (error: ReaderWebClient.ReaderWebException) {
            requests += requestRow(error.diagnostic)
            throw SyncException(
                error.code,
                error.message.orEmpty(),
                diagnostic = error.diagnostic,
                writeAttempted = false,
                writeOutcome = WriteOutcome.NOT_ATTEMPTED,
                requests = requests.toList(),
            )
        }
        requests += requestRow(remote.diagnostic)

        if (!remote.present) {
            write(pinned, local, url, localHash, overwrite = true, remoteHash = null, different = emptyList(), requests = requests)
        } else {
            val existing = remote.source ?: throw SyncException(
                "INCOMPATIBLE",
                "阅读返回了空书源，无法安全比较，已停止同步",
                writeAttempted = false,
                writeOutcome = WriteOutcome.NOT_ATTEMPTED,
                requests = requests.toList(),
            )
            val remoteHash = hashOf(existing)
            val different = differentTopLevelFields(local, existing)
            if (different.isEmpty()) {
                return@withContext Result(
                    status = "already_present",
                    bookSourceUrl = url,
                    written = false,
                    confirmed = true,
                    presentInReader = true,
                    normalizedFields = normalizedFields(local, existing),
                    localHash = localHash,
                    remoteHash = remoteHash,
                    differentTopLevelFields = emptyList(),
                    remoteOnlyTopLevelFields = remoteOnlyTopLevelFields(local, existing),
                    mismatchedFields = emptyList(),
                    projectId = projectId,
                    message = "阅读里已有该书源且内容一致，未写入",
                    writeAttempted = false,
                    writeOutcome = WriteOutcome.NOT_ATTEMPTED,
                    requests = requests.toList(),
                )
            }
            if (!overwrite) {
                return@withContext Result(
                    status = "conflict",
                    bookSourceUrl = url,
                    written = false,
                    confirmed = false,
                    presentInReader = true,
                    normalizedFields = normalizedFields(local, existing),
                    localHash = localHash,
                    remoteHash = remoteHash,
                    differentTopLevelFields = different,
                    remoteOnlyTopLevelFields = remoteOnlyTopLevelFields(local, existing),
                    mismatchedFields = emptyList(),
                    projectId = projectId,
                    message = "阅读里已有该书源但内容不同，未写入；确认要用本地版本覆盖时传 overwrite=true",
                    writeAttempted = false,
                    writeOutcome = WriteOutcome.NOT_ATTEMPTED,
                    requests = requests.toList(),
                )
            }
            write(pinned, local, url, localHash, overwrite = true, remoteHash = remoteHash, different = different, requests = requests)
        }
    }

    private fun requestRow(diagnostic: ReaderWebRequestDiagnostic?): Map<String, Any?> =
        diagnostic?.toPayload().orEmpty()

    /**
     * 判定 `POST /saveBookSource` 失败时「阅读到底有没有可能收到」。
     *
     * 只有**连接根本没建立**（DNS 解析失败、连接被拒/不可达、TLS 握手失败）才能确认请求没送到；
     * 连接建立后的断开/超时/协议错误都可能发生在正文已到达之后，一律记 [WriteOutcome.UNKNOWN]，
     * 并且绝不因为「不确定」就再发一次 POST。
     */
    private fun saveFailureOutcome(error: ReaderWebClient.ReaderWebException): String {
        val diagnostic = error.diagnostic ?: return WriteOutcome.UNKNOWN
        val notDelivered = diagnostic.phase == ReaderWebRequestDiagnostic.Phase.CONNECT &&
            diagnostic.exceptionCategory in setOf(
                ReaderWebRequestDiagnostic.Category.DNS,
                ReaderWebRequestDiagnostic.Category.CONNECT,
                ReaderWebRequestDiagnostic.Category.TLS,
            )
        if (notDelivered) return WriteOutcome.NOT_SENT
        // 服务端明确回了 HTTP（含 4xx/5xx）或返回 isSuccess=false：写入未被接受
        if (diagnostic.httpStatus != null) return WriteOutcome.REJECTED
        return WriteOutcome.UNKNOWN
    }

    private suspend fun write(
        pinned: ReaderWebClient,
        local: JsonObject,
        url: String,
        localHash: String,
        overwrite: Boolean,
        remoteHash: String?,
        different: List<String>,
        requests: MutableList<Map<String, Any?>>,
    ): Result {
        val saved = try {
            pinned.saveSource(local)
        } catch (error: ReaderWebClient.ReaderWebException) {
            requests += requestRow(error.diagnostic)
            val outcome = saveFailureOutcome(error)
            throw SyncException(
                error.code,
                syncWriteFailureMessage(error, outcome),
                diagnostic = error.diagnostic,
                // POST 只发一次：失败不等于没发；不确定时明确说不知道，而不是「肯定没写」
                writeAttempted = true,
                writeOutcome = outcome,
                requests = requests.toList(),
            )
        }
        requests += requestRow(saved.diagnostic)
        // HTTP 200 + isSuccess=true 仍然只是「阅读接受了请求」；必须回读核对
        val readback = try {
            pinned.fetchSource(url, ReaderWebClient.Stage.READBACK)
        } catch (error: ReaderWebClient.ReaderWebException) {
            requests += requestRow(error.diagnostic)
            // 写入已被阅读接受，只是核对读不到：不能报成「没写」，也不能报成功
            throw SyncException(
                "READBACK_FAILED",
                "阅读已接受写入（POST /saveBookSource 成功），但回读失败：${error.message.orEmpty()}。" +
                    "书源可能已写入，请在阅读的书源列表确认；不会自动重发写入",
                diagnostic = error.diagnostic,
                writeAttempted = true,
                writeOutcome = WriteOutcome.ACCEPTED,
                requests = requests.toList(),
            )
        }
        requests += requestRow(readback.diagnostic)
        if (!readback.present || readback.source == null) {
            return Result(
                status = "written_unconfirmed",
                bookSourceUrl = url,
                written = true,
                confirmed = false,
                localHash = localHash,
                remoteHash = null,
                differentTopLevelFields = different,
                remoteOnlyTopLevelFields = emptyList(),
                mismatchedFields = listOf("bookSourceUrl"),
                projectId = null,
                message = "阅读接受了写入（isSuccess=true），但回读不到该书源：可能仍在异步落库，或阅读端未实际保存；请稍后在阅读的书源列表确认",
                writeAttempted = true,
                writeOutcome = WriteOutcome.ACCEPTED,
                // 这不是失败请求：回读成功但没找到源，诊断只在 requests 里列
                requests = requests.toList(),
            )
        }
        val savedSource = readback.source
        // 回读核对覆盖**全部**本地非元数据顶层字段（含任意新增字段与整棵嵌套规则），不只少数关键字段
        val mismatched = differentTopLevelFields(local, savedSource)
        val confirmed = mismatched.isEmpty()
        return Result(
            status = if (confirmed) "written" else "written_unconfirmed",
            bookSourceUrl = url,
            written = true,
            confirmed = confirmed,
            presentInReader = true,
            normalizedFields = normalizedFields(local, savedSource),
            localHash = localHash,
            remoteHash = hashOf(savedSource),
            differentTopLevelFields = different,
            remoteOnlyTopLevelFields = remoteOnlyTopLevelFields(local, savedSource),
            mismatchedFields = mismatched,
            projectId = null,
            message = if (confirmed) {
                "已写入阅读并回读确认全部字段一致；下一步用 reader_web_verify 做阅读端检验"
            } else {
                "已写入阅读并回读到该地址，但字段不一致（见 mismatchedFields）：自动调试仅针对阅读现存版本，不代表本地成品已生效；请核对保存结果"
            },
            writeAttempted = true,
            writeOutcome = WriteOutcome.ACCEPTED,
            requests = requests.toList(),
        )
    }

    /** 写入失败的如实措辞：按「发没发出、阅读收没收到」区分，不能让模型以为一定没写。 */
    private fun syncWriteFailureMessage(
        error: ReaderWebClient.ReaderWebException,
        outcome: String,
    ): String = when (outcome) {
        WriteOutcome.NOT_SENT ->
            "写入请求没有送到阅读（${error.message.orEmpty()}）：可以安全重试"
        WriteOutcome.REJECTED ->
            "阅读拒绝了这次写入（${error.message.orEmpty()}）：书源未写入"
        else ->
            "写入请求已发出，但连接中断（${error.message.orEmpty()}）：无法确认阅读是否已保存，" +
                "不会自动重发；请先在阅读的书源列表确认是否已出现该书源"
    }

    // ---- 比较与哈希（纯逻辑，JVM 可测） ----

    /**
     * 实质差异的顶层字段名。
     *
     * 两侧都参与比较：
     * - 本地有、远端缺：值非默认（空串/`false`/`0`/空对象/空数组）才算差异；
     * - 远端有、本地缺：同样只有非默认值才算差异——阅读旧源残留的非空 `jsLib`/`header`/规则/登录字段
     *   不能因为本地成品省略了该键就被当成「内容一致」；
     * - 两侧都有：整棵子树严格比较。
     *
     * 阅读端补上的默认空值，以及本地省略时单独补出的 `enabledCookieJar=true` 不算差异；显式值仍严格比较。
     */
    private fun differentTopLevelFields(local: JsonObject, remote: JsonObject): List<String> =
        (local.entrySet()
            .filter { it.key !in READER_METADATA }
            .filter { entry ->
                val remoteValue = remote.get(entry.key)
                val remoteMissing = !remote.has(entry.key) || remoteValue == null || remoteValue.isJsonNull
                if (remoteMissing) !isDefaultValue(entry.value) else !sameValue(entry.value, remoteValue)
            }
            .map { it.key } + remoteOnlySubstantiveFields(local, remote))
            .distinct()
            .sorted()

    /** 远端存在、本地省略，且值不是该字段阅读默认值的实质字段。 */
    private fun remoteOnlySubstantiveFields(local: JsonObject, remote: JsonObject): List<String> =
        remote.entrySet()
            .filter { it.key !in READER_METADATA && !local.has(it.key) }
            .filter { entry ->
                if (entry.key == "enabledCookieJar") !isReaderPadding(entry.key, entry.value)
                else !isDefaultValue(entry.value)
            }
            .map { it.key }
            .sorted()

    private fun isReaderPadding(key: String, value: JsonElement): Boolean =
        key == "enabledCookieJar" && value.isJsonPrimitive &&
            value.asJsonPrimitive.isBoolean && value.asBoolean

    private fun normalizedFields(local: JsonObject, remote: JsonObject): List<String> =
        if (!local.has("enabledCookieJar") && remote.has("enabledCookieJar") &&
            isReaderPadding("enabledCookieJar", remote.get("enabledCookieJar"))) listOf("enabledCookieJar")
        else emptyList()

    /**
     * 远端独有、本地省略的字段名（诊断用，含默认空值）。
     * 是否构成冲突由 [remoteOnlySubstantiveFields] / [differentTopLevelFields] 决定，不用本列表。
     */
    private fun remoteOnlyTopLevelFields(local: JsonObject, remote: JsonObject): List<String> =
        remote.keySet()
            .filter { it !in READER_METADATA && !local.has(it) }
            .sorted()

    /**
     * 阅读的默认空值：`null`、空串/纯空白、`false`、`0`、空对象/数组，以及全部成员都是默认值的
     * 规则对象（阅读常把未使用的规则补成 `{"bookList":"",...}`）。这些不构成实质差异。
     */
    private fun isDefaultValue(element: JsonElement?): Boolean = when (element) {
        null, is JsonNull -> true
        is JsonObject -> element.entrySet().all { isDefaultValue(it.value) }
        is JsonArray -> element.all { isDefaultValue(it) }
        is JsonPrimitive -> when {
            element.isBoolean -> !element.asBoolean
            element.isNumber -> runCatching { element.asDouble == 0.0 }.getOrDefault(false)
            else -> element.asString.isBlank()
        }
        else -> false
    }

    private fun sameValue(a: JsonElement?, b: JsonElement?): Boolean {
        if (a == null || b == null) return a == b
        // 比较的是「字段值」：整棵子树严格比较，元数据豁免只属于书源顶层
        return canonical(a, stripReaderMetadata = false) == canonical(b, stripReaderMetadata = false)
    }

    /** 递归规范化：对象键排序、数组保序；字符串/数字/布尔按字面值。 */
    fun canonical(element: JsonElement?): String = canonical(element, stripReaderMetadata = true)

    /**
     * 只在**最外层**剔除阅读自动维护的元数据；嵌套对象（规则体、header 等）里即使出现
     * `enabled`/`group` 这类同名键也严格参与比较——它们是书源内容，不是阅读元数据。
     */
    private fun canonical(element: JsonElement?, stripReaderMetadata: Boolean): String = when (element) {
        null, is com.google.gson.JsonNull -> "null"
        is JsonObject -> element.entrySet()
            .filter { !(stripReaderMetadata && it.key in READER_METADATA) }
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") {
                "\"${it.key}\":${canonical(it.value, stripReaderMetadata = false)}"
            }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") {
            canonical(it, stripReaderMetadata = false)
        }
        is JsonPrimitive -> when {
            element.isBoolean -> if (element.asBoolean) "true" else "false"
            element.isNumber -> element.asNumber.toString()
            else -> "\"${element.asString.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        else -> element.toString()
    }

    /**
     * 稳定短哈希（sha256 前 16 个十六进制字符），只用于报告「两侧是否相同」。
     * 顶层阅读元数据（`lastUpdateTime`/`respondTime`/分组等）不参与：它们是阅读自己维护的，
     * 本地成品与阅读内的同一书源在这些字段上本来就不同。
     */
    fun hashOf(element: JsonElement): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical(element).toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
}
