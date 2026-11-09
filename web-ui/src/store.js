import { reactive } from 'vue'
import { createBridge } from './lib/bridge.js'
import { loadTheme, saveTheme, modeFrom } from './lib/palettes.js'

// 应用状态（单一 reactive 对象）。所有业务数据都来自真实桥接/工具调用，
// 失败时保留错误文本，不填演示数据。

export function createStore({ bridge: injected, storage } = {}) {
  const bridge = injected || createBridge()
  const store = storage || (typeof localStorage !== 'undefined' ? localStorage : null)
  const theme = loadTheme(store)

  const state = reactive({
    // 运行时
    bridgeKind: bridge.kind,
    runtimeConnected: bridge.connected,
    webEndpoint: '',
    bootstrapOk: false,
    bootstrapError: '',
    bootstrapping: false,
    bootstrap: null,
    service: { running: false, starting: false, port: 0, tokenRequired: true, endpoints: [], compact: false },
    runtime: { userAgent: '', bookSourceType: -1 },
    reader: { baseUrl: '', tokenSaved: false, revision: 0 },
    capabilities: {},
    // 主题
    themeIdx: theme.idx,
    mode: theme.mode,
    // 导航 / 弹层
    page: 'mcp',
    sheet: null,
    sheetDirty: false,
    dialog: null,
    toast: null,
    // 工具
    tools: [],
    toolsLoading: false,
    toolsError: '',
    // Rust 核
    coreStatus: 'idle',
    coreError: '',
    coreVersion: ''
  })

  let toastSeq = 0
  let toastTimer = 0

  function toast(text, kind = 'ok') {
    toastSeq += 1
    state.toast = { text: String(text), kind, seq: toastSeq }
    if (toastTimer) clearTimeout(toastTimer)
    toastTimer = setTimeout(() => {
      if (state.toast && state.toast.seq === toastSeq) state.toast = null
    }, kind === 'err' ? 5200 : 2600)
  }

  function applyBootstrap(snapshot) {
    if (!snapshot || typeof snapshot !== 'object') return
    state.bootstrap = snapshot
    if (snapshot.service) state.service = { ...state.service, ...snapshot.service }
    if (snapshot.runtime) state.runtime = { ...state.runtime, ...snapshot.runtime }
    if (snapshot.reader) state.reader = { ...state.reader, ...snapshot.reader }
    if (snapshot.capabilities) state.capabilities = { ...snapshot.capabilities }
  }

  async function bootstrap() {
    state.bootstrapping = true
    state.bootstrapError = ''
    try {
      if (!bridge.connected) {
        throw Object.assign(new Error('未连接运行时：当前页面没有 Android 宿主桥，可在设置里显式连接 MCP 地址'), {
          code: 'runtime_unavailable'
        })
      }
      const snapshot = await bridge.call('app.bootstrap', {})
      applyBootstrap(snapshot)
      state.bootstrapOk = true
      return snapshot
    } catch (error) {
      state.bootstrapOk = false
      state.bootstrapError = (error && error.message) || 'bootstrap 失败'
      return null
    } finally {
      state.bootstrapping = false
    }
  }

  async function loadTools() {
    state.toolsLoading = true
    state.toolsError = ''
    try {
      const result = await bridge.call('tools.list', {})
      const tools = result && Array.isArray(result.tools) ? result.tools : []
      state.tools = tools
      return tools
    } catch (error) {
      state.tools = []
      state.toolsError = (error && error.message) || '工具目录读取失败'
      return []
    } finally {
      state.toolsLoading = false
    }
  }

  async function callTool(name, args) {
    return bridge.call('tools.call', { name, arguments: args || {} })
  }

  async function setService(action) {
    try {
      const result = await bridge.call('service.set', { action })
      if (result) applyBootstrap({ service: result })
      toast(action === 'stop' ? '已请求停止服务' : action === 'restart' ? '已请求重启服务' : '已请求启动服务')
      return result
    } catch (error) {
      toast((error && error.message) || '服务操作失败', 'err')
      throw error
    }
  }

  async function copyConnection(kind, lan = false) {
    const result = await bridge.call('connection.copy', { kind, lan })
    toast('已复制到剪贴板')
    return result
  }

  function setTheme(idx) {
    state.themeIdx = idx
    saveTheme(store, state.themeIdx, state.mode)
  }

  function setMode(mode) {
    state.mode = modeFrom(mode)
    saveTheme(store, state.themeIdx, state.mode)
  }

  function openSheet(name) {
    state.sheet = name
    state.sheetDirty = false
  }

  function closeSheet() {
    state.sheet = null
    state.sheetDirty = false
  }

  function markDirty(dirty = true) {
    state.sheetDirty = Boolean(dirty)
  }

  function requestDiscard(sheet) {
    state.dialog = {
      kind: 'discard',
      title: '放弃未保存的修改？',
      message: '当前编辑内容还没有保存，关闭后不会保留。',
      confirmLabel: '放弃修改',
      danger: true,
      sheet: sheet || state.sheet
    }
  }

  function confirmDialog(options) {
    state.dialog = { kind: 'confirm', ...options }
  }

  function closeDialog() {
    state.dialog = null
  }

  function setPage(page) {
    state.page = page
  }

  function goRoot() {
    state.page = 'mcp'
  }

  return {
    state,
    bridge,
    toast,
    bootstrap,
    loadTools,
    callTool,
    setService,
    copyConnection,
    setTheme,
    setMode,
    openSheet,
    closeSheet,
    markDirty,
    requestDiscard,
    confirmDialog,
    closeDialog,
    setPage,
    goRoot
  }
}
