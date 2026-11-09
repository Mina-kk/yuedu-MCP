# Vue UI 实现报告（web-ui）

分支 `refactor/rust-vue-reader-semantics`。本文件记录本轮补齐的 5 页 + 入口，以及真实接口依据与待父任务验收项。

## 本轮交付（web-ui/src）

| 文件 | 作用 |
|---|---|
| `index.html` | 生产 CSP：`script-src 'self' 'wasm-unsafe-eval'`（无 `unsafe-eval`）、`frame-src 'none'`、`base-uri/form-action 'none'` |
| `src/main.js` | 挂载 App，按 app.css → v8 → v9 → v10 → shell.css 顺序引入样式 |
| `src/App.vue` | 页头（标题「阅读书源 MCP」/副标题「以阅读规则为准」）+ 5 页 + 底部导航 + 设置 sheet + 确认框 + toast；宿主事件 `{event,payload}` 与 window CustomEvent(`detail`) 统一处理 |
| `src/pages/McpPage.vue` | 服务状态/启停/重启、端点与令牌复制、compact 展示、73 工具目录搜索、参数 JSON 调用器（破坏性工具二次确认） |
| `src/pages/SourcesPage.vue` | `list_sources` 分页/过滤、`get_source` 原文编辑、`save_source`→`get_source` 回读、冲突不自动覆盖、导出/导入、`archive_source_versions` 先 dryRun 再删 |
| `src/pages/SkillsPage.vue` | 内置/自定义 tab、`get_skill`、`save_skill`/`delete_skill`（删除确认）、内置只读；`search_knowledge`/`read_knowledge` 折叠区 |
| `src/pages/VerifyPage.vue` | `verification.list` 真实会话、open/complete/close/clear、image_code 输入；外站由宿主隔离 WebView 打开，本页**不创建 iframe** |
| `src/pages/LogsPage.vue` | 操作/HTTP/抓包/崩溃/诊断 5 个标签，按各工具真实参数分页、详情、导出 |
| `src/lib/tools.js` | CallToolResult 解析（content[].text → JSON）、分页字段归一、时间格式化、展示层脱敏 |
| `src/lib/files.js` | Android 走 `file.export`/`file.import`（SAF），浏览器走 Blob 下载 / `<input type=file>`，2MB 上限 |
| `src/lib/core.js` | **修正**：静态 `import('../wasm/studio_ui_core.js')` + `default()` 真实 init；删除 `new URL(...)` + `@vite-ignore` |
| `src/store.js` | 桥接引用改为 `bridgeRef`（浏览器模式 `connectWebRuntime` 后所有调用走新桥），`api.bridge` 以 getter 暴露 |
| `src/styles/shell.css` | 吸顶页头、body 滚动、底部导航让位、键盘弹起处理、验证隔离页工具栏态 |

## 关键修正

1. **WASM 打包**：旧 `core.js` 用 `new URL('./wasm/...', import.meta.url)` + `@vite-ignore`，Vite 不会把 wasm-bindgen 模块与 `.wasm` 打进 dist。现改为静态字面量 import，构建期可解析；`tests/core.test.js` 增加静态断言（不含 `@vite-ignore`）。
2. **数字精度**：Rust `analyze_source_json` 返回的 `formatted` 是重新序列化的 JSON，>2^53 的整数会丢精度。编辑/保存一律提交用户原文（`save_source` 的 `source` 直接传 textarea 文本），Rust 只用于校验/对比展示。
3. **store 桥接替换**：`connectWebRuntime` 之前只改闭包变量，`store.bridge` 与 `tools.call` 仍打旧桥。现统一 `bridgeRef`。
4. **Insets 不双算**：宿主 `studio:insets` 已在原生侧避让，下发的 0/0/0 直接写入 `--native-inset-*` 并置 `data-native-insets=1`；浏览器用 `env(safe-area-inset-*)`。
5. **验证隔离页**：`studio:verification-browser` 打开时主 Web 页头只留「返回/完成」，外站位于 native 顶部 96dp 以下的独立 WebView。

## 真实接口依据（app/src/main/java/com/mina/legadostudio/mcp）

- `McpBookSourceTools.kt`：`list_sources{limit,offset,query,collapseIdentical,latestOnly}` → `{total,offset,limit,hasMore,items[{projectId,bookSourceName,bookSourceUrl,stage,updatedAt,versionCount}]}`；`get_source{key|bookSourceUrl}` → 书源 JSON 文本；`save_source{source,newVersion,fields}`；`archive_source_versions{bookSourceUrl,keepLatest,dryRun}`。
- `McpKnowledgeTools.kt`：`list_skills{}` → `[{id,name,builtIn,enabled}]`；`get_skill{id}`；`save_skill{id,markdown}`；`delete_skill{id}`。
- `McpLogTools.kt`：`get_logs{limit,category,level,query}`、`get_log{id}`、`get_http_logs{limit,offset,date,anchor,contextId,query,includeCapture}`、`get_crash_logs{limit}`、`get_crash_log{name}`、`get_diagnostic_snapshots{}`、`get_diagnostic_snapshot{id}`。
- `McpCaptureTools.kt`：`list_captures{limit,offset}`、`get_capture{contextId,limit,offset}`。
- `McpVerificationTools.kt`：`browser_verify{url,purpose,waitSec,imageUrl}`、`get_verification_status{sessionId}`。
- 宿主方法表见 `docs/WEB_UI_CONTRACT.md`（`verification.*`、`file.*`、`app.theme`、`connection.copy`）。

## 未执行（父任务验收）

- 未运行 `npm install` / `npm test` / `npm run build`（本机限额，禁止编译）。
- 新增/修改的测试均为待远端 CI 运行：`tests/core.test.js`、`tests/sources-page.test.js`、`tests/skills-page.test.js`、`tests/app-shell.test.js`；既有 `tests/mcp-page.test.js` 依赖本轮 `McpPage.vue` 的 `.tool-row`/`textarea`/`调用` 结构，已在实现中保留。
- 需父确认：宿主 `studio:insets` 是否包含 `left/right`；`verification.list` 返回的 session 字段（`imageData`、`finalUrl`）是否与 UI 渲染一致；`save_source` 冲突回执的具体 shape（当前兼容 `{conflict:true}` 与 `{result:{conflict}}` 两种）。
