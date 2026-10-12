package com.mina.legadostudio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mina.legadostudio.BuildConfig
import com.mina.legadostudio.ui.theme.GlassCard
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.LocalStudioHaze
import com.mina.legadostudio.ui.theme.StudioSpacing
import com.mina.legadostudio.ui.theme.studioBottomInset
import com.mina.legadostudio.ui.theme.studioTopInset
import dev.chrisbanes.haze.hazeSource

@Composable
fun FeatureGuideScreen(onBack: () -> Unit = {}) {
    val haze = LocalStudioHaze.current
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().then(if (haze != null) Modifier.hazeSource(haze) else Modifier),
            contentPadding = PaddingValues(
                start = StudioSpacing.screen,
                end = StudioSpacing.screen,
                top = 64.dp + studioTopInset(),
                bottom = StudioSpacing.screenBottomBase + studioBottomInset(),
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                GuideSection("这是什么") {
                    GuideText("阅读书源MCP（v${BuildConfig.VERSION_NAME}）：把手机变成书源制作工作台，本机跑一个 MCP 服务，外部 AI 客户端连进来后可直接调试规则、抓网页、写书源。")
                }
            }
            item {
                GuideSection("功能简介") {
                    GuideKv("MCP 宿主", "启动/停止 MCP 服务，复制服务器链接、Token 与客户端配置。")
                    GuideKv("书源", "查看已保存的成品书源，可导入至阅读、分享 JSON 或删除。")
                    GuideKv("技能", "管理内置与自定义 Skill（SKILL.md 工作手册），AI 按技能干活。")
                    GuideKv("验证中心", "处理站点验证码、登录、WAF/CF 拦截等人工验证会话。")
                    GuideKv("日志", "操作日志、HTTP 事务、抓包（页签内入口进独立页）、崩溃记录与诊断快照。")
                }
            }
            item {
                GuideSection("AI 抓包调试工作流") {
                    GuideStep("①  capture_once(url)：AI 发起公网 HTTP 请求，返回 cap:contextId 与本次日志 ID；webview_capture(url) 发一次性无头浏览器抓包（不可交互）")
                    GuideStep("②  list_captures / get_capture(contextId)：翻历史会话、按会话分页回看逐跳；活会话用 poll_capture 增量拉新事务")
                    GuideStep("③  get_http_log(id)：查看单条请求/响应头及脱敏、截断的正文片段；字体/图片等二进制用 get_capture_resource(logId) 分片取")
                    GuideStep("④  也可由用户在 App「日志→抓包→浏览器抓包」手动点页，AI 凭同一 cap:contextId 回看（MCP 不能遥控该浏览器）")
                    GuideStep("⑤  debug_source(source, entry)：用 App 内 Legado 运行时按入口（关键词/详情 URL/++目录/--正文/分类::发现URL）跑整源；JS 渲染页用 webview_render(url 或 url+pageId) 转真实 DOM")
                    GuideStep("⑥  check_source(source, auto=true, refresh=true, searchKey)：逐源联网检查搜索→详情→目录→正文（exploreUrl/exploreKey 可加发现链），返回各阶段耗时与证据；未覆盖阶段不能算可用")
                    GuideStep("⑦  半成品存 save_project、成品 save_source 入书源库；同 URL 旧版本用 archive_source_versions 清理；import_reader(projectId, packageName) 推送到本机阅读客户端（包名缺省先回目标列表）")
                }
            }
            item {
                GuideSection("人工查看日志") {
                    GuideText("不连 AI 也能排查：")
                    GuideStep("①  打开底栏「日志」→ 切到「抓包」标签：三个入口（逐次抓包 / 浏览器抓包 / 抓包会话历史）各进独立页，可开关 HTTP 事务记录")
                    GuideStep("②  「逐次抓包」页填 URL / 方法 / 请求头发一次手动请求；结果超 200 条去「本次会话详情」分页看全")
                    GuideStep("③  「浏览器抓包」页是可见可交互的 WebView：输入 URL 后像浏览器一样点页/翻页，事务落在底部可折叠面板")
                    GuideStep("④  「抓包会话历史」按 cap:contextId 分组持久化（逐次/可见浏览器/无头 webview 三类，重启可回看），分页加载 + 按抓包 ID 全库精确查找")
                    GuideStep("⑤  会话详情逐跳查看，点单条日志看请求/响应头及正文片段；HTTP 页签按天滚动分页（抓包记录不混入），导出/历史不受窗口限制")
                }
            }
            item {
                GuideSection("安全与边界") {
                    GuideText("抓包只记录本 App 自己发出的 HTTP 流量，不抓其他应用、不抓系统流量。")
                    GuideText("逐次抓包仅允许公网 HTTP/HTTPS：私网、回环地址、URL 内嵌凭据、公网域名解析到私网（DNS 重绑定）都会被拒绝。")
                    GuideText("WebView 验证模式的域名不支持逐次抓包（WebView 内部跳转无法逐跳校验），会被拒绝；请改用 HTTP 模式、fetch_page，或在 App 内用「浏览器抓包」。")
                    GuideText("浏览器抓包（可见 WebView 与 MCP webview_capture 无头）供给/观察的是 OkHttp 侧证据，不是 WebView 原生网络栈抓包；3xx/POST 等记 OBSERVED_ONLY，私网/非法目标记 BLOCKED。")
                    GuideText("重定向逐跳校验：每个带 Location 的中间跳单独保存为 capture_hop 日志，最终响应另有一条 capture_once 日志；旧版 redirectChain 文本仍保留。")
                    GuideText("HTTP 正文最多保存 8KB，超出部分截断；Authorization、Cookie 等敏感请求头会自动脱敏为 ***。")
                    GuideText("日志与摘要仍含完整 URL/query，可能带令牌等参数，分享前请自行检查。")
                }
            }
        }
        GlassTopBar("功能介绍", onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun GuideSection(title: String, content: @Composable () -> Unit) {
    GlassCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun GuideText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GuideKv(term: String, detail: String) {
    Column {
        Text(term, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GuideStep(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
