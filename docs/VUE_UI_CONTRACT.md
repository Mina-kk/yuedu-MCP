# Vue UI 设计与实现契约

## 已等待后置读取的参考基准

用户要求在其他准备完成后才查看逐步写入的 `Mina-kk/apikey-vault`。现已完成分支源码采集、规则/宿主/Rust契约和环境安装，再从官方GitHub API获取其最新 `main`：`2c0974629ed79d93f8daeff4028f4ae87d845344`。仅下载52个UI/设计文档文件，逐项Git blob SHA核对。

私有参考只读根：`/root/.hermes/profiles/yd-mcp/research/vault-ui-reference/`。设计依据是 `frontend-vue/` 生产UI，不是 legacy Rust Leptos页面，不用旧KeyNest业务模型。

## 必须复用的视觉语言

- `frontend-vue/src/lib/palettes.js` 的10套配色/名称/顺序/确切hex：lime/lava/royal/pink/neon/mint/crystal/crimson/porcelain/cherry。默认lime；light/dark/auto三模式。保留主次色与对比色、粉色/红色actionBg调整、瓷白/奶油白背景。
- CSS使用参考 `styles/app.css` + `v8.css` + `v9.css` 的层级、细边框、毛玻璃、背景色团、24px圆角、spring曲线、字体、按钮/胶囊/输入/弹层/页头。不是随意换成紫色AI模板。
- 可直接复用用户授权的无业务耦合组件（Icon、Switch、ThemeSwatch、ConfirmDialog、Toast、Focus helpers）；保留来源说明，不复制密钥管理业务/用户头像/原app名称。
- 主题预览是圆形SVG双色半圆，边缘单线抗锯齿；light/dark分别有contrast。
- 底部悬浮导航与本项目5页匹配：MCP / 书源 / 技能 / 验证 / 日志。桌面可横向顶部/侧边适配，但同一色彩/交互。无需照搬vault账户业务字段。
- 主标题“阅读书源 MCP”，副标题“以阅读规则为准”。上方小Rust/Vue状态仅真实WASM已加载才显示。

## 真实功能

桥接遵循 WEB_UI_CONTRACT.md。新增 `bridge.js` 提供 Promise RPC：Android StudioBridge则消息id往返；浏览器没有bridge时明确未连接，并允许填自有MCP endpoint/token（仅session memory）或dev relay，不能静默fixture。

Web-only可工作策略：浏览器设置“运行时地址”显式用户输入；用标准MCP Streamable HTTP initialize→notifications/initialized→tools/list/call。Vite开发时可本地同源proxy到明示配置的runtime，不猜端口/扫内网；生产APK不需要proxy。跨域/CORS失败显示原因，不假造项目列表。

首屏不能因单项工具失败永久挡住整体UI。bootstrap失败时也能进入本地主题设置与重试/连接说明。业务数据空是正常empty state，无示例源冒充用户数据。

### 页面分工

1. MCP：真实服务状态、启停、连接复制、compact开关、工具目录/搜索和参数JSON调用器。copy token按钮只宿主剪贴板；不在DOM显示token。
2. 书源：`list_sources`摘要分页、Rust filter，JSON editor + schema/字段帮助、Rust analyze/compare、save_source→get_source回读、版本冲突、静态与联网调试分离、export/import。书源方法参数按实际Mcp*Tools读，不凭空命名。
3. 技能：list_skills/get_skill/get_skill_reference，分tab内置/自定义，save/delete明确确认。知识search/read可折叠。
4. 验证：宿主真实session列表，人工open/complete/close，image_code输入；外站在隔离WebView，主Vue toolbar保留关闭/完成。也提供真实cookies/domain modes工具入口。
5. 日志：get_logs/http/captures/crash/diagnostic，按工具输出实际shape分页、详情、导出；无内容显示空，不填假日志。
6. Settings：10主题+三模式；runtime port/UA/source type/reader origin/token fields，保存后bootstrap回读；dirty sheet关闭确认。reading side sync/overwrite/delete有明确确认。

错误输出是纯文本pre，不用v-html。renderMarkdown若没有安全sanitizer则直接pre，不能任意注入HTML。所有button实际可点击，不仅画壳。

## Rust集成

真实import `web-ui/src/wasm/studio_ui_core.js`，调用default init（加载同目录.wasm）。封装 `core.js`；调用 analyze_source_json/compare_source_json/filter_sources/summarize_evidence，WASM缺失时显示不可用，禁止JS假替代后声称Rust在线。测试可mock接口，但生产不得mock。

## 工程

- 仅 Vue3+Vite，版本与参考兼容，包管理npm lock。
- `base:'./'`适配assetloader；prod CSP允许self+wasm-unsafe-eval，不允许unsafe-eval任意JS。
- `npm test`(Vitest+Vue Test Utils+jsdom)，`npm run build`；package script可先执行../ui-core/build-web.sh（它生成真实wasm，等Rustworker完成）。
- modules/components每文件尽量<500行；不要把全部功能塞进1万行App。
- 自己的写入范围只web-ui/**，不要改ui-core/Android/docs契约。

## 交互和设备

返回/Escape：当前dialog→sheet→dirty confirm→root退出confirm。focus trap/return、点击外侧、屏幕阅读aria labels。
Insets+IME一次计算，参考vault native.js/v9；宿主会传 `studio:insets` / `studio:viewport`（若实际host字段不同，报告父任务协调，不让UI/host互猜）。紧凑手机320/390/430，平板768，桌面1280，横屏；textarea全宽无resize把手，页头footer固定、body可滚动，保存按钮不在键盘后。

## 验收

测试主题10×2、focus/back、RPC超时/异常/相关id、sources保存回读/dirty/冲突、wasm真实smoke、build离线资产、未连接状态不假成功。模拟器由父验收。
