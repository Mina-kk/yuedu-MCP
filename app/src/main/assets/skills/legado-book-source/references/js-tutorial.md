# 阅读书源 JS 入门教程

> 基于 Legado(开源阅读) 源码与 134 个真实书源整理，所有 API 均来自官方 JS 扩展类文档。
> 适合不会 JS 的新手：先抄模板，再学着改。

---

## 一、JS 写在书源的哪里？

三种写法，效果一样，选顺手的：

**1. 内嵌块（最常用）**
规则里包一段 `<js> ... </js>`，块里最后一行就是返回值：

```
目录列表: <js>result.reversed()</js>
```

**2. @js: 前缀**
整个字段以 `@js:` 开头，后面全是 JS：

```
chapterList: @js:
let d = JSON.parse(result).data.list;
d
```

**3. 纯 JS 规则**
字段值本身就是一段 JS（多行脚本场景），同样以返回值/最后一行为结果。

> 记住一条：**块里最后一行（或 return 的东西）就是这条规则的结果**。

---

## 二、能用哪些变量？

| 变量 | 是什么 | 常见用途 |
|------|--------|----------|
| `result` | 上一步的抓取结果（字符串/列表/JSON都有可能） | 处理刚抓到的页面或数据 |
| `baseUrl` | 当前页面地址 | 拼接相对链接 |
| `book` | 书籍信息对象 | `book.bookUrl` 取书名页地址 |
| `chapter` | 当前章节对象 | 正文规则里取章节信息 |
| `src` | 图片原始地址 | `imageDecode` 里解析图片 |

列表类规则（目录列表/搜索列表）最后要**返回数组**；字段类规则返回**字符串**。

---

## 三、最常用的三板斧（先会这三个）

### 1. 倒序（目录是反的）

```
<js>result.reversed()</js>
```

不想写 JS？装好书后在目录界面点右上角排序按钮，App 自带正序/倒序切换。

### 2. 按章节号排序（乱序目录）

乱序多半是站点把章节列表打乱了，要按"章节数字"排，不能按字符串排：

```
@js:
result.sort(function(a, b) {
    return toNumChapter(a.chapterUrl) - toNumChapter(b.chapterUrl);
})
```

按标题排（标题里带数字时）：

```
@js:
result.sort(function(a, b) {
    return a.title.match(/\d+/) - b.title.match(/\d+/);
})
```

> 坑：字符串排序会把 `第10章` 排到 `第2章` 前面，必须转数字再比。

### 3. 筛选掉广告章节

```
@js:
result.filter(function(item) {
    return !/广告|APP|下载/.test(item.title);
})
```

---

## 四、网络请求类 API（在 JS 里发请求）

| 方法 | 用法 | 说明 |
|------|------|------|
| `ajax(url)` | `java.ajax(url)` | GET，返回响应体字符串 |
| `ajaxAll(urlList)` | `java.ajaxAll([...])` | 并发请求多个地址 |
| `get(url, headers)` | `java.get(url, {...})` | GET，可带请求头，拦截重定向 |
| `post(url, body, headers)` | `java.post(url, body, {...})` | POST 请求 |
| `connect(url)` | `java.connect(url)` | 返回 StrResponse 对象，可拿状态码 |
| `webView(html, url, js)` | `java.webView(html, url, js)` | 需要浏览器渲染的页面用它 |

示例：在目录页抓完再补一次请求拿真实目录

```
@js:
let html = java.ajax(baseUrl + "/allchapter");
html
```

带请求头：

```
@js:
let h = {"Referer": "https://example.com"};
java.post("https://example.com/api/chapters", "bookId=" + book.getVariable("id"), h)
```

---

## 五、字符串与编码类

| 方法 | 用法 | 场景 |
|------|------|------|
| `base64Encode(str)` / `base64Decode(str)` | `java.base64Encode(s)` | 接口参数加解密 |
| `hexDecodeToString(hex)` | `java.hexDecodeToString(h)` | 十六进制转文字 |
| `strToBytes(s, charset)` | — | 转字节数组 |
| `utf8ToGbk(str)` | `java.utf8ToGbk(s)` | 站点编码不对时转码 |
| `encodeURI(str, enc)` | `java.encodeURI(s, "GBK")` | 搜索词是中文时先编码 |
| `t2s(text)` / `s2t(text)` | 繁简互转 | 繁体站转简体 |
| `timeFormat(time)` | 时间戳格式化 | 处理更新时间 |
| `htmlFormat(str)` | 格式化 HTML 保留图片 | 正文整理 |

搜索词编码实例（GBK 站点搜"剑"）：

```
searchUrl: https://example.com/search?key={{encodeURI(key, "GBK")}}
```

---

## 六、文件操作类

| 方法 | 用途 |
|------|------|
| `importScript(path)` | 导入网络/本地 JS 脚本，复用公共函数 |
| `cacheFile(url, seconds)` | 缓存远程 JS/TXT，减少重复请求 |
| `downloadFile(url)` | 下载文件 |
| `readTxtFile(path, charset)` | 读本地文本 |
| `getTxtInFolder(path)` | 读整个文件夹的文本并拼接（本地书） |
| `unzipFile/un7zFile/unrarFile(path)` | 解压对应格式压缩包 |
| `getZipStringContent(url, path)` | 直接读 zip 里的某个文件内容 |

---

## 七、字体与正文处理（进阶）

- `queryTTF(data)`：解析字体文件（反爬站点常用字体混淆），配合 `replaceFont(text, 错字体, 正字体)` 还原文字
- `htmlFormat(str)`：正文 HTML 格式化
- `toNumChapter(s)`：把"第一章/Chapter 1"这类标题转成数字，配排序用

---

## 八、调试三件套（写不对先打日志）

```
@js:
log(result);          // 输出到订阅源日志
longToast("走到这里了"); // 屏幕弹窗，最直观
result                 // 别忘了返回
```

调试顺序建议：先 `longToast` 确认 JS 执行了，再 `log` 看数据长啥样，最后才改规则。

---

## 九、完整小例子：倒序 + 去广告目录

```
目录列表:
@js:
let list = result;            // 已抓到的章节列表
list = list.reverse();        // 倒序
list = list.filter(function(it) {
    return !/最新章节|广告/.test(it.title);
});
list
```

---

## 十、常见坑

1. **忘写返回值**：JS 块最后一行必须是结果，写了 `let list = ...` 就结束是不返回的
2. **字符串比较章节数字**：必须 `parseInt` / `match(/\d+/)` 转数字
3. **编码没设**：GBK 站点请求/编码都要带 charset，`utf8ToGbk` 救急
4. **拿不到数据先怀疑要登录/要 Referer**：加 headers 再试
5. **改完规则不生效**：书源详情页点"调试"，从搜索一路跑一遍看日志

---

*整理：童零（基于 legado-book-source-developer 技能库）*
