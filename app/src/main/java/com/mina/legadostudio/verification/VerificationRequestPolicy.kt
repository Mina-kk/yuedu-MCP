package com.mina.legadostudio.verification

import com.mina.legadostudio.web.WebBridgePolicy
import java.net.URI

/** Creation validation only; DNS/redirect/connection enforcement stays in the network layer. */
internal object VerificationRequestPolicy {
    /** Reject malformed input, never repair/trim it into a different URL or echo credentials. */
    fun requireUrl(raw: String?): String {
        require(!raw.isNullOrBlank()) { "验证 url 不能为空" }
        require(raw.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) {
            "验证 url 不能包含空白、控制字符或反斜杠"
        }
        val uri = runCatching { URI(raw) }.getOrNull()
        require(uri != null && uri.isAbsolute && !uri.isOpaque &&
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.rawAuthority.isNullOrBlank() && !uri.rawAuthority.contains('@')) {
            "验证 url 必须是完整且不含凭据的 http/https 地址"
        }
        // Keep creation at least as restrictive as verification.open, including private/loopback
        // literals, credential-bearing URLs and the privileged appassets origin.
        require(WebBridgePolicy.isVerificationUrlAllowed(raw)) {
            "验证 url 不允许在隔离浏览器中打开；需使用不含凭据的合法公网 http/https 地址"
        }
        return raw
    }
}
