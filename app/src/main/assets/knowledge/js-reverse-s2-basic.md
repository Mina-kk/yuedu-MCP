# Web JS 逆向 · 入门 SOP（第二章·入门篇精编）

> 来源：ycoo.net 第二章（1）入门。靶场：猿人学 match.yuanrenxue.cn。本页是六阶段 SOP + 反爬分类应对，做书源时配合本 App 工具链使用（见文末映射表）。

## 一、两条终局路线与三条哲学

**路线 A「借浏览器之手」**：不还原算法，让真实页面自己算、自己发，只取结果（CDP / Playwright / 页面内 evaluate）。适用：强环境检测、强反调试、VMP 成本过高。

**路线 B「协议复现」**：彻底还原签名算法，脚本自算参数直连接口。适用：算法标准、常量可抠、追求高并发。

三条哲学：
1. **能借浏览器就不硬逆向**——对抗在环境而非算法时，让页面自己算成本最低
2. **能纯算法复现就不开浏览器**——标准算法与密钥在手就直连
3. **先观察再动手**——先 hook、抓包、重放测试，确认哪些参数被签名，再定路线

## 二、反爬六分类（先分类再选武器）

| 类型 | 识别信号 | 首选应对 |
|---|---|---|
| 行为反调试 | 开控制台闪退、无限 debugger | 换调试通道（CDP 老 Console 域/油猴提前注入） |
| 代码混淆/VMP | 大数组+解释器驱动核心逻辑 | 不整体反混淆，「读字节码」提常量 |
| 加密参数 | 请求带 sign/token/m 等动态值 | 明文—密文配对 → 定位算法 → 复现 |
| 动态凭证 | Cookie 由 JS 写、每次刷新变 | 真浏览器跑一遍拿凭证 |
| 内容伪装 | 字体反爬、图片乱序 | 字体几何特征映射 / 可见性筛选+排序+OCR |
| 环境指纹 | UA/平台/插件校验 | 完整 UA；会话内 IP/UA/Cookie/指纹绑定不互跳 |

## 三、通用 SOP 六阶段

**阶段 0 背景侦察**：确认业务目标 → 抓包定位接口 → 记录参数/头/Cookie → 观察对抗信号。「控制台都打不开」本身就是第一条关键情报（存在行为反调试）。

**阶段 1 静态分析**：脚本落盘 → 格式化 → 全局搜关键词（接口路径、参数名、encrypt/AES/RSA/btoa/charCodeAt/BigInt）→ 混淆分级。普通混淆可格式化+AST；核心逻辑由大数组+解释器驱动则是 JSVMP，直接进入「读字节码」模式，不要试图整体反混淆。

**阶段 2 动态分析**四手段：
- 断点：XHR/fetch、事件、调用栈回溯
- Hook：包装 XHR.open/send、fetch、JSON.stringify、btoa、charCodeAt、BigInt、加密库
- 桩注入：油猴 document-start 或 CDP `addScriptToEvaluateOnNewDocument`，**必须在页面脚本执行前**
- 事件/网络监听：记录时间线

**阶段 3 定位归因与重放**三件套：
- 明文—密文配对（hook 同时抓加密前后样本）
- 重放测试（只改页码/时间戳/token，观察哪种改法被拒 → 签名绑定了什么）
- 归因结论（算法类型、密钥来源、明文格式、时效窗口）

**阶段 4 路线决策**：借浏览器 vs 协议复现，可混合（先浏览器拿常量再转纯协议）。

**阶段 5 复现与提交**：借浏览器须在同一活页面「导航 → 取随机串 → 抠后缀 → 调页面函数 → 立即提交」一条龙；协议复现带齐 session/Referer/UA 直连；尽量复用页面自身 ajax 通道。

**阶段 6 验证与归档**：存档目标文件、参数结论、失败尝试、可运行脚本、坑清单。

## 四、关键技术专题速查

### 反调试绕过（控制台闪退）
- 原理：检测脚本打印带 getter 探针的对象（img.id、Error.stack 被定义 getter），控制台打开时为渲染预览读取属性触发 getter → 跳转 about:blank
- 破局：换不读对象属性的通道。CDP 里 **Runtime 域的 eager preview 会读属性，废弃的老 Console 域（Console.messageAdded）只回传文本**——全程只启用 Console 域，挺过检测窗口
- 可迁移清单：F12 被禁 → 油猴提前注入；无限 debugger → Never pause here 或重写定时器（`setInterval` 包装，回调源码含 `debugger` 则丢弃）；原生函数自检（`[native code]` 校验）→ 换不触发检测的通道

### JSVMP 字节码分析（五步）
1. 抠字节码数组：定位解释器入口，eval 出传入的大数组
2. 建操作码表（PUSHNUM/PUSHSTR/GETPROP/CALL/JMP/JZ…）
3. 反汇编：按「操作码+定长参数」切指令
4. 提字符串常量（常用异或编码）
5. 筛大立即数定位密钥（如大于 1e6 的立即数是 RSA limb 候选）

核心思路：「一涉及 VMP，第一反应就是从字节里找信息」，不要试图还原整个虚拟机。

### 加密参数定位四件套
1. XHR/fetch 断点回溯调用栈
2. hook 网络层（XHR.open/send、fetch、jQuery.ajax）
3. hook 编码/加密原语抓明文—密文配对
4. 全局搜索 + 调用栈确认

三类应对：AES 等对称加密 → 对齐 key/iv 派生与编码后复现；RSA → 重建公钥现算；页面自定义函数 → 不逆算法直接在页面上下文调用。

### 内容伪装
- **图片乱序**：`getComputedStyle` 筛可见图 → `getBoundingClientRect().x` 排序（不能用 DOM 顺序）→ OCR 拼接
- **WOFF 字体反爬**：fontTools 提字形几何特征（轮廓数、坐标点数、上下点比例）建映射规则；**每次刷新映射都变，须重新分析**

### Hook 工程纪律
- 注入时机决定成败，须先于页面脚本
- 保持 toString/length/name/属性描述符与原生一致
- 分层 hook 点：网络层 → 编码层（btoa/TextEncoder）→ 运算层（BigInt/加密库）→ 存储层（cookie/localStorage）→ 环境层（navigator/屏幕）
- 侦查阶段「记录而非阻断」，避免副作用致闪退

## 五、通用坑清单

- 目标站可能校验 UA（含特定标识），UA 要完整含引擎名与版本号
- session 有效、Referer 对齐；时间戳与加密值同源，注意秒/毫秒级
- Base64 入 URL 前必须 encodeURIComponent（含 +、/、=）
- 随机串/签名常绑定页面实例与时效，须一条龙现取现用
- 函数名大小写敏感
- 请求间隔数百毫秒防风控；headless 浏览器可能被检测

## 六、AI 协同逆向（人机分工）

「人负责侦察背景、确定方向、识别破绽；AI 负责在明确方向下高速试错」。
- 先给绝对背景（URL、接口、现象、目标文件、约束）；指方向而非等结论
- 多轮提示词工作法：第 0 轮只要攻击面地图；逐轮只补缺一项；识别「秒点」（标准加密直接算）与「熬点」（VMP 转字节码分析）
- AI 失败纠偏：循环推理 → 补确定性事实；死磕反混淆 → 明示是 VMP；闪退仍继续算 → 要求每步校验 location.href

## 七、映射到本 App 工具链

| SOP 步骤 | 本 App 工具 |
|---|---|
| 阶段 0 抓包侦察 | `fetch_page`（看原始响应判断 SSR/CSR）+ HTTP 日志页（可看请求/响应头与正文，支持导出） |
| 阶段 1/2 静态+动态分析 | `read_page`/`analyze_html` 看结构；`eval_js` 直接跑 hook/还原脚本验证 |
| 阶段 3 重放测试 | `eval_js` 里 `java.connect()` 改参数重放，对比响应 |
| 阶段 4 借浏览器 | `browser_verify` + 验证中心（WebView 真实环境）；`fetch_page` 遇验证会返回 verification_required |
| 阶段 5 协议复现 | 结论写进书源 searchUrl/规则 `<js>` 段，`debug_source` 四链路验证 |
| 动态 Cookie/登录态 | `set_cookie`（默认 merge 不冲掉其他 Cookie）/`get_cookies`；JS 内 `cookie.getCookie()` 官方同名 API |
| 阶段 6 归档 | `update_context(notes=…)` 把结论写进任务上下文 |
