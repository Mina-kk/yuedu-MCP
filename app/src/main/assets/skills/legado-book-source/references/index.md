# 阅读书源参考路由

每轮创建、修改或继续调试先读 `SKILL.md` 与本文件，再只加载当前阶段需要的参考。客户端读不到本地文件时用 MCP 工具分页读取：`get_skill_reference("legado-book-source", "references/xxx.md", offset, limit)`。

## MCP-first 基线

默认在书源工坊真实运行时中工作：

1. `match_sources` 语料命中（域名/站名），再 `list_sources` 查重。
2. 已有源用 `get_source` 读取原文；语料命中用 `get_corpus_source` / `get_corpus_shard` 取底本。
3. `fetch_page` 抓页存快照，用 `analyze_html` / `eval_js` 在快照上做探针。
4. 用 `debug_source` 验证当前阶段（JSON 直接传入，不 save）。
5. 完成后 `save_source` 保存 → `get_source` 回读 → `check_source(refresh=true)` 实时验收。
6. 不要引导用户打开已删除的「项目列表」。本地书源在底栏「书源」页，可导入至阅读或删除。排查请用 `get_logs` / `get_http_logs` / `get_crash_logs`，不要要求用户导出日志。

MCP 连接故障属于环境故障。先恢复连接，不以本地猜测代替应用内调试。旧 `scripts/legado-debug.py` 仅是用户明确同意后的备用入口。

## 按阶段读取

| 当前阶段 | 必读文件 |
|---|---|
| 写规则之前 | `references/corpus.md`（语料命中） |
| 初始化、基础字段、详情、搜索、目录、正文 | `references/basics.md` |
| 请求失败、403、验证页、动态页面 | `references/verification.md`, `references/troubleshoot.md` |
| WebView、webJs、调用网页函数 | `references/webjs.md`, `references/troubleshoot.md` |
| 登录、按钮、回调、变量持久化 | `references/login.md`, `references/patterns.md` |
| 发现分类与布局 | `references/discovery.md` |
| 漫画正文与图片 | `references/comic.md`, `references/basics.md` |
| 多线路、多类型、跨页状态 | `references/patterns.md`, `references/js-api.md` |
| 订阅源/RSS | `references/basics.md`, `references/js-api.md` |

## 按失败现象读取

| 现象 | 读取文件 |
|---|---|
| 返回 verification_required / webview_mode_enabled | `references/verification.md` |
| 选择器无结果、字段为空或错位 | `references/basics.md` |
| 搜索无结果、乱码、分页或 URL 参数异常 | `references/basics.md`, `references/troubleshoot.md` |
| 浏览器有内容但普通请求拿不到 | `references/troubleshoot.md`, `references/webjs.md` |
| 403、验证盾、跳转、UA、Cookie | `references/verification.md`, `references/troubleshoot.md` |
| JS 报错、Rhino 兼容、java.* 用法 | `references/basics.md`, `references/js-api.md` |
| 发现页 JSON、分类和按钮布局 | `references/discovery.md` |
| 漫画图片不显示、403、懒加载或解密 | `references/comic.md`, `references/troubleshoot.md` |

## 领域知识库

技能参考之外的专题文档在知识库，用 `search_knowledge(关键词)` 找命中片段，再 `read_knowledge(path, offset, limit)` 分页读全文：

- 验证码识别与处理：`knowledge/图文验证码.md`
- 正文乱码 / 编码：`knowledge/Legado书源编码处理指南.md`
- CSS 选择器全集：`knowledge/css选择器规则.txt`
- 输出格式约束：`knowledge/书源输出模板_严格模式.md`
- 真实书源写法合集：`knowledge/真实书源模板库.txt`

## 探针原则

在改规则前，用快照上的探针输出最小证据：

- 响应码和最终 URL
- HTML 长度与关键片段
- 候选选择器匹配数量
- 第一个节点的文本、属性和链接
- JavaScript 的输入值、输出值和异常

需要网络级证据时：开启 HTTP 日志 → 复现一次 → 读取该次请求详情。不要批量复现制造噪音。

## 每阶段记录

用 `update_context(contextId, notes)` 保存：

- 当前阶段
- 本轮读取的参考文件
- 测试入口与预期值
- 修改字段
- `debug_source` 结果摘要
- 下一步只处理哪个字段或阶段
