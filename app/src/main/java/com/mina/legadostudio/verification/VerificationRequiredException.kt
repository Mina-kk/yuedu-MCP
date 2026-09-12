package com.mina.legadostudio.verification

class VerificationRequiredException(
    val verificationUrl: String,
    val domain: String,
    val viaWebView: Boolean = false,
    val marker: String? = null,
    val code: Int = 403,
    message: String = "网站需要在 App 内完成验证",
) : Exception(message)
