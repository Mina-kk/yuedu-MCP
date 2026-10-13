package com.mina.legadostudio.testsupport

/**
 * JUnit Category 标记：标注在访问真实网站（非本地 MockWebServer 回环）的用例上。
 * 默认 `:app:testDebugUnitTest` 按此类整类排除；仅 `:app:liveTestDebug` 显式执行。
 */
interface LiveNetworkTest
