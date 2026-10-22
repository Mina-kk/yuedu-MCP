package com.mina.legadostudio.reader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 读取当前活动网络类型，仅用于「同机/异机/蜂窝」差异化提示。
 *
 * 明确不做的事：
 * - 不用网络类型判断阅读是否可达，更不据此拒绝请求——用户可能通过 VPN、同机回环等非常规路径访问；
 * - 不枚举 Wi-Fi 名或地址，不参与地址改写；
 * - 拿不到信息（权限/API 限制）时返回 [ReaderWebGuidance.NetworkKind.UNKNOWN]，界面不做推断。
 */
object ReaderWebNetworkState {

    fun currentKind(context: Context): ReaderWebGuidance.NetworkKind {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ReaderWebGuidance.NetworkKind.UNKNOWN
        val network = manager.activeNetwork ?: return ReaderWebGuidance.NetworkKind.UNKNOWN
        val caps = manager.getNetworkCapabilities(network) ?: return ReaderWebGuidance.NetworkKind.UNKNOWN
        // 有 VPN 时底层传输仍是 Wi-Fi/蜂窝，先报 VPN：用户需要知道「可达性取决于隧道」
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return ReaderWebGuidance.NetworkKind.VPN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ReaderWebGuidance.NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ReaderWebGuidance.NetworkKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ReaderWebGuidance.NetworkKind.OTHER
            else -> ReaderWebGuidance.NetworkKind.UNKNOWN
        }
    }
}
