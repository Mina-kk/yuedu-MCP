# 阅读书源 MCP

本机 **Legado 运行时 + MCP Server**（原「书源工坊」）。App 内不调用任何模型、不保存任何模型密钥。书源的制作、修复和调试全部由外部 MCP 客户端完成。

- 包名：`com.mina.legadostudio`
- 当前版本：`1.0.111`（versionCode 131）
- 许可证：GPL-3.0
- 上游致谢：DandanLLab/legadoSkill、LegadoTeam/legado

## 界面

iOS 简约白风格：液态玻璃顶栏/底栏（高斯模糊）、大圆角卡片、底部悬浮标签栏。底栏为 **MCP / 书源 / 技能 / 日志**。

## 连接 MCP

1. 打开 App，底栏 **MCP** 页开启服务（默认端口 **58823**）
2. 外部 MCP 客户端配置：
   - URL：`http://127.0.0.1:58823/mcp`
   - 局域网（同一 Wi-Fi）：MCP 页可复制 `http://<局域网IP>:58823/mcp`
   - Header：`X-Studio-Token: <你的 Token>`（在 App 的 MCP 页可查看/复制）
3. 调用 `get_app_info`，应返回 `"ai": false`、`"role": "mcp-runtime"`

### 书源类型开关

MCP 页可选择目标书源类型：**文本 / 音频 / 图片 / 文件 / 视频**（对应 Legado `bookSourceType` 0–4）。

- `save_source` 保存书源时自动写入所选类型；
- `fetch_page` 按类型过滤二进制内容：文本类型跳过图片/音视频/压缩包等二进制响应（返回 `bodyNote`/`binaryBytes` 说明），非文本类型保留对应媒体内容。

## 制作流程

```
list_skills / search_knowledge
  → 编写 BookSource JSON
  → validate_source
  → debug_source / inspect_rule / eval_js
  → check_source
  → save_source
```

`save_source` 默认按书源 URL 覆盖更新同站记录；需要保留历史版本时传 `newVersion=true` 追加。

验证码 / 登录 / WAF 用 `browser_verify`：通过系统通知或 MCP 页顶部横幅，在应用内完成站点验证。

本 App **不提供** `list_models`、`ai_generate_source`、`start_job` 等模型或任务工具。

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
