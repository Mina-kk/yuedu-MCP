package com.mina.legadostudio.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.JsonObject
import com.mina.legadostudio.data.db.VerificationSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Parent bridge dispatches only these two methods here. No URL from JS is accepted. */
internal class VerificationManagementActions(
    private val context: Context,
    private val readSession: suspend (String) -> VerificationSessionEntity?,
    private val currentView: (String) -> VerificationBrowserView?,
) {
    suspend fun handle(method: String, params: JsonObject): JsonObject {
        require(method == "verification.external" || method == "verification.browser.refresh")
        val id = WebBridgeProtocol.parseVerificationId(params) ?: error("需要验证会话 id")
        val row = readSession(id) ?: error("验证任务不存在")
        require(WebBridgePolicy.isVerificationUrlAllowed(row.url)) { "验证地址不允许打开" }
        return withContext(Dispatchers.Main) {
            if (method == "verification.browser.refresh") {
                val view = currentView(id) ?: error("该验证页未打开")
                check(view.refresh()) { "验证页刷新失败" }
                JsonObject().apply { addProperty("refreshed", true); addProperty("sessionId", id) }
            } else {
                // Stored entry URL only: neither JS nor stale observed navigation chooses the external target.
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                JsonObject().apply { addProperty("launched", true); addProperty("cookiesShared", false); addProperty("sessionId", id) }
            }
        }
    }
}
