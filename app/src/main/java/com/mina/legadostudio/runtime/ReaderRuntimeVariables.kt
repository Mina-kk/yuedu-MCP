package com.mina.legadostudio.runtime

/**
 * 一次 debug/求值里 `java.put` / `java.get` 的变量层级（上游 LegadoTeam/legado @330e606
 * `AnalyzeRule.put` 856-865 / `get` 875-891）：
 *
 * 写入：chapter → book → ruleData → source，取第一个非空层级；
 * 读取：localBindings → `bookName`/`title` 特殊键 → chapter → book → ruleData → source，取首个非空。
 *
 * 沙箱没有持久实体库，[put] 的落点由调用方按阶段决定（搜索/发现条目写 book，目录/正文写 chapter，
 * 详情字段写 book），[get] 则按同一层级顺序逐层查找。一次 debug 一份实例，
 * 不同 MCP 任务各自新建，天然不串。
 */
class ReaderRuntimeVariables(
    /** 按 chapter→book→ruleData→source 顺序取首个非空值；都没有返回 "" */
    private val get: (String) -> String,
    /** 写入当前阶段的变量层（上游 put 的 chapter/book/ruleData/source 分派） */
    private val put: (String, String) -> Unit,
    /** 上游 `get("bookName")` → book.name */
    private val bookName: () -> String? = { null },
    /** 上游 `get("title")` → chapter.title */
    private val chapterTitle: () -> String? = { null },
) {
    fun get(key: String): String = get.invoke(key)

    fun put(key: String, value: String) {
        put.invoke(key, value)
    }

    /** 上游 AnalyzeRule.get 的 `bookName` 特殊键；null = 当前阶段没有 book */
    fun bookNameOrNull(): String? = bookName()

    /** 上游 AnalyzeRule.get 的 `title` 特殊键；null = 当前阶段没有 chapter */
    fun chapterTitleOrNull(): String? = chapterTitle()
}
