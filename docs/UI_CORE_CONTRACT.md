# Rust 前端领域核契约

路径 `ui-core/`，crate `studio-ui-core`，依赖 `serde`, `serde_json`, `wasm-bindgen`（版本以本机缓存/可用版本锁定）；cdylib + rlib。JavaScript 输出只用 JSON string，避免非可移植 JS object 边界。

它不是沙箱规则执行器。不要用 Rust 简陋重写 Legado DSL。它承担真实前端领域功能，Vue 必须导入并调用构建出的 WASM：

## 导出函数

- `analyze_source_json(text: &str) -> String`：返回 `{valid,sourceCount,sources:[{bookSourceUrl,bookSourceName,bookSourceType,hasSearch,hasExplore,hasToc,hasContent}],issues:[{path,code,message}],formatted?}`。允许单对象或数组。检查 JSON 可解析且顶层对象/数组、各项书源 URL/name 类型；不能因为空搜索/缺某阶段判不可保存草稿，不做网络通过结论。不修改字段、不删除未知字段。formatted 保留所有 JSON 数据（serde_json map 排序不影响语义）。拒绝重复关键 JSON 字段（至少 bookSourceUrl/name 和嵌套规则关键路径），不悄悄 last-wins。书源 URL 身份逐字保持，不折叠 `/`、`#`、查询、端口。最大 2M 字符（具体统一 UTF-8 字节，前端文案说 MB），超限返回 issue，不 panic。
- `compare_source_json(left: &str,right: &str) -> String`：返回 `{equal,changedTopLevelFields,identityChanged}`，完整 JSON structural equality；数字1与1.0可保守不同；未知字段也比较，不能泄露秘密值到摘要。身份必须比较完整 bookSourceUrl。输入非法返回结构化 error。
- `summarize_evidence(text: &str) -> String`：输入 runtime 真实 JSON 输出，返回 `{scope,status,label,tone,passed,notes}`。保守：VALIDATED=static，sandbox verdict full 不算 reader passed；machineVerdict unverifiable 不算 passed；reader_fields_unconfirmed/conflict/stale_state/session_replaced/session_unconfirmed/timeout 绝不通过。只对服务端明确 reader_verified 或 readerSidePassed=true + identityMatches/confirmed 且无失败标 reader passed。不推断没有字段的情况。
- `filter_sources(items_json: &str,query: &str) -> String`：保留所有条目，仅在摘要 bookSourceName/name/bookSourceUrl/siteUrl 子串筛选（Unicode lower），不合并/丢同 URL 版本；返回 `{items,total}`。
- `core_version() -> String`：`studio-ui-core/2.0.0`。

## 构建

`cargo test --manifest-path ui-core/Cargo.toml`
`cargo build --manifest-path ui-core/Cargo.toml --target wasm32-unknown-unknown --release`
`wasm-bindgen --target web --out-dir web-ui/src/wasm ui-core/target/wasm32-unknown-unknown/release/studio_ui_core.wasm`

worker 写 `ui-core/build-web.sh` 供父集成；不能写 web-ui 源码（build 生成产物允许），必须锁依赖 Cargo.lock。不读取 apikey-vault；它稍后作为主题参考。

## 回归

TDD逐行为：JSON语法错误、空数组、未知字段保留、同 URL 多版本不合并、尾斜杠/#标签/默认端口不归并、深层差异、异常证据保守分级、Unicode检索、过大输入、duplicate keys；实际 cargo test、WASM build 和 JS 或浏览器调用 smoke，不能只写 crate 无人调用就声称完成集成。
