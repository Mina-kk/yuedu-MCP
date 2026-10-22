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
                GuideSection("网页与书源工具") {
                    GuideText("这一页介绍本 App 提供的通用能力，具体站点、地址、端口与连接方式都由你在自己的客户端和书源里配置。")
                    GuideStep("① 抓包：capture_once / webview_capture 记录一次请求，返回 cap:contextId；list_captures / get_capture / poll_capture 回看会话，get_http_log 看单条请求与响应，get_capture_resource 分片取资源。")
                    GuideStep("② 书源调试：debug_source 按当前书源跑单个指定入口，check_source 可沿着书源规则依次尝试多个入口并返回耗时与过程记录；已跑的内容按本次样本如实展示，未跑到的部分标注为未覆盖。半成品存 save_project，成品存 save_source，同一地址的历史版本用 archive_source_versions 归档。")
                    GuideStep("③ 阅读 Web：在「设置→阅读 Web 服务」按阅读 App 当前显示的地址与端口填写，同机可把主机改为 127.0.0.1。reader_web_status 探测所填地址；reader_web_page(action=open/snapshot/click/press_enter/fill/scroll/back/close) 在 App 内网页里按元素列表操作。click/press_enter/fill 会登录、翻页或提交筛选，保存或覆盖书源前请先确认；动作无效果不能算成功。")
                    GuideStep("④ 阅读端执行：reader_web_debug 对阅读里已保存的书源真实调试，日志仅供参考；reader_web_search 在阅读当前搜索范围真实全局搜索，不能冒充单源验证。接口匹配时可选 reader_web_sync_source(projectId) 写入（同地址旧源默认 conflict 不写，须你明确授权才 overwrite=true）与 reader_web_verify(bookSourceUrl, keyword=实际关键词)。")
                    GuideStep("⑤ 导入：import_reader(projectId, packageName) 只唤起本机阅读的导入勾选页，最终是否导入由你在阅读里确认。客户端重新连接后 tools/list 才会刷新到 73 个工具；精简连接可按名读 studio://tools/reader_web_page 取完整定义。")
                    GuideText("以上能力只作用于你自己填写的地址与书源，不包含任何站点或客户端的内置预设。")
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
                    GuideText("浏览器抓包（可见 WebView 与 MCP webview_capture 无头）记录的是 OkHttp 侧的 HTTP 事务，不是 WebView 原生网络栈抓包；3xx/POST 等记 OBSERVED_ONLY，私网/非法目标记 BLOCKED。")
                    GuideText("重定向逐跳校验：每个带 Location 的中间跳单独保存为 capture_hop 日志，最终响应另有一条 capture_once 日志；redirectChain 文本字段同时保留。")
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
