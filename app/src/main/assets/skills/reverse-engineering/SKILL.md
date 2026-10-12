---
name: reverse-engineering
description: 对目标做逆向取证时使用——含 JS/Web 加密接口、APP 行为、抓包/运行时证据、加密参数还原；面向书源、协议复现、行为解释。融合 REA 证据方法论与 js-reverse 实战体系。
---

# 逆向取证（REA × js-reverse）

对**没有源码**的目标（加密站点、APP、二进制、协议）做逆向时按本技能走。两条主线索：**REA 的证据语义纪律**（结论必须分级、挂证据、标未知）+ **js-reverse 的实战打法**（抓包→静态→动态→复现）。书源制作是逆向结论的最终载体之一。

## 证据纪律（REA 方法论，最高优先级）

逆向中所有结论按四级标注，**结论强度不得超过证据强度**：

| 级别 | 含义 | 书源/逆向对应 |
|---|---|---|
| `observation` 观察 | 直接测到的事实 | 抓包看到的请求/响应、fetch_page 快照、调试器读到的值 |
| `derivation` 推导 | 由观察严格推出 | 从密文样本推出拼接顺序、从两处参数变化推出签名绑定 |
| `inference` 推断 | 合理假设待证 | "应该是 AES-GCM"、"大概绑 session"——未复现验证前都属此类 |
| `unknown` 未知 | 没证据支撑 | 未验证的阶段、反爬墙后、需要的运行环境缺失 |

**铁律**：
- **静态 ≠ 运行**。看代码推出"它会加密"是 inference；只有真发一次请求、拿到真实响应才是 observation。不要用静态分析冒充运行时结果。
- **未知要登记**。没解决的问题写明还需要什么证据/环境（required authority），带支持和反驳的证据 id，不要含糊带过或假装完成。
- **覆盖不全 = 未知**。声明"新增了/没有了"必须双侧完整覆盖，否则报 unknown，不报"不存在"。
- **summary-first**。大结果先取摘要再按 evidence 引用分页，不重复同一调用。
- **证据台账**：每条结论 → 证据ID + 置信度/类型 + 搜索边界 + 剩余未知，后续可复用。

## 四阶段实战流程（js-reverse）

**阶段0 侦察抓包**：定位数据接口、记录参数/头/Cookie、重放测时效、观察对抗信号（控制台打不开=有反调试）。

**阶段1 静态分析**：脚本落盘格式化 → 关键词搜（`encrypt|sign|token|AES|HMAC|nonce|session|signature`）→ 混淆分级（普通可 AST，大数组+解释器=JSVMP 走读字节码路线）。**签名逆向顺序：先逆拼接规则→再确认算法→最后找 salt/key。**

**阶段2 动态分析**：断点/Hook（fetch、XHR、JSON.stringify、btoa、charCodeAt、加密库）抓"明文—密文"配对；提前注入须先于页面脚本；换通道优先于硬改。

**阶段3 复现验证**：标准库直接复现 / 模拟执行 / 借页面环境 RPC 调用；用脚本自算并对照真实响应逐段 diff（编码、字节序、填充、拼接顺序是最常见偏差）。

## 路线选择（按成本）

1. **借浏览器之手**（最省）：强环境检测/反调试/VMP 时，让真实页面自己算，只取结果（WebView、CDP、startBrowserAwait）。
2. **协议复现**（最彻底）：算法标准、常量可抠时脚本直连，追求免浏览器。
3. **判断防线在哪层再动手**：不要在错误层硬刚（本地伪造 TLS/指纹被服务端直连校验堵=白做）。

## 参考

- `references/reverse-engineer-anything__SKILL.md`：REA 完整方法论（目标路由、证据模型、工具选型、调查计划）。
- `references/reverse-engineer-anything__references__*.md`：REA 分领域指南（JS/Electron、Android、native/artifact、运行时观察、证据工作流）。
- `references/rea-tool-design__SKILL.md`：REA 工具契约设计（改 MCP 工具时参考）。
- 书源实战案例：见 `knowledge/` 的 `js-reverse-s0-methodology.md`、`js-reverse-s2-basic.md`、`js-reverse-s2-advanced.md`、`js-reverse-s2-booksource.md`、`JS挑战与动态搜索避坑实战-阿里书屋.md`，以及技能 `legado-book-source` 的 `references/evidence-methodology.md`（书源调试语境的证据分级落地）。

## 与本 App 的衔接

逆向结论最终要落成 `legado-book-source` 技能产出的书源 JSON：搜索/详情/目录/正文四链路的 `searchUrl`/`@js` 规则 + `jsLib` 里的 `javax.crypto`/`java.webView`/`startBrowserAwait` 实现。加密 API 站点直接参考独立技能包 `booksource-reverse`（zjread.cc 实战）。
