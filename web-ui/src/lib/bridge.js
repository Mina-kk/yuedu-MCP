// 桥接：Android 宿主 StudioBridge（WebMessageListener 注入）或浏览器里的标准 MCP Streamable HTTP。
//
// 契约 docs/WEB_UI_CONTRACT.md：
//   JS → 宿主: StudioBridge.postMessage(JSON.stringify({id, method, params}))
//   宿主 → JS: StudioBridge.onmessage = event => ...，回包 {id, ok:true, result} / {id, ok:false, error:{code,message}}
// 每个请求一个 id，超时/宿主关闭/来源失活都 reject，不留下永久 loading。

const DEFAULT_TIMEOUT_MS = 30_000
const LONG_TIMEOUT_MS = 180_000
const INTERACTIVE_TIMEOUT_MS = 600_000

/** 需要更长等待的宿主方法（联网检查、调试、验证等待）。 */
const SLOW_METHODS = new Set(['tools.call'])

// SAF 文件选择器/权限确认需等待用户操作，但仍保留有界超时。
const INTERACTIVE_METHODS = new Set([
  'skills.import', 'skills.export', 'file.import', 'file.export',
  'logs.export', 'capture.browser.export', 'notification.request'
])

export class BridgeError extends Error {
  constructor(code, message, extra) {
    super(message || code)
    this.name = 'BridgeError'
    this.code = code
    if (extra) Object.assign(this, extra)
  }
}

let seq = 0

function nextId() {
  seq += 1
  return `r${Date.now().toString(36)}${seq.toString(36)}`
}

/** 浏览器里的 Android 桥对象（WebViewAssetLoader 页面由宿主注入）。 */
export function detectAndroidBridge(win) {
  const w = win || (typeof window !== 'undefined' ? window : null)
  const bridge = w && w.StudioBridge
  return bridge && typeof bridge.postMessage === 'function' ? bridge : null
}

/**
 * Android 宿主桥。没有 StudioBridge 时 kind='none'、connected=false，
 * 任何调用都以 runtime_unavailable 失败（明确「未连接运行时」，不造数据）。
 */
export function createAndroidBridge({ win, timeoutMs } = {}) {
  const w = win || (typeof window !== 'undefined' ? window : null)
  const native = detectAndroidBridge(w)
  const pending = new Map()
  const listeners = new Set()
  let disposed = false

  function settle(id, fn) {
    const entry = pending.get(id)
    if (!entry) return
    pending.delete(id)
    clearTimeout(entry.timer)
    fn(entry)
  }

  function onMessage(event) {
    let data = event && event.data
    if (typeof data === 'string') {
      try {
        data = JSON.parse(data)
      } catch {
        return
      }
    }
    if (!data || typeof data !== 'object') return
    if (typeof data.event === 'string') {
      const payload = data.payload && typeof data.payload === 'object' ? data.payload : {}
      listeners.forEach((fn) => {
        try {
          fn(data.event, payload)
        } catch {
          // 监听者异常不能打断桥接。
        }
      })
      return
    }
    if (data.id == null) return
    const id = String(data.id)
    if (data.ok === true) {
      settle(id, (entry) => entry.resolve(data.result))
    } else {
      const error = data.error || {}
      settle(id, (entry) =>
        entry.reject(new BridgeError(error.code || 'error', error.message || '宿主返回错误'))
      )
    }
  }

  if (native) native.onmessage = onMessage

  // 宿主也可能用 window CustomEvent 派发事件（MainActivity 的 studio:route 就是这样），
  // 同一个 handler 从 event.detail 读取；与 StudioBridge.onmessage 共用同一份监听者。
  const HOST_EVENTS = ['studio:back', 'studio:insets', 'studio:service', 'studio:verification-browser', 'studio:route']
  const customHandler = (event) => {
    if (!event || typeof event.type !== 'string') return
    const payload = event.detail && typeof event.detail === 'object' ? event.detail : {}
    onMessage({ data: { event: event.type, payload } })
  }
  if (w && typeof w.addEventListener === 'function') {
    HOST_EVENTS.forEach((name) => w.addEventListener(name, customHandler))
  }

  function call(method, params) {
    if (!native) {
      return Promise.reject(new BridgeError('runtime_unavailable', '未连接运行时：当前页面没有 Android 宿主桥'))
    }
    if (disposed) {
      return Promise.reject(new BridgeError('host_gone', '宿主已关闭'))
    }
    const id = nextId()
    // sources.deliver 的文件导出动作在宿主契约中名为 save。
    const interactive = INTERACTIVE_METHODS.has(method) || (method === 'sources.deliver' && params?.action === 'save')
    const limit = timeoutMs || (interactive ? INTERACTIVE_TIMEOUT_MS : SLOW_METHODS.has(method) ? LONG_TIMEOUT_MS : DEFAULT_TIMEOUT_MS)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        settle(id, (entry) => entry.reject(new BridgeError('timeout', `请求超时：${method}`)))
      }, limit)
      pending.set(id, { resolve, reject, timer, method })
      try {
        native.postMessage(JSON.stringify({ id, method, params: params || {} }))
      } catch (error) {
        settle(id, (entry) => entry.reject(new BridgeError('host_gone', `发送失败：${error && error.message}`)))
      }
    })
  }

  function dispose(code = 'host_gone') {
    disposed = true
    if (w && typeof w.removeEventListener === 'function') {
      HOST_EVENTS.forEach((name) => w.removeEventListener(name, customHandler))
    }
    const ids = [...pending.keys()]
    ids.forEach((id) => settle(id, (entry) => entry.reject(new BridgeError(code, '宿主已关闭，请求已取消'))))
  }

  return {
    kind: native ? 'android' : 'none',
    connected: Boolean(native),
    call,
    onEvent(fn) {
      listeners.add(fn)
      return () => listeners.delete(fn)
    },
    dispose,
    describe() {
      return { kind: native ? 'android' : 'none', connected: Boolean(native) }
    }
  }
}

// ---------------------------------------------------------------------------
// Web：标准 MCP Streamable HTTP（显式用户输入 endpoint，不扫端口）
// ---------------------------------------------------------------------------

const WEB_UNSUPPORTED = new Set([
  'service.set',
  'connection.copy',
  'verification.list',
  'verification.open',
  'verification.complete',
  'verification.clear',
  'verification.close',
  'verification.browser.close',
  'file.export',
  'file.import',
  'app.theme',
  'app.exit',
  'settings.save'
])

function parseSse(text) {
  // 只取 data: 行里的 JSON-RPC 消息（服务端可能分多帧）。
  const out = []
  for (const line of String(text || '').split(/\r?\n/)) {
    if (!line.startsWith('data:')) continue
    const chunk = line.slice(5).trim()
    if (!chunk) continue
    try {
      out.push(JSON.parse(chunk))
    } catch {
      // 忽略非 JSON 帧
    }
  }
  return out
}

/**
 * 浏览器直连 MCP。endpoint 必须由用户显式填写；
 * token 只保存在内存里，不进 localStorage、不进 DOM。
 */
export function createWebRuntimeBridge({ endpoint, token, fetchImpl, timeoutMs } = {}) {
  const base = String(endpoint || '').trim()
  const auth = String(token || '').trim()
  const doFetch = fetchImpl || ((...args) => (typeof fetch === 'function' ? fetch(...args) : Promise.reject(new Error('fetch unavailable'))))
  const listeners = new Set()
  let initialized = false
  let initialization = null
  let protocolReady = false
  let sessionId = ''
  let rpcId = 0
  let protocolVersion = '2024-11-05'
  let disposed = false
  const activeRequests = new Set()

  if (!/^https?:\/\//i.test(base)) {
    // 允许在构造后 call 时再报错，但 describe 要如实。
  }

  function headers() {
    const h = {
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream'
    }
    if (auth) h.Authorization = `Bearer ${auth}`
    if (sessionId) h['Mcp-Session-Id'] = sessionId
    if (protocolReady) h['MCP-Protocol-Version'] = protocolVersion
    return h
  }

  async function rpc(method, params, { notification = false } = {}) {
    if (disposed) throw new BridgeError('host_gone', '连接已关闭')
    let target
    try { target = new URL(base) } catch { throw new BridgeError('bad_endpoint', '运行时地址必须是 http(s) URL') }
    if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password) {
      throw new BridgeError('bad_endpoint', '运行时地址必须是无内嵌凭据的 http(s) URL')
    }
    const body = notification
      ? { jsonrpc: '2.0', method, params: params || {} }
      : { jsonrpc: '2.0', id: ++rpcId, method, params: params || {} }
    const controller = new AbortController()
    activeRequests.add(controller)
    let timedOut = false
    const timer = setTimeout(() => { timedOut = true; controller.abort() }, timeoutMs || LONG_TIMEOUT_MS)
    try {
      const response = await doFetch(base, {
        method: 'POST', headers: headers(), body: JSON.stringify(body),
        signal: controller.signal, redirect: 'error'
      })
      if (!response.ok) {
        throw new BridgeError(response.status === 401 ? 'unauthorized' : 'http_error', `运行时返回 HTTP ${response.status}`)
      }
      const sid = response.headers?.get('Mcp-Session-Id')
      if (sid) sessionId = sid
      if (notification) return null
      const text = typeof response.text === 'function' ? await response.text() : ''
      const contentType = response.headers?.get('content-type') || ''
      let message
      if (contentType.includes('text/event-stream')) {
        message = parseSse(text).find((frame) => frame && frame.id === body.id)
      } else {
        try { message = text ? JSON.parse(text) : null }
        catch { throw new BridgeError('bad_response', '运行时返回的不是 JSON-RPC 消息') }
      }
      if (!message || message.jsonrpc !== '2.0' || message.id !== body.id) {
        throw new BridgeError('bad_response', '运行时没有返回与本次 id 匹配的 JSON-RPC 结果')
      }
      if (message.error) throw new BridgeError('rpc_error', 'MCP 请求失败', { rpcCode: message.error.code })
      if (!Object.prototype.hasOwnProperty.call(message, 'result')) {
        throw new BridgeError('bad_response', '运行时结果缺少 result')
      }
      return message.result
    } catch (error) {
      if (disposed) throw new BridgeError('host_gone', '连接已关闭')
      if (timedOut) throw new BridgeError('timeout', `运行时请求超时：${method}`)
      if (error instanceof BridgeError) throw error
      const reason = String(error?.message || 'network error').replaceAll(auth || '\u0000', '[已隐藏]')
      throw new BridgeError('network_error', `无法连接运行时（可能是 CORS 或地址错误）：${reason.slice(0, 200)}`)
    } finally {
      clearTimeout(timer)
      activeRequests.delete(controller)
    }
  }

  async function ensureInitialized() {
    if (initialized) return
    if (initialization) return initialization
    initialization = (async () => {
      const result = await rpc('initialize', {
        protocolVersion, capabilities: {}, clientInfo: { name: 'yuedu-mcp-web', version: '2.0.0' }
      })
      if (!result || typeof result.protocolVersion !== 'string') {
        throw new BridgeError('bad_response', '初始化响应缺少协议版本')
      }
      protocolVersion = result.protocolVersion
      protocolReady = true
      await rpc('notifications/initialized', {}, { notification: true })
      initialized = true
    })()
    try { await initialization }
    finally { initialization = null }
  }

  async function call(method, params) {
    if (WEB_UNSUPPORTED.has(method)) {
      throw new BridgeError('unsupported', `浏览器模式不支持宿主方法 ${method}；请在 App 内使用`)
    }
    switch (method) {
      case 'tools.list': {
        await ensureInitialized()
        const result = await rpc('tools/list', {})
        return { tools: (result && result.tools) || [] }
      }
      case 'tools.call': {
        const name = params && params.name
        if (!name) throw new BridgeError('bad_params', 'tools.call 需要 name')
        await ensureInitialized()
        const result = await rpc('tools/call', { name, arguments: (params && params.arguments) || {} })
        return result
      }
      case 'app.bootstrap': {
        await ensureInitialized()
        const info = await rpc('tools/call', { name: 'get_app_info', arguments: { detail: false } })
        return {
          versionName: '',
          versionCode: 0,
          buildRevision: '',
          ui: 'rust-vue',
          service: { running: true, starting: false, port: 0, tokenRequired: true, endpoints: [base], compact: false },
          runtime: { userAgent: '', bookSourceType: -1 },
          reader: { baseUrl: '', tokenSaved: false, revision: 0 },
          appearance: { theme: 'system', palette: 'studio' },
          capabilities: { webMessageListener: false, saf: false, verificationBrowser: false, toolCount: 0, webRuntime: true },
          webRuntime: { endpoint: base, appInfo: info }
        }
      }
      default:
        throw new BridgeError('unsupported', `未知方法 ${method}`)
    }
  }

  return {
    kind: 'web',
    connected: Boolean(base),
    call,
    onEvent(fn) {
      listeners.add(fn)
      return () => listeners.delete(fn)
    },
    dispose() {
      disposed = true
      for (const controller of activeRequests) controller.abort()
      activeRequests.clear()
    },
    describe() {
      // 只描述形状，绝不包含 token。
      return { kind: 'web', connected: Boolean(base), endpoint: base }
    }
  }
}

/**
 * 统一入口：Android 有桥用桥；否则返回未连接桥（kind='none'），
 * 由 UI 明确显示「未连接运行时」并允许用户显式配置 MCP 地址。
 */
export function createBridge(options = {}) {
  const win = options.win || (typeof window !== 'undefined' ? window : null)
  if (detectAndroidBridge(win)) return createAndroidBridge({ ...options, win })
  const b = createAndroidBridge({ ...options, win })
  return b
}
