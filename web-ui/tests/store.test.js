import { describe, it, expect } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createStore } from '../src/store.js'

// 应用状态：bootstrap 失败也必须能进入主题/连接设置；工具列表来自真实服务。

function fakeBridge(handlers = {}) {
  const calls = []
  return {
    calls,
    kind: 'android',
    connected: true,
    call: async (method, params) => {
      calls.push([method, params])
      const fn = handlers[method]
      if (!fn) throw Object.assign(new Error('unsupported'), { code: 'unsupported' })
      return fn(params)
    },
    onEvent: () => () => {},
    dispose: () => {},
    describe: () => ({ kind: 'android', connected: true })
  }
}

const BOOTSTRAP = {
  versionName: '2.0.0',
  versionCode: 201,
  buildRevision: 'abc',
  ui: 'rust-vue',
  service: { running: true, starting: false, port: 8787, tokenRequired: true, endpoints: ['http://127.0.0.1:8787/mcp'], compact: false, clientCount: 2 },
  runtime: { userAgent: 'UA', bookSourceType: 0, bookSourceTypeName: '文本' },
  reader: { baseUrl: 'https://reader.example', tokenSaved: true, revision: 3 },
  appearance: { theme: 'system', palette: 'studio' },
  capabilities: { toolCount: 73, webMessageListener: true }
}

describe('store', () => {
  it.each([true, false])('鉴权从%s部分保存后标未知，重读失败不解锁，完整重读后才能反向切换', async initialRequired => {
    let hostRequired = initialRequired
    let readFails = false
    let partial = true
    const snapshot = () => ({ ...BOOTSTRAP, service: { ...BOOTSTRAP.service, tokenRequired: hostRequired }, capabilities: { tokenRequiredEditable: true } })
    const bridge = fakeBridge({
      'app.bootstrap': async () => {
        if (readFails) throw new Error('readback unavailable')
        return snapshot()
      },
      'settings.save': async ({ tokenRequired }) => {
        hostRequired = tokenRequired
        return partial ? { ...snapshot(), partial: true, failed: ['compact'] } : snapshot()
      }
    })
    const store = createStore({ bridge })
    await store.bootstrap()
    await expect(store.saveSettings({ tokenRequired: !initialRequired, compact: true })).rejects.toThrow(/compact/)
    expect(hostRequired).toBe(!initialRequired)
    expect(store.state.bootstrapOk).toBe(false)
    expect(store.state.bootstrapError).toMatch(/compact/)
    expect(store.state.toast?.kind).not.toBe('ok')
    await expect(store.saveSettings({ tokenRequired: initialRequired })).rejects.toThrow()
    expect(bridge.calls.filter(([m]) => m === 'settings.save')).toHaveLength(1)
    readFails = true
    await store.bootstrap()
    expect(store.state.bootstrapOk).toBe(false)
    expect(store.state.bootstrapError).toContain('readback unavailable')
    readFails = false
    await store.bootstrap()
    expect(store.state.bootstrapOk).toBe(true)
    expect(store.state.service.tokenRequired).toBe(hostRequired)
    partial = false
    await store.saveSettings({ tokenRequired: !store.state.service.tokenRequired })
    expect(bridge.calls.filter(([m]) => m === 'settings.save').at(-1)[1]).toEqual({ tokenRequired: initialRequired })
    expect(hostRequired).toBe(initialRequired)
  })

  it.each([
    ['missing receipt', null],
    ['empty receipt', {}],
    ['service-only receipt', { service: { tokenRequired: false } }],
    ['missing field', { service: { port: 8787 } }],
    ['wrong type', { service: { tokenRequired: 'false' } }],
    ['old state', BOOTSTRAP],
    ['partial', { ...BOOTSTRAP, service: { tokenRequired: false }, partial: true, failed: ['service'] }],
    ['failed fields', { ...BOOTSTRAP, service: { tokenRequired: false }, failed: ['service'] }]
  ])('鉴权保存拒绝%s且不能本地假改状态或报成功', async (_name, reply) => {
    const bridge = fakeBridge({
      'app.bootstrap': async () => ({ ...BOOTSTRAP, capabilities: { tokenRequiredEditable: true } }),
      'settings.save': async () => reply
    })
    const store = createStore({ bridge })
    await store.bootstrap()
    await expect(store.saveSettings({ tokenRequired: false })).rejects.toThrow(/回读|保存/)
    expect(store.state.service.tokenRequired).toBe(true)
    expect(store.state.bootstrapOk).toBe(false)
    expect(store.state.bootstrapError).toMatch(/回读|保存/)
    await expect(store.saveSettings({ tokenRequired: false })).rejects.toThrow()
    expect(bridge.calls.filter(([m]) => m === 'settings.save')).toHaveLength(1)
    expect(store.state.toast?.kind).not.toBe('ok')
    expect(bridge.calls.filter(([m]) => m === 'app.bootstrap')).toHaveLength(1)
  })

  it.each([
    ['missing', null], ['empty', {}], ['missing auth', { ...BOOTSTRAP, service: { port: 8787 } }],
    ['wrong auth type', { ...BOOTSTRAP, service: { tokenRequired: 'false' } }],
    ['partial', { ...BOOTSTRAP, partial: true, failed: ['compact'] }],
    ['failed', { ...BOOTSTRAP, failed: ['compact'] }]
  ])('鉴权状态未知后%s重读不能复活旧状态', async (_label, badRead) => {
    let reply = { ...BOOTSTRAP, capabilities: { tokenRequiredEditable: true } }
    const bridge = fakeBridge({ 'app.bootstrap': async () => reply, 'settings.save': async () => null })
    const store = createStore({ bridge }); await store.bootstrap()
    await expect(store.saveSettings({ tokenRequired: false })).rejects.toThrow()
    reply = badRead
    await store.bootstrap()
    expect(store.state.bootstrapOk).toBe(false)
    expect(store.state.bootstrapError).toMatch(/回读|读取/)
    await expect(store.saveSettings({ tokenRequired: false })).rejects.toThrow()
    expect(bridge.calls.filter(([m]) => m === 'settings.save')).toHaveLength(1)
  })

  it('无效的其他设置回包也不能解锁未知鉴权', async () => {
    const initial = { ...BOOTSTRAP, capabilities: { tokenRequiredEditable: true } }
    const store = createStore({ bridge: fakeBridge({ 'app.bootstrap': async () => initial, 'settings.save': async () => ({}) }) })
    await store.bootstrap()
    await expect(store.saveSettings({ tokenRequired: false })).rejects.toThrow()
    await expect(store.saveSettings({ compact: true })).rejects.toThrow(/回读/)
    expect(store.state.bootstrapOk).toBe(false)
  })

  it.each([true, false])('迟到service.set不能覆盖从%s保存后的鉴权值', async initialRequired => {
    let resolveService
    const initial = { ...BOOTSTRAP, service: { ...BOOTSTRAP.service, tokenRequired: initialRequired }, capabilities: { tokenRequiredEditable: true } }
    const bridge = fakeBridge({
      'app.bootstrap': async () => initial,
      'service.set': () => new Promise(resolve => { resolveService = resolve }),
      'settings.save': async ({ tokenRequired }) => ({ ...initial, service: { ...initial.service, tokenRequired } })
    })
    const store = createStore({ bridge }); await store.bootstrap()
    const oldService = store.setService('restart')
    await store.saveSettings({ tokenRequired: !initialRequired })
    resolveService(initial.service); await oldService
    expect(store.state.service.tokenRequired).toBe(!initialRequired)
    expect(store.state.bootstrap.service.tokenRequired).toBe(!initialRequired)
    expect(store.state.bootstrapOk).toBe(true)
  })

  it('保存期间service.set回包不能将旧鉴权重新合入状态', async () => {
    let resolveSave, resolveService
    const initial = { ...BOOTSTRAP, capabilities: { tokenRequiredEditable: true } }
    const bridge = fakeBridge({
      'app.bootstrap': async () => initial,
      'service.set': () => new Promise(resolve => { resolveService = resolve }),
      'settings.save': () => new Promise(resolve => { resolveSave = resolve })
    })
    const store = createStore({ bridge }); await store.bootstrap()
    const saving = store.saveSettings({ tokenRequired: false })
    const service = store.setService('restart')
    resolveService({ ...initial.service, tokenRequired: false }); await service
    expect(store.state.service.tokenRequired).toBe(true)
    expect(store.state.bootstrap.capabilities.tokenRequiredEditable).toBe(true)
    resolveSave({ ...initial, service: { ...initial.service, tokenRequired: false } }); await saving
    expect(store.state.service.tokenRequired).toBe(false)
  })

  it('鉴权仅采用完整回读且阻止并发保存，旧bootstrap不能覆盖保存结果', async () => {
    let resolveSave, resolveOld
    let reads = 0
    const initial = { ...BOOTSTRAP, capabilities: { tokenRequiredEditable: true } }
    const bridge = fakeBridge({
      'app.bootstrap': () => ++reads === 1 ? initial : new Promise(resolve => { resolveOld = resolve }),
      'settings.save': () => new Promise(resolve => { resolveSave = resolve })
    })
    const store = createStore({ bridge })
    await store.bootstrap()
    const oldRead = store.bootstrap()
    const saving = store.saveSettings({ tokenRequired: false })
    await expect(store.saveSettings({ tokenRequired: true })).rejects.toThrow(/保存中/)
    expect(store.state.settingsSaving).toBe(true)
    resolveSave({ ...initial, service: { ...initial.service, tokenRequired: false } })
    await saving
    resolveOld(initial); await oldRead
    expect(store.state.service.tokenRequired).toBe(false)
    expect(store.state.settingsSaving).toBe(false)
    expect(store.state.bootstrapping).toBe(false)
    expect(bridge.calls.filter(([m]) => m === 'settings.save')).toHaveLength(1)
  })

  it.each([
    ['android', {}], ['web', { tokenRequiredEditable: true }],
    ['android', { tokenRequiredEditable: true, tokenRequiredLocked: true }]
  ])('不支持的宿主%s/%j禁止发送鉴权写入', async (kind, capabilities) => {
    const bridge = fakeBridge({ 'app.bootstrap': async () => ({ ...BOOTSTRAP, capabilities }), 'settings.save': async () => BOOTSTRAP })
    bridge.kind = kind
    const store = createStore({ bridge }); await store.bootstrap()
    await expect(store.saveSettings({ tokenRequired: false })).rejects.toThrow(/不支持/)
    expect(bridge.calls.some(([m]) => m === 'settings.save')).toBe(false)
  })

  it('验证完成及迟到页面事件不会重新打开已关闭的验证视图', () => {
    const store = createStore({ bridge: fakeBridge({}) })
    for (const action of ['open', 'close', 'closed', 'completed', 'url', 'title']) {
      store.applyVerificationBrowser({ action, id: 'qa-session' })
    }
    expect(store.state.verificationBrowser.open).toBe(false)
  })

  it('旧会话完成不能关闭新会话，未登记事件不改变视图', () => {
    const store = createStore({ bridge: fakeBridge({}) })
    store.applyVerificationBrowser({ action: 'open', id: 'new-session' })
    store.applyVerificationBrowser({ action: 'completed', id: 'old-session' })
    expect(store.state.verificationBrowser).toMatchObject({ open: true, sessionId: 'new-session' })
    store.applyVerificationBrowser({ action: 'unknown', id: 'other' })
    expect(store.state.verificationBrowser).toMatchObject({ open: true, sessionId: 'new-session' })
    store.applyVerificationBrowser({ action: 'closed' })
    expect(store.state.verificationBrowser.open).toBe(false)
  })

  it('有效完成事件关闭当前验证视图，不依赖Promise回包先后', () => {
    const store = createStore({ bridge: fakeBridge({}) })
    store.applyVerificationBrowser({ action: 'open', id: 'qa-session' })
    store.applyVerificationBrowser({ action: 'completed', id: 'qa-session' })
    expect(store.state.verificationBrowser.open).toBe(false)
    store.applyVerificationBrowser({ action: 'title', id: 'qa-session' })
    expect(store.state.verificationBrowser.open).toBe(false)
  })
  it('bootstrap 成功写入真实快照', async () => {
    const bridge = fakeBridge({ 'app.bootstrap': async () => BOOTSTRAP })
    const store = createStore({ bridge })
    await store.bootstrap()
    expect(store.state.bootstrapOk).toBe(true)
    expect(store.state.service.port).toBe(8787)
    expect(store.state.capabilities.toolCount).toBe(73)
  })

  it('bootstrap 失败时记录错误但仍可访问设置与重试', async () => {
    const bridge = fakeBridge({
      'app.bootstrap': async () => {
        throw Object.assign(new Error('boom'), { code: 'internal' })
      }
    })
    const store = createStore({ bridge })
    await store.bootstrap()
    expect(store.state.bootstrapOk).toBe(false)
    expect(store.state.bootstrapError).toMatch(/boom/)
    // 设置 sheet 仍可打开（主题/连接设置不依赖 bootstrap）
    store.openSheet('settings')
    expect(store.state.sheet).toBe('settings')
  })

  it('未连接运行时：bootstrap 明确标记 runtime 未连接', async () => {
    const bridge = fakeBridge({})
    bridge.connected = false
    bridge.kind = 'none'
    const store = createStore({ bridge })
    await store.bootstrap()
    expect(store.state.bootstrapOk).toBe(false)
    expect(store.state.runtimeConnected).toBe(false)
    expect(store.state.bootstrapError).toMatch(/未连接运行时/)
  })

  it('工具列表只来自 tools.list，失败时不造目录', async () => {
    const bridge = fakeBridge({
      'app.bootstrap': async () => BOOTSTRAP,
      'tools.list': async () => ({ tools: [{ name: 'list_sources', description: 'd' }] })
    })
    const store = createStore({ bridge })
    await store.bootstrap()
    await store.loadTools()
    expect(store.state.tools.map((t) => t.name)).toEqual(['list_sources'])
    expect(store.state.toolsError).toBe('')

    const failing = createStore({
      bridge: fakeBridge({
        'app.bootstrap': async () => BOOTSTRAP,
        'tools.list': async () => {
          throw Object.assign(new Error('registry down'), { code: 'internal' })
        }
      })
    })
    await failing.bootstrap()
    await failing.loadTools()
    expect(failing.state.tools).toEqual([])
    expect(failing.state.toolsError).toMatch(/registry down/)
  })

  it('服务启停调用 service.set 并刷新状态', async () => {
    const bridge = fakeBridge({
      'app.bootstrap': async () => BOOTSTRAP,
      'service.set': async () => ({ ...BOOTSTRAP.service, running: false })
    })
    const store = createStore({ bridge })
    await store.bootstrap()
    await store.setService('stop')
    expect(bridge.calls.some(([m, p]) => m === 'service.set' && p.action === 'stop')).toBe(true)
    expect(store.state.service.running).toBe(false)
  })

  it.each([null, {}, { copied: false }, { copied: 'true' }])('复制回执%j不能假报成功，必须抛错', async receipt => {
    const store = createStore({ bridge: fakeBridge({ 'connection.copy': async () => receipt }) })
    await expect(store.copyConnection('token')).rejects.toThrow(/复制/)
    expect(store.state.toast?.kind).not.toBe('ok')
  })

  it('复制保留宿主真实错误且不报成功', async () => {
    const store = createStore({ bridge: fakeBridge({ 'connection.copy': async () => { throw new Error('clipboard denied') } }) })
    await expect(store.copyConnection('token')).rejects.toThrow('clipboard denied')
    expect(store.state.toast?.kind).not.toBe('ok')
  })

  it('连接复制只调用 connection.copy，不把 token 放进状态', async () => {
    const bridge = fakeBridge({
      'app.bootstrap': async () => BOOTSTRAP,
      'connection.copy': async () => ({ copied: true })
    })
    const store = createStore({ bridge })
    await store.bootstrap()
    const res = await store.copyConnection('token')
    expect(res).toEqual({ copied: true })
    expect(JSON.stringify(store.state)).not.toMatch(/bearer|Bearer/)
  })

  it('sheet 脏状态与关闭确认', () => {
    const store = createStore({ bridge: fakeBridge({}) })
    store.openSheet('settings')
    store.markDirty(true)
    expect(store.state.sheetDirty).toBe(true)
    store.requestDiscard()
    expect(store.state.dialog).toMatchObject({ kind: 'discard' })
    store.closeSheet()
    expect(store.state.sheet).toBe(null)
    expect(store.state.sheetDirty).toBe(false)
  })

  it('部分保存失败必须向调用方报错，不误报全部成功', async () => {
    const store = createStore({ bridge: fakeBridge({
      'settings.save': async () => ({ ...BOOTSTRAP, partial: true, failed: ['reader'] })
    }) })
    await expect(store.saveSettings({ readerBaseUrl: 'https://reader.example' })).rejects.toThrow(/reader/)
  })

  it('主题选择将 palette 和 mode 一起持久化至宿主', async () => {
    const bridge = fakeBridge({ 'settings.save': async () => BOOTSTRAP })
    const store = createStore({ bridge })
    store.setTheme(1)
    store.setMode('dark')
    await flushPromises()
    const saves = bridge.calls.filter(([m]) => m === 'settings.save')
    expect(saves.length).toBeGreaterThan(0)
    expect(saves.at(-1)[1].appearance).toEqual({ palette: 'lava', theme: 'dark' })
  })

  it('页面有未保存编辑时切页必须确认，取消后编辑仍在', () => {
    const store = createStore({ bridge: fakeBridge({}) })
    store.setPage('sources')
    store.markPageDirty(true)
    store.setPage('skills')
    expect(store.state.page).toBe('sources')
    store.closeDialog()
    expect(store.state.pageDirty).toBe(true)
    store.goRoot()
    const accept = store.state.dialog.onAccept
    store.closeDialog()
    accept()
    expect(store.state.page).toBe('mcp')
    expect(store.state.pageDirty).toBe(false)
  })

  it('放弃设置确认实际关闭 sheet，不丢失页面脏状态', () => {
    const store = createStore({ bridge: fakeBridge({}) })
    store.openSheet('settings')
    store.markDirty(true)
    store.requestDiscard()
    const accept = store.state.dialog.onAccept
    expect(typeof accept).toBe('function')
    store.closeDialog()
    accept()
    expect(store.state.sheet).toBeNull()
    expect(store.state.sheetDirty).toBe(false)
  })

  it('toast 队列只保留最新一条并有序号', () => {
    const store = createStore({ bridge: fakeBridge({}) })
    store.toast('已复制')
    const first = store.state.toast.seq
    store.toast('失败', 'err')
    expect(store.state.toast.seq).toBeGreaterThan(first)
    expect(store.state.toast.kind).toBe('err')
  })
})
