// MCP CallToolResult 解析：真实工具把业务 JSON 放在 content[].text 里。
// 所有内容只做纯文本渲染（<pre>），绝不 v-html。
// 大结果/敏感字段不写 console：日志里只记工具名与是否出错。

export function contentText(result) {
  if (!result || !Array.isArray(result.content)) return ''
  return result.content
    .map((item) => {
      if (!item) return ''
      if (item.type === 'text') return String(item.text ?? '')
      if (item.type === 'image') return `[图片 ${item.mimeType || ''}]`
      if (item.type === 'resource') return JSON.stringify(item.resource || item, null, 2)
      return JSON.stringify(item, null, 2)
    })
    .filter(Boolean)
    .join('\n')
}

/**
 * 结构化解析：优先 JSON.parse 文本；失败返回 { ok:false, text }。
 * 返回对象时保留原始文本 raw，供「以用户原文为准」的编辑路径使用。
 */
export function parseToolResult(result) {
  const text = contentText(result)
  const isError = Boolean(result && result.isError)
  if (!text) return { ok: !isError, empty: true, isError, text: '', data: null, raw: '' }
  try {
    return { ok: !isError, empty: false, isError, text, data: JSON.parse(text), raw: text }
  } catch {
    return { ok: !isError, empty: false, isError, text, data: null, raw: text }
  }
}

/** 工具调用统一封装：返回解析结果；错误统一成可读文本，不吞掉真实原因。 */
export async function callToolParsed(store, name, args) {
  try {
    const result = await store.callTool(name, args)
    return { ...parseToolResult(result), threw: null }
  } catch (error) {
    return {
      ok: false,
      empty: true,
      isError: true,
      text: '',
      data: null,
      raw: '',
      threw: (error && error.message) || `调用 ${name} 失败`
    }
  }
}

/** 从工具结果里取数组：兼容 {items:[]} / 直接数组 / 单对象。 */
export function itemsOf(parsed, key = 'items') {
  if (!parsed || !parsed.data) return []
  const d = parsed.data
  if (Array.isArray(d)) return d
  if (Array.isArray(d[key])) return d[key]
  if (Array.isArray(d.sessions)) return d.sessions
  if (Array.isArray(d.skills)) return d.skills
  if (Array.isArray(d.tools)) return d.tools
  return []
}

export function totalOf(parsed, fallback) {
  const d = parsed && parsed.data
  if (d && typeof d === 'object') {
    if (Number.isFinite(Number(d.total))) return Number(d.total)
    if (Number.isFinite(Number(d.count))) return Number(d.count)
  }
  return Number.isFinite(Number(fallback)) ? Number(fallback) : null
}

/** 时间戳 → 本地简短时间；无效值原样返回空串，不编造。 */
export function formatTime(value) {
  const n = Number(value)
  if (!Number.isFinite(n) || n <= 0) return ''
  try {
    const d = new Date(n)
    const pad = (x) => String(x).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  } catch {
    return ''
  }
}

/** 敏感字段脱敏：只在 UI 展示层使用，不改动真实数据。 */
const SECRET_KEYS = /^(cookie|authorization|token|password|secret|api[-_]?key)$/i
export function redactForDisplay(value, depth = 0) {
  if (depth > 6) return '…'
  if (Array.isArray(value)) return value.map((v) => redactForDisplay(v, depth + 1))
  if (value && typeof value === 'object') {
    const out = {}
    for (const [k, v] of Object.entries(value)) {
      out[k] = SECRET_KEYS.test(k) ? '（已隐藏）' : redactForDisplay(v, depth + 1)
    }
    return out
  }
  return value
}

export function prettyJson(value) {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}
