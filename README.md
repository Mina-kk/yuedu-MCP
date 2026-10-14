# 阅读书源 MCP

内置 **Legado 运行时 + MCP Server**。App 内不调用任何模型、不保存任何模型 Key。书源的制作、修复和调试全部由外部 MCP 客户端完成。

- 包名：`com.mina.legadostudio`
- 当前版本：`1.2.0`（versionCode 188，以 `app/build.gradle.kts` 为准）
- 许可证：GPL-3.0
- 上游致谢：DandanLLab/legadoSkill、LegadoTeam/legado、morluto/rea
- 下载：[Releases](https://github.com/Mina-kk/yuedu-MCP/releases)（每个版本附带已签名 APK）

## 界面

iOS 简约白风格：液态玻璃顶栏/底栏（高斯模糊）、大圆角卡片、底部悬浮标签栏。底栏为 **MCP / 书源 / 技能 / 验证中心 / 日志**。

验证中心运行时提供圆形 **M** 字悬浮球：可自由拖动到任意位置，靠近屏幕左右边缘松手时自动吸附半隐（点按展开）；人工验证气泡弹出时自动贴边完整显示，不打断当前操作。

## 连接 MCP

MCP 页分三个页签：**连接**（服务开关 / 接入信息 / 健康检查）、**设置**（端口、令牌、书源类型、外观）、**前置条件**（通知、电池策略、端口占用等）。

1. 底栏 **MCP** →「连接」页签开启服务（默认端口 **58823**）
2. 「接入信息」卡按你的客户端界面形态给出对应填法，每一项都可一键复制：
   - **服务器链接（所有客户端）**：`http://127.0.0.1:58823/mcp`；客户端不在同一台设备时切「局域网」，使用 `http://<局域网IP>:58823/mcp`
   - 客户端有 **Token / Bearer Token 输入框** → 只粘贴令牌本身，不要加 `Bearer` 前缀
   - 客户端只有 **自定义请求头（名称 + 值）两个框** → 名称填 `Authorization`，值填 `Bearer <令牌>`（名称填成 `Bearer` 是常见错误，会 401）
   - 客户端没有任何鉴权输入 → 建议换用支持鉴权的客户端；不要为局域网或远程接入关闭令牌校验
   - **「配置文件客户端（高级）」折叠卡**：标准 `mcpServers` JSON（Claude Code / Cline / Cherry Studio 等）整段复制
3. 鉴权统一使用 MCP 规范的标准头：**`Authorization: Bearer <令牌>`**
4. 调用 `get_app_info` 可查看版本、运行能力及构建标识 `buildRevision`（源码提交标识）与 `buildType`（`debug`/`release`）。反馈问题时请提供版本和构建标识，不要提交访问令牌或未脱敏日志。

#### 精简工具目录（省 token）

服务器链接末尾追加 `?compact=1`（如 `http://127.0.0.1:58823/mcp?compact=1`）后建立的会话，`tools/list` 只回每个工具的首句描述、入参 `description` 同样截断（单句超过 100 字符硬截并加 `…`），工具数量、名称顺序、`inputSchema` 的 `type`/`required`/`enum`/数值约束逐字不变。被截掉的完整定义随时经只读资源模板 `studio://tools/{name}` 按名读回（返回 `{"name","description","inputSchema","annotations"}`）。不带该参数的默认行为逐字不变。

### 书源类型开关

MCP 页可选择目标书源类型：**自动 / 文本 / 音频 / 图片 / 文件 / 视频**（对应 `bookSourceType` -1、0–4）。`save_source` 保存时自动写入所选类型（「自动」保留 JSON 原样）；`fetch_page` 按类型过滤二进制内容：文本类型跳过图片/音视频等二进制响应，非文本类型保留对应媒体内容，「自动」不干预抓取，适合图文混合等类型不确定的站点。

## MCP 工具一览

共 67 个工具。

| 分组 | 工具 |
|---|---|
| 语料 / 知识 / 技能 | `match_sources` `get_corpus_source` `get_corpus_shard` `search_knowledge` `read_knowledge` `list_skills` `get_skill` `get_skill_reference` `save_skill` `delete_skill` |
| 任务上下文 | `create_context` `get_context` `list_contexts` `update_context` `clear_context` `read_page` `read_result` |
| 抓取与解析 | `fetch_page` `analyze_html` `inspect_rule` `eval_js` `webview_render` `webview_eval` |
| 字体反爬 | `fetch_font` `get_font_map` |
| 书源 | `validate_source` `list_sources` `get_source` `save_source` `export_source` `debug_source` `check_source` `analyze_sources` `export_checked_sources` `archive_source_versions` `import_reader` `list_projects` `get_project` `save_project` `delete_projects` |
| 验证与 Cookie | `browser_verify` `get_verification_status` `get_domain_modes` `set_domain_mode` `get_cookies` `set_cookie` `clear_cookies` `apply_webview_cookies` |
| 抓包 | `capture_once` `webview_capture` `list_captures` `get_capture` `poll_capture` `get_capture_resource` |
| 状态与日志 | `get_app_info` `app_status` `set_http_log_recording` `get_http_log_recording` `get_http_logs` `get_http_log` `get_logs` `get_log` `get_crash_logs` `get_crash_log` `export_diagnostic` `get_diagnostic_snapshots` `get_diagnostic_snapshot` |

- `save_skill` / `delete_skill`：管理自定义 Skill，内置 Skill 不可覆盖或删除。
- `fetch_font` / `get_font_map`：抓取 woff/woff2/ttf/otf 字体并分页读取 cmap（码点→glyphId），用于字体加密站点；glyphId 不等于汉字，还原需字形比对。
- `apply_webview_cookies`：人工过完 CF/JS 挑战后，把 WebView 采到的 Cookie（及可选 User-Agent）按名合并进目标书源 header 并保存。
- `export_diagnostic`：创建诊断快照；`get_http_log_recording`：只读查询 HTTP 事务记录开关。

## 制作流程

```
match_sources 语料命中（同域/同模板族现成书源）
  → create_context 建任务，全程显式传 contextId
  → fetch_page 抓页存快照；read_page / inspect_rule / analyze_html / eval_js 在快照上作业
  → 编写 BookSource JSON → debug_source 逐阶段调试（正文→目录→详情→搜索→发现）
  → check_source(auto=true, refresh=true, searchKey="实际关键词") 按搜索结果贯通详情/目录/正文取证
  → save_source 保存，get_source 按保存结果的项目 ID 回读确认
```

`save_source` 仅在同一完整 `bookSourceUrl` 且 JSON 内容相同时幂等更新；同 URL 规则不同会新增版本并报告冲突，**不再按同域或去掉 URL 尾部 `/` 自动合并、删除**。保存返回的 `VALIDATED` 只表示静态结构校验通过，不表示联网可用。技能包 `legado-book-source` 内置完整工作流与参考文档：`get_skill` 读主文件，`get_skill_reference` 分页读参考（语料 / 验证 / 基础 / 排障 / JS API 等），`search_knowledge` → `read_knowledge` 查验证码、编码、Web JS 逆向等知识库；另附《书源 JS 入门教程》知识包。

### eval_js 与书源 JS 环境

`eval_js` 与书源 `<js>` / `{{}}` 段运行在与官方一致的 Rhino + analyzeRule 引擎上，除 `java`（ajax/connect/加解密）外还注入官方同名对象：`cookie`、`cache`、`source`（`debug_source` 时与书源变量互通）。规则求值与官方语义一致：CSS/XPath 子规则在列表元素自身上求值，裸 `@attr`（如 `chapterUrl: "@href"`）取当前节点属性，多段落 `@text` 合并全部段落。`set_cookie` 默认 `merge=true` 按 Cookie 名合并（不会冲掉该域其他 Cookie，如登录态），`merge=false` 为整串替换。

### 校验闭环

`validate_source` 只检查静态结构：`searchUrl` 必须配 `ruleSearch.bookList`、`exploreUrl` 必须配 `ruleExplore.bookList`。`check_source(auto=true, refresh=true, searchKey="…")` 使用真实搜索结果继续检测目录与正文，返回逐阶段 `evidence` / `verdict`；`auto` 默认关闭，`refresh=false` 可能复用快照；未传 `searchKey` 而源有 `searchUrl` 时自动用「我」探测并在 `warnings` 标注。零结果、验证墙和脚本错误应分开处理，任何一次通过也不保证站点永远可用。

`analyze_sources` 只读分析本地书源（可传 `ids`）或外部 JSON 数组 `sources`：同 URL 但规则不同是冲突；`list_sources` 逐条列出各版本，同 URL 多版本用项目 ID `get_source` 取回。`export_checked_sources(ids=[…])` 仅导出显式选中的本地项目，排除开关 `exclude.excludeLogin`、`excludeCaptcha`、`excludeFailed`、`excludeUnverified` 默认全部关闭；`collapseIdentical=true` 只折叠 JSON 完全一致且无冲突的副本。过滤只采信本 App `check_source` 最近 30 分钟的证据；MCP 单次结果超过 11,500 字符会报错，请缩小 `ids` 或分批导出。书源页「分析/过滤」提供同样的静态预览、逐条检测和不受该回包上限约束的本地导出/分享/导入；外部数组静态分析**不会执行陌生书源的 JS 或批量探测网络**。

### 语料命中（省 token 第一步）

内置 **26861 个现成书源**，按内容规则签名聚成 **1838 个模板族**（同族 = 同 CMS / 同模板结构）：

- `match_sources(域名或站名)`：返回 `i`（序号）、`d`（域名）、`f`（族 ID）、`t`（类型）、`g`（特征位掩码：CookieJar / 登录 / 验证码 / Cloudflare / 禁用等）；
- 命中同域 → `get_corpus_source(i)` 取完整书源做底本最小修改；
- 未命中同域 → `get_corpus_shard(f)` 读同族 ≤6 个最完整代表样例，参考结构改写；
- 都未命中才走 `fetch_page` 探索。语料样例改写通常比抓页探索省一个数量级调用。

### 验证中心（验证码 / CF / WAF）

遇到站点验证时，MCP 工具返回结构化 JSON 而不是裸错误，按 `status` 行动：

| status | 含义与行动 |
|---|---|
| `verification_required` | 需要人工验证：`browser_verify(url, waitSec=90)` 阻塞等待用户在验证中心完成，随后重试原工具 |
| `webview_mode_enabled` | 该域已自动切换 WebView 抓取（`autoRetried=true` 时本次即为成功结果） |

- 每域验证模式：`get_domain_modes` / `set_domain_mode(domain, auto|always|webview)`。**`always` 适合「一搜一验」的站点**——缓存 Cookie 无效，每次都走人工验证。同一域等待中的验证自动复用，不重复创建。
- 被 JS 盾拦截的站点自动改用 WebView 通道抓取；成品源在官方阅读 App 内过盾靠书源 `loginCheckJs` + `java.startBrowserAwait`。

### 并行多书源

- 每个任务各自 `create_context` 并全程显式传 `contextId`，任务间缓存、引用、笔记完全隔离；未传 `contextId` 时使用每条连接各自的默认任务，多客户端同时连接不串数据；
- 多个书源可同时 `debug_source` 而不互相等待；
- 书源页「导入至阅读」支持最多 **4 个书源同时排队**，新导入不会顶掉尚未被拉取的端点。

## 书源与技能

书源页按站点域名分组、组内按保存时间倒序；「分析/过滤」展示重复候选与冲突，导出只影响本次预览，不删除项目也不更改阅读客户端已有源；逐条「检测」会联网运行该源的规则/JS，需用户主动确认。每个书源版本可查看 JSON 详情、复制、分享与导入至阅读。技能页可查看内置 Skill 并控制启用/停用；自定义 Skill 支持新增、导入、导出和删除，`save_skill` / `delete_skill` 不能改写内置 Skill。

## 日志与诊断

底栏 **日志** 分五段：操作日志 / HTTP / 抓包 / 崩溃 / 诊断快照，可多选删除。

- 操作日志与 HTTP 日志按天翻看、支持**按天导出为文本**（可发给电脑/AI 排查，含请求/响应头与正文）；HTTP 详情页含请求/响应头、正文与重定向链；
- 「抓包」页签是三个入口：逐次抓包（发一次请求看逐跳结果）、浏览器抓包（可见可交互的 WebView）、抓包历史（按 `cap:contextId` 分组持久化，重启可回看）；
- 诊断快照只含版本、MCP 状态和前置条件，不含 HTTP 或崩溃正文。

排查请用 MCP：`get_logs` / `get_log` / `get_http_logs` / `get_http_log` / `get_crash_logs` / `get_crash_log` / `get_diagnostic_snapshots` / `get_diagnostic_snapshot`。

抓包取证：`capture_once` 发单次请求并逐跳记录重定向；`webview_capture` 做一次性无头 WebView 抓包（跑完即销毁）；「浏览器抓包」页的交互会话同样以 `cap:contextId` 入库。AI 侧读取：`list_captures` 找会话 → `get_capture(contextId)` 分页回看（活动会话用 `poll_capture(contextId, afterLogId)` 增量拉新）→ `get_http_log(id)` 看单条详情 → 字体/图片等二进制资源用 `get_capture_resource(logId)` 读取。日志与摘要含完整 URL/query，可能带令牌等敏感参数，分享前请自行检查。

构建说明见 `BUILDING.md`。

## 任务上下文

`create_context` 创建任务后全程传 `contextId`：`fetch_page` 保存网页返回 `pageId`（相同请求 5 分钟内复用快照），`read_page` 分段读取，或把 `pageId` 直接交给 `inspect_rule` / `analyze_html` / `eval_js`；工具结果超长时自动转 `resultId` + 预览，用 `read_result` 分段取回。`update_context` 保存阶段笔记，`get_context` 恢复进度，`clear_context` 收尾。

上限 **16 个任务**、每任务 32 项 / 200 万字符；快照 5 分钟内复用、任务闲置 30 分钟过期。应用重启后任务自动恢复，但恢复的条目标记 `stale`，先 `refresh=true` 刷新再依赖其内容。`fetch_page` 支持 `responseMode=reference`（只存快照不回传正文）与 `maxChars` 省流量；最终确认传 `refresh=true` 全程实时。调试输出有意截断（正文 4000 字符、目录 20 章、搜索 10 条等），均带全量计数。`app_status` 可查看活跃连接与任务占用状态。
