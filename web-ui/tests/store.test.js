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
