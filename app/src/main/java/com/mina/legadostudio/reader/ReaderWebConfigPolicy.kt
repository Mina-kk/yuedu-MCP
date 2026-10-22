package com.mina.legadostudio.reader

/**
 * 「保存阅读 Web 服务配置」的纯判定（不碰 Android prefs，JVM 可测）。
 *
 * 令牌与 origin 绑定：访问令牌属于某台设备上的阅读，换了地址就必须重新填写；
 * 地址变了而令牌框留空时清掉旧令牌，绝不让旧设备的令牌跟着发到新地址。
 */
object ReaderWebConfigPolicy {

    /** @param token 保存后应持久化的令牌；null 表示清除 */
    data class Resolved(val origin: String, val token: String?)

    /**
     * @param previousOrigin 已保存的 origin（无配置传 null），需已规范化
     * @param previousToken 已保存的令牌（无则 null/空）
     * @param newOriginRaw 界面上刚填的地址，这里做规范化与校验
     * @param inputToken 界面上刚填的令牌：null = 留空不改；空串/空白 = 显式清除；非空 = 覆盖
     * @throws IllegalArgumentException 新地址不合法
     */
    fun resolve(
        previousOrigin: String?,
        previousToken: String?,
        newOriginRaw: String,
        inputToken: String?,
    ): Resolved {
        val origin = ReaderWebOrigin.normalize(newOriginRaw)
        val trimmed = inputToken?.trim()
        val token = when {
            // 界面上明确清空：无论地址是否变化都清除
            inputToken != null && trimmed.isNullOrEmpty() -> null
            !trimmed.isNullOrEmpty() -> trimmed
            // 留空且换了地址：旧令牌属于旧设备，不能跟着发到新地址
            previousOrigin != null && previousOrigin != origin -> null
            else -> previousToken?.takeIf { it.isNotBlank() }
        }
        return Resolved(origin, token)
    }
}
