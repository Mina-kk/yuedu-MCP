# yuedu-MCP 2.0 重构 SPEC

状态：执行中。主导 gpt-6-astra；实现 Mistral-deepseek-v4-1-flash-1017。用户授权自动完成环境安装、开发和验证；不需要中途审批。UI参考仓库正在写入，按用户纠正后置读取。

## 0. 不可变约束

1. 交付是真实可运行 Android APK + 源码分支 + Web构建，不交付静态 mock 展示。
2. 新UI只用 Rust/WASM + Vue，不以 Compose/XML/Android 原生控件呈现业务界面。允许 OS 文件选择/权限对话框、WebView及服务生命周期的必要宿主。
3. 阅读规则以选定热门活跃分支固定提交的真实实现为准；不同分支不能合成一个无出处的“更方便”方言。对缺失能力返回范围与环境差异，不能悄悄改源。
4. 保存、调试、沙箱初筛、阅读真实执行、结构化阅读通过是不同证据。不要弱化既有写入预检/回读、冲突拒绝、配置绑定、鉴权和未知状态。
5. 包名 `com.mina.legadostudio`、Room schema/用户项目与 secure storage alias保持。新代码无清库逻辑。开发版本2.0.0，versionCode201；正式签名不可得则明确测试签名，不冒充官方覆盖包。
6. 基线工具73（已实际统计）。新增工具必须文档/目录测试同改；默认保持73个原工具和现有入参，不随意删能力。前端可增加宿主方法，和外部MCP目录分开。
7. apikey-vault只是视觉代码参考，绝不复制用户生产数据、私钥、API Key或身份到本应用。其业务不是本项目业务；沿用设计语言而非API账户页面文案。

## 1. 模块

- `app/`：原生必需宿主 + 现有MCP/Room/网络/阅读桥接；MainActivity换受限Web宿主。
- `legado-rhino/`：保留现有Rhino，验证API签名/作用域，不能改成浏览器JS后假称Java兼容。
- `web-ui/`：Vue3/Vite业务页面，全部离线静态资产打包；图标本地SVG、无CDN依赖。
- `ui-core/`：Rust→WASM负责无损源JSON分析/差异/过滤/证据标签，Vue真实调用。不是另造一个Rust规则引擎。
- `docs/research/`：分支固定commit、规则/JS语义、迁移说明、基线。
- `docs/WEB_UI_CONTRACT.md`：宿主消息API，必须遵守。
- `docs/UI_CORE_CONTRACT.md`：Rust前端核API，必须遵守。
- `tests/e2e/`：浏览器/ADB端到端与迁移、安全、真实MCP协议测试。
- `scripts/`：可复现 Web/WASM/Android构建脚本；不用依赖用户手工跑命令。
- `.tasks/`：本地工作器brief/log，禁止上传敏感或巨型运行日志，最终仅保留适当任务说明或移至本profile。

## 2. 规则重构要求

以 `docs/research/branch-selection.md` 中核心集合为主，JS/规则研究完成后冻结差异清单；至少修复会迫使书源作者改变正常阅读写法的已证实差异。

核心领域：
- typed rule result，不把 regex capture list、JS对象、JSoup Element、JSON Map强制字符串化后再猜类型。
- 统一 AnalyzeRule 风格的 getString / getStringList / getElement(s) 编排，作用域里 src=原始内容、result=当前阶段，java获取当前解析上下文，不把规则当HTML直接输出。
- @put/@get变量、嵌入{{}}、多段JS、字符串替换、regex capture、空规则及列表节点语义按上游可追溯实现。
- URL模板与URL option、GET query/POST form/body/charset的编码顺序按源码；不要先全局替换{{key}}导致JS表达式与body二次编码。
- JS lib作用域、source/book/chapter变量、request java桥接及持久状态明确生命周期；remote jsLib按安全受限加载或明确不可运行，不静默跳过后把报错归因规则。
- 可选择兼容profile，但必须有真实语义差异测试或能力限制说明。共享核心不承诺全分支扩展。
- 超时/取消/响应大小边界要可观测；不取消安全边界以追求“全兼容”。

每个实际修复用红绿测试、引用固定源码、保留期望输入输出。既有默认JVM测试与MCP目录全部重跑。对互相矛盾行为不得“取并集”而隐藏差异。

## 3. 页面

- MCP：服务启停、连接信息复制、标准/精简模式、真实健康/会话/调用概览、工具目录；高级参数折叠。
- 书源：列表/搜索/按版本查看、JSON编辑、无损导入导出、静态校验、调试、阅读同步/覆盖确认/回读、证据范围可视化。禁止同域自动合并，保留同URL多版本。
- 技能：内置/自定义列表、读取/参考、启停（若宿主对应支持）、自定义保存/删除确认，不允许覆盖内置。
- 验证：真实待验证会话、人工完成入口、隔离网页、验证码、Cookie/域模式管理；不自动替用户绕过CAPTCHA。
- 日志：operation/http/capture/crash/diagnostic 分组、真实分页/详情、脱敏提示、导出。
- 设置：vault主题/配色/日夜模式，端口/UA/源类型/阅读origin+token；secret写后不回显，失联/鉴权/超时明确。
- 全局：loading/error/empty/toast统一，自定义Vue对话框/开关/可搜索选择器；键盘焦点、Escape、返回、safe-area/IME、横竖屏及窄屏可用。

## 4. 兼容与发布

- UI替换不要求重建用户数据库；新配置只增量，不覆盖旧令牌。
- 旧Compose源可暂留 `legacy-ui/` 作为非打包参考，旧源码字符串测试对应迁移到新UI业务测试；不得以删除测试来掩盖行为回归。
- Release公开JSON字段/@Keep契约继续检查。运行构建后校验APK真实版本、包名、文件列表、签名、hash；确保Vue/WASM在APK内不是开发URL。
- 不自动合并main。最终交付独立分支/可审阅diff，若网络上传失败则保留git bundle与本地commit并如实说明。

## 5. 验收矩阵

A. baseline与上游依据：固定commits、sha校验、星标/活跃时间和排除原因。
B. sandbox：差异案例红→绿、JVM全套、真实本地fixture HTTP链路、不可支持能力报告。
C. frontend：cargo test/clippy、WASM构建、Vue unit/build、真实桥接状态与保存回读、无mock生产数据。
D. protocol/security：73工具契约、MCP initialize/list/call、Bearer拒绝、WebMessage来源/mainframe限制、文件路径拒绝、secret不外泄。
E. migration：旧包数据→新包启动保留项目/源内容与配置（测试签名基线包→相同测试签名升级）。
F. device：模拟器安装/启动、服务、导航、编辑/保存、导出/导入、light/dark、键盘和Back；记录哪些仅模拟器、哪些未做物理机。
G. review：独立审查、修复后重跑，最终产物与源码commit绑定。

## 6. 执行账本

总体计划另见本profile `reports/yuedu-MCP-refactor-plan.md`。各worker结果都是自报告，Astra必须重跑关键测试，不凭“完成了”关闭验收项。
