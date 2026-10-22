package com.mina.legadostudio.reader

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mina.legadostudio.domain.LogFilterUtils
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 持续 WebView 会话的纯逻辑：origin 判定、子资源放行、脚本字面量编码、快照解析与上限。
 *
 * 与 android.webkit 无关，JVM 单测直接覆盖；[ReaderWebUiSession] 只负责把这些判定落到 WebView 上。
 *
 * 安全边界：
 * - 主导航/重定向只允许与已保存的 Web 服务 **严格同 scheme + host + port**；
 * - 子资源允许公网（阅读页面可能引用 CDN），但跨 origin 的回环/私网地址一律阻断；
 *   同 origin 的子资源始终允许——用户保存的阅读服务本身可能就在局域网里；
 * - 模型只能传 selector / 文本 / 滚动量，所有脚本都是本对象拼出的固定脚本，
 *   用户输入通过 [jsStringLiteral] 变成 JS 字面量，不存在把模型文本当脚本执行的路径；
 * - 快照只回显同源 path（去掉携带令牌的查询参数），不回显 host；敏感输入框的值不回显。
 */
object ReaderWebUiPolicy {

    /** 单次滚动请求的绝对值上限（正负都限），防止一次把长列表甩到底。 */
    const val MAX_SCROLL_DELTA = 20_000

    /** 单次填充的文本上限。 */
    const val MAX_FILL_TEXT_CHARS = 20_000

    /** 快照体积上限。JS 侧先截断，解析侧再按同一组上限兜底，避免自定义页面绕过注入脚本。 */
    data class Limits(
        val maxTitleChars: Int = 200,
        val maxPathChars: Int = 400,
        val maxTextChars: Int = 12_000,
        val maxElements: Int = 80,
        val maxElementTextChars: Int = 160,
        val maxSelectorChars: Int = 400,
        val maxHrefChars: Int = 512,
        val maxValueChars: Int = 120,
    )

    /** 规范化后的目标。`port` 已按 scheme 补默认端口，因此 http/https 与显式 80/443 视为同一 origin。 */
    data class Origin(val scheme: String, val host: String, val port: Int) {

        /** 唯一入口 URL：`scheme://authority/`，不带路径、参数、片段。 */
        fun rootUrl(): String = buildString {
            append(scheme).append("://")
            if (host.contains(':')) append('[').append(host).append(']') else append(host)
            val defaultPort = if (scheme == "https") 443 else 80
            if (port != defaultPort) append(':').append(port)
            append('/')
        }

        companion object {
            /** 已保存配置的 origin（接受 `http(s)://主机[:端口]`，与 ReaderWebOrigin 同口径）。 */
            fun fromOriginString(raw: String?): Origin? =
                ReaderWebOrigin.normalizeOrNull(raw)?.let(::fromUrl)

            /** 任意 http(s) URL 的 origin；其它 scheme、带账号密码、无法解析的 URL 返回 null。 */
            fun fromUrl(raw: String?): Origin? {
                val text = raw?.trim().orEmpty()
                if (text.isEmpty()) return null
                val url = text.toHttpUrlOrNull() ?: return null
                if (url.scheme != "http" && url.scheme != "https") return null
                if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
                return Origin(url.scheme, url.host.lowercase(), url.port)
            }
        }
    }

    /** @property code `ALLOW` / `CROSS_ORIGIN` / `FORBIDDEN_SCHEME` / `INVALID_URL` / `PRIVATE_SUBRESOURCE` */
    data class Decision(val allowed: Boolean, val code: String) {
        companion object {
            val ALLOW = Decision(true, "ALLOW")
            fun block(code: String) = Decision(false, code)
        }
    }

    // ---- 会话身份（配置归属） ----

    /**
     * 会话启动时固定的配置身份：规范化 origin + 配置 revision + 令牌指纹。
     *
     * 只用 revision 不够：revision 为 0（旧数据、测试构造）时无从区分；只用 origin 也不够：
     * 同一地址换令牌同样是一次新配置。令牌只保留 SHA-256 指纹，身份对象里不出现明文。
     */
    data class SessionIdentity(
        val origin: Origin,
        val revision: Long,
        val tokenFingerprint: String,
    ) {
        companion object {
            /** 已保存配置的身份；未配置或地址不合法返回 null。 */
            fun of(config: ReaderWebConfigStore.Config?): SessionIdentity? =
                of(config?.baseUrl, config?.revision ?: 0L, config?.token)

            fun of(baseUrl: String?, revision: Long, token: String?): SessionIdentity? {
                val origin = Origin.fromOriginString(baseUrl) ?: return null
                return SessionIdentity(origin, revision, tokenFingerprint(token))
            }

            /** 令牌指纹：SHA-256 十六进制；空令牌为空串（与「无令牌」等价）。 */
            fun tokenFingerprint(token: String?): String {
                val secret = token?.trim().orEmpty()
                if (secret.isEmpty()) return ""
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(secret.toByteArray(Charsets.UTF_8))
                return digest.joinToString("") { "%02x".format(it) }
            }
        }
    }

    /** 身份变化的分类：都会要求销毁旧 WebView，只是给模型的说明不同。 */
    enum class IdentityChange(val code: String, val reason: String) {
        ORIGIN_CHANGED("ORIGIN_CHANGED", "阅读 Web 服务地址已变更"),
        TOKEN_CHANGED("TOKEN_CHANGED", "阅读 Web 服务访问令牌已变更"),
        CONFIG_CHANGED("CONFIG_CHANGED", "阅读 Web 服务配置已更新"),
    }

    /**
     * 固定身份与当前配置身份的差异；null 表示同一身份（可继续复用同一 WebView）。
     *
     * revision 变化也算变化——即使用户改回同样的地址与令牌，revision 不会回退，
     * 旧页面的 SPA 状态仍属于「另一次配置」，不能当作当前配置的观测结果。
     */
    fun identityChange(pinned: SessionIdentity?, current: SessionIdentity?): IdentityChange? {
        if (pinned == null || current == null) return null
        if (pinned.origin != current.origin) return IdentityChange.ORIGIN_CHANGED
        if (pinned.tokenFingerprint != current.tokenFingerprint) return IdentityChange.TOKEN_CHANGED
        if (pinned.revision != current.revision) return IdentityChange.CONFIG_CHANGED
        return null
    }

    // ---- 令牌脱敏 ----

    /** 命中已保存令牌时统一替换成这个标记（与 ReaderWebClient 的错误文本脱敏同口径）。 */
    const val TOKEN_PLACEHOLDER = "[令牌已隐藏]"

    /**
     * 把快照里出现的**已保存真实令牌**替换成 [TOKEN_PLACEHOLDER]，其余内容原样保留。
     * 同时替换 URL 编码形态（path/href 里常见 `%2B`、`%3D` 之类的转义）。
     *
     * 安全优先：1～3 个字符的短令牌同样一律替换。宁可把正常正文里偶然出现的同形串
     * 一起遮盖，也不能让用户设置的短令牌经快照回到模型。空令牌、不在快照里的令牌不改动内容。
     */
    fun redactToken(snapshot: Snapshot, token: String?): Snapshot {
        val secret = token?.trim().orEmpty()
        if (secret.isEmpty()) return snapshot
        var changed = false
        fun redact(value: String): String {
            val out = redactSecrets(value, secret)
            if (out != value) changed = true
            return out
        }
        val redacted = snapshot.copy(
            title = redact(snapshot.title),
            path = redact(snapshot.path),
            text = redact(snapshot.text),
            elements = snapshot.elements.map { element ->
                element.copy(
                    text = redact(element.text),
                    ariaLabel = element.ariaLabel?.let(::redact),
                    placeholder = element.placeholder?.let(::redact),
                    type = element.type?.let(::redact),
                    role = element.role?.let(::redact),
                    name = element.name?.let(::redact),
                    href = element.href?.let(::redact),
                    value = element.value?.let(::redact),
                )
            },
        )
        return if (changed) redacted else snapshot
    }

    /** 单段文本的令牌脱敏；`token` 应为已保存的明文令牌（短令牌同样替换）。 */
    fun redactSecrets(value: String, token: String?): String {
        val secret = token?.trim().orEmpty()
        if (secret.isEmpty() || value.isEmpty()) return value
        var out = value.replace(secret, TOKEN_PLACEHOLDER)
        val encoded = runCatching {
            java.net.URLEncoder.encode(secret, Charsets.UTF_8.name())
        }.getOrNull()
        if (!encoded.isNullOrEmpty() && encoded != secret) out = out.replace(encoded, TOKEN_PLACEHOLDER)
        return out
    }

    // ---- origin / 子资源判定 ----

    /**
     * 主文档导航（含重定向落点）：只放行严格同 scheme + host + port。
     * 其它 scheme（file/content/javascript/data/blob/about…）一律拒绝。
     */
    fun navigationDecision(url: String?, origin: Origin): Decision {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return Decision.block("INVALID_URL")
        val target = Origin.fromUrl(text) ?: return Decision.block(if (isHttpScheme(text)) "INVALID_URL" else "FORBIDDEN_SCHEME")
        return if (target == origin) Decision.ALLOW else Decision.block("CROSS_ORIGIN")
    }

    /**
     * 子资源（脚本/样式/图片/字体/iframe）：
     * - 同 origin：允许，包括局域网/回环上的阅读服务本身；
     * - 跨 origin：只允许公网 http(s)，回环/私网/链路本地/CGNAT 一律阻断（空响应）；
     * - 非 http(s)（file/content/data/javascript/blob…）一律阻断。
     */
    fun subresourceDecision(url: String?, origin: Origin): Decision {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return Decision.block("INVALID_URL")
        val parsed = Origin.fromUrl(text)
        if (parsed == null) return Decision.block(if (isHttpScheme(text)) "INVALID_URL" else "FORBIDDEN_SCHEME")
        if (parsed == origin) return Decision.ALLOW
        return if (LogFilterUtils.isLoopbackOrPrivate(text)) Decision.block("PRIVATE_SUBRESOURCE") else Decision.ALLOW
    }

    private fun isHttpScheme(url: String?): Boolean {
        val text = url?.trim().orEmpty()
        return text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
    }

    // ---- 固定脚本 ----

    /**
     * 任意文本 → 合法的 JS 双引号字面量。
     * 转义引号、反斜杠、控制字符与 U+2028/U+2029（JS 行终止符，未转义会截断字面量），
     * 因此模型传入的 `";...` 之类的文本只会落在字符串里，不能闭合字面量。
     */
    fun jsStringLiteral(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> when {
                    c < ' ' || c.code in 0x7F..0x9F || c == '\u000B' -> sb.append("\\u%04x".format(c.code))
                    else -> sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * selector 的入口校验（空 / 超长 / 含控制字符）；合法返回 null。
     * 语法是否有效由页面 `querySelectorAll` 判定，返回 `INVALID_SELECTOR`。
     */
    fun selectorProblem(selector: String, limits: Limits = Limits()): String? {
        val text = selector.trim()
        if (text.isEmpty()) return "EMPTY_SELECTOR"
        if (text.length > limits.maxSelectorChars) return "SELECTOR_TOO_LONG"
        if (text.any { it < ' ' || it.code in 0x7F..0x9F }) return "CONTROL_CHARS"
        return null
    }

    /** 固定快照脚本：DOM 文本 + 有界可交互元素清单，返回 JSON 字符串。 */
    fun buildSnapshotScript(limits: Limits = Limits()): String = """
(function(){try{
var L={t:${limits.maxTextChars},k:${limits.maxTitleChars},p:${limits.maxPathChars},e:${limits.maxElements},et:${limits.maxElementTextChars},s:${limits.maxSelectorChars},h:${limits.maxHrefChars},v:${limits.maxValueChars}};
var SENS=["token","accesstoken","refreshtoken","idtoken","authtoken","auth","authorization","password","passwd","pwd","secret","clientsecret","apikey","key","sig","signature","session","sessionid","sid","jwt","ticket","credential","credentials","csrftoken","xsrftoken","csrf","state","code"];
function nz(x){return x==null?"":String(x).replace(/\s+/g," ").trim();}
function cl(x,n){var s=nz(x);return s.length>n?s.substring(0,n):s;}
function sensitiveName(name){var seg=String(name||"").toLowerCase().split(/[^a-z0-9]+/);for(var i=0;i<seg.length;i++){if(SENS.indexOf(seg[i])>=0)return true;}return false;}
function echoable(tag,type,name,el){
if(tag!=="input"&&tag!=="textarea")return false;
var t=String(type||"text").toLowerCase();
if(t!=="text"&&t!=="number")return false;
if(sensitiveName(name))return false;
var ac=String(el.getAttribute&&el.getAttribute("autocomplete")||"").toLowerCase();
if(ac.indexOf("password")>=0||ac.indexOf("cc-")===0)return false;
return true;
}
function privateInput(el,tag,type,name){var ac=String(el.getAttribute&&el.getAttribute("autocomplete")||"").toLowerCase();return (tag==="input"||tag==="textarea")&&(type==="password"||type==="hidden"||sensitiveName(name)||ac.indexOf("password")>=0||ac.indexOf("cc-")===0);}
function label(el,tag,type,name){
if(privateInput(el,tag,type,name))return "";
var aria=el.getAttribute&&el.getAttribute("aria-label")||"",ph=el.getAttribute&&el.getAttribute("placeholder")||"",ti=el.getAttribute&&el.getAttribute("title")||"";
var own=aria||(el.innerText||el.textContent||"")||ph||ti;
if(!own&&echoable(tag,type,name,el)){try{own=el.value||"";}catch(e0){}}
return cl(own,L.et);
}
var Q='a,button,input,select,textarea,summary,[role="button"],[role="link"],[role="tab"],[role="menuitem"],[role="checkbox"],[role="radio"],[role="switch"],[role="searchbox"],[role="textbox"],[role="combobox"],[onclick],[contenteditable="true"],[contenteditable=""],[aria-label][tabindex]';
function selectorFor(el){
var parts=[],node=el,budget=L.s,guard=0;
while(node&&node.nodeType===1&&guard<8){
var tag=node.tagName.toLowerCase(),part=tag;
var id=node.getAttribute?node.getAttribute("id"):null;
if(id&&id.length<=64&&/^[A-Za-z][A-Za-z0-9_-]*$/.test(id)){parts.unshift(tag+"#"+id);return parts.join(" > ");}
var raw=(node.getAttribute?node.getAttribute("class"):"")||"",cls=[],cs=raw.split(/\s+/);
for(var i=0;i<cs.length&&cls.length<2;i++){var c=cs[i];if(c&&c.length<=40&&/^[A-Za-z][A-Za-z0-9_-]*$/.test(c))cls.push(c);}
if(cls.length)part+="."+cls.join(".");
var parent=node.parentElement;
if(parent){var same=0,idx=0,kids=parent.children;for(var k=0;k<kids.length;k++){if(kids[k].tagName===node.tagName){same++;if(kids[k]===node)idx=same;}}if(same>1)part+=":nth-of-type("+idx+")";}
if(part.length+2>budget)break;
budget-=part.length+2;
parts.unshift(part);
node=parent;guard++;
}
return parts.join(" > ");
}
function idxWithin(el,sel){try{var list=document.querySelectorAll(sel);for(var i=0;i<list.length;i++){if(list[i]===el)return i;}}catch(e){}return 0;}
var nodes=document.querySelectorAll(Q),out=[];
for(var i=0;i<nodes.length;i++){
if(out.length>=L.e)break;
var el=nodes[i];
try{
var st=window.getComputedStyle?window.getComputedStyle(el):null;
if(st&&(st.display==="none"||st.visibility==="hidden"))continue;
if(el.getClientRects&&el.getClientRects().length===0)continue;
var sel=selectorFor(el);
if(!sel||sel.length>L.s)continue;
var tag=el.tagName.toLowerCase();
var type=(el.getAttribute&&el.getAttribute("type")||"").toLowerCase();
if(tag==="input"&&type==="hidden")continue;
var name=el.getAttribute&&el.getAttribute("name")||"";
var item={order:out.length,index:idxWithin(el,sel),tag:tag,selector:sel,text:label(el,tag,type,name)};
if(!privateInput(el,tag,type,name)){
var aria=cl(el.getAttribute&&el.getAttribute("aria-label")||"",L.et);if(aria)item.ariaLabel=aria;
var ph=cl(el.getAttribute&&el.getAttribute("placeholder")||"",L.et);if(ph)item.placeholder=ph;
}
if(type)item.type=type;
var role=cl(el.getAttribute&&el.getAttribute("role")||"",32);
if(!role){if(tag==="button")role="button";else if(tag==="a")role="link";else if(tag==="textarea")role="textbox";else if(tag==="input")role=type==="search"?"searchbox":(type==="checkbox"||type==="radio"?type:"textbox");else if(tag==="select")role="combobox";}
if(role)item.role=role;
var nm=cl(name,64);if(nm)item.name=nm;
if(tag==="a"){var hr=el.getAttribute&&el.getAttribute("href")||"";if(hr)item.href=cl(hr,L.h);}
if((tag==="input"||tag==="textarea"||tag==="select")&&echoable(tag,type,name,el)){try{item.value=cl(el.value,L.v);}catch(e0){}}
if(el.disabled===true||(el.getAttribute&&el.getAttribute("aria-disabled")==="true"))item.disabled=true;
out.push(item);
}catch(e1){}
}
var text="";try{text=document.body?(document.body.innerText||document.body.textContent||""):"";}catch(e2){}
if(text.length>L.t)text=text.substring(0,L.t);
var title="";try{title=document.title||"";}catch(e3){}
if(title.length>L.k)title=title.substring(0,L.k);
var path="";try{path=(location.pathname||"")+(location.search||"")+(location.hash||"");}catch(e4){}
if(path.length>L.p)path=path.substring(0,L.p);
var href="";try{href=String(location.href||"");}catch(e5){}
return JSON.stringify({ok:true,href:href,title:title,path:path,text:text,candidates:nodes.length,elements:out});
}catch(e){return JSON.stringify({ok:false,code:"SNAPSHOT_ERROR"});}})()
""".trimIndent()

    /** 有界 DOM 签名：含 hash 路由、等长正文更新及输入值变化；只回传摘要，不回传正文/表单值。 */
    fun buildSignatureScript(): String = """
(function(){try{
var t=document.body?(document.body.innerText||document.body.textContent||""):"";
var fields=document.querySelectorAll("input,textarea,select,[contenteditable]");
var s=String(location.pathname||"")+String(location.search||"")+String(location.hash||"")+String(document.title||"");
s+="|"+t.length+"|"+t.slice(0,4096)+"|"+t.slice(Math.max(0,(t.length>>1)-512),(t.length>>1)+512)+"|"+t.slice(-2048);
for(var i=0;i<fields.length&&i<80;i++){var el=fields[i];s+="|"+String(el.value||el.textContent||"").slice(0,120);}
var hash=2166136261;for(var j=0;j<s.length;j++){hash^=s.charCodeAt(j);hash=Math.imul(hash,16777619);}
return String(document.readyState)+"|"+String(hash>>>0)+"|"+String(document.getElementsByTagName("*").length)+"|"+String(t.length);
}catch(e){return "ERR";}})()
""".trimIndent()

    /** 连续两次签名相同且 `readyState=complete` 才算稳定。 */
    fun isStable(previous: String?, current: String?): Boolean =
        previous != null && current != null && previous == current &&
            current != "ERR" && current.startsWith("complete|")

    /**
     * 固定点击脚本：selector/index 只作为字面量嵌入；真实派发 pointer/mouse 序列后调用 `el.click()`，
     * 让依赖原生 click 或 pointerdown 的组件都能收到事件，并且只触发一次 click。
     */
    fun buildClickScript(selector: String, index: Int = 0): String {
        val sel = jsStringLiteral(selector)
        val idx = index.coerceIn(0, 1_000_000)
        return """
(function(){try{
var sel=$sel,idx=$idx,list;
try{list=document.querySelectorAll(sel);}catch(e0){return JSON.stringify({ok:false,code:"INVALID_SELECTOR"});}
if(!list||list.length===0)return JSON.stringify({ok:false,code:"NO_MATCH"});
if(idx>=list.length)return JSON.stringify({ok:false,code:"INDEX_OUT_OF_RANGE"});
var el=list[idx];
try{el.scrollIntoView({block:"center",inline:"center"});}catch(e1){}
if(el.focus){try{el.focus({preventScroll:true});}catch(e2){try{el.focus();}catch(e3){}}}
var r=el.getBoundingClientRect();
var opt={bubbles:true,cancelable:true,view:window,clientX:r.left+r.width/2,clientY:r.top+r.height/2,button:0};
try{el.dispatchEvent(new PointerEvent("pointerdown",opt));}catch(e4){}
try{el.dispatchEvent(new MouseEvent("mousedown",opt));}catch(e5){}
try{el.dispatchEvent(new PointerEvent("pointerup",opt));}catch(e6){}
try{el.dispatchEvent(new MouseEvent("mouseup",opt));}catch(e7){}
try{if(typeof el.click==="function"){el.click();}else{el.dispatchEvent(new MouseEvent("click",opt));}}catch(e8){return JSON.stringify({ok:false,code:"CLICK_FAILED"});}
return JSON.stringify({ok:true});
}catch(e){return JSON.stringify({ok:false,code:"SCRIPT_ERROR"});}})()
""".trimIndent()
    }

    /**
     * 固定填充脚本：用原型上的 value setter 写入（React 等受控组件能感知），
     * 再依次派发 `input` 与 `change`；select 走 value + change，contenteditable 走 textContent + input。
     */
    fun buildFillScript(selector: String, text: String, index: Int = 0): String {
        val sel = jsStringLiteral(selector)
        val val1 = jsStringLiteral(text)
        val idx = index.coerceIn(0, 1_000_000)
        return """
(function(){try{
var sel=$sel,idx=$idx,txt=$val1,list;
try{list=document.querySelectorAll(sel);}catch(e0){return JSON.stringify({ok:false,code:"INVALID_SELECTOR"});}
if(!list||list.length===0)return JSON.stringify({ok:false,code:"NO_MATCH"});
if(idx>=list.length)return JSON.stringify({ok:false,code:"INDEX_OUT_OF_RANGE"});
function editable(el){try{var attr=el.getAttribute&&el.getAttribute("contenteditable");return el.isContentEditable===true||attr!==null&&(attr===""||String(attr).toLowerCase()==="true");}catch(ee){return false;}}
var el=list[idx],tag=el.tagName.toLowerCase();
if(el.disabled||el.readOnly)return JSON.stringify({ok:false,code:"UNSUPPORTED_TARGET"});
if(tag==="input"&&String(el.type||"").toLowerCase()==="file")return JSON.stringify({ok:false,code:"UNSUPPORTED_INPUT"});
if(tag==="input"&&["hidden","button","submit","reset","checkbox","radio","image"].indexOf(String(el.type||"").toLowerCase())>=0)return JSON.stringify({ok:false,code:"UNSUPPORTED_TARGET"});
try{el.scrollIntoView({block:"center",inline:"center"});}catch(e1){}
if(el.focus){try{el.focus({preventScroll:true});}catch(e2){try{el.focus();}catch(e3){}}}
if(tag==="select"){el.value=txt;}
else if(tag==="input"||tag==="textarea"){
var proto=tag==="textarea"?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
var desc=Object.getOwnPropertyDescriptor(proto,"value");
if(desc&&desc.set){desc.set.call(el,txt);}else{el.value=txt;}
}
else if(editable(el)){el.textContent=txt;}
else{return JSON.stringify({ok:false,code:"UNSUPPORTED_TARGET"});}
var ev;
try{ev=new InputEvent("input",{bubbles:true,data:txt,inputType:"insertText"});}catch(e4){ev=new Event("input",{bubbles:true});}
try{el.dispatchEvent(ev);}catch(e5){}
try{el.dispatchEvent(new Event("change",{bubbles:true}));}catch(e6){}
return JSON.stringify({ok:true});
}catch(e){return JSON.stringify({ok:false,code:"SCRIPT_ERROR"});}})()
""".trimIndent()
    }

    /** 只对可编辑的文本表单控件派发 Enter；有同源表单时用原生 requestSubmit 兜底。 */
    fun buildPressEnterScript(selector: String, index: Int = 0): String {
        val sel = jsStringLiteral(selector)
        val idx = index.coerceIn(0, 1_000_000)
        return """
(function(){try{
var sel=$sel,idx=$idx,list;
try{list=document.querySelectorAll(sel);}catch(e0){return JSON.stringify({ok:false,code:"INVALID_SELECTOR"});}
if(!list||list.length===0)return JSON.stringify({ok:false,code:"NO_MATCH"});
if(idx>=list.length)return JSON.stringify({ok:false,code:"INDEX_OUT_OF_RANGE"});
var el=list[idx],tag=el.tagName.toLowerCase(),type=String(el.type||"text").toLowerCase();
if(el.disabled||el.readOnly||!(tag==="textarea"||tag==="input"&&["text","search","email","url","tel","number","password"].indexOf(type)>=0))return JSON.stringify({ok:false,code:"UNSUPPORTED_TARGET"});
var form=el.form;
if(form){
var target=String(form.getAttribute("target")||"").toLowerCase();
if(target&&target!=="_self")return JSON.stringify({ok:false,code:"UNSAFE_FORM"});
var action=new URL(form.getAttribute("action")||location.href,location.href);
if((action.protocol!=="http:"&&action.protocol!=="https:")||action.origin!==location.origin||action.username||action.password)return JSON.stringify({ok:false,code:"UNSAFE_FORM"});
}
try{el.focus({preventScroll:true});}catch(e1){el.focus();}
var opt={key:"Enter",code:"Enter",keyCode:13,which:13,bubbles:true,cancelable:true};
var down=new KeyboardEvent("keydown",opt),proceed=el.dispatchEvent(down);
if(proceed)el.dispatchEvent(new KeyboardEvent("keypress",opt));
el.dispatchEvent(new KeyboardEvent("keyup",opt));
if(proceed&&form&&typeof HTMLFormElement.prototype.requestSubmit==="function")HTMLFormElement.prototype.requestSubmit.call(form);
return JSON.stringify({ok:true});
}catch(e){return JSON.stringify({ok:false,code:"SCRIPT_ERROR"});}})()
""".trimIndent()
    }

    /** 固定滚动脚本；delta 在 [buildScrollScript] 内先夹紧。 */
    fun buildScrollScript(deltaY: Int): String {
        val delta = deltaY.coerceIn(-MAX_SCROLL_DELTA, MAX_SCROLL_DELTA)
        return "(function(){try{window.scrollBy(0,$delta);" +
            "return JSON.stringify({ok:true,y:Math.round(window.scrollY||window.pageYOffset||0)});}" +
            "catch(e){return JSON.stringify({ok:false,code:\"SCRIPT_ERROR\"});}})()"
    }

    /** `evaluateJavascript` 的回包是 JSON 编码的字符串：`"{\"ok\":true}"` → `{"ok":true}`。 */
    fun decodeJsResult(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null" || text == "undefined") return null
        return runCatching {
            val element = JsonParser.parseString(text)
            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
        }.getOrNull()
    }

    /** @property scrollY 仅滚动脚本会带上，其余为 null */
    data class ActionResult(val ok: Boolean, val code: String?, val scrollY: Int?)

    fun parseActionResult(raw: String?): ActionResult {
        val decoded = decodeJsResult(raw) ?: return ActionResult(false, "SCRIPT_ERROR", null)
        val root = runCatching { JsonParser.parseString(decoded).asJsonObject }.getOrNull()
            ?: return ActionResult(false, "SCRIPT_ERROR", null)
        val ok = root.get("ok")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrDefault(false) } == true
        val code = root.stringOrNull("code")?.take(64)
        val y = root.get("y")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asInt }.getOrNull() }
        return ActionResult(ok, code, y)
    }

    // ---- 快照模型与解析 ----

    /** @property order 在快照清单中的顺序；@property index 该 selector 匹配列表里的下标（click/fill 用） */
    data class Element(
        val order: Int,
        val index: Int,
        val tag: String,
        val text: String,
        val selector: String,
        val ariaLabel: String? = null,
        val placeholder: String? = null,
        val type: String? = null,
        val role: String? = null,
        val name: String? = null,
        val href: String? = null,
        val value: String? = null,
        val disabled: Boolean = false,
    ) {
        fun toPayload(): Map<String, Any?> = linkedMapOf(
            "order" to order,
            "index" to index,
            "tag" to tag,
            "text" to text,
            "selector" to selector,
            "ariaLabel" to ariaLabel,
            "placeholder" to placeholder,
            "type" to type,
            "role" to role,
            "name" to name,
            "href" to href,
            "value" to value,
            "disabled" to disabled,
        )
    }

    /** @property path 同源 pathname+search+hash（敏感查询参数已剔除），不含主机 */
    data class Snapshot(
        val title: String,
        val path: String,
        val text: String,
        val textTruncated: Boolean,
        val elements: List<Element>,
        val elementLimitReached: Boolean,
    ) {
        fun toPayload(): Map<String, Any?> = linkedMapOf(
            "title" to title,
            "path" to path,
            "text" to text,
            "textTruncated" to textTruncated,
            "elements" to elements.map { it.toPayload() },
            "elementCount" to elements.size,
            "elementLimitReached" to elementLimitReached,
        )
    }

    sealed class SnapshotParse {
        data class Ok(val snapshot: Snapshot) : SnapshotParse()

        /** 页面已经不在已保存的 origin 上：调用方必须销毁会话，不能把内容交出去。 */
        object OffOrigin : SnapshotParse()

        /** 脚本报错或回包不可解析。 */
        object Malformed : SnapshotParse()
    }

    fun parseSnapshot(raw: String?, origin: Origin, limits: Limits = Limits()): SnapshotParse {
        val decoded = decodeJsResult(raw) ?: return SnapshotParse.Malformed
        val root = runCatching { JsonParser.parseString(decoded).asJsonObject }.getOrNull()
            ?: return SnapshotParse.Malformed
        val ok = root.get("ok")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrDefault(false) } == true
        if (!ok) return SnapshotParse.Malformed

        val href = root.stringOrNull("href")
        if (href == null || Origin.fromUrl(href) != origin) return SnapshotParse.OffOrigin

        val elements = parseElements(root.get("elements") as? JsonArray, origin, limits)
        val candidates = root.get("candidates")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asInt }.getOrNull() }
        val normalizedText = normalizeBody(root.stringOrNull("text").orEmpty())
        return SnapshotParse.Ok(
            Snapshot(
                title = clip(root.stringOrNull("title").orEmpty(), limits.maxTitleChars),
                path = sanitizePath(root.stringOrNull("path"), href, limits.maxPathChars),
                text = takeChars(normalizedText, limits.maxTextChars),
                textTruncated = normalizedText.length > limits.maxTextChars,
                elements = elements,
                elementLimitReached = (candidates ?: elements.size) > elements.size,
            ),
        )
    }

    private fun parseElements(array: JsonArray?, origin: Origin, limits: Limits): List<Element> {
        if (array == null) return emptyList()
        val out = ArrayList<Element>(minOf(array.size(), limits.maxElements))
        for (item in array) {
            if (out.size >= limits.maxElements) break
            val obj = item as? JsonObject ?: continue
            val selector = obj.stringOrNull("selector")?.trim().orEmpty()
            if (selector.isEmpty() || selector.length > limits.maxSelectorChars) continue
            if (selector.any { it < ' ' || it.code in 0x7F..0x9F }) continue
            val tag = obj.stringOrNull("tag")?.trim()?.lowercase().orEmpty()
            if (tag.isEmpty() || tag.length > 32) continue
            val type = obj.stringOrNull("type")?.trim()?.lowercase()?.take(32)
            val name = obj.stringOrNull("name")?.trim()?.take(64)
            val index = obj.get("index")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.let { runCatching { it.asInt }.getOrNull() }?.coerceIn(0, 1_000_000) ?: 0
            val autocomplete = obj.stringOrNull("autocomplete")?.trim()?.lowercase()?.take(64)
            val rawValue = obj.stringOrNull("value")
            val echoValue = echoableValue(tag, type, name, autocomplete, rawValue, limits.maxValueChars)
            val text = clip(obj.stringOrNull("text").orEmpty(), limits.maxElementTextChars)
            out += Element(
                order = out.size,
                index = index,
                tag = tag,
                // 兜底：页面若把敏感输入框的值塞进可读文本（如 aria-label），同样不回显
                text = if (echoValue == null && rawValue != null && text.trim() == rawValue.trim()) "" else text,
                selector = selector,
                ariaLabel = clip(obj.stringOrNull("ariaLabel").orEmpty(), limits.maxElementTextChars).ifBlank { null },
                placeholder = clip(obj.stringOrNull("placeholder").orEmpty(), limits.maxElementTextChars).ifBlank { null },
                type = type,
                role = obj.stringOrNull("role")?.trim()?.take(32)?.ifBlank { null },
                name = name?.ifBlank { null },
                href = sanitizeHref(obj.stringOrNull("href"), origin, limits.maxHrefChars),
                value = echoValue,
                disabled = obj.get("disabled")?.takeIf { it.isJsonPrimitive }?.let {
                    runCatching { it.asBoolean }.getOrDefault(false)
                } == true,
            )
        }
        return out
    }

    // ---- 隐私与脱敏 ----

    /** 只有普通文本/数字输入框的值才回显；password/hidden/email/tel/file/search/url 等一律不回显。 */
    private val VALUE_ECHO_TYPES = setOf("text", "number")

    private fun echoableValue(
        tag: String,
        type: String?,
        name: String?,
        autocomplete: String?,
        value: String?,
        limit: Int,
    ): String? {
        if (tag != "input" && tag != "textarea") return null
        val resolvedType = type?.takeIf { it.isNotEmpty() } ?: "text"
        if (resolvedType !in VALUE_ECHO_TYPES) return null
        if (name != null && isSensitiveParamName(name)) return null
        // 浏览器/密码管理器语义：autocomplete=current-password、new-password、cc-number 等都算敏感
        if (autocomplete != null && (autocomplete.contains("password") || autocomplete.startsWith("cc-"))) return null
        val text = value?.trim().orEmpty()
        return if (text.isEmpty()) null else takeChars(text, limit)
    }

    /** 查询参数/表单字段名里的敏感词（按去掉 `-_.[]` 后的完整词匹配，避免误伤 `keyword`）。 */
    private val SENSITIVE_PARAM_NAMES = setOf(
        "token", "accesstoken", "refreshtoken", "idtoken", "authtoken", "auth", "authorization",
        "password", "passwd", "pwd", "secret", "clientsecret", "apikey", "key",
        "sig", "signature", "session", "sessionid", "sid", "jwt", "ticket",
        "credential", "credentials", "csrftoken", "xsrftoken", "csrf", "state", "code",
    )

    /**
     * 查询参数/表单字段名里的敏感词：整个名字（去掉 `-_.[]` 后）或**按分隔符切出的任一段**
     * 命中敏感词表即视为敏感，例如 `accessToken`、`X-Auth-Token`、`X-CSRF-Token`。
     * 不做子串匹配，避免误伤 `keyword`、`tokenizer`、`author` 这类正常字段。
     */
    fun isSensitiveParamName(raw: String): Boolean {
        val segments = raw.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        if (segments.size == 1 && segments[0] in SENSITIVE_PARAM_NAMES) return true
        return segments.any { it in SENSITIVE_PARAM_NAMES }
    }

    /**
     * 去掉 path/查询/片段里携带凭据的参数对，保留其余参数与顺序。
     * 处理 `?a=1&token=x`、`#/r?access_token=y`、`?token=x`（整段只剩分隔符时把分隔符一并清掉）。
     */
    fun redactSensitiveParams(raw: String): String {
        if (raw.isEmpty()) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '?' || c == '&' || c == '#') {
                var end = i + 1
                while (end < raw.length && raw[end] != '?' && raw[end] != '&' && raw[end] != '#') end++
                val token = raw.substring(i + 1, end)
                val eq = token.indexOf('=')
                out.append(c)
                if (eq < 0 || !isSensitiveParamName(token.substring(0, eq))) out.append(token)
                i = end
            } else {
                out.append(c)
                i++
            }
        }
        return cleanupSeparators(out.toString())
    }

    private fun cleanupSeparators(value: String): String {
        var out = value
        while (out.contains("&&")) out = out.replace("&&", "&")
        out = out.replace("?&", "?").replace("#&", "#").replace("&#", "#")
        return out.trimEnd('?', '&', '#')
    }

    /**
     * 同源 path 的清洗：绝对 URL 只取 path/query/fragment；主机与账号一律不出现。
     * [href] 仅在 path 为空时用于补全（快照自身已校验过同源，这里仍按 origin 处理）。
     */
    private fun sanitizePath(rawPath: String?, href: String?, limit: Int): String {
        var value = rawPath?.trim().orEmpty()
        fun fromUrl(url: String): String {
            val parsed = url.toHttpUrlOrNull() ?: return ""
            return parsed.encodedPath +
                (parsed.encodedQuery?.let { "?$it" } ?: "") +
                (parsed.encodedFragment?.let { "#$it" } ?: "")
        }
        if (isHttpScheme(value)) value = fromUrl(value)
        if (value.isEmpty()) value = fromUrl(href.orEmpty())
        return takeChars(redactSensitiveParams(value), limit)
    }

    /** 元素 href 只回显同源绝对 URL 的 path，或站内绝对路径；跨 origin / 协议相对 / 相对路径都不回显。 */
    private fun sanitizeHref(raw: String?, origin: Origin?, limit: Int): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (isHttpScheme(text)) {
            val parsed = Origin.fromUrl(text)
            if (origin != null && parsed != origin) return null
            val url = text.toHttpUrlOrNull() ?: return null
            if (origin == null && url.host.isEmpty()) return null
            val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            return takeChars(redactSensitiveParams(path), limit)
        }
        if (text.startsWith("//") || !text.startsWith("/")) return null
        return takeChars(redactSensitiveParams(text), limit)
    }

    // ---- 文本工具 ----

    private fun normalizeBody(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[\\t\\u000B\\u000C]+"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()

    private fun clip(value: String, limit: Int): String =
        takeChars(value.replace(Regex("\\s+"), " ").trim(), limit)

    /** 按字符截断且不切断代理对。 */
    private fun takeChars(value: String, limit: Int): String {
        if (limit <= 0) return ""
        if (value.length <= limit) return value
        var end = limit
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.let { runCatching { it.asString }.getOrNull() }
}
