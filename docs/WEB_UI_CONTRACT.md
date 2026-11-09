# Web UI / Android 宿主契约 v2

这是领导模型冻结的集成契约。各 worker 不可自行改接口；发现问题写报告，由父任务修改。

## 架构决定

- UI = `web-ui/`（Vue 3 + Vite），`ui-core/`（Rust -> wasm-bindgen，前端领域计算），均打包进 APK。
- Android 保留现有 `StudioApplication`、Room、网络、Rhino、MCP、ReaderBridge，避免为了 UI 重写 Java 签名导致兼容性退化。
- `MainActivity` 只承载全屏 WebView（必要的系统文件/权限操作仍走 Android OS）；所有按钮、列表、弹层、设置、状态、验证工具栏均 Web 渲染，不用 Compose/原生 AlertDialog/Toast/Spinner 做业务 UI。
- 页面从 `https://appassets.androidplatform.net/assets/web/index.html` 使用 `WebViewAssetLoader` 加载。无 CDN、无远程脚本；CSP 禁止非必要远端脚本、iframe。第三方验证页面永不加载到 privileged 主 WebView。
- 业务调用通过 `WebViewCompat.addWebMessageListener`，origin allowlist 仅 `https://appassets.androidplatform.net`，必须 `isMainFrame`，对象 `StudioBridge`。拒绝全域 JS interface 和任意命令/文件调用。旧 WebView 不支持 WebMessageListener 时明确提示升级，不能退回不安全全域桥接。

## 消息协议

JS：`StudioBridge.postMessage(JSON.stringify({id, method, params}))`
原生回包：`JSON.stringify({id, ok:true, result})` 或 `{id,ok:false,error:{code,message}}`。
前端安装 `StudioBridge.onmessage = event => ...`，用 id 关联 Promise。超时、Activity 销毁、来源失效应拒绝 pending，而非永久 loading。
请求 UTF-8/字符上限：2MB。错误包含可定位原因但脱敏秘密，不要把调试 JSON 作为 HTML 插入。

### 方法表（全部显式 allowlist）

| method | params | result |
|---|---|---|
| `app.bootstrap` | `{}` | `{versionName,versionCode,buildRevision,ui:"rust-vue",service:{running,starting,port,tokenRequired,endpoints,compact},runtime:{userAgent,bookSourceType},reader:{baseUrl,tokenSaved,revision},appearance:{theme,palette},capabilities:{...}}` |
| `service.set` | `{action:"start"|"stop"|"restart"}` | 最新 service 状态（不回 token） |
| `settings.save` | `{port?,compact?,userAgent?,bookSourceType?,readerBaseUrl?,readerToken?,appearance?}` | bootstrap 回读；所有字段先整体验证再写；readerToken 缺省保留同 origin 令牌，改 origin 则原规则清除；禁止暴露 tokenRequired=false 操作 |
| `connection.copy` | `{kind:"url"|"token"|"json",lan?:boolean}` | `{copied:true}`；秘密直接写本机剪贴板，不向 JS 回 token；URL 取真实配置/本机地址 |
| `tools.list` | `{}` | `{tools:[{name,description,inputSchema,annotations}]}`；与真实 MCP 注册表共用，不造目录 |
| `tools.call` | `{name,arguments:{...}}` | MCP CallToolResult 原形（content/isError），与真实工具 dispatch 共用；不绕过参数、日志、上下文和证据处理 |
| `verification.list` | `{}` | `{sessions:[{id,domain,url,purpose,status,kind,imageData,finalUrl,...}]}` |
| `verification.open` | `{id}` | `{opened:true}`；仅打开现存验证任务的 URL 到无 privileged bridge 的独立 WebView |
| `verification.complete` | `{id,answer?}` | 更新后 session；webview finalUrl 来自宿主实际观察，不信任 JS 传地址 |
| `verification.clear` | `{id}` | `{cleared:true}` |
| `verification.close` | `{id}` | `{closed:true}` |
| `verification.browser.close` | `{}` | `{closed:true}`；销毁隔离验证页返回 web 控制台 |
| `file.export` | `{name,text,mime?}` | `{saved:true}` 或取消状态；系统 SAF，不接受路径 |
| `file.import` | `{}` | `{name,text}` 或取消状态；限制 2MB，系统 SAF |
| `app.theme` | `{dark:boolean}` | `{applied:true}`；仅同步系统栏图标/背景 |
| `app.exit` | `{}` | `{closing:true}`；前端先弹自有确认 |

未来 profile 选择通过 sandboxProfile 字段和后续规则契约扩展，当前宿主不杜撰 profile 类型。

## 真实工具复用方式

在 `StudioMcpServer` 中维护同一注册过程建立的 local handler map：`Server.tool` 同时注册 wrapped handler；增加供本包使用的本地目录与调用方法，调用仍经过 `dispatchToolCallInner` 与已有 boundedResult/logged/context 校验。不复制 73 工具实现；App Web UI 的工具实例不是网络客户端，不应该污染网络会话统计。可给 `create(registerSession:Boolean=true)` 增加明确开关，默认现有路径不变。本地实例每 Activity 一个、销毁释放；保留线程/协程安全。

## 页面信息结构

5 个业务页：MCP 连接、书源、技能、验证中心、日志；每页对应原有能力。设全局设置 sheet；书源编辑 JSON 与分字段可切换，保存前静态检查，保存后 get_source 回读。日志按 operation/http/capture/crash/diagnostic 标签，用实际工具分页。MCP 工具浏览器可调用完整 73 项，敏感/破坏性工具需前端确认。所有单源调试与阅读同步显示 sandbox/readerside 范围，禁止输出伪造通过。

前端 browser 开发可通过 Vite 的 dev-only bridge relay 连 ADB 测试宿主或已有 MCP HTTP。默认未连接状态必须明确“未连接运行时”，不能填演示数据冒充真实状态。Fixture/mock 只存在 tests。

## 系统行为

- Back：派发 `studio:back` 给 web app，先关确认/弹层/编辑草稿，再根页退出确认；不使用原生确认框。
- Insets：把状态栏/导航栏/IME 高度通过 JS custom properties/event 传入，键盘遮挡不得把保存按钮压住。
- 验证 WebView 与主 WebView 并排/叠放都允许，但关闭/完成控件必须由主 Web UI 渲染；无 JS bridge 的外站不得遮挡或触达 privileged origin。
- 系统悬浮球改为 WebView 渲染受控本地 HTML/CSS，或暂时沿用系统通知 + App 内 Vue 悬浮入口（需写明迁移，不保留原生 TextView 球）。不剥夺人工验证功能。
- 留存 Room 表、项目 ID、修订和安全存储 alias；不 destructive migration，不重置用户设置。

## 可写范围分配

- 宿主 worker：`MainActivity.kt`、新 `web/` 包、`mcp/StudioMcpServer.kt` 中最小本地接口、对应 `web/` 测试、宿主 manifest 必要项。
- UI worker：`web-ui/`；设计基准等待后续提供，不读尚在写入的 vault。
- Rust worker：`ui-core/` + 其测试与 build helper，API 按最终 frontend 契约。
- 规则 worker：`runtime/`、`io/legado/.../analyzeRule/`、相关语义测试；不动宿主工具目录文件。
- 主导：Gradle/build 集成、移除旧 UI 编译路径、版本/发布文档、端到端测试、审查与产物。

## 测试要求

先针对来源限制、main-frame、方法 allowlist、消息大小、JSON 转义、设置全部验证/回读、reader token 绑定、handler 真实调用写失败测试，再实现。实际设备/模拟器验证主页面、MCP 启停、项目保存回读、verification 工作流（本地测试站点）、文件往返和 Back/键盘。不得在生产 bridge 中加入 debug token/adb backdoor。
