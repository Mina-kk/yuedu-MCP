package com.mina.legadostudio.reader

/**
 * 阅读 Web 服务地址填写与「测试连接」失败后的提示（纯逻辑，JVM 可测）。
 *
 * 目标：同机、异机同 Wi-Fi、蜂窝三种场景分别给建议，不做任何「按地址或网络类型直接拒绝」的判断；
 * 也无法判断是否同机时明确说「需要你确认」，由用户点选一键回环，而不是猜着改写地址。
 *
 * 安全约定：本文件只处理已规范化的 origin 与错误码，不拼接、不回显任何用户输入的私网地址；
 * 面向用户/模型的文案里出现的地址一律是回环建议（`127.0.0.1:<端口>`），端口来自用户输入。
 */
object ReaderWebGuidance {

    /** 当前活动网络（仅用于差异化提示，不参与可达性判定）。 */
    enum class NetworkKind { WIFI, CELLULAR, VPN, OTHER, UNKNOWN }

    data class Advice(val headline: String, val detail: String)

    data class Status(val title: String, val detail: String, val stageLabel: String?)

    private val URL_PATTERN = Regex("(?i)https?://\\S+")

    /** 错误码 → 用户可读的诊断；版本差异只描述「接口不匹配」，不压成「不像阅读」。 */
    fun describe(code: String?, stage: String?, message: String?): Status {
        val stageLabel = stageLabel(stage)
        val raw = sanitize(message).ifBlank { "连接失败" }
        val title = when (code) {
            "LEGACY_READER" -> "网页在线，但没有结构化检验接口"
            "CHECK_UNSUPPORTED" -> "网页在线，但该书源管理/检验接口不可用"
            "NOT_READER" -> "网页在线，官方阅读接口不匹配"
            "INCOMPATIBLE" -> "返回内容不是阅读 Web 服务的接口"
            "UNREACHABLE" -> "无法连接该地址"
            "TIMEOUT" -> "连接超时"
            "REDIRECT_BLOCKED" -> "被重定向，已拒绝跟随"
            "TOKEN_REQUIRED" -> "阅读要求访问令牌"
            "TOKEN_INVALID" -> "访问令牌不正确"
            "BUSINESS_ERROR" -> "阅读端返回业务错误"
            "NOT_CONFIGURED" -> "尚未配置地址"
            "INVALID_CONFIG" -> "已保存的地址无效"
            "HTTP_ERROR" -> "阅读 Web 返回 HTTP 错误"
            "ENDPOINT_MISSING" -> "阅读 Web 没有该接口"
            "RESPONSE_TOO_LARGE" -> "响应过大"
            else -> "连接失败"
        }
        return Status(title = title, detail = raw, stageLabel = stageLabel)
    }

    private fun stageLabel(stage: String?): String? = when (stage) {
        "web_page" -> "打开阅读 Web 页面"
        "token_probe" -> "探测新版接口（令牌开关）"
        "legacy_probe" -> "核对旧版公开书源接口"
        "lookup" -> "同步前预检（GET /getBookSource 查阅读里是否已有该书源）"
        "save" -> "写入书源（POST /saveBookSource）"
        "readback" -> "写入后回读核对（GET /getBookSource）"
        "sources" -> "读取书源列表"
        "start" -> "启动阅读端检验"
        "states" -> "读取检验状态"
        else -> null
    }

    /** 去掉消息里任何 http(s) 地址，避免把用户私网地址带回界面/模型。 */
    fun sanitize(text: String?): String =
        text.orEmpty().replace(URL_PATTERN, "[地址已隐藏]").trim().take(400)

    /**
     * 地址填写建议。
     *
     * @param loopback 当前输入是否已经是回环地址
     * @param privateLan 当前输入是否写了私网/运营商 NAT 地址（10/8、172.16/12、192.168/16、100.64/10）
     * @param kind 当前设备的活动网络；UNKNOWN 表示无法判断，不做任何推断
     */
    fun advise(loopback: Boolean, privateLan: Boolean, kind: NetworkKind): Advice = when {
        loopback -> Advice(
            headline = "同机访问：回环地址可用，无需 Wi-Fi",
            detail = "客户端与阅读装在同一台设备上时，阅读服务同时监听回环，用当前这个 127.0.0.1:<端口> 即可；" +
                "没有 Wi-Fi、只开蜂窝数据也能连。端口必须与阅读设置里的 Web 服务端口一致。",
        )
        kind == NetworkKind.WIFI || kind == NetworkKind.VPN -> Advice(
            headline = "异机访问：用阅读显示的 Wi-Fi 地址",
            detail = "不同设备访问时，127.0.0.1 指向的是这台设备自己，必须填阅读设置里显示的局域网地址，" +
                "并保证两台设备在同一 Wi-Fi（或同一个可达的 VPN）。本机当前网络可承载这种直连。",
        )
        kind == NetworkKind.CELLULAR -> Advice(
            headline = if (privateLan) "蜂窝下 10.x/私网地址通常只能同机访问" else "蜂窝网络下跨设备直连通常不通",
            detail = "运营商蜂窝网络大多在 NAT 后面，另一台设备无法反连过来；同机访问仍然可用回环地址。" +
                "如果阅读就装在本机，请点下方按钮改填 127.0.0.1:<端口>；确实需要跨设备时请改用同一 Wi-Fi 或可达的 VPN。",
        )
        else -> Advice(
            headline = "先确认客户端与阅读是否在同一台设备",
            detail = "同机请用回环地址 127.0.0.1:<端口>（不要求 Wi-Fi）；不同设备请用阅读显示的 Wi-Fi 地址，" +
                "并确保两台设备同一网络或可达 VPN。本 App 无法判断两者是否同机，请按实际情况选择，不会自动改写你填的地址。",
        )
    }

    /** 判断已规范化 origin 是否指向本机回环（127/8、localhost、::1）。 */
    fun isLoopback(origin: String): Boolean {
        val host = runCatching { ReaderWebOrigin.hostOf(origin) }.getOrNull() ?: return false
        val lower = host.lowercase().removeSurrounding("[", "]")
        if (lower == "localhost" || lower == "::1") return true
        return lower.split('.').let { parts ->
            parts.size == 4 && parts[0] == "127" && parts.drop(1).all { p -> p.toIntOrNull() in 0..255 }
        }
    }

    /** 是否写了私网或运营商 NAT 地址（仅用于提示，不参与拒绝）。 */
    fun isPrivateLan(origin: String): Boolean {
        val host = runCatching { ReaderWebOrigin.hostOf(origin) }.getOrNull() ?: return false
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
    }
}
