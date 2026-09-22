# Legado 书源编码处理指南

## 概述

在开发 Legado 书源时，编码问题是一个常见且重要的技术细节。特别是许多中文老网站使用 GBK 编码，如果不正确处理，会导致内容乱码或请求失败。

## 常见编码类型

- **UTF-8**: 默认编码，可省略 charset 参数
- **GBK**: 常见于中文老网站
- **GB2312**: GBK 的子集，中文老网站常见
- **GB18030**: 更完整的中文编码标准

## 如何判断网站编码

### 1. 查看 HTTP 响应头

在浏览器开发者工具（F12）中：
- 切换到 **Network** 标签
- 找到搜索或内容请求
- 查看 **Response Headers** 中的 `Content-Type`

示例：
```
Content-Type: text/html; charset=GBK
```

### 2. 查看 HTML meta 标签

```html
<meta charset="GBK" />
<!-- 或 -->
<meta http-equiv="Content-Type" content="text/html; charset=GBK">
```

### 3. 乱码现象

- 如果不指定 `charset`，中文显示为乱码（如 "ÎÒ°®ãá"），说明网站使用非 UTF-8 编码
- 常见于小说、资讯类老网站

## 编码配置方法

### 方式一：简单配置（推荐）

在 `searchUrl`、`bookInfoUrl` 等请求字段中，使用 JSON 对象配置：

```json
{
  "searchUrl": "/modules/article/search.php,{\"method\":\"POST\",\"body\":\"searchkey={{key}}&searchtype=all\",\"charset\":\"gbk\"}"
}
```

**要点**：
- 使用逗号分隔 URL 和 JSON 配置
- `charset` 字段指定编码格式
- 对于 GBK，大小写均可（`gbk` 或 `GBK`）

### 方式二：高级配置（JavaScript）

对于复杂场景，使用 JavaScript 动态构造：

```javascript
@js:
var ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
var headers = {"User-Agent": ua};
var body = "keyword=" + String(key) + "&page=" + String(page);
var option = {
  "charset": "gbk",
  "method": "POST",
  "body": String(body),
  "headers": headers
};
"https://www.example.com/search," + JSON.stringify(option)
```

**要点**：
- `body` 必须用 `String()` 强转类型
- 使用 `JSON.stringify()` 序列化 option 对象
- option 对象与 URL 之间用逗号分隔

## 编码转换工具

### UTF-8 → GBK 字节转换（无 `java.utf8ToGbk`，该 API 不存在）

阅读官方没有 `java.utf8ToGbk`（写了必报 `not implements`）。需要把 UTF-8 字符串转成 GBK 字节时，用标准两件套的字节路径：

```javascript
// UTF-8 → GBK 字节数组（java.strToBytes 支持 charset 参数）
var gbkBytes = java.strToBytes(str, "GBK");
```

**使用场景**：
- 需要将 UTF-8 字符串转换为 GBK 编码后发送给网站
- 网站 POST 请求体需要 GBK 编码（GBK 字节直接拼接进 body）

### java.encodeURI

对字符串进行 URI 编码，可指定编码格式。

```javascript
// 使用 UTF-8 编码（默认）
java.encodeURI(str: String)

// 指定编码格式
java.encodeURI(str: String, enc: String)
```

**使用场景**：
- URL 参数需要编码时
- 网站要求特定编码格式的 URL 参数

**示例**：
```javascript
// GBK 编码
java.encodeURI('你好', 'GBK')

// UTF-8 编码
java.encodeURI('你好', 'UTF-8')
```

## 常见问题解决

### 1. 乱码问题

**现象**：中文显示为乱码

**原因**：未指定 `charset` 参数或编码设置错误

**解决方法**：添加 `"charset": "gbk"`

```json
{
  "searchUrl": "/search.php,{\"charset\":\"gbk\",\"method\":\"POST\",\"body\":\"keyword={{key}}\"}"
}
```

### 2. POST 请求乱码

**现象**：POST 提交的中文参数在服务器端乱码

**原因**：body 编码未指定

**解决方法**：
- 方式一：在 option 中指定 `charset`
- 方式二：使用 `java.encodeURI` 编码参数

```javascript
// 方式一
var option = {
  "charset": "gbk",
  "method": "POST",
  "body": String(body)
};

// 方式二
var encodedKeyword = java.encodeURI(key, 'GBK');
var body = "keyword=" + encodedKeyword + "&page=" + page;
```

### 3. GET 请求乱码

**现象**：GET 请求参数乱码

**原因**：URL 参数编码格式不正确

**解决方法**：使用 `java.encodeURI` 编码参数

```javascript
// GBK 编码
var encodedKey = java.encodeURI(key, 'GBK');
var url = "/search.php?keyword=" + encodedKey + "&page=" + page;
```

## 完整示例

### 示例 1：69书吧（Default 规则）

```json
{
  "bookSourceName": "69书吧",
  "bookSourceUrl": "https://www.69shuba.com",
  "bookSourceType": 0,
  "searchUrl": "/modules/article/search.php,{\"method\":\"POST\",\"body\":\"searchkey={{key}}&searchtype=all\",\"charset\":\"gbk\"}",
  "ruleSearch": {
    "bookList": "class.newbox@tag.li",
    "name": "tag.a.0@text",
    "author": "tag.span.-1@text##.*：",
    "bookUrl": "tag.a.0@href",
    "coverUrl": "tag.img@src"
  }
}
```

### 示例 2：复杂 POST（JavaScript）

```json
{
  "bookSourceName": "示例网站",
  "searchUrl": "@js:\nvar ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36';\nvar headers = {'User-Agent': ua};\nvar body = 'keyword=' + String(key) + '&page=' + String(page);\nvar option = {'charset': 'gbk', 'method': 'POST', 'body': String(body), 'headers': headers};\n'https://www.example.com/search,' + JSON.stringify(option)",
  "ruleSearch": {
    "bookList": "class.result@tag.li",
    "name": "tag.h3@text"
  }
}
```

### 示例 3：GBK URL 编码

```javascript
@js:
var keyword = java.encodeURI(key, 'GBK');
"https://example.com/search?keyword=" + keyword + "&page=" + page
```

## 最佳实践

### 1. 编码检测流程

```
1. 访问网站，打开开发者工具（F12）
2. 切换到 Network 标签
3. 执行搜索或获取内容
4. 查看响应头的 Content-Type
5. 如果包含 charset=GBK，必须在请求中指定 "charset":"gbk"
```

### 2. 编写规则时的检查清单

- [ ] 检查 HTTP 响应头的 Content-Type
- [ ] 检查 HTML meta 标签的 charset
- [ ] 如果是 GBK，添加 `"charset":"gbk"`
- [ ] POST 请求的 body 用 `String()` 包裹
- [ ] URL 参数使用 `java.encodeURI` 编码
- [ ] 测试中文关键字是否正常

### 3. 调试方法

使用日志输出检查编码：

```javascript
@js:
var response = java.ajax(url);
java.log("原始响应: " + response);
// 页面乱码优先用 URL 选项 {"charset":"gbk"} 或在 header 里指定；
// 确需手动转码时用 java.strToBytes(response, "GBK") 拿 GBK 字节
java.log("GBK字节长度: " + java.strToBytes(response, "GBK").length);
```

## 注意事项

1. **charset 参数位置**：
   - 必须在 JSON 对象中，用逗号与 URL 分隔
   - 错误示例：`/search.php?charset=gbk` ❌
   - 正确示例：`/search.php,{"charset":"gbk"}` ✅

2. **大小写问题**：
   - `"charset":"gbk"` 和 `"charset":"GBK"` 都可以
   - 推荐使用小写 `"gbk"` 以保持一致性

3. **编码转换顺序**：
   - 需要 GBK 字节时用 `java.strToBytes(str, "GBK")`（没有 `java.utf8ToGbk`）
   - 需要 URL 编码用 `java.encodeURI(str, "GBK")`
   - 两者的顺序很重要，根据实际情况选择

4. **字符集兼容性**：
   - GBK 是 GB2312 的超集，兼容 GB2312
   - 如果网站使用 GB2312，指定 `charset="gbk"` 也可以正常工作

## 常见编码速查表

| 编码 | charset 值 | 适用场景 |
|------|------------|----------|
| UTF-8 | 可省略 | 现代网站（推荐） |
| GBK | "gbk" | 中文老网站 |
| GB2312 | "gbk" | 中文老网站（GBK 子集） |
| GB18030 | "gbk" | 完整中文标准（兼容 GBK） |

## 总结

编码处理是 Legado 书源开发的基础技能：

- **优先判断**：通过响应头或 HTML meta 标签判断编码
- **正确配置**：使用 `"charset":"gbk"` 指定编码
- **工具辅助**：用 `java.strToBytes(str, "GBK")` 和 `java.encodeURI(str, "GBK")` 处理特殊情况（不存在 `java.utf8ToGbk`）
- **充分测试**：用中文关键字测试，确保无乱码

掌握这些技巧，可以轻松应对各种编码场景，让书源更加稳定可靠。
