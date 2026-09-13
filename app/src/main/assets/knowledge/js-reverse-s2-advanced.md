# Web JS 逆向 · 进阶对抗（第二章·进阶篇精编）

> 来源：ycoo.net 第二章（2）进阶。适用场景：目标站有混淆、反调试、环境检测、wasm 签名或 TLS 指纹风控。书源制作多数用不到本页全部内容——先按 `js-reverse-s2-basic.md` 的 SOP 分类，确认对抗类型后再来查对应章节。

## 一、AST 反混淆体系（只治「写法层面」的混淆）

工具链：babel 三件套（parser 解析 → traverse 改写 → generator 生成）。**对 JSVMP 自定义字节码无效**（VMP 走入门篇的字节码路线）。

五类手法与对应 pass，按此顺序执行（每步验证语义等价）：
1. **字符串数组+轮转**：沙箱执行初始化与轮转函数，把取值函数调用批量内联为字符串字面量
2. **编码字符串/数字**：常量求值（constant folding）
3. **控制流平坦化**：解析调度数组，重排基本块，去掉 while+switch 分发器
4. **死代码/花指令**：删除不可达分支与无效语句
5. **标识符混淆**：作用域安全前提下统一重命名（放最后）

静态算不出的取值函数：直接在浏览器沙箱里执行该函数动态求值。

## 二、反调试全集与绕过对照表

| 手段 | 绕过 |
|---|---|
| 无限 debugger | 行号右键 Never pause here；条件断点置 false；拦截定时器与 Function 构造 |
| 控制台 getter 探针 | 换不读对象属性的通道（CDP 老 Console 域） |
| 尺寸差检测 | 真浏览器自动化，或 hook 尺寸属性返回正常值 |
| 时间差检测 | 覆盖计时函数；用不触发渲染的通道观察 |
| 原生函数校验（`[native code]`） | Proxy/Reflect 伪造外观，或从 iframe 借未污染原生函数 |
| 属性描述符检测 | hook 时连描述符一起伪装 |
| 构造器/堆栈检测 | 对齐构造器链、清洗堆栈 |
| 完整性/轮询校验 | 不改被校验字节，走旁路通道 |

三总则：**提前注入**（须先于页面脚本）、**无痕**（toString/length/name/描述符与原生一致）、**换通道优先于硬改**。

## 三、环境指纹与补环境

检测点：navigator（userAgent、platform、languages、plugins、webdriver）、screen、window 尺寸、document、history、localStorage、时区、WebGL、Canvas、permissions。

三条路线（按稳定性排序）：
1. **真浏览器承载**（CDP/Playwright，最稳）
2. jsdom 打底 + 手补缺失属性
3. Node `vm` 模块建隔离上下文，只执行目标片段

高效补环境技巧：用 Proxy 包全局对象，把「JS 读取了哪个缺失属性」全部打印，按记录逐层补齐 getter、toString、原型链与 instanceof。

分层认知：**补环境只解决 JS 层指纹，管不到网络层的 TLS/HTTP2 指纹**。

## 四、WebAssembly 逆向路径

1. 定位边界：搜 `WebAssembly.instantiate`、抓 .wasm 文件、hook 导出函数入参与返回值——先拿「明文—结果」配对
2. **优先「借函数」**：浏览器或 Node 直接实例化 wasm 调导出函数，多数场景到此即可
3. 必须逆时：wabt 的 `wasm2wat` 转 WAT 文本，或 IDA/Ghidra；重点看导入/导出表、线性内存读写、算法主循环
4. 复现：Node 加载同一份 wasm，或把算法翻译成 JS

## 五、RPC 方案（本地服务调用页面内函数）

签名函数留在真实页面运行，本地架桥送明文、收签名，再用高性能直连接口。比硬逆 VMP/wasm 省事，比 UI 自动化快。

- 最简形态：CDP 保活页面，需要签名时 `Runtime.evaluate` 直接调 window 上的函数
- 服务形态：页面注入 WebSocket 桥 + 本地 HTTP 服务 = 可并发的「签名服务」
- 注意：保活与登录态、返回值与时效绑定、并发与超时

**书源场景对应**：Legado 书源的 `<js>` 段 + WebView 本质上就是 RPC 思路——让运行环境代算，而不是在规则里硬复现算法。

## 六、传输层指纹（JA3/JA4、HTTP2）

- Python requests、Node 默认客户端的 TLS 握手（密码套件、扩展顺序、椭圆曲线）与真浏览器不同；HTTP2 的 SETTINGS 帧、头序、优先级可被识别（Akamai 指纹）
- 对策：
  - 真浏览器发请求（指纹天然正确）
  - 定制 TLS 客户端：curl-impersonate、uTLS、curl_cffi 模拟浏览器 ClientHello
  - 选可定制帧设置与头序的库对齐 HTTP2
- 代理纪律：**同一会话内 IP、UA、Cookie、指纹保持绑定不互跳**；控制并发频率

## 七、注入模板库（三段脚手架）

统一在 document-start 或 CDP `addScriptToEvaluateOnNewDocument` 最早注入：

1. **XHR/fetch 监听**：包装 `XMLHttpRequest.prototype.open/send` 与 `window.fetch`，打印 URL、body、响应文本
2. **编码函数监听**：包装 `btoa` 与 `JSON.stringify`，打印输入输出配对
3. **反反调试**：置空 `console.clear` 保住日志；包装 `setInterval`，回调源码含 `debugger` 则丢弃（return 0）

## 八、与本 App 的边界

- 本 App 的 Rhino 环境（eval_js / 书源 JS 段）适合做：算法复现验证、明文—密文配对实验、签名函数原型开发
- 本 App **不做** AST 反混淆与 JSVMP 反汇编——在电脑上完成分析，把结论（算法+常量）带回 `eval_js` 验证
- 遇 TLS/HTTP2 指纹风控（HttpFetcher 直连 403 但浏览器正常）：不要硬刚传输层，用 `browser_verify` + `set_domain_mode(domain, ALWAYS)` 走 WebView 通道
- 遇 CF 五秒盾/验证码：验证中心人工过一次，Cookie 落 Runtime 仓库后自动带
