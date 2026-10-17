package com.mina.legadostudio.runtime

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.HttpOrigin
import com.mina.legadostudio.verification.RuntimeCookieStore
import com.mina.legadostudio.verification.WebViewPageLoader
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

class RhinoEvaluator(
    private val fetcher: HttpFetcher,
    private val gson: Gson,
    private val webViewLoader: WebViewPageLoader? = null,
    private val cookies: RuntimeCookieStore? = null,
    private val userAgentProvider: () -> String = { HttpFetcher.DEFAULT_UA },
    private val originProvider: () -> HttpOrigin? = { null },
    private val httpLogs: com.mina.legadostudio.network.HttpLogRecorder? = null,
    /** 图片验证码：入参图 URL，返回用户输入的答案（null=超时/取消）；由 StudioApplication 注入 */
    private val verificationProvider: (suspend (String) -> String?)? = null,
    /**
     * 浏览器等待（java.startBrowserAwait）：入参验证页 URL 与可选交接 html，先在手机验证中心
     * WebView 让用户过盾，完成后返回过盾页的整页源码（官方 StrResponse.body）。null=超时/取消。
     * 由 StudioApplication 注入；为 null 时 startBrowserAwait 退化为抛 VerificationRequiredException。
     */
    private val browserAwaitProvider: (suspend (url: String, html: String?) -> String?)? = null,
) {
    @Keep data class Result(
        @SerializedName("value") val value: String?,
        @SerializedName("logs") val logs: List<String>,
        @SerializedName("elapsedMs") val elapsedMs: Long,
    )
    data class RawResult(val value: Any?, val logs: List<String>, val elapsedMs: Long)

    fun evaluate(js: String, baseUrl: String = "", previous: Any? = null, bindings: Map<String, Any?> = emptyMap(), origin: HttpOrigin? = null): Result {
        val raw = evaluateRaw(js, baseUrl, previous, bindings, origin)
        return Result(normalize(raw.value), raw.logs, raw.elapsedMs)
    }

    fun evaluateRaw(js: String, baseUrl: String = "", previous: Any? = null, bindings: Map<String, Any?> = emptyMap(), origin: HttpOrigin? = null): RawResult {
        require(js.isNotBlank()) { "JavaScript 不能为空" }
        val logs = mutableListOf<String>()
        // 登录桩锚点：一次求值内 java.upLoginData 与 source.getLoginInfo* 必须同键——
        // 优先书源锚点（debug 链路经 BINDING_SOURCE_ANCHOR 传入 bookSourceUrl），退到 origin 锚点、baseUrl。
        val loginAnchor = (bindings[BINDING_SOURCE_ANCHOR] as? String)
            ?: (origin ?: originProvider())?.sourceAnchor ?: baseUrl
        @Suppress("UNCHECKED_CAST")
        val jsLogSink = bindings[BINDING_JS_LOG] as? (String) -> Unit
        val api = StudioJsApi(
            fetcher, { line -> logs += line; jsLogSink?.invoke(line) }, webViewLoader, cookies, userAgentProvider, httpLogs,
            useCookieJar = bindings[BINDING_USE_COOKIE_JAR] as? Boolean ?: true,
            loginAnchor = loginAnchor,
            originProvider = { origin ?: originProvider() },
            verificationProvider = verificationProvider,
            browserAwaitProvider = browserAwaitProvider,
        )
        val started = System.currentTimeMillis()
        // 每次求值挂一套全新标准对象作用域：var 声明与原型修改都不跨调用泄漏；
        // 跨调用共享只能显式走 cache / source（bindings）等注入对象，没有隐式共享全局。
        val scope = ScriptBindings()
        scope.put("java", api)
        scope.put("result", previous)
        scope.put("baseUrl", baseUrl)
        scope.put("src", previous)
        // 官方同名对象：cookie/cache/source。bindings 传入的 source 状态图包成 SourceJsApi，JS 写入回传调试流程
        scope.put("cookie", CookieJsApi(cookies))
        // bindings 指定 cache 实现时用同一个实例（debug 链路要共享一份观测缓存——
        // 否则每个 JS 段各自拿到全新的 CacheJsApi，SnapshottingCache 的写入痕迹会分段丢失）
        scope.put("cache", bindings["cache"] as? CacheJsApi ?: CacheJsApi())
        val jsoup = JsoupJsApi()
        scope.put("Jsoup", jsoup)
        // AI 常把官方 java.ajax 风格与 org.jsoup.Jsoup 包路径混写；沙箱没有 Java 包树，
        // 用 Map 桥出 org.jsoup.Jsoup 的常用静态入口，调用落到同一 JsoupJsApi 代理。
        scope.put("org", mapOf("jsoup" to mapOf("Jsoup" to jsoup)))
        bindings.forEach { (key, item) -> if (key !in RESERVED_BINDING_KEYS) scope.put(key, item) }
        @Suppress("UNCHECKED_CAST")
        scope.put("source", when (val bound = bindings["source"]) {
            is SourceJsApi -> bound
            is MutableMap<*, *> -> SourceJsApi(bound as MutableMap<String, String>, loginAnchor, logs::add)
            else -> SourceJsApi(anchor = loginAnchor, warn = logs::add)
        })
        // 官方同名内置变量：book / chapter。debug 链路传入共享实例（字段抽取写回、变量池跨阶段共享）；
        // eval_js 不传时兜底空对象，保证 book.name / book.getVariable / chapter.index 等惯用法不报错。
        scope.put("book", when (val bound = bindings["book"]) {
            is BookJsApi -> bound
            is Map<*, *> -> BookJsApi().also { book -> bound.forEach { (k, v) -> book[k?.toString().orEmpty()] = v } }
            else -> BookJsApi()
        })
        scope.put("chapter", when (val bound = bindings["chapter"]) {
            is ChapterJsApi -> bound
            is Map<*, *> -> ChapterJsApi().also { ch -> bound.forEach { (k, v) -> ch[k?.toString().orEmpty()] = v } }
            else -> ChapterJsApi()
        })
        // jsLib 装载：官方 BookSource 的 jsLib 字段是纯 JS 片段（函数/全局 var 声明），
        // 官方 App 把它 eval 进该书源的共享作用域供所有规则 JS 直接调用。沙箱按书源隔离实现：
        // 每书源建立一个 jsLib 父层作用域（自身挂在全新标准对象上），bindings 作用域 chainTo 其下。
        // 表达式里同名 var/赋值只写进本作用域的 globalThis（TopLevel.put 直写 globalThis），
        // 不覆盖父层 jsLib 函数；jsLib 的 `const {java,source}=this` 惯用法依赖
        // FEATURE_LEGADO_DYNAMIC_DEFAULT_THIS：函数被调用时 this 取当次顶层作用域的 globalThis，
        // 即表达式所在作用域，java/cookie/source 等注入绑定在调用点生效。
        // jsLib 为 http(s) URL 时在 EmbeddedLegadoRuntime 层被拒（不做外部加载），到不了这里；
        // eval_js 不传该 binding 时不会凭空装载任何 jsLib。
        @Suppress("UNCHECKED_CAST")
        val jsLibProvider = bindings[BINDING_JSLIB] as? () -> String?
        // 先取一次 jsLib 源码：装载失败时错误包装需要原样引用，行归属回退也要这份文本
        val jsLibSource = jsLibProvider?.invoke()?.takeIf { it.isNotBlank() }
        val parent = jsLibSource?.let { libSource ->
            RhinoScriptEngine.newStandardTopLevel().also { libScope ->
                try {
                    RhinoScriptEngine.eval(libSource, libScope)
                } catch (error: Exception) {
                    throw decorateApiError(
                        com.script.ScriptException("jsLib 执行失败：${error.message.orEmpty()}", "jsLib", -1)
                            .also { it.initCause(error) },
                        js, jsLibSource,
                    )
                }
            }
        }
        scope.chainTo(parent ?: RhinoScriptEngine.newStandardTopLevel())
        val value = try {
            RhinoScriptEngine.eval(js, scope)
        } catch (error: Exception) {
            throw decorateApiError(error, js, jsLibSource)
        }
        return RawResult(value, logs, System.currentTimeMillis() - started)
    }

    /**
     * 错误包装，两件事：
     * 1) 报错行号归属：Rhino 的行号按「编译单元」给（jsLib 与规则脚本各是独立编译单元，
     *    但 fileName 都是 <script-N> 匿名标签），这里把第 N 行内容（截断 200 字符，带前后各 1 行）
     *    附到错误里；行号超出当前规则脚本但落在 jsLib 范围内时，按「可能对应 jsLib」标注——
     *    这是真机修书源最常见的迷惑点（书源报错指着规则行号，实际炸在 jsLib 里的函数）。
     * 2) 确凿的「方法不存在 / 签名不匹配 / 调用的不是函数」异常追加沙箱 API 清单提示；
     *    方法确实存在但重载都对不上（如 java.get(url, headers, timeout) 这类真机签名）
     *    额外标注「沙箱不支持该签名（验收环境限制，非书源错误）」，并解析出 java.xxx(参数类型)。
     * 其余 JS 一般异常只做行归属，不改写文案。
     */
    private fun decorateApiError(error: Exception, script: String? = null, jsLib: String? = null): Exception {
        val scriptError = error as? com.script.ScriptException
        if (error.causeChainContainsSanitizedInput()) {
            return com.script.ScriptException(
                StudioSymmetricCrypto.SanitizedInputException.MESSAGE,
                scriptError?.fileName,
                scriptError?.lineNumber ?: -1,
                scriptError?.columnNumber ?: -1,
            )
        }
        val scriptContext = scriptError?.lineNumber?.takeIf { it > 0 }?.let { line ->
            lineContext(script.orEmpty(), line)?.let { "\n[规则 JS 第${line}行] $it" }
                ?: lineContext(jsLib.orEmpty(), line)?.let { "\n[jsLib 第${line}行（可能对应）] $it" }
        }.orEmpty()
        val message = error.message.orEmpty()
        val firstLine = message.substringBefore('\n')
        val sureMissingApi = MISSING_API_PATTERNS.any { it.containsMatchIn(firstLine) }
        if (!sureMissingApi) {
            if (scriptContext.isEmpty()) return error
            return com.script.ScriptException(
                message + scriptContext,
                scriptError?.fileName,
                scriptError?.lineNumber ?: -1,
                scriptError?.columnNumber ?: -1,
            ).also { it.initCause(error.cause ?: error) }
        }
        val signatureNote = MISSING_API_METHOD.find(firstLine)?.let { match ->
            val call = match.groupValues[1]                                   // e.g. "...StudioJsApi.get(string,object,number,string)"
            val method = call.substringBefore('(').substringAfterLast('.')    // e.g. "get"
            val known = method in STUDIO_API_METHODS
            if (!known) null
            else {
                val argTypes = call.substringAfter('(').substringBeforeLast(')')
                    .split(',').joinToString(",") { it.trim().substringAfterLast('.') }
                "\n【签名提示】沙箱不支持该签名：java.$method($argTypes)；属验收环境限制，非书源错误。真机有对应重载的话请改写调用或改用 connect/ajax。（原始：$call）"
            }
        }.orEmpty()
        return com.script.ScriptException(
            message + scriptContext + signatureNote + API_HINT,
            scriptError?.fileName,
            scriptError?.lineNumber ?: -1,
            scriptError?.columnNumber ?: -1,
        ).also { it.initCause(error.cause ?: error) }
    }

    private fun Throwable?.causeChainContainsSanitizedInput(): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current = this
        while (current != null && seen.add(current)) {
            if (current is StudioSymmetricCrypto.SanitizedInputException) return true
            current = current.cause
        }
        return false
    }

    /** 取脚本第 [line] 行内容（截断 200 字符）+ 前后各 1 行，供错误归属提示；行号越界返回 null。 */
    private fun lineContext(source: String, line: Int): String? {
        if (source.isBlank()) return null
        val lines = source.lines()
        if (line !in 1..lines.size) return null
        return (maxOf(1, line - 1)..minOf(lines.size, line + 1))
            .joinToString(" ⏎ ") { i -> "${i}:${lines[i - 1].trim().take(200)}" }
    }

    companion object {
        /**
         * bindings 内部保留键：值是 `() -> String?`，惰性返回该书源的 jsLib 纯 JS 片段。
         * 由 EmbeddedLegadoRuntime.debug 按书源传入；eval_js 不传 → 不装载。
         * 返回 null/空白 = 无 jsLib；返回 http(s) URL 字符串表示书源声明了外链库，
         * 沙箱不外部加载（由调用方决定报错或降级）。
         */
        const val BINDING_JSLIB = "__legadoJsLib"

        /**
         * bindings 内部保留键：值是 `Boolean`，书源级 `enabledCookieJar` 开关——
         * `false` 时本次求值里 java.ajax/connect/get/post 的二次请求禁用自动 Cookie jar
         * （不回带、不吸收 Set-Cookie；显式用户 cookie 经 cookieHeaderProvider 照旧回带）。
         * 由 EmbeddedLegadoRuntime.debug 按书源注入；eval_js 等不传该键 → 缺省 true（旧行为不变）。
         */
        const val BINDING_USE_COOKIE_JAR = "__legadoUseCookieJar"

        /**
         * bindings 内部保留键：值是 `String`，书源锚点（bookSourceUrl）——
         * 进程内登录信息桩（source.getLoginInfo/putLoginInfo、java.upLoginData）按它分桶共享。
         * 由 EmbeddedLegadoRuntime.debug 按书源注入；不传则回退 origin 锚点 / baseUrl。
         */
        const val BINDING_SOURCE_ANCHOR = "__legadoSourceAnchor"

        /**
         * bindings 内部保留键：值是 `((String) -> Unit)?` 的 JS 日志汇出回调——
         * 该次求值里 java.log/toast/桩提示除进 Result.logs 外，同步推给此回调
         * （debug 报告 jsLogs 字段用它汇聚跨求值的全部输出）。
         */
        const val BINDING_JS_LOG = "__legadoJsLog"

        /**
         * 注入对象时单独处理/跳过的 bindings 保留键：source/book/chapter 包成官方同名 API 对象，
         * cache 取传入实例（debug 链路注入 SnapshottingCache 观测写入），
         * jsLib/锚点/cookieJar 开关/日志汇出是内部传递键不进作用域。
         */
        private val RESERVED_BINDING_KEYS = setOf(
            "source", "book", "chapter", "cache", BINDING_JSLIB, BINDING_SOURCE_ANCHOR,
            BINDING_USE_COOKIE_JAR, BINDING_JS_LOG,
        )
        /**
         * htmlunit-core-js 对「方法不存在/签名不匹配」的固定报错文案。
         * jar 内带 Messages_zh_CN 本地化资源，错误文案随 JVM Locale 走——
         * zh_CN 下是「找不到函数/找不到方法/未定义/不是一个函数」等中文，英文正则不匹配，所以两套都列。
         */
        private val MISSING_API_PATTERNS = listOf(
            // en
            Regex("Cannot find function \\S+"),
            Regex("Method \"\\S+\" not found"),
            Regex("Can't find method \\S+"),
            Regex("Cannot call method \"\\S+\" of"),
            Regex("Cannot call property \\S+ in object"),
            Regex("\\S+ is not a function"),
            Regex("Java class \"\\S+\" has no public instance field or method"),
            Regex("The choice of Java method .* is ambiguous"),
            Regex("Constructor for \"\\S+\" not found"),
            Regex("\\S+ is not defined"),
            // zh_CN（Messages_zh_CN.properties）
            Regex("找不到函数 \\S+"),
            Regex("找不到方法 “\\S+”"),
            Regex("无法调用 \\S+ 的方法"),
            Regex("无法调用对象 \\S+ 中的属性"),
            Regex("\\S+ 不是函数"),
            Regex("\\S+ 未定义"),
            Regex("Java 方法 .* 的选择不明确"),
            Regex("未找到 “\\S+” 的构造函数"),
        )
        /** Rhino「找不到方法」消息里完整方法引用，如 “com.mina...StudioJsApi.get(...)”（zh_CN 带引号）/ Can't find method io.x.Y.z(...).（en 不带引号） */
        private val MISSING_API_METHOD = Regex("(?:[\"“]|Can't find method )([\\w.$]+\\([^\"”)]*\\))")
        /**
         * StudioJsApi 上确实存在的方法名：「找不到方法」且方法名在本集合 = 签名不支持的调用
         * （真机有该重载而沙箱没有），错误里附加「属验收环境限制」的明确提示。
         */
        private val STUDIO_API_METHODS = setOf(
            "log", "logType", "toast", "longToast", "upLoginData", "getVerificationCode",
            "ajax", "ajaxAll", "connect", "get", "post", "head", "startBrowserAwait", "webView",
            "setContent", "getString", "getStringList", "put",
            "encodeURI", "base64Encode", "base64Decode", "base64DecodeToByteArray",
            "md5Encode", "md5Encode16", "sha1Encode", "sha256Encode", "digestHex", "digestBase64Str",
            "HMacHex", "HMacBase64", "hexEncodeToString", "hexDecodeToString", "hexDecodeToByteArray",
            "strToBytes", "bytesToStr", "htmlFormat", "timeFormat", "timeFormatUTC", "toURL",
            "randomUUID", "getWebViewUA", "getCookie", "setCookie", "createSymmetricCrypto",
            "fetchFont", "decodeWoff2", "queryTTF", "replaceFont",
        )
        // 与 evaluateRaw 实际注入的对象/方法保持一致；新增 API 时同步更新本清单与
        // references/js-api.md「Studio 沙箱可用 API 清单」一节。
        private const val API_HINT = "\n【API 提示】以上是确凿的「方法不存在/签名不匹配」错误。沙箱实际可用 API：\n" +
            "java.*：log(msg) logType(obj) toast(msg) longToast(msg)；ajax(url) ajaxAll(urls) connect(url[,header][,methodOrTimeout][,body][,charset]) " +
            "get(url[,headers][,timeout毫秒]) post(url,body[,headers][,timeout毫秒]) head(url[,headers][,timeout毫秒]) webView(url|html,url,js) startBrowserAwait(url,title?,refetch?) fetchFont(url) " +
            "decodeWoff2(data) queryTTF(data) replaceFont(text,errFont,correctFont[,filter])；" +
            "setContent(html) getString(rule,content?) getStringList(rule,content?) put(key,value) get(key或url[,headers])；" +
            "encodeURI(str,charset?) base64Encode(str) base64Decode(str,charset?) base64DecodeToByteArray(str) " +
            "md5Encode(str) md5Encode16(str) sha1Encode(str) sha256Encode(str) digestHex(str,算法) digestBase64Str(str,算法) HMacHex(str,算法,key) HMacBase64(str,算法,key) " +
            "hexEncodeToString(str) hexDecodeToString(str) hexDecodeToByteArray(str) strToBytes(str,charset?) bytesToStr(bytes,charset?) " +
            "htmlFormat(str) timeFormat(time[,format]) timeFormatUTC(time,format,时区偏移毫秒) toURL(url[,baseUrl]) randomUUID() getWebViewUA() " +
            "getCookie(url[,key]) setCookie(url,cookie) createSymmetricCrypto(transformation,key,iv?) java.lang.Thread.sleep(ms)\n" +
            "Jsoup.*（全局对象）：Jsoup.parse(html[,baseUri]) Jsoup.parseBodyFragment(html) Jsoup.connect(url)；org.jsoup.Jsoup.* 写法已桥接到同一对象，但 Packages.org.jsoup 不可用。\n" +
            "cookie.*：getCookie(url) getKey(url,key) setCookie(url,cookie) replaceCookie(url,cookie) removeCookie(url) setWebCookie(url,cookie)\n" +
            "cache.*：put(key,value,saveTime秒?) get(key) delete(key) putMemory(key,value) getFromMemory(key) deleteMemory(key)\n" +
            "source.*：getVariable() setVariable(json) putVariable(json) put(key,value) get(key) " +
            "getLoginInfo([key]) getLoginInfoMap() putLoginInfo(json或map或key,value)（登录信息为进程内桩存储，默认空）\n" +
            "book.*（官方 Book 属性可读写）：bookUrl tocUrl origin originName name author kind coverUrl intro type " +
            "latestChapterTitle totalChapterNum durChapterTitle durChapterIndex canUpdate variable 等；" +
            "putVariable(key,value) getVariable(key?) putCustomVariable(json) getVariableMap() setVariableMap(map) " +
            "upCustomIntro(v) setReverseToc(b) getUseReplaceRule()/setUseReplaceRule(b)\\n" +
            "chapter.*（官方 BookChapter 属性）：url title baseUrl bookUrl index resourceUrl tag start end variable；" +
            "putVariable/getVariable putLyric putImgUrl update\\n" +
            "内置变量：java result(上一步结果) src(同 result) baseUrl cookie cache source book chapter。\\n" +
            "java.getVerificationCode(url)：拉验真图片→手机验证中心 image_code 会话等输入（最长120s）；取消/超时返回空串，拉图失败抛错。\n" +
            "官方有但沙箱为桩实现（能调用、返回空值并记日志提示，真机行为不同）：java.upLoginData(data?)\n" +
            "官方有但沙箱未实现（写了必报错）：java.getElement/getElements java.importScript java.cacheFile java.toNumChapter java.t2s/s2t " +
            "java.androidId java.openUrl java.searchBook java.startBrowser java.getReadBookConfig/getThemeConfig " +
            "java.downloadFile/readTxtFile 等文件读写 java.getZip*/getRar*/get7z* java.createAsymmetricCrypto/createSign；" +
            "java.lang.* 除 Thread.sleep 外全部不可用，标准库类请走 Packages.*（如 new Packages.java.lang.String(b,\"UTF-8\")）。\n" +
            "完整说明见 references/js-api.md「Studio 沙箱可用 API 清单」。"
    }

    private fun normalize(value: Any?): String? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value.toString()
        else -> runCatching { gson.toJson(value) }.getOrElse { value.toString() }
    }
}

class StudioJsApi(
    private val fetcher: HttpFetcher,
    private val logger: (String) -> Unit,
    private val webViewLoader: WebViewPageLoader? = null,
    private val cookies: RuntimeCookieStore? = null,
    private val userAgentProvider: () -> String = { HttpFetcher.DEFAULT_UA },
    private val httpLogs: com.mina.legadostudio.network.HttpLogRecorder? = null,
    private val useCookieJar: Boolean = true,
    /** 登录桩锚点（书源 bookSourceUrl）；与 source.getLoginInfo* 同键，由 evaluateRaw 统一解析 */
    private val loginAnchor: String = "",
    private val originProvider: () -> HttpOrigin? = { null },
    /**
     * 图片验证码会话供应器：入参验证码图 URL，返回用户在验证中心输入的答案（null=超时/取消）。
     * 由 RhinoEvaluator 构造时注入（包 VerificationCoordinator.create+awaitAnswer 与抓图）。
     */
    private val verificationProvider: (suspend (String) -> String?)? = null,
    /**
     * 浏览器等待（java.startBrowserAwait）：入参验证页 URL 与可选交接 html，先在手机验证中心
     * WebView 让用户过盾，完成后返回过盾页的整页源码（官方 StrResponse.body）。null=超时/取消。
     */
    private val browserAwaitProvider: (suspend (url: String, html: String?) -> String?)? = null,
) {
    /** 官方兼容：java.lang.Thread.sleep(ms)（沙箱仅放行这一个 java.lang 用法，其余包路径不可用） */
    @JvmField
    val lang: StudioLangApi = StudioLangApi()

    /** 官方 java.log 返回入参本身，便于链式调试 */
    fun log(message: Any?): Any? {
        logger(message?.toString().orEmpty())
        return message
    }

    fun toast(message: Any?) { logger("toast: ${message?.toString().orEmpty()}") }
    fun longToast(message: Any?) { logger("longToast: ${message?.toString().orEmpty()}") }

    /** 官方 java.logType(obj)：打印对象 Java 类型名（null 打印 "null"），用于调试 */
    fun logType(any: Any?) { logger(any?.javaClass?.name ?: "null") }

    /**
     * 官方 java.upLoginData(data?)：刷新登录界面用户数据。沙箱无登录 UI，退化为写进程内登录桩存储
     * （source.getLoginInfo* 可读回），并记一行桩提示。null = 清空（真机重置为默认值）。
     */
    @JvmOverloads
    fun upLoginData(data: Any? = null) {
        logger("java.upLoginData 为沙箱桩实现：沙箱无登录界面，仅写入进程内登录信息（source.getLoginInfo* 可见）；真机会刷新书源 loginUi 表单值")
        when (data) {
            null -> RuntimeLoginStore.map(loginAnchor).clear()
            is Map<*, *> -> data.forEach { (k, v) -> if (k != null) RuntimeLoginStore.map(loginAnchor)[k.toString()] = v?.toString().orEmpty() }
            is String -> runCatching {
                com.google.gson.JsonParser.parseString(data).asJsonObject.entrySet()
                    .forEach { RuntimeLoginStore.map(loginAnchor)[it.key] = it.value.asString }
            }
            else -> { /* 不认识的形态忽略，别让脚本崩 */ }
        }
    }

    /**
     * 官方 java.getVerificationCode(imageUrl)：拉验证码图 → 在 App 验证中心弹 image_code 会话等用户输入。
     * 返回用户在验证中心提交的验证码文本；超时/用户关闭返回空串，拉图失败抛错。
     * 验证会话由 StudioApplication 注入的 [verificationProvider] 创建并等待回答。
     */
    fun getVerificationCode(imageUrl: Any? = null): String {
        val url = imageUrl?.toString().orEmpty()
        require(url.isNotBlank()) { "java.getVerificationCode：验证码图片 URL 不能为空" }
        val provider = verificationProvider ?: error("java.getVerificationCode：验证中心未初始化，无法拉取验证码图片")
        logger("java.getVerificationCode：正在拉图并等待手机验证中心输入（最长 120s）")
        val answer = kotlinx.coroutines.runBlocking { provider(url) }
        if (answer == null) logger("java.getVerificationCode：用户取消或等待超时")
        return answer.orEmpty()
    }

    fun ajax(url: String): String = fetcher.fetch(HttpFetcher.FetchRequest(url, origin = withKind("js_ajax"), useCookieJar = useCookieJar)).body
    fun connect(url: CharSequence): StudioJsResponse = connectInternal(url.toString(), null, null, null, null)

    fun connect(url: CharSequence, header: Any?): StudioJsResponse = connectInternal(url.toString(), header, null, null, null)

    fun connect(url: CharSequence, header: Any?, param3: Any?): StudioJsResponse {
        // param3 可能是 timeout (数值) 或 method (如 "POST")
        val str3 = param3?.toString().orEmpty().trim()
        val isMethod = str3.equals("GET", true) || str3.equals("POST", true) || str3.equals("HEAD", true)
        val method = if (isMethod) str3.uppercase() else null
        return connectInternal(url.toString(), header, method, null, null)
    }

    fun connect(url: CharSequence, header: Any?, method: Any?, body: Any?): StudioJsResponse {
        return connectInternal(url.toString(), header, method?.toString(), body?.toString(), null)
    }

    fun connect(url: CharSequence, header: Any?, method: Any?, body: Any?, charset: Any?): StudioJsResponse {
        return connectInternal(url.toString(), header, method?.toString(), body?.toString(), charset?.toString())
    }

    private fun connectInternal(url: String, header: Any?, explicitMethod: String?, explicitBody: String?, explicitCharset: String?): StudioJsResponse {
        val parsed = LegadoUrlOptions.parse(url)
        val extra = headerMap(header, "connect")
        val absolute = parsed.url.ifBlank { url }
        val finalMethod = explicitMethod ?: parsed.method
        val finalBody = explicitBody ?: parsed.body
        val finalCharset = explicitCharset ?: parsed.charset
        val result = fetcher.fetch(HttpFetcher.FetchRequest(absolute, finalMethod, parsed.headers + extra, finalBody, finalCharset, origin = withKind("js_connect"), useCookieJar = useCookieJar))
        return StudioJsResponse(result.code, result.finalUrl, result.body, result.headers, result.elapsedMs, result.rawBytes)
    }
    /**
     * 官方 java.startBrowserAwait(url, title, refetchAfterSuccess, html?)：弹浏览器让用户过盾，
     * refetchAfterSuccess=true 时过盾后重抓该 URL，返回整页 StrResponse。沙箱经手机验证中心的
     * webview 会话完成同一链路：用户在 App 内 WebView 过盾 → 用 WebView 通道抓该 URL（可选 html
     * 作 loadDataWithBaseURL 交接文档）返回源码。用户取消/超时返回空 body 的响应；
     * 未注入 provider 时退化为抛 VerificationRequiredException。
     */
    @JvmOverloads
    fun startBrowserAwait(url: String, title: Any? = null, refetch: Any? = null, html: Any? = null): StudioJsResponse {
        val provider = browserAwaitProvider
            ?: throw com.mina.legadostudio.verification.VerificationRequiredException(url, com.mina.legadostudio.verification.DomainKey.fromUrl(url))
        logger("java.startBrowserAwait：已转交手机验证中心，等待在 WebView 里过盾（最长 120s）${title?.let { "，$it" }.orEmpty()}")
        val started = System.currentTimeMillis()
        val body = kotlinx.coroutines.runBlocking { provider(url, html?.toString()) }
        if (body == null) logger("java.startBrowserAwait：用户取消或等待超时")
        return StudioJsResponse(if (body == null) 0 else 200, url, body.orEmpty(), emptyMap(), System.currentTimeMillis() - started)
    }
    private var currentDocument: org.jsoup.nodes.Document? = null
    private val memoryStore = java.util.concurrent.ConcurrentHashMap<String, Any?>()
    /** 「真机 java.get/post 传 null headers 可能 NPE」警告每次求值只记一次 */
    private var nullHeadersWarned = false

    /** queryTTF 结果缓存：按字体字节 sha256 作键，LRU 上限 8 份（反爬字体通常几十 KB，解析一次够用整轮调试） */
    private val ttfCache = object : LinkedHashMap<String, com.mina.legadostudio.network.QueryTTF>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, com.mina.legadostudio.network.QueryTTF>?): Boolean = size > 8
    }

    fun setContent(html: Any?) {
        currentDocument = org.jsoup.Jsoup.parse(html?.toString().orEmpty())
    }

    fun put(key: String, value: Any?): Any? {
        if (value == null) memoryStore.remove(key) else memoryStore[key] = value
        return value
    }

    @JvmOverloads
    fun get(keyOrUrl: String, headers: Any? = null): Any? {
        if (keyOrUrl.startsWith("http://", ignoreCase = true) ||
            keyOrUrl.startsWith("https://", ignoreCase = true) ||
            keyOrUrl.startsWith("/")
        ) {
            return request(keyOrUrl, "GET", null, headers, null)
        }
        return memoryStore[keyOrUrl]
    }

    /**
     * 官方三参形态 java.get(url, headers, timeout)：timeout 毫秒（JS Number → int/long/double 皆可）。
     * 独立声明而不进 @JvmOverloads：避免生成 get(String,long) 把内存键查询也带上超时参数。
     */
    fun get(url: String, headers: Any?, timeout: Any?): StudioJsResponse =
        request(url, "GET", null, headers, timeoutMillis(timeout))

    fun post(url: String, body: String): StudioJsResponse = request(url, "POST", body, null, null)

    fun post(url: String, body: String, headers: Any?): StudioJsResponse = request(url, "POST", body, headers, null)

    /** 官方 java.post(url, body, headers, timeout)：timeout 毫秒 */
    fun post(url: String, body: String, headers: Any?, timeout: Any?): StudioJsResponse =
        request(url, "POST", body, headers, timeoutMillis(timeout))

    fun head(url: String): StudioJsResponse = request(url, "HEAD", null, null, null)

    fun head(url: String, headers: Any?): StudioJsResponse = request(url, "HEAD", null, headers, null)

    /** 官方 java.head(url, headers, timeout)：timeout 毫秒 */
    fun head(url: String, headers: Any?, timeout: Any?): StudioJsResponse =
        request(url, "HEAD", null, headers, timeoutMillis(timeout))

    @JvmOverloads
    fun getString(rule: String, content: Any? = null): String {
        val root = resolveElement(content)
        val (css, attr) = parseRuleParts(rule)
        val target = if (css.isNotBlank()) root.select(css).firstOrNull() ?: root else root
        return extractValue(target, attr)
    }

    @JvmOverloads
    fun getStringList(rule: String, content: Any? = null): List<String> {
        val root = resolveElement(content)
        val (css, attr) = parseRuleParts(rule)
        val elements = if (css.isNotBlank()) root.select(css) else org.jsoup.select.Elements(root)
        return elements.map { extractValue(it, attr) }
    }

    private fun resolveElement(content: Any?): org.jsoup.nodes.Element {
        return when (content) {
            is org.jsoup.nodes.Element -> content
            null -> currentDocument?.body() ?: org.jsoup.Jsoup.parse("").body()
            else -> org.jsoup.Jsoup.parse(content.toString()).body()
        }
    }

    private fun parseRuleParts(rule: String): Pair<String, String> {
        val trimmed = rule.trim()
        if ('@' !in trimmed) return trimmed to "all"
        val parts = trimmed.split('@').filter { it.isNotBlank() }
        if (parts.size == 1) return "" to parts[0]
        val css = parts.dropLast(1).joinToString(" ")
        val attr = parts.last()
        return css to attr
    }

    private fun extractValue(element: org.jsoup.nodes.Element, attr: String): String {
        return when (attr.lowercase()) {
            "text", "textnodes" -> element.text()
            "html" -> element.html()
            "all" -> element.outerHtml()
            else -> element.attr(attr).ifBlank { element.text() }
        }
    }
    @JvmOverloads
    fun encodeURI(value: String, charset: String = "UTF-8"): String = URLEncoder.encode(value, charset).replace("+", "%20")
    fun base64Encode(value: String): String = Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray())
    @JvmOverloads
    fun base64Decode(value: String, charset: String = "UTF-8"): String = String(Base64.getDecoder().decode(value), java.nio.charset.Charset.forName(charset))
    fun base64DecodeToByteArray(value: String): ByteArray = Base64.getDecoder().decode(value)
    fun md5Encode(value: String): String = digest(value, "MD5")
    fun md5Encode16(value: String): String = md5Encode(value).substring(8, 24)
    fun digestHex(data: String, algorithm: String): String = digest(data, algorithm)
    fun digestBase64Str(data: String, algorithm: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(data.toByteArray()))
    /** 官方 JsEncodeUtils.HMacHex/HMacBase64（官方就叫大写 HMac 开头，不额外提供小写别名——大小写并存会让 Rhino 把方法当 CatchableJavaFunction 引用而非调用） */
    @Suppress("FunctionName")
    fun HMacHex(data: String, algorithm: String, key: String): String =
        hmac(data, algorithm, key).joinToString("") { "%02x".format(it) }
    @Suppress("FunctionName")
    fun HMacBase64(data: String, algorithm: String, key: String): String =
        Base64.getEncoder().encodeToString(hmac(data, algorithm, key))
    @JvmOverloads
    fun timeFormat(value: Long, format: String = "yyyy-MM-dd HH:mm:ss"): String = java.text.SimpleDateFormat(format, java.util.Locale.getDefault()).format(java.util.Date(value))
    fun timeFormatUTC(time: Long, format: String, sh: Int): String =
        java.text.SimpleDateFormat(format, java.util.Locale.getDefault()).run {
            timeZone = java.util.SimpleTimeZone(sh, "UTC")
            format(java.util.Date(time))
        }
    @JvmOverloads
    fun toURL(url: String, baseUrl: Any? = null): StudioJsURL = StudioJsURL(url, baseUrl?.toString())
    fun randomUUID(): String = java.util.UUID.randomUUID().toString()
    fun getWebViewUA(): String = userAgentProvider()
    fun getCookie(url: String): String = cookies?.headerFor(url).orEmpty()
    fun getCookie(url: String, key: String?): String {
        val all = getCookie(url)
        if (key.isNullOrBlank()) return all
        return all.split(';').map { it.trim() }.firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "").orEmpty()
    }
    fun setCookie(url: String, cookie: String) { cookies?.set(url, cookie) ?: error("CookieStore 不可用") }
    fun hexEncodeToString(value: String): String = value.toByteArray().joinToString("") { "%02x".format(it) }
    fun hexDecodeToString(value: String): String = String(value.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    fun sha1Encode(value: String): String = digest(value, "SHA-1")
    fun sha256Encode(value: String): String = digest(value, "SHA-256")
    fun hexDecodeToByteArray(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun strToBytes(value: String): ByteArray = value.toByteArray()
    fun strToBytes(value: String, charset: String): ByteArray = value.toByteArray(Charsets.UTF_8.takeIf { charset.equals("UTF-8", true) } ?: java.nio.charset.Charset.forName(charset))
    fun bytesToStr(value: ByteArray): String = String(value)
    fun bytesToStr(value: ByteArray, charset: String): String = String(value, java.nio.charset.Charset.forName(charset))
    fun htmlFormat(value: String): String = org.jsoup.Jsoup.parseBodyFragment(value).body().html()
    fun ajaxAll(urls: Array<String>): Array<String> = urls.map(::ajax).toTypedArray()
    fun webView(html: String?, url: String?, js: String?): String? {
        // 先校验：危险 scheme/回环私网/超大 html 的报错必须优先于「Loader 不可用」浮出
        val plan = com.mina.legadostudio.verification.WebViewLoadPlan.plan(url, html)
        val loader = webViewLoader ?: error("WebView Loader 不可用")
        val target = plan.url
        val origin = withKind("webview")
        // 主文档 + 子资源都记入 HTTP 日志；子资源走 shouldInterceptRequest 观测回调（statusCode=0，响应由 WebView 自管）
        // html 非空时以 url 为 baseURL 直接渲染交接文档（loadDataWithBaseURL）；null/空白仍走 loadUrl
        val result = kotlinx.coroutines.runBlocking {
            loader.load(target, js, html = html) { method, requestUrl, headers ->
                // recordAsync：shouldInterceptRequest 回调线程不能阻塞，高频子资源排队异步写库
                httpLogs?.recordAsync(
                    com.mina.legadostudio.network.HttpLogRecorder.Draft(
                        method = method, url = requestUrl, statusCode = 0,
                        requestHeaders = headers,
                        sourceAnchor = origin?.sourceAnchor, contextId = origin?.contextId,
                        originKind = "webview_resource",
                    )
                )
            }
        }
        httpLogs?.record(
            com.mina.legadostudio.network.HttpLogRecorder.Draft(
                method = "WEBVIEW", url = target, finalUrl = result.finalUrl, statusCode = 200,
                durationMs = result.elapsedMs, responseBody = result.html,
                sourceAnchor = origin?.sourceAnchor, contextId = origin?.contextId,
                originKind = "webview",
            )
        )
        return result.html
    }
    fun webView(url: String): String? = webView(null, url, null)

    /**
     * 字体专用抓取：仅放行字体 Content-Type 或 .woff/.woff2/.ttf/.otf URL 的响应，
     * 服务端 4 字节魔数必须命中 wOF2/wOFF/TTF/OTTO，否则报错。返回原始字体 ByteArray。
     * 与其他 js 请求一样走 HttpFetcher（含日志）；body 恒为空，二进制只在内存 ByteArray 传递。
     */
    fun fetchFont(url: String): ByteArray {
        require(url.startsWith("http://") || url.startsWith("https://")) { "fetchFont 仅支持 http(s) URL" }
        // 凭据注入校验：与 java.ajax/connect 一致，JS 沙箱允许书源访问任意主机（含内网测试），
        // 但 URL 内嵌用户名/密码始终是异常特征，拒绝。完整三层 SSRF 防护在 MCP fetch_font 工具。
        val parsed = url.toHttpUrlOrNull() ?: error("fetchFont：URL 无法解析")
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "fetchFont：URL 不允许内嵌用户名/密码" }
        val result = fetcher.fetch(
            HttpFetcher.FetchRequest(
                url = url, method = "GET", timeoutSec = 30,
                maxBodyBytes = 2 * 1024 * 1024, allowFontBinary = true,
                origin = withKind("js_fetchFont"), useCookieJar = useCookieJar,
            )
        )
        // 只认成功响应；文本/兜底路径也会把正文字节塞进 rawBytes，消费端必须做魔数终检：
        // Content-Type 与扩展名同时撒谎（如 text/html 伪装 .woff2）时在此拒绝。
        val bytes = result.rawBytes
        if (result.code !in 200..299 || bytes == null || result.body.isNotEmpty() ||
            !com.mina.legadostudio.network.Woff2Decoder.looksLikeFont(bytes)) {
            error("fetchFont：响应不是可放行的字体二进制（code=${result.code} FONT_NOT_FONT）：${result.bodyNote.take(160)}")
        }
        return bytes
    }

    /**
     * 解 WOFF2/WOFF/TTF/OTTO 的 cmap，返回 {codepointHex: glyphId} 的 Map。
     * 入参可为 ByteArray（fetchFont 的结果）或 base64 字符串。
     * 注意：返回的是 glyphId 而非汉字——gid→真实汉字需要字形比对，本 API 只做码点→gid。
     */
    fun decodeWoff2(data: Any): Map<String, Int> {
        val bytes = when (data) {
            is ByteArray -> data
            is String -> runCatching { Base64.getDecoder().decode(data) }
                .getOrElse { throw IllegalArgumentException("decodeWoff2：字符串参数必须是 base64 字体数据") }
            else -> throw IllegalArgumentException("decodeWoff2 参数必须是 ByteArray 或 base64 字符串")
        }
        require(bytes.size <= com.mina.legadostudio.network.Woff2Decoder.MAX_INPUT_BYTES) {
            "decodeWoff2：输入超过 ${com.mina.legadostudio.network.Woff2Decoder.MAX_INPUT_BYTES / 1024 / 1024}MB"
        }
        val decoded = com.mina.legadostudio.network.Woff2Decoder.decode(bytes)
        return decoded.mappings.entries.associate { (cp, gid) -> "U+%04X".format(cp) to gid }
    }

    /**
     * 官方 JsExtensions.queryTTF：解析字体为 QueryTTF（码点↔轮廓指纹双向映射），供 replaceFont 比对。
     * 入参可为 ByteArray（fetchFont 的结果）、base64 字符串、http(s) URL（走 fetchFont 同套抓取与校验）。
     * 支持 TTF/OTF/WOFF/WOFF2：woff/woff2 先解包成 sfnt；woff2 若 glyf/loca 带 transformed glyf
     * （transformVersion≠3）会报错——沙箱未实现其逆变换，请改用未变换的 woff2/woff/ttf。
     * 结果按字体字节 sha256 做 LruCache（同一份字体重复 queryTTF 不重解析）。
     */
    fun queryTTF(data: Any?): com.mina.legadostudio.network.QueryTTF? {
        val bytes = when (data) {
            is ByteArray -> data
            is String -> {
                if (data.startsWith("http://") || data.startsWith("https://")) fetchFont(data)
                else runCatching { Base64.getDecoder().decode(data) }
                    .getOrElse { throw IllegalArgumentException("queryTTF：字符串参数必须是 base64 字体数据或 http(s) URL") }
            }
            else -> throw IllegalArgumentException("queryTTF 参数必须是 ByteArray、base64 字符串或 http(s) URL")
        }
        require(bytes.size <= com.mina.legadostudio.network.Woff2Decoder.MAX_INPUT_BYTES) {
            "queryTTF：输入超过 ${com.mina.legadostudio.network.Woff2Decoder.MAX_INPUT_BYTES / 1024 / 1024}MB"
        }
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        synchronized(ttfCache) { ttfCache[key] }?.let { return it }
        return com.mina.legadostudio.network.QueryTTF(bytes).also { q ->
            synchronized(ttfCache) { ttfCache.put(key, q) }
        }
    }

    /**
     * 官方 JsExtensions.replaceFont：通用字形轮廓比对替换——
     * errorTTF 是网页声明的「错误」字体（页面文本按它编码），correctTTF 是轮廓相同的正确字体；
     * 逐码点取 errorTTF 轮廓指纹，到 correctTTF 里反查回正确汉字。
     * filter=true 时删除 errorTTF 中无轮廓的字符；空白字符恒保留。两字体任一 null 返回原文。
     */
    @JvmOverloads
    fun replaceFont(
        text: String,
        errorQueryTTF: com.mina.legadostudio.network.QueryTTF?,
        correctQueryTTF: com.mina.legadostudio.network.QueryTTF?,
        filter: Boolean = false,
    ): String {
        if (errorQueryTTF == null || correctQueryTTF == null) return text
        // 按码点遍历（不能用 CharArray：有些字符占多个 UTF-16 单元），与官方 toStringArray 同语义
        val contentArray = text.codePoints().toArray().map { cp -> String(intArrayOf(cp), 0, 1) }.toTypedArray()
        val intArray = IntArray(1)
        contentArray.forEachIndexed { index, s ->
            val oldCode = s.codePointAt(0)
            // 忽略正常的空白字符
            if (errorQueryTTF.isBlankUnicode(oldCode)) {
                return@forEachIndexed
            }
            // 删除轮廓数据不存在的字符
            var glyf = errorQueryTTF.getGlyfByUnicode(oldCode)  // 轮廓数据不存在
            if (errorQueryTTF.getGlyfIdByUnicode(oldCode) == 0) glyf = null // 轮廓数据指向保留索引0
            if (filter && (glyf == null)) {
                contentArray[index] = ""
                return@forEachIndexed
            }
            // 使用轮廓数据反查Unicode
            val code = correctQueryTTF.getUnicodeByGlyf(glyf)
            if (code != 0) {
                intArray[0] = code
                contentArray[index] = String(intArray, 0, 1)
            }
        }
        return contentArray.joinToString("")
    }

    private fun request(url: String, method: String, body: String?, headers: Any?, timeoutMs: Long?): StudioJsResponse {
        val result = fetcher.fetch(HttpFetcher.FetchRequest(url, method, headerMap(headers, method.lowercase()), body,
            timeoutSec = timeoutMs?.let { (it / 1000L).coerceAtLeast(1L).toInt() },
            origin = withKind("js_http"), useCookieJar = useCookieJar))
        return StudioJsResponse(result.code, result.finalUrl, result.body, result.headers, result.elapsedMs, result.rawBytes)
    }

    /** headers 归一：JS 对象会桥成 Map（NativeObject 包成 Map 的适配）；字符串按 JSON 对象解析；null 接受但记一条真机警告。 */
    private fun headerMap(headers: Any?, api: String): Map<String, String> = when (headers) {
        null -> {
            if (!nullHeadersWarned) {
                nullHeadersWarned = true
                logger("java.$api 收到 null headers：沙箱已按空 headers 放行；真机 java.get/post 传 null headers 可能 NPE，建议传 HashMap")
            }
            emptyMap()
        }
        is Map<*, *> -> headers.entries.associate { it.key.toString() to it.value.toString() }
        is String -> runCatching { com.google.gson.JsonParser.parseString(headers).asJsonObject.entrySet().associate { it.key to it.value.asString } }.getOrDefault(emptyMap())
        else -> emptyMap()
    }

    /** 官方 timeout 参数：毫秒；JS Number（int/long/double）、数字字符串都接受，非法/非正值回退 null。 */
    private fun timeoutMillis(timeout: Any?): Long? = when (timeout) {
        is Number -> timeout.toLong().takeIf { it > 0 }
        is String -> timeout.toLongOrNull()?.takeIf { it > 0 }
        else -> null
    }
    /** 把当前调用的来源链路与 JS 子请求类型合并，逐事务传递 */
    private fun withKind(kind: String): HttpOrigin? {
        val base = originProvider()
        if (base == null && kind.isBlank()) return null
        return HttpOrigin(
            sourceAnchor = base?.sourceAnchor,
            contextId = base?.contextId,
            originKind = kind,
        )
    }
    private fun digest(value: String, algorithm: String): String = MessageDigest.getInstance(algorithm).digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun hmac(data: String, algorithm: String, key: String): ByteArray {
        val normalized = if (algorithm.startsWith("Hmac", true)) algorithm else "Hmac$algorithm"
        return javax.crypto.Mac.getInstance(normalized).run {
            init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), normalized))
            doFinal(data.toByteArray())
        }
    }

    @JvmOverloads
    fun createSymmetricCrypto(transformation: String, key: Any, iv: Any? = null): StudioSymmetricCrypto =
        StudioSymmetricCrypto(transformation, key, iv)
}

class StudioSymmetricCrypto(
    private val transformation: String,
    key: Any,
    iv: Any?,
) {
    private val keyBytes: ByteArray = key.toBytes()
    private val ivBytes: ByteArray? = iv?.toBytes()

    private fun Any.toBytes(): ByteArray = when (this) {
        is ByteArray -> this
        is String -> this.toByteArray()
        else -> this.toString().toByteArray()
    }

    private fun algorithm(): String = transformation.substringBefore('/').ifBlank { "AES" }

    private fun needsIv(): Boolean = transformation.uppercase().contains("/CBC/") ||
        transformation.uppercase().contains("/CFB/") ||
        transformation.uppercase().contains("/OFB/") ||
        transformation.uppercase().contains("/CTR/")

    private fun newCipher(mode: Int): javax.crypto.Cipher {
        val cipher = runCatching { javax.crypto.Cipher.getInstance(transformation) }
            .getOrElse { javax.crypto.Cipher.getInstance(transformation.replace(Regex("(?i)pkcs7padding"), "PKCS5Padding")) }
        val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, algorithm())
        if (needsIv()) {
            val vector = ivBytes ?: error("$transformation 需要 iv 参数")
            cipher.init(mode, keySpec, javax.crypto.spec.IvParameterSpec(vector))
        } else {
            cipher.init(mode, keySpec)
        }
        return cipher
    }

    fun decrypt(data: ByteArray): ByteArray = newCipher(javax.crypto.Cipher.DECRYPT_MODE).doFinal(data)

    fun decryptStr(value: String): String {
        val cipherBytes = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw SanitizedInputException()
        }
        return String(decrypt(cipherBytes), Charsets.UTF_8)
    }

    fun decryptStr(data: ByteArray): String = String(decrypt(data), Charsets.UTF_8)

    fun decryptBase64Str(value: String): String = decryptStr(value)

    fun encrypt(data: ByteArray): ByteArray = newCipher(javax.crypto.Cipher.ENCRYPT_MODE).doFinal(data)

    fun encryptStr(value: String): ByteArray = encrypt(value.toByteArray(Charsets.UTF_8))

    fun encryptBase64(data: ByteArray): String = Base64.getEncoder().encodeToString(encrypt(data))

    fun encryptBase64(value: String): String = encryptBase64(value.toByteArray(Charsets.UTF_8))

    fun encryptHex(data: ByteArray): String = encrypt(data).joinToString("") { "%02x".format(it) }

    fun encryptHex(value: String): String = encryptHex(value.toByteArray(Charsets.UTF_8))

    fun encryptBase64Str(value: String): String = encryptBase64(value)

    internal class SanitizedInputException : IllegalArgumentException(MESSAGE) {
        companion object { const val MESSAGE = "decryptStr：密文必须是合法的标准 Base64 字符串" }
    }
}

/** 官方 java.toURL 返回对象：java.net.URL 解析出的 host/origin/pathname/searchParams */
@Keep
@Suppress("MemberVisibilityCanBePrivate")
class StudioJsURL(url: String, baseUrl: String? = null) {
    val host: String
    val origin: String
    val pathname: String
    val searchParams: Map<String, String>?

    init {
        val mUrl = if (!baseUrl.isNullOrEmpty()) java.net.URL(java.net.URL(baseUrl), url) else java.net.URL(url)
        host = mUrl.host.orEmpty()
        origin = if (mUrl.port > 0) "${mUrl.protocol}://$host:${mUrl.port}" else "${mUrl.protocol}://$host"
        pathname = mUrl.path.orEmpty()
        searchParams = mUrl.query?.let { query ->
            query.split('&').mapNotNull { piece ->
                val pair = piece.split('=', limit = 2)
                if (pair.size == 2) pair[0] to java.net.URLDecoder.decode(pair[1], "utf-8") else null
            }.toMap()
        }
    }
}

data class StudioJsResponse(
    private val status: Int,
    private val finalUrl: String,
    private val content: String,
    private val responseHeaders: Map<String, String>,
    private val duration: Long,
    private val rawBytes: ByteArray? = null,
    private val prior: StudioJsResponse? = null,
) {
    fun code(): Int = status
    /** 官方阅读兼容别名：部分旧书源写 r.statusCode() */
    fun statusCode(): Int = status
    fun url(): String = finalUrl
    fun body(): String = content
    fun headers(): Map<String, String> = responseHeaders

    /** 兼容单 header 查询（如 res.header("set-cookie") 或 "Set-Cookie"） */
    fun header(name: String): String? {
        return responseHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /** 兼容二进制字节获取（用于转 GBK 等非 UTF-8 编码或图片处理） */
    fun bytes(): ByteArray = rawBytes ?: content.toByteArray(Charsets.UTF_8)

    /** 兼容 Jsoup Connection.Response 风格：Set-Cookie 头按名拆成 cookie 映射（多值取首个） */
    fun cookies(): Map<String, String> = buildMap {
        responseHeaders.forEach { (name, value) ->
            if (name.equals("set-cookie", ignoreCase = true)) {
                value.split(';').firstOrNull()?.split('=', limit = 2)
                    ?.takeIf { it.size == 2 && it[0].isNotBlank() }
                    ?.let { putIfAbsent(it[0].trim(), it[1].trim()) }
            }
        }
    }

    /** 兼容重定向链路前置响应 */
    fun priorResponse(): StudioJsResponse? = prior

    fun callTime(): Long = duration
    fun raw(): StudioJsResponse = this
    fun request(): StudioJsResponse = this
    override fun toString(): String = content
}

/** 官方兼容 java.lang 子路径：沙箱仅放行 Thread.sleep，其余 java.lang.* 一律不可用 */
class StudioLangApi {
    @JvmField
    val Thread: ThreadApi = ThreadApi()

    fun String(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
    fun String(bytes: ByteArray, charset: String): String = String(bytes, java.nio.charset.Charset.forName(charset))

    class ThreadApi {
        fun sleep(ms: Long) {
            val wait = if (ms < 0) 0 else ms
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}

/** 官方 Jsoup 全局包装代理，方便在 JS 脚本中直接使用 Jsoup.parse(...) */
class JsoupJsApi {
    fun parse(html: String): org.jsoup.nodes.Document = org.jsoup.Jsoup.parse(html)
    fun parse(html: String, baseUri: String): org.jsoup.nodes.Document = org.jsoup.Jsoup.parse(html, baseUri)
    fun parseBodyFragment(bodyHtml: String): org.jsoup.nodes.Document = org.jsoup.Jsoup.parseBodyFragment(bodyHtml)
    fun connect(url: String): org.jsoup.Connection = org.jsoup.Jsoup.connect(url)
}
