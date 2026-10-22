# Web JS 逆向 · 书源迁移指南（第二章·书源篇精编）

> 来源：ycoo.net 第二章（3）书源。**本页与本 App 最直接相关**：逆向方法论怎么落到 Legado 书源制作。

## 一、核心思路与关键差异

书源开发的本质与靶场逆向一致——分析目标站请求与数据规则、必要时还原加密参数。区别：**规则最终运行在阅读 App 的解析引擎与 WebView 中**。

重要差异：App 内置 WebView 对部分浏览器事件的触发能力弱于桌面浏览器——**能用规则/请求直接拿到的，就不依赖事件模拟**。

## 二、调试通道

**手机端直接调试**：在 App 内逐环节（搜索、详情、目录、正文）执行调试，查看实际请求 URL、原始返回内容与每条规则的匹配结果，以区分问题出在请求层还是规则解析层。

**电脑端调试（同一局域网）**：App 开启 Web 服务，浏览器输入 IP:端口 → 选目标书源 → 编辑源 → 调试源。

**本 App 对应**：`debug_source`（按指定入口逐阶段调试）+ `check_source(auto=true, refresh=true)`（逐源从搜索结果推进详情、目录、正文并记录阶段证据）+ HTTP 日志页（查看实际请求/响应）。它内嵌 Legado 规则引擎，可提供本次检测证据，但未覆盖阶段、验证墙与阅读客户端实际导入使用仍需分别核验，不能把静态合法或一次 HTTP 200 当成永久可用。

**阅读端另有独立通道**（本项目默认完成标准的一部分，且**默认走网页、不按官方 API 判身份**）：用户在 MCP 页「设置」→「阅读 Web 服务」填好阅读的 Web 服务地址后：

1. `reader_web_status()` 只读探测：`webPageReachable` 表网页入口能否打开，`apiProfile`/`originEcho`/`configRevision` 给出接口画像与用户保存的当前真实端口（不要猜默认端口）。`status=web_page_available` 表示**网页在线、官方接口不匹配**——继续用网页工具，不断言不是阅读、不要求升级；`status=auth_required`/`authReady=false` 表示令牌缺失、无效或 Bearer 挑战，地址有响应，先补令牌或让用户在网页里登录。
2. `reader_web_page(action=open)` 在 App 内持续 WebView 会话打开同一 origin，用 `snapshot`/`click`/`fill`/`scroll`/`back` 导航到书源管理或调试页面，按页面实际显示核对。工具没有 URL/host/请求头/JS 参数，不能换站；`click`/`fill` 有副作用（登录、翻页、提交筛选），保存/覆盖/删除书源须用户确认。
3. 官方结构化接口恰好匹配时才用快捷：`reader_web_sync_source(projectId)` 把已保存成品写入阅读（旧源内容相异默认 `conflict` 不写，须用户明确授权才 `overwrite=true`；`confirmed=true` 才算写入生效），`reader_web_verify(bookSourceUrl, keyword=实际关键词)` 让阅读端自己检验，新版返回结构化 `PASSED`/`FAILED`/`UNCHECKABLE`；旧版缺接口时只回 `legacy_debug_only` + `machineVerdict=unverifiable` 的 WebSocket 调试日志——**旧版 WS 日志不判 `PASSED`**。

只有网页的真实反馈才算调试证据：页面实际显示了搜索结果、目录或正文节点；**网页根路径 200 只证明网页在线，不等于书源通过**。阅读端证据与本 App `check_source` 互不替代，导入阅读前后的可用性要各自验证。写进文档或汇报的只保留通用结论，不带具体实例地址、令牌或个人书源内容。

## 三、「真阅读」生态的判定标准

判断一个成品是不是「阅读（Legado）书源」，看的是**书源 JSON 契约**：含 `bookSourceUrl`、`searchUrl`、`ruleSearch`、`ruleToc`、`ruleContent` 这套字段，由官方规则引擎（CSS/XPath/JsonPath/正则 + JS）执行。

- **规则引擎是身份标准，网页入口不是。** 阅读的 Web 服务可能是官方分支、第三方分支或改过端口的构建：只要网页能打开、书源管理/调试页面能操作，就能用它核对与调试；`apiProfile=mismatch` 只是没识别到官方结构化接口，**不代表不是阅读**，也不构成升级理由。
- 官方 Web API 只是**可选的加速通道**：恰好匹配时可以用结构化同步/检验少读几页；不匹配就继续用网页会话，功能不缺。
- 兼容性判断以**当前用户实际填写的那个网页**为准：同一份书源在不同分支上的表现，各自按该网页的真实反馈记录，不用一个分支的行为否定另一个分支。

反例仍是反例：另一套格式（.js 文件加注释头、自带 JS 引擎、没有上述字段）即便名字带 Legado 也不通用。

**本 App 立场**：产出物永远是官方 JSON 书源（`save_source`），内置引擎 vendored 官方 analyzeRule 实现，「结论强度不能超过证据强度」。

## 四、书源工具三种范式（社区生态总结）

1. **知识库型**：内置官方字段结构 + CSS 速查 + 真实书源样例；维护「引擎不存在的伪规则」黑名单
2. **验证器/真机型**：复现规则引擎语义，自动跑搜索→详情→目录→正文四链路；真机执行为最高权威
3. **MCP 真机型**：封装运行中 App 的调试接口，AI 直接对真机保存、读回、调试

本 App = 2+3 合体（内嵌引擎 + MCP 协议）+ 知识库（search_knowledge/get_skill_reference）。

### 引擎事实（伪规则黑名单，写规则时自查）
- 没有 `:contains()`，文本匹配用 `text.关键词`
- 没有 `:first-child/:last-child`，索引用 `.0` / `.-1`
- 没有 `prevContentUrl` 字段
- 规则串结构：`选择器@提取类型##正则##替换`

## 五、过反爬与注意事项（底线）

- **基于真实证据，不许猜**：判断 SSR/CSR 要看 HTTP 原始响应（`fetch_page` 的 body），而非浏览器渲染后的 DOM
- **反复探测同一反爬端点可能触发风控**——调试期控频，用 `read_page` 读快照而不是反复 fetch
- **回到官方引擎验证**：最终产物必须是官方 JSON 书源
- **请求头 UA 要完整**（含引擎名与版本号）
- **PC 上 JVM 的 TLS 指纹可能被识别**——成熟验证器因此改用 curl 发请求；本 App 在 Android 真机上用 OkHttp，指纹与桌面 JVM 不同，遇到传输层风控走 WebView 通道

## 六、靶场方法 → 书源场景映射

| 通用逆向做法 | 书源场景对应 |
|---|---|
| 抓接口、定位动态参数 | `fetch_page` + HTTP 日志看每步真实 URL 与返回，确定搜索/详情/目录/正文接口 |
| 借浏览器之手 | 书源规则内置 JS（`<js>` 段）让运行环境代算；验证走 `browser_verify` |
| 协议复现 | 逆出签名后在书源 JS 中直接拼请求参数（`searchUrl` 的 `{{}}` 模板或规则 JS） |
| 反调试/加密分析 | 方法通用，结论翻译成 Legado 规则（URL 模板、JS、解析规则） |
| 动态 Cookie | `cookie.getCookie/setCookie`（官方同名 API，eval_js 与书源 JS 内均可用）；调试用 `set_cookie`/`get_cookies` 工具 |
| 重放归因 | `eval_js` 里 `java.connect()` 改参数重放，观察哪种改法被拒 |

## 七、推荐串联链路

知识库写规则（`search_knowledge` + 语料 `match_sources`）→ `debug_source` 四链路跑通 → `check_source(refresh=true)` 本机联网验收 → `save_source` 保存并 `get_source` 回读 → `reader_web_status` 探测（读 `originEcho` 拿真实端口、`apiProfile` 判断有无结构化快捷）→ `reader_web_page(action=open)` 在用户保存的阅读网页里导航到书源管理/调试页面核对。

网页核对是默认路径，对官方分支与非官方分支同样适用：`apiProfile=mismatch` 只说明没识别到官方接口，不代表页面不能用。只有该版本恰好匹配官方结构化接口时，才可选 `reader_web_sync_source(projectId)` 写入（`confirmed=true` 才继续）→ `reader_web_verify(bookSourceUrl, keyword=实际关键词)` 作为省步数的快捷。

结论按实际证据写：网页里要看到确实的书名/目录/正文才算可用，根路径 200 不算；结构化接口可报 `passed`/`failed`/`uncheckable`；旧版分支恒为 `legacy_debug_only`/`unverifiable`，只有日志、没有通过结论。阅读 Web 未配置或不可达时把这一段登记为未覆盖，不要用 `import_reader` 的唤起回执冒充导入成功——该工具只负责唤起阅读的导入勾选页，需用户在阅读里确认。阅读端证据与本机 `check_source` 四阶段证据互不替代。新装 APK 后客户端要重连才会刷新 `tools/list`（旧会话看不到 `reader_web_page`）。

## 八、合规提示

逆向方法只用于授权靶场、自有系统或获得许可的目标；书源仅供学习研究，请遵守目标站 robots 与版权要求。第三方工具（插件/Skill/MCP）安装授权前需自行评估代码与风险。
