# 阅读书源 MCP

本机 **Legado 运行时 + MCP Server**（原「书源工坊」）。App 内不调用任何模型、不保存任何模型密钥。书源的制作、修复和调试全部由外部 MCP 客户端完成。

- 包名：`com.mina.legadostudio`
- 当前版本：`1.0.122`（versionCode 142）
- 许可证：GPL-3.0
- 上游致谢：DandanLLab/legadoSkill、LegadoTeam/legado
- 下载：[Releases](https://github.com/Mina-kk/yuedu-MCP/releases)（每个版本附带已签名 APK；仓库 `apks/` 目录保留最新 APK。自 1.0.120 起改用正式签名，从旧版本升级需先卸载再安装）

## 界面

iOS 简约白风格：液态玻璃顶栏/底栏（高斯模糊）、大圆角卡片、底部悬浮标签栏。底栏为 **MCP / 书源 / 技能 / 验证中心 / 日志**。

## 连接 MCP

1. 打开 App，底栏 **MCP** 页开启服务（默认端口 **58823**）
2. 外部 MCP 客户端配置：
   - URL：`http://127.0.0.1:58823/mcp`
   - 局域网（同一 Wi-Fi）：MCP 页可复制 `http://<局域网IP>:58823/mcp`
   - Header：`X-Studio-Token: <你的 Token>`（在 App 的 MCP 页可查看/复制）
3. 调用 `get_app_info`，应返回 `"ai": false`、`"role": "mcp-runtime"` 与语料库信息

### 书源类型开关

MCP 页可选择目标书源类型：**文本 / 音频 / 图片 / 文件 / 视频**（对应 Legado `bookSourceType` 0–4）。

- `save_source` 保存书源时自动写入所选类型；
- `fetch_page` 按类型过滤二进制内容：文本类型跳过图片/音视频/压缩包等二进制响应（返回 `bodyNote`/`binaryBytes` 说明），非文本类型保留对应媒体内容。

## MCP 工具一览

| 分组 | 工具 |
|---|---|
| 语料 / 知识 / 技能 | `match_sources` `get_corpus_source` `get_corpus_shard` `search_knowledge` `read_knowledge` `list_skills` `get_skill` `get_skill_reference` |
| 任务上下文 | `create_context` `get_context` `list_contexts` `update_context` `clear_context` `read_page` `read_result` |
| 抓取与解析 | `fetch_page` `analyze_html` `inspect_rule` `eval_js` |
| 书源 | `validate_source` `list_sources` `get_source` `save_source` `export_source` `debug_source` `check_source` `list_projects` `get_project` `delete_projects` |
| 验证与 Cookie | `browser_verify` `get_verification_status` `get_domain_modes` `set_domain_mode` `get_cookies` `set_cookie` `clear_cookies` |
| 状态与日志 | `get_app_info` `app_status` `set_http_log_recording` `get_http_logs` `get_http_log` `get_logs` `get_log` `get_crash_logs` `get_crash_log` `get_diagnostic_snapshots` `get_diagnostic_snapshot` |

## 制作流程

```
match_sources 语料命中（同域/同模板族现成书源）
  → create_context 建任务，全程显式传 contextId
  → fetch_page 抓页存快照；read_page / inspect_rule / analyze_html / eval_js 在快照上作业
  → 编写 BookSource JSON → debug_source 逐阶段调试（正文→目录→详情→搜索→发现）
  → check_source(refresh=true) 全程实时验收
  → save_source 保存，get_source 回读确认
```

`save_source` 默认按书源 URL 覆盖更新同站记录；需要保留历史版本时传 `newVersion=true` 追加。技能包 `legado-book-source` 内置完整工作流与参考文档：`get_skill` 读主文件，`get_skill_reference` 分页读参考（语料 / 验证 / 基础 / 排障 / JS API 等），`search_knowledge` → `read_knowledge` 查验证码、编码等专题知识库。

### 语料命中（省 token 第一步）

内置 **4256 个现成书源**，按内容规则签名聚成 **696 个模板族**（同族 = 同 CMS / 同模板结构）：

- `match_sources(域名或站名)`：返回 `i`（序号）、`d`（域名）、`f`（族 ID）、`t`（类型）、`g`（特征位掩码：CookieJar / 登录 / 验证码 / Cloudflare / 禁用等）；
- 命中同域 → `get_corpus_source(i)` 取完整书源做底本最小修改；
- 未命中同域 → `get_corpus_shard(f)` 读同族 ≤6 个最完整代表样例，参考结构改写；
- 都未命中才走 `fetch_page` 探索。语料样例改写通常比抓页探索省一个数量级调用。

### 验证中心（验证码 / CF / WAF）

遇到站点验证时，MCP 工具返回结构化 JSON 而不是裸错误，按 `status` 行动：

| status | 含义与行动 |
|---|---|
| `verification_required` | 需要人工验证：`browser_verify(url, waitSec=90)` 阻塞等待用户在验证中心完成，完成后自动取证并返回 `evidence`；随后重试原工具 |
| `webview_mode_enabled` | 该域已自动切换 WebView 抓取（`fetch_page` 已原地重试一次，`autoRetried=true` 时本次即成功结果） |

- 每域验证模式：`get_domain_modes` / `set_domain_mode(domain, auto|always|webview)`。**`always` 适合「一搜一验、URL 次次不同」的站点**——这类站点缓存 Cookie 无效，每次都走人工验证；验证完成但 `evidence.marker` 仍非空说明每次访问都要验，同样设 `always`。
- 同一域的等待中验证会话自动复用，不会重复创建。
- WebView 通道的 Cookie 与 OkHttp 通道 TLS 指纹不一致（如 `cf_clearance`）：系统对被 JS 盾拦截的站点自动改用 WebView 通道抓取，而不是把无效 Cookie 灌进规则。
- 成品源在官方阅读 App 内过盾靠书源 `loginCheckJs` + `java.startBrowserAwait`，与调试期验证中心互补。

### 并行多书源

同时制作多个书源时：

- 每个任务各自 `create_context` 并全程显式传 `contextId`，任务间缓存、引用、笔记完全隔离；
- 未传 `contextId` 时使用**每条 MCP 连接各自的默认上下文**，多客户端同时连接不会串数据；
- 调试缓存按调用传入（无全局锁），多个书源可同时 `debug_source` 而不互相等待；
- 书源页「导入至阅读」支持最多 **4 个书源同时排队待导入**，新导入不会顶掉尚未被阅读拉取的端点。

## 书源与技能

书源页按站点域名分组，组内按保存时间倒序展开。每个书源版本支持点击查看格式化 JSON 详情（全屏独立滚动、支持文本自由选中复制），并在版本操作栏提供「复制源」一键写入剪贴板及「导入至阅读」。技能页可查看内置 Skill 并控制启用/停用（停用后对 MCP 不可见）；自定义 Skill 支持新增、导入、导出和删除。`save_skill` / `delete_skill` 不能改写内置 Skill。

## 日志与诊断

底栏 **日志** 分四段：操作日志 / HTTP / 崩溃 / 诊断快照。可多选删除。

- 操作日志与 HTTP 日志均采用吸顶日期切换栏（一天一页），支持左右按天翻看与弹窗跳选日期；
- HTTP 记录含时间、状态码与耗时；自动过滤本地回环与私网探测流量，点击进入全屏详情页（请求/响应头、正文、重定向链），详情页内滚动与列表互不影响，系统返回键只关闭详情、回到列表；打开详情时隐藏底部标签栏；
- HTTP 列表停留在顶部时自动跟随最新记录，翻历史时不被打断；
- 诊断快照只含版本、MCP 状态和前置条件，不含 HTTP 或崩溃正文。

排查请用 MCP：`get_logs` / `get_log` / `get_http_logs` / `get_http_log` / `get_crash_logs` / `get_crash_log` / `get_diagnostic_snapshots` / `get_diagnostic_snapshot`。

构建说明见 `BUILDING.md`。

## 任务上下文

使用 `create_context` 创建任务，后续调用传入 `contextId`。`fetch_page` 保存网页并返回 `pageId`；相同 method+URL+body（**含 POST 搜索**）在 5 分钟内复用快照，只返回引用。正文通过 `read_page` 分段或搜索，也可直接交给 `inspect_rule`、`analyze_html`、`eval_js`（传 `pageId` 即可，免传 HTML）。工具结果超过 12000 字符自动转 `resultId` + 预览，用 `read_result` 分段取回，不重复执行原工具；`list_contexts` 可列出当前任务与占用，不返回正文。

`update_context` 保存阶段笔记，重连后使用 `get_context` 恢复目录与进度，完成后 `clear_context`。上限 **16 个任务**、每任务 32 项 / 200 万字符 / 单项 100 万字符；快照 5 分钟内新鲜、闲置 30 分钟过期。上下文持久化到应用私有目录（memory + snapshot）：进程重启后自动恢复，但恢复条目标记 `stale`，先 `refresh=true` 刷新再依赖其内容。MCP 令牌、User-Agent 或书源类型变化会使旧引用失效；它不自动读取、总结或裁剪客户端的聊天历史。

`fetch_page` 支持 `responseMode=reference`（不回传正文，仅存快照）与 `maxChars`（预览大小）进一步省流量；`debug_source` / `check_source` 默认复用本任务快照，**最终验收传 `refresh=true` 全程实时**；调试输出有意截断（正文 4000 字符、目录 20 章、搜索 10 条等），均带全量计数字段。

MCP 会话按 `Mcp-Session-Id` 统计：`clientCount` 只计最近 60 秒内发生过**真实工具调用**的活跃会话（客户端保活/心跳流量不计入），任务停止后自动掉出计数。会话活跃度同样只由工具调用刷新：闲置超过 `reapIdleSeconds`（默认 300 秒）即由 `McpSessions` 主动关闭释放（客户端再请求会收到 404 并重新握手），不再长期占用。`app_status` 同时返回 `sessionTotal`（累计建立）、`sessionClosed`、`sessionReaped`（已回收）、`reapIdleSeconds` 与 `maxLifetimeSeconds`（绝对存活上限，默认 7200 秒）。
