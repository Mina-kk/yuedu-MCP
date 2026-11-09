/*
 * Copyright (C) 2018-2026 LegadoTeam / gedoor
 *
 * This file is part of yuedu-MCP and is a Kotlin/JVM adaptation of
 * LegadoTeam/legado (GPL-3.0) source. It is distributed under the terms of
 * the GNU General Public License v3.0 (see LICENSE).
 *
 * Upstream origin (fixed commit 330e606c4d57e08adc056ff8ab7a4132c784da9a):
 *   app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt
 *     setContent 104-116, getString 302-382, getStringList 205-296,
 *     getElement 387-417, getElements 453-502, put 856-865, get 875-891,
 *     evalJS bindings 896-936
 *   app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt evalJS
 *   app/src/main/java/io/legado/app/ui/rss/read/RssJsExtensions.kt 286-322
 *     (java.setContent/getString/getStringList/getElement/getElements 的官方形态)
 *
 * Deviations are marked "ADAPT:" and documented in docs/research/runtime-implementation.md.
 */
package com.mina.legadostudio.runtime

import com.mina.legadostudio.network.HttpOrigin

/**
 * 一次规则求值的上下文：把 [ReaderRuleSession]（解析核）与 JS 求值回调接成闭环。
 *
 * 上游 `AnalyzeRule` 的 `java` 就是 `AnalyzeRule` 自身（`bindings["java"] = this`），
 * 因此 `java.getString(...)` / `java.setContent(...)` / `java.put/get` 与规则核是**同一份**
 * content/baseUrl/变量层。沙箱旧实现里 `StudioJsApi` 有一套自己的 CSS 实现与 memoryStore，
 * 与规则核形成两个方言；这里改成 [ReaderRuntimeContext] 持有 session，
 * `StudioJsApi` 的这几个方法全部委托给它。
 *
 * 线程模型：与上游一致，一次解析链一个实例，非线程安全。
 */
class ReaderRuntimeContext(
    content: Any?,
    baseUrl: String = "",
    private val engine: io.legado.app.model.analyzeRule.LegadoRuleEngine = io.legado.app.model.analyzeRule.LegadoRuleEngine(),
    /** 上游 AnalyzeRule.evalJS 的 java/cookie/cache/source/book/result/baseUrl/chapter/title/src/... */
    private val evaluator: (script: String, result: Any?, src: Any?) -> Any?,
    /** 变量层级（chapter→book→ruleData→source） */
    val variables: ReaderRuntimeVariables = ReaderRuntimeVariables({ "" }, { _, _ -> }),
    /** 当前阶段是否有 book（`get("bookName")` 用） */
    private val bookName: () -> String? = { null },
    /** 当前阶段是否有 chapter（`get("title")` 用） */
    private val chapterTitle: () -> String? = { null },
    /** @webjs 的平台能力；null = 当前环境没有 WebView，按 Unsupported 报错 */
    private val evalWebJs: ((String, Any?) -> Any?)? = null,
    /** 规则解析诊断（字段级错误）汇出处 */
    private val onRuleError: ((Exception) -> Unit)? = null,
) {
    /**
     * 解析核：与 `java.*` 共用同一实例（上游 `java = this`）。
     *
     * `evalJs` 闭包里的 `src` 必须是**原始内容**（上游 `bindings["src"] = content`），
     * 不能用 `session.content` 自引用——lambda 在 `session` 初始化表达式里，捕获的是尚未
     * 赋值的字段，Kotlin 会把它当未初始化变量（或运行时读到 null）。这里用构造参数 [content]
     * 与 [self] 延迟引用：`self` 在 `also` 里赋值后，闭包读到的才是当前 session 的实时内容
     * （`java.setContent` 之后 `src` 应随之更新）。
     */
    private val self: ReaderRuleSession

    init {
        self = ReaderRuleSession(
            content = content,
            baseUrl = baseUrl,
            engine = engine,
            evalJs = { script, result -> evaluator(script, result, self.content) },
            evalWebJs = evalWebJs?.let { web -> { script: String, result: Any? -> web(script, result) } },
            getVariable = { key -> variables.get(key) },
            putVariable = { key, value -> variables.put(key, value) },
            bookNameProvider = bookName,
            chapterTitleProvider = chapterTitle,
        ).also { it.onError = onRuleError }
    }

    /** 解析核：与 `java.*` 共用同一实例（上游 `java = this`） */
    val session: ReaderRuleSession get() = self

    /** 当前规则内容（`java.setContent` 会替换它，见上游 AnalyzeRule.setContent） */
    val content: Any? get() = session.content

    /** 当前页地址（`java.setContent(content, baseUrl)` 可改） */
    val baseUrl: String get() = session.baseUrl

    fun setContent(content: Any?, baseUrl: String? = null) {
        session.setContent(content, baseUrl)
    }

    fun getString(rule: String?, content: Any? = null, isUrl: Boolean = false): String =
        session.getString(rule, content, isUrl)

    fun getStringList(rule: String?, content: Any? = null, isUrl: Boolean = false): List<String>? =
        session.getStringList(rule, content, isUrl)

    fun getElement(rule: String): Any? = session.getElement(rule)

    fun getElements(rule: String): List<Any> = session.getElements(rule)

    fun put(key: String, value: String): String = session.put(key, value)

    fun get(key: String): String = session.get(key)

    /**
     * 为一次 JS 求值准备 `src`：上游 `bindings["src"] = content`，即**原始内容**，
     * 不是链中上一段结果（旧实现把 src 设成 previous，链式 JS 拿不到原页面）。
     */
    fun srcForEvaluation(): Any? = session.content

    companion object {
        /** 上游 `AnalyzeUrl.evalJS` 的 baseUrl 语义：URL 阶段的 baseUrl 是站点根 */
        fun urlStageBaseUrl(url: String): String = ReaderRequestTemplate.siteBaseUrl(url)

        /**
         * 构造 URL 阶段（searchUrl/exploreUrl 模板求值）的上下文：没有内容，
         * 只有 `page`/`key`/`baseUrl`/`result` 与变量层。
         */
        fun forUrlStage(
            url: String,
            variables: ReaderRuntimeVariables,
            evaluator: (script: String, result: Any?, src: Any?) -> Any?,
            bookName: () -> String? = { null },
            chapterTitle: () -> String? = { null },
        ): ReaderRuntimeContext = ReaderRuntimeContext(
            content = url,
            baseUrl = urlStageBaseUrl(url),
            evaluator = evaluator,
            variables = variables,
            bookName = bookName,
            chapterTitle = chapterTitle,
        )
    }
}

/** 一次 debug 的共享状态：变量层 + jsLib 装载结果 + 诊断行。多任务各自新建，不串。 */
class ReaderRuntimeState(
    val sourceUrl: String,
    val origin: HttpOrigin? = null,
) {
    /** 书源级变量（`source.put/get`，上游 BaseSource.variable） */
    val sourceVariables = LinkedHashMap<String, String>()

    /** 当前阶段的 book 变量池（上游 Book.variable） */
    val bookVariables = LinkedHashMap<String, String>()

    /** 当前阶段的 chapter 变量池（上游 BookChapter.variable） */
    val chapterVariables = LinkedHashMap<String, String>()

    /** 规则数据层（上游 RuleData.variable；沙箱没有独立实体，按阶段挂在当前条目上） */
    val ruleDataVariables = LinkedHashMap<String, String>()

    /** jsLib 装载诊断（脱敏） */
    val jsLibDiagnostics = mutableListOf<String>()

    /** jsLib 源码（内联 + 已加载远程），首次使用时装载一次 */
    var jsLibSources: List<String>? = null

    /** 当前 book 名（`java.get("bookName")`） */
    var bookName: String? = null

    /** 当前 chapter 标题（`java.get("title")`） */
    var chapterTitle: String? = null

    /**
     * 上游 `AnalyzeRule.get` 的层级查找：chapter → book → ruleData → source，取首个非空。
     */
    fun getVariable(key: String): String =
        chapterVariables[key]?.takeIf { it.isNotEmpty() }
            ?: bookVariables[key]?.takeIf { it.isNotEmpty() }
            ?: ruleDataVariables[key]?.takeIf { it.isNotEmpty() }
            ?: sourceVariables[key].orEmpty()

    /**
     * 上游 `AnalyzeRule.put` 的层级写入：chapter → book → ruleData → source，取第一个存在的层。
     * 沙箱按阶段设置 [writeLayer]，默认写 book（搜索/详情条目）。
     */
    var writeLayer: Layer = Layer.BOOK

    enum class Layer { CHAPTER, BOOK, RULE_DATA, SOURCE }

    fun putVariable(key: String, value: String) {
        when (writeLayer) {
            Layer.CHAPTER -> chapterVariables[key] = value
            Layer.BOOK -> bookVariables[key] = value
            Layer.RULE_DATA -> ruleDataVariables[key] = value
            Layer.SOURCE -> sourceVariables[key] = value
        }
    }

    fun variables(): ReaderRuntimeVariables = ReaderRuntimeVariables(
        get = ::getVariable,
        put = ::putVariable,
        bookName = { bookName },
        chapterTitle = { chapterTitle },
    )

    /** 进入一个新的条目（搜索/发现列表的每条结果）：清空条目级变量，保留 source 级 */
    fun beginItem() {
        bookVariables.clear()
        chapterVariables.clear()
        ruleDataVariables.clear()
        bookName = null
        chapterTitle = null
    }
}
