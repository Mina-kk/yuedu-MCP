package com.mina.legadostudio.runtime

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.mina.legadostudio.verification.RuntimeCookieStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 官方阅读 JS 环境同名对象：cookie / cache / source。
 * 注入 eval_js 与书源 <js>/{{}} 求值上下文，方法名与官方保持一致，AI 按官方 js-api 文档写的 JS 不需要改写。
 * 说明：cache 为进程内实现（官方是磁盘持久层）；调试工具进程内有效即可，重启后请重新写入。
 */

/**
 * 登录信息桩存储：真机 source.getLoginInfo/putLoginInfo 与登录 UI/书源 loginUi 配置联动，
 * 沙箱没有登录界面，退化为「按书源锚点进程内共享的 Map」。写入可见（putLoginInfo 后
 * getLoginInfo 能读回），同一书源跨次 eval/debug 共享，模拟真机里登录信息已保存的状态；
 * 首次读取返回 null 并由沙箱在日志行提示「桩实现，真机行为可能不同」。
 * 锚点用书源 bookSourceUrl，取不到时共用 "" 键。
 */
object RuntimeLoginStore {
    private val data = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    /** 每个锚点已提示过的桩 API 名，避免每次调用都重复打同一行 warning */
    private val noted = CopyOnWriteArraySet<String>()

    fun anchorOf(source: Any?): String = when (source) {
        is SourceJsApi -> source.anchor
        else -> ""
    }

    fun map(anchor: String): ConcurrentHashMap<String, String> =
        data.getOrPut(anchor) { ConcurrentHashMap() }

    fun noteOnce(anchor: String, api: String, warn: (String) -> Unit) {
        if (noted.add("$anchor|$api")) {
            warn("$api 为沙箱桩实现：沙箱无登录界面，返回进程内模拟值（默认空）；真机行为可能不同（真机值来自书源 loginUi 表单/用户输入）")
        }
    }
}

@Keep
class CookieJsApi(private val cookies: RuntimeCookieStore?) {
    /** 官方 cookie.getCookie(url)：该域完整 Cookie 串 */
    fun getCookie(url: String): String = cookies?.headerFor(url).orEmpty()

    /** 官方 cookie.getKey(url, key)：取单个 Cookie 值 */
    fun getKey(url: String, key: String): String =
        getCookie(url).split(';').map { it.trim() }
            .firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "").orEmpty()

    /** 官方 cookie.setCookie(url, cookie)：按名合并，保留该域其他 Cookie（如登录态） */
    fun setCookie(url: String, cookie: String) {
        cookies?.merge(url, cookie) ?: error("CookieStore 不可用")
    }

    /** 官方 cookie.replaceCookie(url, cookie)：整串替换该域 Cookie */
    fun replaceCookie(url: String, cookie: String) {
        cookies?.set(url, cookie) ?: error("CookieStore 不可用")
    }

    /** 官方 cookie.removeCookie(url)：清除该域 Cookie */
    fun removeCookie(url: String) {
        cookies?.clear(url) ?: error("CookieStore 不可用")
    }

    /** 官方 cookie.setWebCookie(url, cookie)：本实现 Runtime 与 WebView 同源，等价 setCookie */
    fun setWebCookie(url: String, cookie: String) = setCookie(url, cookie)
}

/** 进程内缓存，TTL 语义对齐官方 CacheManager（saveTime 秒，0=不过期）。 */
object RuntimeCacheStore {
    private data class Item(val value: String, val expireAt: Long)
    private val disk = linkedMapOf<String, Item>()
    private val memory = linkedMapOf<String, Any?>()

    @Synchronized
    fun put(key: String, value: String, saveTimeSec: Long) {
        disk[key] = Item(value, if (saveTimeSec <= 0) Long.MAX_VALUE else System.currentTimeMillis() + saveTimeSec * 1000)
    }

    @Synchronized
    fun get(key: String): String? {
        val item = disk[key] ?: return null
        if (System.currentTimeMillis() >= item.expireAt) { disk.remove(key); return null }
        return item.value
    }

    @Synchronized
    fun delete(key: String) { disk.remove(key) }

    @Synchronized
    fun putMemory(key: String, value: Any?) { memory[key] = value }

    @Synchronized
    fun getFromMemory(key: String): Any? = memory[key]

    @Synchronized
    fun deleteMemory(key: String) { memory.remove(key) }
}

@Keep
open class CacheJsApi(internal val store: RuntimeCacheStore = RuntimeCacheStore) {
    /** 官方 cache.put(key, value[, saveTime])；saveTime 秒，0/缺省 = 不过期 */
    @JvmOverloads
    open fun put(key: String, value: String, saveTime: Any? = null) {
        store.put(key, value, when (saveTime) {
            is Number -> saveTime.toLong()
            is String -> saveTime.toLongOrNull() ?: 0L
            else -> 0L
        })
    }

    /** 官方 cache.get(key[, onlyDisk])；onlyDisk 形参保留（本实现无分层） */
    @JvmOverloads
    open fun get(key: String, onlyDisk: Any? = null): String? = store.get(key)

    /** 官方 cache.delete(key) */
    open fun delete(key: String) = store.delete(key)

    /** 官方 cache.putMemory(key, value)：存任意对象（仅内存） */
    open fun putMemory(key: String, value: Any?) = store.putMemory(key, value)

    /** 官方 cache.getFromMemory(key) */
    open fun getFromMemory(key: String): Any? = store.getFromMemory(key)

    /** 官方 cache.deleteMemory(key) */
    open fun deleteMemory(key: String) = store.deleteMemory(key)
}

/**
 * 观测型 cache：debug 链路专用实例。除转发到底层 store 外，维护
 * - [memorySnapshot]：putMemory 写入的当前键值快照（值截断 300 字符，最多 100 键，按写入序）；
 * - [recentPuts]：最近 50 次 put/putMemory 的键与时间顺序（`#序号 时间戳ms kind key`，截断后拼接）。
 * 与 RuntimeCacheStore 全局单例无关：整个观测面只限本次 debug 的书源写入。
 */
class SnapshottingCache(store: RuntimeCacheStore = RuntimeCacheStore) : CacheJsApi(store) {
    private val memoryWrites = LinkedHashMap<String, String>()
    private val puts = ArrayDeque<String>()

    @Synchronized
    override fun put(key: String, value: String, saveTime: Any?) {
        super.put(key, value, saveTime)
        record("put", key)
    }

    @Synchronized
    override fun putMemory(key: String, value: Any?) {
        super.putMemory(key, value)
        if (value == null) memoryWrites.remove(key)
        else {
            memoryWrites.remove(key)                        // 重排到最后写入位置
            while (memoryWrites.size >= 100) memoryWrites.remove(memoryWrites.keys.first())
            memoryWrites[key] = value.toString().take(300)
        }
        record("putMemory", key)
    }

    @Synchronized
    override fun delete(key: String) {
        super.delete(key)
        record("delete", key)
    }

    @Synchronized
    override fun deleteMemory(key: String) {
        super.deleteMemory(key)
        memoryWrites.remove(key)
        record("deleteMemory", key)
    }

    /** 执行结束时 memory 键值快照（键按写入序；值为 toString 截断 300 字符） */
    @Synchronized
    fun memorySnapshot(): Map<String, String> = LinkedHashMap(memoryWrites)

    /** 最近 50 次 put/delete 系列调用，`时间戳ms kind key` 一行一条，按发生顺序 */
    @Synchronized
    fun recentPuts(): List<String> = puts.toList()

    private fun record(kind: String, key: String) {
        while (puts.size >= 50) puts.removeFirst()
        puts.addLast("${System.currentTimeMillis()} $kind ${key.take(100)}")
    }
}

/** 官方 source 对象：调试期书源变量仓库。底层 state 与 debug 流程共享，JS 写入对后续规则可见。 */
@Keep
class SourceJsApi(
    private val state: MutableMap<String, String> = mutableMapOf(),
    /** 登录信息锚点（书源 bookSourceUrl）；桩存储按键隔离，见 [RuntimeLoginStore] */
    internal val anchor: String = "",
    private val warn: (String) -> Unit = {},
) {
    private val gson = Gson()

    /** 官方 source.getVariable()：全部变量的 JSON 串 */
    fun getVariable(): String = gson.toJson(state)

    /** 官方 source.setVariable(json)：整体替换变量 */
    fun setVariable(variable: String?) {
        state.clear()
        if (variable.isNullOrBlank()) return
        runCatching {
            JsonParser.parseString(variable).asJsonObject.entrySet().forEach { state[it.key] = it.value.asString }
        }
    }

    /** 官方 source.putVariable(json)：同 setVariable */
    fun putVariable(variable: String?) = setVariable(variable)

    /** 官方 source.put(key, value) */
    fun put(key: String, value: Any?) { state[key] = value?.toString().orEmpty() }

    /** 官方 source.get(key) */
    fun get(key: String): String? = state[key]

    /** 官方 source.getLoginInfo()：登录信息 JSON 串；沙箱桩，未写入过返回 null 并提示一次 */
    fun getLoginInfo(): String? {
        RuntimeLoginStore.noteOnce(anchor, "source.getLoginInfo", warn)
        return RuntimeLoginStore.map(anchor).takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }
    }

    /** 官方 source.getLoginInfoMap()：登录信息 Map；沙箱桩 */
    fun getLoginInfoMap(): Map<String, String>? {
        RuntimeLoginStore.noteOnce(anchor, "source.getLoginInfoMap", warn)
        return RuntimeLoginStore.map(anchor).takeIf { it.isNotEmpty() }?.toMap()
    }

    /** 官方 source.getLoginInfo(key)：单个登录信息值；沙箱桩 */
    fun getLoginInfo(key: String): String? {
        RuntimeLoginStore.noteOnce(anchor, "source.getLoginInfo", warn)
        return RuntimeLoginStore.map(anchor)[key]
    }

    /** 官方 source.putLoginInfo(json)：登录函数存放登录信息（JSON 串写入，键值均转字符串） */
    fun putLoginInfo(info: String?) {
        RuntimeLoginStore.noteOnce(anchor, "source.putLoginInfo", warn)
        if (info.isNullOrBlank()) return
        runCatching {
            JsonParser.parseString(info).asJsonObject.entrySet()
                .forEach { RuntimeLoginStore.map(anchor)[it.key] = it.value.asString }
        }
    }

    /** 官方 source.putLoginInfo(map)：Map 形态重载 */
    fun putLoginInfo(info: Map<*, *>?) {
        RuntimeLoginStore.noteOnce(anchor, "source.putLoginInfo", warn)
        info?.forEach { (k, v) -> if (k != null) RuntimeLoginStore.map(anchor)[k.toString()] = v?.toString().orEmpty() }
    }

    /** 官方 source.putLoginInfo(key, value)：单键写入重载 */
    fun putLoginInfo(key: String, value: Any?) {
        RuntimeLoginStore.noteOnce(anchor, "source.putLoginInfo", warn)
        RuntimeLoginStore.map(anchor)[key] = value?.toString().orEmpty()
    }
}

/**
 * 官方内置变量 `book`（io.legado.app.data.entities.Book/BaseBook）。
 * 调试期间是「当前这本书的内存对象」：debugInfo/debugList 抽取的字段写回本对象，
 * 一次 debug 链路里各阶段共享同一实例，book.putVariable 的写入对后续规则可见。
 * 属性覆盖官方 Book 全部常用字段；variable/自定义变量方法与官方同名。
 */
@Keep
@Suppress("MemberVisibilityCanBePrivate")
class BookJsApi(
    private val variableMap: MutableMap<String, String> = mutableMapOf(),
) {
    @JvmField var bookUrl: String = ""
    @JvmField var tocUrl: String = ""
    @JvmField var origin: String = ""
    @JvmField var originName: String = ""
    @JvmField var name: String = ""
    @JvmField var author: String = ""
    @JvmField var kind: String = ""
    @JvmField var customTag: String = ""
    @JvmField var coverUrl: String = ""
    @JvmField var customCoverUrl: String = ""
    @JvmField var intro: String = ""
    @JvmField var customIntro: String = ""
    @JvmField var charset: String = ""
    /** 官方 bookSourceType 语义：0:text 1:audio 2:image 3:file 4:video（官方 Book.type 实际写死 0，源类型在 source 上） */
    @JvmField var type: Int = 0
    @JvmField var group: Int = 0
    @JvmField var latestChapterTitle: String = ""
    @JvmField var latestChapterTime: Long = 0L
    @JvmField var lastCheckTime: Long = 0L
    @JvmField var lastCheckCount: Int = 0
    @JvmField var totalChapterNum: Int = 0
    @JvmField var durChapterTitle: String = ""
    @JvmField var durChapterIndex: Int = 0
    @JvmField var durChapterPos: Double = 0.0
    @JvmField var durChapterTime: Long = 0L
    @JvmField var canUpdate: Boolean = true
    @JvmField var order: Int = 0
    @JvmField var originOrder: Int = 0
    /** 官方 BaseBook.variable：自定义变量 JSON 串，JS 直接赋值会写进变量池。
     *  JVM 层与 getVariable(key) 签名撞名，getter/setter 用 @JvmName 区分开（JS 侧仍是 book.variable）。 */
    var variable: String? = null
        @JvmName("getVariableField")
        get
        @JvmName("setVariableField")
        set(value) {
            field = value
            variableMap.clear()
            if (value.isNullOrBlank()) return
            runCatching {
                JsonParser.parseString(value).asJsonObject.entrySet()
                    .forEach { variableMap[it.key] = it.value.asString }
            }
        }
    /** 官方 useReplaceRule（Book.kt 无字段，BaseBook 上有）：是否启用替换净化 */
    @JvmField var useReplaceRule: Boolean = true
    /** 官方 isSplitLongChapter（调试链路不逐页取正文，无拆章效果，仅保留字段语义） */
    @JvmField var isSplitLongChapter: Boolean = true
    var reverseToc: Boolean = false
        private set

    /** 官方 BaseBook.getVariable(key)：null 键返回整个变量池 JSON 串（getVariable()） */
    @JvmOverloads
    fun getVariable(key: String? = null): String? {
        if (key == null) return variableMap.takeIf { it.isNotEmpty() }?.let { Gson().toJson(it) }
        return variableMap[key]
    }

    /** 官方 BaseBook.putVariable(key, value)：null value = 删除该键 */
    fun putVariable(key: String, value: Any?) {
        if (value == null) variableMap.remove(key) else variableMap[key] = value.toString()
    }

    /** 官方 BaseBook.putCustomVariable(value)：把整个变量池替换为自定义串（官方存为 JSON 对象串） */
    fun putCustomVariable(value: Any?) {
        variable = value?.toString()
    }

    /** 官方 BaseBook.getVariableMap() */
    fun getVariableMap(): Map<String, String> = variableMap.toMap()

    /** 官方 Book.setVariableMap(map) */
    fun setVariableMap(map: Map<*, *>?) {
        variableMap.clear()
        map?.forEach { (k, v) -> if (k != null) variableMap[k.toString()] = v?.toString().orEmpty() }
    }

    /** 官方 Book.upCustomIntro(value)：用户覆盖简介（不覆盖 customIntro 已设值时真机行为，沙箱直接写入） */
    fun upCustomIntro(value: Any?) {
        customIntro = value?.toString().orEmpty()
    }

    /** 官方 Book.setReverseToc(reverse)：目录倒序标记 */
    fun setReverseToc(reverse: Any?) {
        reverseToc = reverse == true || reverse?.toString().equals("true", true)
    }

    /** 官方 Book.getUseReplaceRule() */
    fun getUseReplaceRule(): Boolean = useReplaceRule

    /** 官方 Book.setUseReplaceRule(useReplaceRule) */
    fun setUseReplaceRule(use: Any?) {
        useReplaceRule = use == null || use == true || use?.toString().equals("true", true)
    }

    /** 便捷赋值：bindings 里传 Map 形态 book 时逐键落字段，键名与官方 Book 属性同名。 */
    operator fun set(field: String, value: Any?) {
        when (field) {
            "bookUrl" -> bookUrl = value?.toString().orEmpty()
            "tocUrl" -> tocUrl = value?.toString().orEmpty()
            "origin" -> origin = value?.toString().orEmpty()
            "originName" -> originName = value?.toString().orEmpty()
            "name" -> name = value?.toString().orEmpty()
            "author" -> author = value?.toString().orEmpty()
            "kind" -> kind = value?.toString().orEmpty()
            "customTag" -> customTag = value?.toString().orEmpty()
            "coverUrl" -> coverUrl = value?.toString().orEmpty()
            "customCoverUrl" -> customCoverUrl = value?.toString().orEmpty()
            "intro" -> intro = value?.toString().orEmpty()
            "customIntro" -> customIntro = value?.toString().orEmpty()
            "charset" -> charset = value?.toString().orEmpty()
            "type" -> type = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
            "group" -> group = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
            "latestChapterTitle" -> latestChapterTitle = value?.toString().orEmpty()
            "latestChapterTime" -> latestChapterTime = (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: 0L
            "lastCheckTime" -> lastCheckTime = (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: 0L
            "lastCheckCount" -> lastCheckCount = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
            "totalChapterNum" -> totalChapterNum = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
            "durChapterTitle" -> durChapterTitle = value?.toString().orEmpty()
            "durChapterIndex" -> durChapterIndex = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
            "durChapterPos" -> durChapterPos = (value as? Number)?.toDouble() ?: value?.toString()?.toDoubleOrNull() ?: 0.0
            "durChapterTime" -> durChapterTime = (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: 0L
            "canUpdate" -> canUpdate = value != false
            "order" -> order = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
            "originOrder" -> originOrder = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
            "variable" -> variable = value?.toString()
            "useReplaceRule" -> useReplaceRule = value != false
            "isSplitLongChapter" -> isSplitLongChapter = value != false
            "reverseToc" -> setReverseToc(value)
            else -> if (field.isNotBlank()) variableMap[field] = value?.toString().orEmpty()
        }
    }
}

/**
 * 官方内置变量 `chapter`（io.legado.app.data.entities.BookChapter）。
 * debugToc 抽取每条章节时实例化（url/title/index/bookUrl/baseUrl），debugContent 复用同一对象。
 * 字段与方法与官方同名；lyric/imgUrls 为进程内存储（沙箱无持久章节库）。
 */
@Keep
@Suppress("MemberVisibilityCanBePrivate")
class ChapterJsApi(
    private val variableMap: MutableMap<String, String> = mutableMapOf(),
) {
    @JvmField var url: String = ""
    @JvmField var title: String = ""
    @JvmField var baseUrl: String = ""
    @JvmField var bookUrl: String = ""
    @JvmField var index: Int = 0
    @JvmField var resourceUrl: String? = null
    @JvmField var tag: String? = null
    @JvmField var start: Long? = null
    @JvmField var end: Long? = null
    var variable: String? = null
        @JvmName("getVariableField")
        get
        @JvmName("setVariableField")
        set(value) {
            field = value
            variableMap.clear()
            if (value.isNullOrBlank()) return
            runCatching {
                JsonParser.parseString(value).asJsonObject.entrySet()
                    .forEach { variableMap[it.key] = it.value.asString }
            }
        }

    private var lyric: String? = null
    private val imgUrls = mutableListOf<String>()

    @JvmOverloads
    fun getVariable(key: String? = null): String? {
        if (key == null) return variableMap.takeIf { it.isNotEmpty() }?.let { Gson().toJson(it) }
        return variableMap[key]
    }

    /** 官方 BookChapter.putVariable(key, value)：null value = 删除该键 */
    fun putVariable(key: String, value: Any?) {
        if (value == null) variableMap.remove(key) else variableMap[key] = value.toString()
    }

    /** 官方 BookChapter.putLyric(value)：存储音频章节歌词（沙箱进程内存储） */
    fun putLyric(value: Any?) {
        lyric = value?.toString()
    }

    fun getLyric(): String? = lyric

    /** 官方 BookChapter.putImgUrl(value)：存储章节图标链接（可重复调用，沙箱按序保存） */
    fun putImgUrl(value: Any?) {
        value?.toString()?.takeIf { it.isNotBlank() }?.let { imgUrls += it }
    }

    fun getImgUrls(): List<String> = imgUrls.toList()

    /** 官方 BookChapter.update()：真机写数据库；沙箱无持久层，空实现不报错 */
    fun update() {}

    /** 便捷赋值：bindings 里传 Map 形态 chapter 时逐键落字段（兼容旧版 {index: n} 极简形态）。 */
    operator fun set(field: String, value: Any?) {
        when (field) {
            "url" -> url = value?.toString().orEmpty()
            "title" -> title = value?.toString().orEmpty()
            "baseUrl" -> baseUrl = value?.toString().orEmpty()
            "bookUrl" -> bookUrl = value?.toString().orEmpty()
            "index" -> index = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
            "resourceUrl" -> resourceUrl = value?.toString()
            "tag" -> tag = value?.toString()
            "start" -> start = (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull()
            "end" -> end = (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull()
            "variable" -> variable = value?.toString()
            else -> if (field.isNotBlank()) variableMap[field] = value?.toString().orEmpty()
        }
    }
}
