# 第三方搜索逆向实战：rrssk 引擎 + 书友社 AES 目录（2026-09-21 实测）

> 案例：主站 www.shuyous.com 不带搜索，搜索外包给 www.rrssk.com；主站目录页由 AES 加密 AJAX 驱动。
> 全部结论经 volc 云机 + 用户真机日志双重验证。适用于「搜索外包型」小说站与「加密 AJAX 目录」站。

## 0. 复用铁律（先读这条）

**凡是搜索走 rrssk（主站搜索框跳外站）的书源，一律以本文书友社成品为模板骨架起步**，不要在未知站点上从零试错——同类问题已反复翻车。起步后只按第 8 节清单逐项替换站点差异。

模板已内置且必须原样继承的部分：

- 搜索四步链 @js（`00F5.html` → signJs 伪装 script 加载取 `__snc` → 本地签名 → POST search → 跟随 302 到 find 页）；
- 会话自管：`source.getVariable()/setVariable()` JSON 状态（按关键词隔离 + TTL），配 `enabledCookieJar:false`；
- Step② 的 script 加载伪装头三件套（`referer=00F5.html` / `sec-fetch-dest:script` / `sec-fetch-mode:no-cors`）；
- 翻页只改 find URL 的 `page`，复用同次链路的 q + PHPSESSID + `__snc`；
- 签名脚本诱饵常量防护（锚点用 `!=="number"` 与 `(s,t,ts);`，不全文档搜数字常量）。

边界：书友社是**实战模板**，不是官方标准；规则语义争议仍以 `knowledge/legado-rules-map.md` 与内置语料共识为准（见 `skills/legado-book-source/references/generation-contract.md` 零节铁律）。换站点时只重取证第 8 节清单里的差异项，已继承的链路结构不要推倒重写。

## 1. 场景识别

- 主站搜索框 `onclick` 直接 `window.location` 跳外站 → 搜索外包；
- 外站返回卡片，`onclick` 里 `?action=go&t=<base64>` 藏着主站真实书址（t 先 URL-decode 再 base64-decode）；
- 主站目录页分页 select 是 `javascript:;` → 目录走 AJAX，无静态分页页。

## 2. 搜索四步链（rrssk）

```
① GET  /00F5.html            → PHPSESSID + hidden input[name=_t]（一次性令牌）+ signJs 地址
② GET  /?action=signJs&v=..  → 当日签名算法源码 + __snc 验证 Cookie   ★最易漏
③ 本地算 _s = f(enc(kw), _t, ts)
④ POST /?action=search       → 302 → Location: /?action=find&v=16&q=<base64>&page=1
   GET find URL（同 cookie）  → 200 + 20 张卡片/页
```

### 关键坑（按隐蔽程度排序）

1. **Step② 必须伪装成「页面里的 script 加载」**：`referer=00F5.html` + `sec-fetch-dest:script` + `sec-fetch-mode:no-cors` + `sec-fetch-site:same-origin`。用普通 GET 头服务端**不下发 `__snc`**，Step④ 必 403，且不报错无提示。
2. **POST 恒 302**：结果不在 POST 响应里，在 `Location` 的 find URL 里；POST body/查询串带 `page` 均被忽略，翻页只能改 find URL 的 `page`。
3. **q 每次随机化且绑定会话**：同关键词两次搜索 q 不同；旧 q 直接 GET find 得 403。翻页必须复用同次链路的 q + PHPSESSID + __snc。
4. **超范围 page 返回 200 空页（0 卡片，不钳位）**：Legado 翻页机制遇空列表自然停止，勿自己判断末页；page=0 等同 page=1。
5. **签名脚本有诱饵常量**（`__SIGN_DECOY`）：提取锚点用 `!=="number"`（反查指纹变量+门限）和 `(s,t,ts);`（反查函数名），别全文档搜数字常量。
6. **Set-Cookie 要看全**：`__snc` 和 PHPSESSID 同在 Step② 响应里，只取第一个就漏。
7. 服务端只校验 `_s` 值：本地以 FP=35 复算即得合法签名（手势验证 G 只是防 Console 手算，不传输）；动态提取失败可回退写死常量，当日仍可用。

## 3. Legado 落地要点（均有源仓库统计支撑）

- `java.post(url, body, headers)` 3 参签名（~350 源先例）；`java.connect(url, headers)` 返回 StrResponse；
- @js searchUrl 作用域可直接读 `page` 变量（~774 源先例）；返回串里 `{{page}}` 也会被 App 替换（~197 源先例）——**双机制并存，任选其一，别混用**；
- 搜索分页 `{{page}}` 机制 ~3118/16724 源在用，是主流；
- `source.getVariable()/setVariable()` 是**单字符串**持久化：JSON 序列化后自管会话状态（按关键词隔离 + TTL，本案例 20 分钟）；
- searchUrl @js 返回 `url,{headers:{...cookie}}` 可让 App 请求自带 cookie；配 `enabledCookieJar:false` 会话完全自管；
- chapterList @js 返回 `<a href="绝对URL">name</a>` 字符串数组（~136 源先例），配 `chapterName:a@text` / `chapterUrl:a@href`；
- `book.bookUrl` 可在 toc @js 用（~88 源先例）；
- **exploreUrl 字段是字符串**（内含 JSON 数组 `[{"title":..,"url":..,"style":null}]`），构建时勿直接输出 JS 数组。

## 4. 主站 AES 加密 AJAX 目录（书友社）

契约（真机日志验证）：

```
POST /index.php?action=loadChapterPage
headers: content-type=x-www-form-urlencoded; referer=/book/<aid>.html;
         x-requested-with=XMLHttpRequest; 移动 wv UA   （无需 cookie）
body: data=<urlencode(base64(AES-256-CBC(key32, iv16, JSON.stringify({id:aid,page:N}))))>
响应: {"code":0,"msg":"Success","data":[{chapterid,articlename,chaptername,
      chapterorder,chapterurl:"/book/<aid>-<N>.html"}...]} 每页 100 章
```

落地套路：

- **纯 JS AES-256-CBC 实现**（Rhino ES5：var、无箭头/模板字符串；PKCS7；固定 IV 时确定性加密）；
- 正确性验证：对真机抓到的密文做**逐字节比对**（同 plaintext+key+iv → 同 base64），比 round-trip 自校更强；
- **key/iv 动态提取**：先试已知 JS 路径，失败再从详情页枚举 `templates/js/*.js` 找特征串（如 `AESCrypt`），6h 缓存 + 写死兜底并记标志位；
- **循环终止三保险**：`chapterorder` 回退检测（服务端超范围会钳位重复返回末页）+ 不满 100 章终止 + 页数封顶；
- **失败分级**：单页失败忙等重试 1 次；首页失败 → 回退解析详情页 HTML（正则提全章链接按章号去重升序 + 提示章 + 不缓存）；已抓部分中途失败 → 保留 + 提示章 + 不缓存；
- 缓存 30 分钟（store 只存一本书），重复进入 0 请求。

## 5. 环境陷阱：机房 IP 应用层封锁（本案例最耗时发现）

- 现象：**同一密文同一组头**，手机 Carrier IP → 200，云机房 IP → 403 `{"code":-1}`；
- 已排除（均试过仍 403）：HTTP/2、curl_cffi Chrome/Safari TLS 指纹（JA3）、补 Origin/sec-fetch-*、去 x-requested-with、cookie jar 预种、同 IP HTML GET（200，仅该 AJAX 端点被封）；
- 同一响应体有两种成因勿混淆：**body 不是 `data=<密文>`**（明文/GET 必挂）和 **IP 类型不符**。真机日志同晚先 403（明文 body）后 200（密文 body）可作区分参照；
- 工程结论：书源跑在手机即可用；**服务器侧验证此类站点需要 Carrier IP**，否则以「真机日志夹具 + 本地单测」补足验证；
- 设计惯例：AJAX 首选 + HTML 回退降级，保证任何网络环境下目录不为空。

## 6. 测试方法论（node 桩 + 真机夹具）

- `execFileSync(curl)` 同步桩模拟 Legado 的 java/source/book/key/page，App 请求单独模拟（合并全局 header）；
- 请求计数断言：验证缓存命中不发请求、翻页只 1 次 App GET、换词重走链；
- **真机日志当夹具**：成功响应体存文件，单测解析函数（章数/首末章序/`\uXXXX` 转义/被拒体 ok=false/截断体抛错被捕获）；
- 密文向量单测：与真机请求体逐字节比对；
- 环境感知断言：同一份 harness 在机房 IP 验证回退分支、在手机网络验证全量分支（用回退标志自动切换）；
- 自检清单：JSON 合法、必填字段非空、exploreUrl 是字符串且可解析、@js 字段含占位替换结果。

## 7. 排查方法论（通用）

1. 差分法：一次只改一个变量（头/参数/cookie），对比响应码与体；
2. 400 vs 403 有信息量：400=缺字段，403=任一校验失败，可推断校验顺序；
3. 想确认浏览器真实请求：桩环境 eval 站点 JS，hook `$.post/$.ajax` 打印真实 URL 与 data；
4. 混淆常量表 dump：`var X={...}` → `var X=__G.X={...}` 再 eval，打印全部明文键值；
5. 时间戳类签名必须「同一次运行内两种实现互校」，跨运行对比必不同；
6. 403 先查 IP 类型再查契约——机房 IP 嫌疑优先用 TLS 指纹/头变体批量排除。

## 8. 换站复用清单（rrssk 联盟新站点）

以书友社模板为骨架，逐项重新取证，不要照抄域名了事：

1. `00F5.html` 等入口路径与 hidden input 名（`_t` 是否同名）；
2. `action=signJs` 的调用方式与返回脚本的两个锚点是否仍存在（诱饵常量会变）；
3. find URL 的 action 名与参数名（`action=find&v=..&q=..&page=..` 是否同构）；
4. 结果卡片选择器（每站不同，必须 `analyze_html` 实测，禁止照抄书友社选择器）；
5. 卡片到主站书址的跳参（`?action=go&t=<base64>` 是否同构，t 的解码顺序是否变化）；
6. 机房 IP 是否被封（先用手机网验证全量链路，再谈云机复现；被封则以真机日志夹具 + 本地单测补足）；
7. 主站目录是否也是加密 AJAX（`loadChapterPage` 契约是否同构，key/iv 是否需要重新提取）。

全部差异项取证完成后按 `check_source(refresh=true)` 验收；搜索烟雾测试用与开发样本不同的关键词。
