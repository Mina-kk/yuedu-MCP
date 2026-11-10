# Web 宿主补齐（Android 宿主侧）

分支 `refactor/rust-vue-reader-semantics`，本轮只做编辑，**未编译、未跑测试**（父在全部编辑退出后限额构建）。
范围：`MainActivity.kt`、`web/**`、`web tests`、`StudioMcpServer` 的 local 接口、overlay manager/page/page tests。
未触碰：runtime/**、其它 tests、Vue（`web-ui/**`）、Rust、Gradle、CI。未 commit / push。

## 起点：父的实际编译诊断

`/root/.hermes/profiles/yd-mcp/reports/yuedu-local-compile.log` 里宿主相关的失败全部来自
`StudioWebBridge.kt` 被旧 worker 删除后的连锁（`ThemeModeStore`、`SafHost`、`SafRequest`、
`StudioWebBridge`、`emitBack`、`attachSaf`、`install`、`emitInsets`、`emitVerificationBrowser`、
`launch`/`cancel` 未解析），以及 `AssetsPathHandler(Context, String)` 不存在、
`SafLauncher` 在非 ComponentActivity 上调 `registerForActivityResult`、
`VerificationBrowserView.cookieManager` 未定义。以下逐条落地。

## 11 项修复的落点

1. **工具目录与 SDK 原形**（`StudioWebBridge`、`StudioMcpServer`、`ToolRegistryOrder`）
   - `tools.list` 用 `Json.encodeToString(Tool.serializer(), tool)` 序列化整个 Tool：
     `inputSchema` 是 `ToolSchema`（JSON Schema），不再手拼成 `KJsonObject`。
   - `tools.call` 用 `CallToolResult.serializer()` 整个序列化，`structuredContent` 保留。
   - `fullToolSpecs` 是 ConcurrentHashMap（哈希序）；新增 `toolOrder` 注册顺序快照
     （`CopyOnWriteArrayList`）+ 纯函数 `ToolRegistryOrder.arrange/register` 重排，
     快照缺失的名字追加在末尾绝不丢工具。73 个工具与 `dispatchToolCallInner` 链路不变。
2. **桥的编译与幂等**（`StudioWebBridge`）
   - `palettes` 用 `JsonArray` 变量 + `add`（不再 `addProperty(..., JsonArray)`）。
   - `SafHost` 是普通 interface，`safHost` 改为 `SafHost?`，未挂载时 `file.*` 明确回
     `ERR_UNSUPPORTED`，`capabilities.saf=false`，不假装成功。
   - `release()` 幂等：先置 `released` 停止入站，drain 在途 id 回 `host_gone`，再 `scope.cancel()`；
     **只在真正创建过 localServer 时**才 `releaseLocal()`（`localToolsSnapshot != null`），
     不再触发未初始化的 lazy。
3. **设置与令牌**（`StudioWebBridge.settingsSave`、`WebBridgeProtocol`）
   - 历史实现曾在保存端口时强制 `tokenRequired = true`；该行为已被用户明确要求撤销。当前契约以 `docs/WEB_UI_CONTRACT.md` 为准：默认开启但可显式关闭，端口更新保留未指定的鉴权值，`service.tokenRequired` 回真实值。
   - 写前完成全部校验：`parseSettings` 聚合所有字段错误；`readerToken` 单独保存需要已存在 origin
     的检查**移到任何写之前**（原先在端口/UA 写之后）。
   - 写入串行化：`Mutex settingsLock`；每个字段独立 `step()`，IO 失败记日志并在回包里
     标 `partial:true` + `failed:[...]`，回读真实落盘值——不谎称原子事务。
   - `appearanceTheme` / `appearancePalette` 分开保存，`save(palette, mode=null)` 不会重置 mode。
4. **剪贴板**（`connectionCopy`）
   - `kind=token` 复制**纯 token**（原 `tokenHeaderLine` 是 Authorization 头，与 Vue「复制令牌」不符）。
   - 局域网地址用 `McpAccess.lanEndpoints(port)`（当前真实网卡），无地址时回 `not_found`。
   - API 33+ 标 `ClipDescription.EXTRA_IS_SENSITIVE`；回包只有 `{copied:true}`，令牌不回 JS。
5. **完成会话的 finalUrl**（`verificationComplete` + `pickFinalUrl`）
   - 顺序：隔离页当前 `lastObservedUrl`（`setObservedUrlProvider` 注入）→ 宿主在 WAITING 期间
     回写的 `session.finalUrl` → 任务 `session.url`。前端传的 finalUrl 依旧丢弃。
6. **外站隔离与验证页布局**（`VerificationBrowserView`、`WebBridgePolicy`、`MainActivity`）
   - 显式 `private val cookieManager = CookieManager.getInstance()`（编译错误来源）。
   - 验证页 `allowFileAccess=false`、`allowContentAccess=false`、禁 geolocation、禁多窗口。
   - 新增 `verificationResourceDecision`：只放行外站自己的 http(s) 与 `data:`/`blob:`；
     指向 privileged origin、`file:`、`content:` 一律 403（返回响应，不回 null）。
   - 验证页从顶部 96dp 之下开始（`FrameLayout.LayoutParams.topMargin`），**主 WebView 不 resize**
     （避免底部弹层错位），`onConfigurationChanged` 按新 density 重算 topMargin；
     `onResume/onPause` 配对调用 `webView.onResume/onPause`。
7. **AssetsPathHandler 与拦截**（`MainActivity`、`WebBridgePolicy`）
   - 用标准 `AssetsPathHandler(this)` 挂 `/assets/`，再由 `shouldInterceptRequest` 只放行
     `/assets/web/` 子树，其余回 **404 空体**（`DENY_RESPONSE`），绝不回 null 去联网。
   - 新增 `hostNavigationDecision`：两个 `shouldOverrideUrlLoading` 重载共用同一严格策略
     （https + host + 443 + 无凭据 + `/assets/web/` 前缀 + 拒绝 `..`/`%2e%2e`/`\` 穿越），
     相似域 `appassets.androidplatform.net.evil.com`、其它端口、自定义 scheme 全部拦截。
8. **外观与 insets**（`MainActivity`、`StudioWebBridge`）
   - 删掉不存在的 `ThemeModeStore`，统一 `WebAppearance.load(this)`；`mode` 给 Vue 是
     `light/auto/dark`，palette 是 ID（`lime`…`cherry`），`onConfigurationChanged` 同步系统深浅色。
   - insets 只算一次：原生 padding 让出 systemBars/IME，随后 `emitInsets(top=0,bottom=0,ime=0)`
     并附 `rawTop/rawBottom/rawIme/density` 仅作信息字段（前端不得用于布局）。
9. **事件信封**（`WebBridgeProtocol.event`）
   - 改为 `{event, payload}`（前端 `bridge.js` 读 `data.payload`）；MainActivity 的
     `studio:route` 仍走 `CustomEvent.detail`（DOM 规定字段，前端两路都读）。
   - 新增 `exactInt`：`1.5`、`1e3`、`1.0`、`2147483648` 一律拒绝，不再 `asInt` 截断。
   - `parseRequest` 先按字符数粗筛再按 UTF-8 字节判定 2MiB。
10. **SAF**（`SafLauncher`）
    - 构造参数改为 `ComponentActivity`（`registerForActivityResult` 前提），`MainActivity` 在
      `onCreate` 里注册。
    - 单 pending：并发调用抛 `Busy`，不覆盖前一个 deferred；`launch` 在主线程，读写走
      `Dispatchers.IO`（不再裸 `Thread`）。
    - `release()` 结算所有 pending 并取消作用域；`ActivityNotFoundException`/`IllegalStateException`
      都结算，不永久 pending。
    - 导入上限 `MAX_IMPORT_BYTES = WebBridgeProtocol.MAX_REQUEST_BYTES`（2MiB）并按 UTF-8
      **严格解码**（非法字节抛 IOException，不替换成 U+FFFD）。
11. **悬浮球尺寸**（`VerificationOverlayManager`、`VerificationOverlayPage`）
    - 常态 52dp 正方形；提醒态放宽到 ≤240dp 宽 / ≤110dp 高并按屏幕 clamp，x/y 钳回屏内，
      关闭恢复 52dp；吸附在提醒态按窗口实宽贴边且 x 不为负。
    - 页面常量抽出（`BALL_CSS_PX`、`ALERT_BUBBLE_MAX_WIDTH_CSS_PX`、`ALERT_LABEL_MAX_WIDTH_CSS_PX`、
      `ALERT_WINDOW_MAX_HEIGHT_CSS_PX`），CSS 用插值，测试可断言。JS/网络策略原样保留。

## 测试（已写，**父来跑**）

新增/扩展（全 JVM 单测，无 Robolectric）：
- `web/WebBridgePolicyTest.kt`：`payload` 信封、主页面导航（相似域/端口/凭据/穿越/自定义 scheme）、
  本地资源只在 `/assets/web/`、验证页子资源隔离、`pickFinalUrl` 优先级、SAF 严格 UTF-8 与上限。
- `web/WebBridgeProtocolTest.kt`：小数/越界整数拒绝、`exactInt`、2MiB UTF-8 多字节边界、
  代理对计数；修正了原有「exactly at limit」用例的 head/tail（原用例实际生成非法 JSON）。
- `web/WebBridgeToolSerializationTest.kt`（新）：Tool 的 `inputSchema` 为 JSON Schema 对象、
  CallToolResult 保留 `content/isError/structuredContent`、工具顺序。
- `web/WebHostLayoutTest.kt`（新）：`VERIFICATION_TOOLBAR_DP = 96` 与 px 换算。
- `mcp/ToolRegistryOrderTest.kt`（新）：注册顺序重排、缺失名不丢、重复注册不移位。
- `verification/VerificationOverlayPageTest.kt`：气泡尺寸不超提醒态窗口、常态页无气泡。

现有业务测试一个未删；`WebBridgePolicyTest` 里原有的 `payload` 断言本来就期望 `payload`，
与本次修正一致。

## 未验收 / 需父注意

- **未编译**：本轮禁止 Gradle。以下是我按源码推断、需要编译确认的点：
  `Tool.serializer()` / `CallToolResult.serializer()` 与 `explicitNulls=false` 在
  kotlinx-serialization 1.9.0 下的行为；`ClipDescription.extras` 赋值（API 33+）；
  `ActivityResultLauncher.launch` 在 `withContext(Main)` 中的调用；`FrameLayout.LayoutParams`
  作为 `WebView.layoutParams` 的赋值。
- **SDK 类型确认过但未编译**：`Tool`/`ToolSchema`/`CallToolResult` 的字段名来自
  `kotlin-sdk-core-jvm-0.15.0.jar` 的 `javap` 输出（`inputSchema: ToolSchema`、
  `structuredContent: JsonObject?`）。
- **设备行为未验收**：96dp 顶部区域与 Vue 顶部控制栏是否严丝合缝、IME 弹起时保存按钮可见、
  SAF 选择器往返、隔离页 cookie 挑战、悬浮球拖动/吸附手感——都需要真机。
- **Vue 侧并行**：`web-ui/**` 是另一位 worker 的改动（`App.vue` 的 `verify-open`、`store.js` 的
  `applyInsets/applyVerificationBrowser`）。宿主现在下发 insets 全 0 + 原生 padding，
  若 Vue 仍按 `s.insets.top` 加 padding 会双算——需要 Vue worker 确认只读 `raw*` 作展示。
- `runtime/**` 的编译错误（`JS_LIB_TIMEOUT_SEC`、`extractUrlList`、`defaultPort`、
  `getElement/getElements`）不在我的范围，仍待 runtime worker。
