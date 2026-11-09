# 隔离验证页的网络边界

## 适用范围

此边界保护主控制台打开的外站验证 WebView，不改写阅读书源、不修改 73 个已鉴权网络 MCP 工具的注册表。主控制台只加载 APK 内 `assets/web/`，外站验证页不安装特权 JS bridge。

## 连接级限制

仅预解析 DNS 再返回 `null` 给 Chromium 不足以防护私网：两次 DNS 解析可能不一致，子资源重定向也不保证再次进入 `shouldInterceptRequest`。

验证页打开前，`WebProxyBoundary` 等待 AndroidX `ProxyController.setProxyOverride` 的应用完成回调，再创建外站 WebView。代理仅监听设备回环随机端口；配置调用 `removeImplicitRules()`，没有 DIRECT 回退或私网 bypass。

`PinnedPublicConnection` 一次解析、拒绝任何混合私网答案，再使用通过检查的 `InetAddress` 创建实际连接，连接阶段不再次按域名解析。HTTP 每连接只转发一条请求；HTTPS 使用 CONNECT 字节隧道，不替换证书、不解密 TLS、不关闭 WebView 证书校验。重定向产生的新连接仍须经过同一限制。代理有并发/头部/请求体/超时上限；错误不记录地址、头或凭据。

## 进程级影响与恢复

Android 的代理覆盖作用于当前应用进程的全部 WebView，不能假装它仅影响一个 View。因此打开隔离验证页会关闭持续的阅读网页会话；验证期间 `reader_web_page` 明确返回 `VERIFICATION_ACTIVE`，关闭验证页后可重新打开。原生 HTTP/WebSocket 阅读同步工具不受代理影响。**不会把已保存的阅读私网 origin 放到 bypass 列表**，否则外站页面也可能访问它。

关闭时撤销在途打开请求，并由唯一所有权状态机保留占用直到代理 acquire 完成；重新恢复的打开协程必须检查当前 owner 及取消状态。所有构建后异常与正常关闭共用回滚：停止/分离/销毁分别尝试，只在确认外站 WebView 销毁后归还代理租约。销毁失败保留 View 与隔离，不恢复直连。代理清理失败同样保留受限状态并提示重启。系统 WebView 不支持代理覆盖时明确要求升级，不静默放行。

## 证据边界

- 单测覆盖固定实际 IP、混合/后续私网答案拒绝、HTTP 正文保留、代理凭据不转发。
- Android 设备验收与独立审查须单列，不能用以上单测推断所有 WebView 版本均安全。
- `verificationNetworkAllowed` 的预解析辅助判断不是实际建连安全证明；实际路径由上述代理负责。
- 不承诺任意网站登录、验证码或风控都能通过；不绕过付费/登录/人机验证。
