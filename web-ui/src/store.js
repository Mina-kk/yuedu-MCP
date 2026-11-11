import { reactive } from 'vue'
import { createBridge } from './lib/bridge.js'
import { loadTheme, saveTheme, modeFrom, paletteIndex, palette } from './lib/palettes.js'

// 应用状态（单一 reactive 对象）。所有业务数据都来自真实桥接/工具调用，
// 失败时保留错误文本，不填演示数据。

export const PAGES = ['mcp', 'sources', 'skills', 'verify', 'logs']

export const TOKEN_DISABLE_CONFIRM = {
  title: '关闭访问令牌校验？',
  message: '关闭后，能连接此 MCP 地址的本机或局域网设备将无需令牌即可访问工具和数据。仅在可信环境使用；现有令牌会保留，可随时重新开启校验。',
  confirmLabel: '确认关闭校验',
  danger: true
}

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
    settingsSaving: false,
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
    pageDirty: false,
    dialog: null,
    toast: null,
    // 工具
    tools: [],
    toolsLoading: false,
    toolsError: '',
    // Rust 核
    coreStatus: 'idle',
    coreError: '',
    coreVersion: '',
    // 输入法/安全区（宿主 studio:insets 一次性下发）
    insets: { top: 0, bottom: 0, ime: 0 },
    // 验证隔离页（宿主 studio:verification-browser 事件）
    captureWorkbenchOpen: false,
    verificationBrowser: { open: false, sessionId: '', action: '' }
  })

  let toastSeq = 0
  let toastTimer = 0
  // 主题版本号：每次用户本地改主题 +1。bootstrap 回包只允许在版本未变时写主题，
  // 否则旧响应（或慢响应）会覆盖用户刚选的主题。
  // 当前桥接：浏览器模式连接后会被替换，所有调用都走这里而不是初始闭包。
  let bridgeRef = bridge
  let themeEpoch = 0
  let bootstrapSeq = 0
  let settingsReadbackRequired = false
  let pendingAppearance = null
  let appearanceSaving = false

  async function persistAppearance() {
    if (bridgeRef.kind !== 'android') return
    pendingAppearance = { palette: palette(state.themeIdx).key, theme: state.mode }
    if (appearanceSaving) return
    appearanceSaving = true
    try {
      while (pendingAppearance) {
        const appearance = pendingAppearance
        pendingAppearance = null
        const snapshot = await bridgeRef.call('settings.save', { appearance })
        if (snapshot?.partial) throw new Error('外观保存失败')
      }
    } catch (error) {
      toast(error?.message || '外观保存失败', 'err')
    } finally { appearanceSaving = false }
  }

  function toast(text, kind = 'ok') {
    toastSeq += 1
    state.toast = { text: String(text), kind, seq: toastSeq }
    if (toastTimer) clearTimeout(toastTimer)
    toastTimer = setTimeout(() => {
      if (state.toast && state.toast.seq === toastSeq) state.toast = null
    }, kind === 'err' ? 5200 : 2600)
  }

  /**
   * 应用 bootstrap 快照。appearance 只在用户本次会话还没动过主题时采用宿主值，
   * 避免 settings.save 的慢回包把刚选的主题改回去。
   */
  function applyBootstrap(snapshot) {
    if (!snapshot || typeof snapshot !== 'object') return
    state.bootstrap = snapshot
    if (snapshot.service) state.service = { ...state.service, ...snapshot.service }
    if (snapshot.runtime) state.runtime = { ...state.runtime, ...snapshot.runtime }
    if (snapshot.reader) state.reader = { ...state.reader, ...snapshot.reader }
    if (snapshot.capabilities) state.capabilities = { ...snapshot.capabilities }
  }

  function adoptHostAppearance(snapshot, epochAtRequest) {
    if (epochAtRequest !== themeEpoch) return
    const appearance = snapshot && snapshot.appearance
    if (!appearance) return
    if (typeof appearance.palette === 'string' && appearance.palette) {
      // 宿主存 palette key（lime…cherry）；未知值由 paletteIndex 回落默认。
      state.themeIdx = paletteIndex(appearance.palette)
    }
    if (typeof appearance.theme === 'string' && appearance.theme) {
      state.mode = modeFrom(appearance.theme)
    }
  }

  function hasAuthSnapshot(snapshot) {
    return snapshot && !snapshot.partial && !snapshot.failed?.length &&
      [snapshot.service, snapshot.runtime, snapshot.reader, snapshot.capabilities]
        .every(value => value && typeof value === 'object' && !Array.isArray(value)) &&
      typeof snapshot.service.tokenRequired === 'boolean'
  }

  async function bootstrap() {
    bootstrapSeq += 1
    const seq = bootstrapSeq
    const epochAtRequest = themeEpoch
    state.bootstrapping = true
    state.bootstrapError = ''
    try {
      if (!bridgeRef.connected) {
        throw Object.assign(new Error('未连接运行时：当前页面没有 Android 宿主桥，可在设置里显式连接 MCP 地址'), {
          code: 'runtime_unavailable'
        })
      }
      const snapshot = await bridgeRef.call('app.bootstrap', {})
      // 只接受最后一次 bootstrap 的回包，避免并发重试互相覆盖。
      if (seq !== bootstrapSeq) return snapshot
      if ((settingsReadbackRequired || snapshot?.capabilities?.tokenRequiredEditable === true) && !hasAuthSnapshot(snapshot)) {
        throw new Error('状态回读无效或不完整，请重新读取状态')
      }
      applyBootstrap(snapshot)
      adoptHostAppearance(snapshot, epochAtRequest)
      settingsReadbackRequired = false
      state.bootstrapOk = true
      return snapshot
    } catch (error) {
      if (seq === bootstrapSeq) {
        state.bootstrapOk = false
        state.bootstrapError = (error && error.message) || 'bootstrap 失败'
      }
      return null
    } finally {
      if (seq === bootstrapSeq) state.bootstrapping = false
    }
  }

  async function loadTools() {
    state.toolsLoading = true
    state.toolsError = ''
    try {
      const result = await bridgeRef.call('tools.list', {})
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
    return bridgeRef.call('tools.call', { name, arguments: args || {} })
  }

  async function setService(action) {
    const seq = bootstrapSeq
    try {
      const result = await bridgeRef.call('service.set', { action })
      // service.set also contains auth. A response crossing a save/read boundary
      // is stale; the page will obtain a fresh bootstrap after the action.
      if (result && seq === bootstrapSeq && !state.settingsSaving) {
        applyBootstrap({ ...state.bootstrap, service: { ...state.service, ...result } })
      }
      toast(action === 'stop' ? '已请求停止服务' : action === 'restart' ? '已请求重启服务' : '已请求启动服务')
      return result
    } catch (error) {
      toast((error && error.message) || '服务操作失败', 'err')
      throw error
    }
  }

  async function copyConnection(kind, lan = false) {
    if (state.settingsSaving) throw new Error('设置保存中，请稍候再复制')
    const result = await bridgeRef.call('connection.copy', { kind, lan })
    if (result?.copied !== true) throw new Error('复制失败：宿主未确认写入剪贴板')
    toast('已复制到剪贴板')
    return result
  }

  function setTheme(idx) {
    themeEpoch += 1
    state.themeIdx = idx
    saveTheme(store, state.themeIdx, state.mode)
    persistAppearance()
  }

  function setMode(mode) {
    themeEpoch += 1
    state.mode = modeFrom(mode)
    saveTheme(store, state.themeIdx, state.mode)
    persistAppearance()
  }

  /**
   * settings.save：宿主整体校验后返回 bootstrap 回读快照。
   * 失败必须抛出真实错误给设置面板显示，不能假装保存成功。
   */
  async function saveSettings(payload = {}) {
    if (state.settingsSaving) throw new Error('设置保存中，请稍候')
    const changesSecret = payload.mcpToken != null && payload.mcpToken !== ''
    if (changesSecret && (typeof payload.mcpToken !== 'string' || payload.mcpToken.length > 4096 ||
      !/^[A-Za-z0-9._~+/-]+=*$/.test(payload.mcpToken) || /\s/.test(payload.mcpToken))) {
      throw new Error('新访问令牌格式无效：限 1–4096 位 token68 字符，不允许空白')
    }
    if (changesSecret && (bridgeRef.kind !== 'android' || !bridgeRef.connected || !state.bootstrapOk ||
      state.capabilities.mcpTokenEditable !== true)) throw new Error('当前宿主或状态不支持修改访问令牌')
    const changesToken = payload.tokenRequired != null
    if (changesToken && (bridgeRef.kind !== 'android' || !bridgeRef.connected || !state.bootstrapOk ||
      state.capabilities.tokenRequiredEditable !== true || state.capabilities.tokenRequiredLocked === true)) {
      throw new Error('当前宿主不支持更改访问令牌校验')
    }
    if (changesToken && typeof payload.tokenRequired !== 'boolean') throw new Error('访问令牌校验必须为布尔值')
    state.settingsSaving = true
    const epochAtRequest = themeEpoch
    // Invalidate older reads, including one started while this save is in flight.
    bootstrapSeq += 1
    state.bootstrapping = false
    try {
      const snapshot = await bridgeRef.call('settings.save', payload)
      bootstrapSeq += 1
      state.bootstrapping = false
      if (!snapshot || typeof snapshot !== 'object' || Array.isArray(snapshot)) {
        throw new Error('设置保存回读无效，未确认生效，请重新读取状态')
      }
      if (snapshot.partial || (Array.isArray(snapshot.failed) && snapshot.failed.length)) {
        throw new Error(`部分设置未保存：${Array.isArray(snapshot.failed) ? snapshot.failed.join('、') || '未知字段' : '未知字段'}；请重新读取状态`)
      }
      if (changesSecret && snapshot.settingsResult?.mcpTokenApplied !== true) {
        throw new Error('访问令牌更新结果未知')
      }
      if (((changesToken || changesSecret || settingsReadbackRequired) && !hasAuthSnapshot(snapshot)) ||
        (changesToken && snapshot.service.tokenRequired !== payload.tokenRequired)) {
        throw new Error('访问令牌校验保存回读不一致或缺失，未确认生效，请重新读取状态')
      }
      applyBootstrap(snapshot)
      adoptHostAppearance(snapshot, epochAtRequest)
      settingsReadbackRequired = false
      state.bootstrapOk = true
      state.bootstrapError = ''
      return snapshot
    } catch (error) {
      // A failed/partial write can still have changed auth on the host. Keep
      // drafts and the last snapshot, but never treat it as current until reread.
      // Never forward arbitrary host error text from a write-only secret path.
      if (changesSecret) error = new Error('访问令牌更新结果未知或设置未全部保存；请重新读取状态后重试，或复制当前配置核验')
      bootstrapSeq += 1
      settingsReadbackRequired = true
      state.bootstrapping = false
      state.bootstrapOk = false
      state.bootstrapError = error?.message || '设置保存未确认，请重新读取状态'
      throw error
    } finally { state.settingsSaving = false }
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

  function markPageDirty(dirty = true) {
    state.pageDirty = Boolean(dirty)
  }

  function requestDiscard(sheet) {
    state.dialog = {
      kind: 'discard',
      title: '放弃未保存的修改？',
      message: '当前编辑内容还没有保存，关闭后不会保留。',
      confirmLabel: '放弃修改',
      danger: true,
      sheet: sheet || state.sheet,
      onAccept: () => closeSheet()
    }
  }

  function confirmDialog(options) {
    state.dialog = { kind: 'confirm', ...options }
  }

  function closeDialog() {
    state.dialog = null
  }

  async function hostAction(method, params = {}) {
    if (state.settingsSaving) throw new Error('设置保存中，请稍候')
    if (bridgeRef.kind !== 'android' || !bridgeRef.connected) throw new Error('需要 Android 宿主')
    const result = await bridgeRef.call(method, params)
    if (!await bootstrap()) throw new Error('操作后状态回读失败，请重新读取状态')
    return result
  }

  async function exitApp(stopService = false) {
    if (typeof stopService !== 'boolean') throw new Error('停止服务选项无效')
    return bridgeRef.call('app.exit', { stopService })
  }

  function requestExit() {
    state.dialog = {
      kind: 'exit',
      title: '退出阅读书源 MCP？',
      message: '默认仅关闭界面，保持 MCP 服务当前状态。系统仍可能限制后台运行；可选择同时停止服务。',
      stopService: false,
      confirmLabel: '退出',
      danger: true,
      action: 'app.exit'
    }
  }

  function setPage(page) {
    if (!PAGES.includes(page) || page === state.page) return
    if (state.pageDirty) {
      confirmDialog({
        kind: 'discard', title: '放弃未保存的修改？',
        message: '切换页面会丢失当前编辑内容。', confirmLabel: '放弃并切换', danger: true,
        onAccept: () => { state.pageDirty = false; state.page = page }
      })
      return
    }
    state.page = page
  }

  function goRoot() {
    setPage('mcp')
  }

  function applyInsets(payload) {
    const p = payload && typeof payload === 'object' ? payload : {}
    const num = (v) => (Number.isFinite(Number(v)) ? Math.max(0, Number(v)) : 0)
    state.insets = { top: num(p.top), bottom: num(p.bottom), ime: num(p.ime) }
  }

  function applyVerificationBrowser(payload) {
    const p = payload && typeof payload === 'object' ? payload : {}
    const action = typeof p.action === 'string' ? p.action : ''
    const id = typeof p.id === 'string' ? p.id : ''
    const current = state.verificationBrowser
    if (action === 'open') {
      if (id) state.verificationBrowser = { open: true, sessionId: id, action }
      return
    }
    if (!['close', 'closed', 'completed', 'url', 'title'].includes(action)) return
    if (id && id !== current.sessionId) return
    if (action === 'url' || action === 'title') {
      // Metadata can arrive after destroy; it must never resurrect a closed page.
      if (current.open) state.verificationBrowser = { ...current, action }
      return
    }
    state.verificationBrowser = { ...current, open: false, action }
  }

  /** 浏览器模式：显式配置 MCP 地址后替换桥接并重读状态。 */
  async function connectWebRuntime(factory) {
    if (typeof factory !== 'function') throw new Error('缺少运行时工厂')
    const next = factory()
    if (!next || !next.connected) throw new Error('运行时地址无效')
    if (bridgeRef.dispose) bridgeRef.dispose('replace')
    bridgeRef = next
    state.bridgeKind = next.kind
    state.runtimeConnected = next.connected
    state.webEndpoint = next.describe ? next.describe().endpoint || '' : ''
    return bootstrap()
  }

  function openCaptureWorkbench() {
    if (state.verificationBrowser.open || state.sheet || state.dialog) return false
    state.captureWorkbenchOpen = true
    return true
  }
  function closeCaptureWorkbench() { state.captureWorkbenchOpen = false }

  const api = {
    openCaptureWorkbench,
    closeCaptureWorkbench,
    state,
    get bridge() {
      return bridgeRef
    },
    toast,
    bootstrap,
    loadTools,
    callTool,
    setService,
    copyConnection,
    setTheme,
    setMode,
    saveSettings,
    hostAction,
    connectWebRuntime,
    openSheet,
    closeSheet,
    markDirty,
    markPageDirty,
    requestDiscard,
    confirmDialog,
    closeDialog,
    requestExit,
    exitApp,
    setPage,
    goRoot,
    applyInsets,
    applyVerificationBrowser
  }
  return api
}
