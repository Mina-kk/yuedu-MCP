import { afterEach, describe, it, expect } from 'vitest'
import { enableAutoUnmount, mount, flushPromises } from '@vue/test-utils'
enableAutoUnmount(afterEach)
import { createStore } from '../src/store.js'
import McpPage from '../src/pages/McpPage.vue'

function fakeBridge(handlers = {}) {
  const calls = []
  return {
    calls,
    kind: 'android',
    connected: true,
    call: async (method, params) => {
      calls.push([method, params])
      const fn = handlers[method]
      if (!fn) throw Object.assign(new Error(`unsupported ${method}`), { code: 'unsupported' })
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
  service: {
    running: true,
    starting: false,
    port: 8787,
    tokenRequired: true,
    endpoints: ['http://127.0.0.1:8787/mcp', 'http://192.168.1.5:8787/mcp'],
    compact: false,
    clientCount: 2
  },
  runtime: { userAgent: 'UA', bookSourceType: 0 },
  reader: { baseUrl: '', tokenSaved: false, revision: 0 },
  capabilities: { toolCount: 73 }
}

const TOOLS = {
  tools: [
    { name: 'list_sources', description: '列出书源', inputSchema: { type: 'object', properties: {} }, annotations: { readOnlyHint: true } },
    { name: 'delete_projects', description: '删除项目', inputSchema: { type: 'object', properties: { ids: { type: 'array' } } }, annotations: { destructiveHint: true } }
  ]
}

async function mountPage(handlers) {
  const bridge = fakeBridge(handlers)
  const store = createStore({ bridge, storage: null })
  await store.bootstrap()
  const wrapper = mount(McpPage, { attachTo: document.body, props: { store } })
  await flushPromises()
  return { wrapper, store, bridge }
}

describe('McpPage', () => {
  it('应用内指南使用bootstrap版本，独立展示并返回入口焦点', async () => {
    const { wrapper } = await mountPage({ 'app.bootstrap': () => ({ ...BOOTSTRAP, versionName: 'fixture-build', versionCode: 987 }), 'tools.list': () => TOOLS })
    const entry = wrapper.findAll('button').find(b => b.text() === '功能指南')
    expect(entry).toBeTruthy()
    entry.element.focus()
    await entry.trigger('click'); await flushPromises()
    const guide = wrapper.get('[aria-label="功能指南"]')
    expect(guide.text()).toContain('fixture-build')
    expect(guide.text()).toContain('987')
    for (const text of ['人工工作流', 'AI 工作流', 'OkHttp', '无头', '可交互', '沙箱', '阅读端', '敏感 URL']) expect(guide.text()).toContain(text)
    expect(wrapper.find('.tool-row').isVisible()).toBe(false)
    expect(wrapper.vm.handleBack()).toBe(true)
    await flushPromises()
    expect(wrapper.find('[aria-label="功能指南"]').exists()).toBe(false)
    expect(document.activeElement).toBe(entry.element)
  })

  it('选择局域网配置只传lan标记，复制中禁用并不重复请求', async () => {
    let finish
    const { wrapper, bridge } = await mountPage({ 'app.bootstrap': () => BOOTSTRAP, 'tools.list': () => TOOLS,
      'connection.copy': () => new Promise(resolve => { finish = resolve }) })
    await wrapper.get('[data-connection-lan]').trigger('click')
    const copy = wrapper.findAll('button').find(b => b.text() === '复制客户端配置')
    await copy.trigger('click'); await flushPromises()
    expect(copy.attributes('disabled')).toBeDefined()
    await copy.trigger('click')
    expect(bridge.calls.filter(([m]) => m === 'connection.copy')).toEqual([['connection.copy', { kind: 'json', lan: true }]])
    finish({ copied: true }); await flushPromises()
    expect(copy.attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-connection-local]').trigger('click')
    await copy.trigger('click')
    expect(bridge.calls.at(-1)).toEqual(['connection.copy', { kind: 'json', lan: false }])
    finish({ copied: true }); await flushPromises()
  })

  it.each(['header-name', 'header-value', 'header-line'])('请求头%s仅通过已声明能力的宿主复制并检查精确kind回执', async kind => {
    let receipt = { copied: true, kind: 'token' }
    const { wrapper, store, bridge } = await mountPage({
      'app.bootstrap': () => ({ ...BOOTSTRAP, capabilities: { connectionHeaderCopy: true } }), 'tools.list': () => TOOLS,
      'connection.copy': () => receipt
    })
    const button = wrapper.get(`[data-copy-kind="${kind}"]`)
    const before = wrapper.html()
    await button.trigger('click'); await flushPromises()
    expect(bridge.calls.at(-1)).toEqual(['connection.copy', { kind, lan: false }])
    expect(store.state.toast.kind).toBe('err')
    receipt = { copied: true, kind }
    await button.trigger('click'); await flushPromises()
    expect(store.state.toast.kind).toBe('ok')
    expect(wrapper.html()).toBe(before)
    expect(wrapper.text()).toContain('不支持 OAuth')
    expect(wrapper.text()).toContain('不要再加 Bearer 前缀')
  })

  it('旧宿主不可复制敏感请求头；鉴权关闭时不建议使用请求头', async () => {
    const { wrapper, bridge } = await mountPage({ 'app.bootstrap': () => BOOTSTRAP, 'tools.list': () => TOOLS })
    for (const kind of ['header-value', 'header-line']) {
      const button = wrapper.get(`[data-copy-kind="${kind}"]`)
      expect(button.attributes('disabled')).toBeDefined()
      await button.trigger('click')
    }
    expect(bridge.calls.some(([m]) => m === 'connection.copy')).toBe(false)
    expect(wrapper.text()).toContain('宿主尚未支持请求头复制')
  })

  it('端点列表仅展示实际端点，复制入口按连接位置而非猜IP选择', async () => {
    const { wrapper } = await mountPage({ 'app.bootstrap': () => BOOTSTRAP, 'tools.list': () => TOOLS })
    expect(wrapper.findAll('.endpoint-row button')).toHaveLength(0)
    expect(wrapper.findAll('button').some(b => b.text() === '复制所选连接地址')).toBe(true)
  })

  it.each([{}, { copied: false, kind: 'header-value' }, { copied: 'true', kind: 'header-value' }])('请求头拒绝不完整回执%j且busy保护所有复制', async receipt => {
    let finish
    const { wrapper, store, bridge } = await mountPage({
      'app.bootstrap': () => ({ ...BOOTSTRAP, capabilities: { connectionHeaderCopy: true } }), 'tools.list': () => TOOLS,
      'connection.copy': () => new Promise(resolve => { finish = resolve })
    })
    await wrapper.get('[data-copy-kind="header-value"]').trigger('click')
    for (const button of wrapper.findAll('[data-copy-kind]')) expect(button.attributes('disabled')).toBeDefined()
    await wrapper.get('[data-copy-kind="header-line"]').trigger('click')
    expect(bridge.calls.filter(([m]) => m === 'connection.copy')).toHaveLength(1)
    finish(receipt); await flushPromises()
    expect(store.state.toast.kind).toBe('err')
    store.state.service.tokenRequired = false
    await flushPromises()
    expect(wrapper.get('[data-copy-kind="header-value"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Token 和 Authorization 请求头留空')
  })

  it('修改访问令牌入口打开真实设置，保存中复制不可用', async () => {
    const { wrapper, store } = await mountPage({ 'app.bootstrap': () => BOOTSTRAP, 'tools.list': () => TOOLS })
    const edit = wrapper.findAll('button').find(b => b.text() === '修改访问令牌')
    expect(edit).toBeTruthy()
    await edit.trigger('click')
    expect(store.state.sheet).toBe('settings')
    store.state.settingsSaving = true
    await flushPromises()
    for (const label of ['复制令牌', '复制客户端配置']) {
      expect(wrapper.findAll('button').find(b => b.text() === label).attributes('disabled')).toBeDefined()
    }
  })
  it('首页关闭访问令牌校验先Vue确认，取消不写，确认后仅保存鉴权字段并等真实回读', async () => {
    let resolveSave
    const initial = { ...BOOTSTRAP, capabilities: { tokenRequiredEditable: true, tokenRequiredLocked: false } }
    const { wrapper, store, bridge } = await mountPage({
      'app.bootstrap': async () => initial,
      'tools.list': async () => TOOLS,
      'settings.save': () => new Promise(resolve => { resolveSave = resolve })
    })
    const toggle = wrapper.find('[role="switch"][aria-label="访问令牌校验"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.attributes('aria-checked')).toBe('true')
    await toggle.trigger('click')
    expect(store.state.dialog?.message).toMatch(/本机|局域网/)
    expect(store.state.dialog?.message).toMatch(/无需令牌|无令牌/)
    store.closeDialog()
    expect(bridge.calls.filter(([m]) => m === 'settings.save')).toEqual([])
    await toggle.trigger('click')
    const accept = store.state.dialog.onAccept
    store.closeDialog()
    const saving = accept()
    await flushPromises()
    expect(toggle.attributes('disabled')).toBeDefined()
    expect(toggle.attributes('aria-checked')).toBe('true')
    await toggle.trigger('click')
    expect(bridge.calls.filter(([m]) => m === 'settings.save')).toEqual([['settings.save', { tokenRequired: false }]])
    resolveSave({ ...initial, service: { ...BOOTSTRAP.service, tokenRequired: false } })
    await saving; await flushPromises()
    expect(toggle.attributes('aria-checked')).toBe('false')
    expect(store.state.toast?.kind).toBe('ok')
  })

  it('关闭状态指引留空Token和Authorization，复制令牌仍可用；开启直接保存不弹风险确认', async () => {
    const off = { ...BOOTSTRAP, service: { ...BOOTSTRAP.service, tokenRequired: false }, capabilities: { tokenRequiredEditable: true } }
    const { wrapper, store, bridge } = await mountPage({
      'app.bootstrap': async () => off, 'tools.list': async () => TOOLS,
      'settings.save': async ({ tokenRequired }) => ({ ...off, service: { ...off.service, tokenRequired } }),
      'connection.copy': async () => ({ copied: true })
    })
    expect(wrapper.text()).toContain('免令牌')
    expect(wrapper.text()).toContain('Token 和 Authorization 请求头留空')
    await wrapper.findAll('button').find(b => b.text() === '复制令牌').trigger('click'); await flushPromises()
    expect(bridge.calls).toContainEqual(['connection.copy', { kind: 'token', lan: false }])
    await wrapper.get('[aria-label="访问令牌校验"]').trigger('click'); await flushPromises()
    expect(store.state.dialog).toBeNull()
    expect(bridge.calls).toContainEqual(['settings.save', { tokenRequired: true }])
    expect(wrapper.get('[aria-label="访问令牌校验"]').attributes('aria-checked')).toBe('true')
  })

  it.each([
    ['android', {}], ['web', { tokenRequiredEditable: true }],
    ['android', { tokenRequiredEditable: false }]
  ])('首页宿主%s/%j不支持更改时明确禁用，不发送写入', async (kind, capabilities) => {
    const bridge = fakeBridge({ 'app.bootstrap': async () => ({ ...BOOTSTRAP, capabilities }), 'tools.list': async () => TOOLS })
    bridge.kind = kind
    const store = createStore({ bridge }); await store.bootstrap()
    const wrapper = mount(McpPage, { props: { store } }); await flushPromises()
    const toggle = wrapper.get('[aria-label="访问令牌校验"]')
    expect(toggle.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('不支持更改访问令牌校验')
    await toggle.trigger('click')
    expect(bridge.calls.some(([m]) => m === 'settings.save')).toBe(false)
  })

  it.each(['rejected', 'mismatch', 'partial', 'missing'])('首页保存%s保留错误并标未知，不toast成功', async result => {
    const initial = { ...BOOTSTRAP, capabilities: { tokenRequiredEditable: true } }
    const { wrapper, store } = await mountPage({
      'app.bootstrap': async () => initial, 'tools.list': async () => TOOLS,
      'settings.save': async () => {
        if (result === 'rejected') throw new Error('host write failed')
        if (result === 'missing') return {}
        if (result === 'mismatch') return initial
        return { ...initial, service: { ...initial.service, tokenRequired: false }, partial: true, failed: ['service'] }
      }
    })
    await wrapper.get('[aria-label="访问令牌校验"]').trigger('click')
    const accept = store.state.dialog.onAccept; store.closeDialog(); await accept(); await flushPromises()
    const toggle = wrapper.get('[aria-label="访问令牌校验"]')
    expect(toggle.attributes('aria-checked')).toBeUndefined()
    expect(toggle.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('访问令牌校验状态未知')
    expect(wrapper.text()).not.toMatch(/已开启 · 连接需要令牌|已关闭 · 免令牌访问|当前免令牌访问|将纯令牌粘贴/)
    expect(wrapper.get('[role="alert"]').text()).toMatch(/failed|保存/)
    expect(store.state.toast.kind).toBe('err')
  })

  it.each([true, false])('首页从%s部分保存后不再展示或切换旧值，重读恢复后反向写入', async initialRequired => {
    let hostRequired = initialRequired
    let readFails = false
    const snapshot = () => ({ ...BOOTSTRAP, service: { ...BOOTSTRAP.service, tokenRequired: hostRequired }, capabilities: { tokenRequiredEditable: true } })
    const { wrapper, store, bridge } = await mountPage({
      'app.bootstrap': async () => { if (readFails) throw new Error('readback failed'); return snapshot() },
      'tools.list': async () => TOOLS,
      'settings.save': async payload => {
        hostRequired = payload.tokenRequired
        return payload.compact ? { ...snapshot(), partial: true, failed: ['compact'] } : snapshot()
      }
    })
    // The settings panel can save auth successfully while compact fails.
    await expect(store.saveSettings({ tokenRequired: !initialRequired, compact: true })).rejects.toThrow('compact')
    await flushPromises()
    const toggle = wrapper.get('[aria-label="访问令牌校验"]')
    expect(toggle.attributes('aria-checked')).toBeUndefined()
    expect(wrapper.text()).toContain('访问令牌校验状态未知')
    expect(toggle.attributes('disabled')).toBeDefined()
    await toggle.trigger('click')
    expect(store.state.dialog).toBeNull()
    expect(bridge.calls.filter(([m]) => m === 'settings.save')).toHaveLength(1)
    readFails = true
    await wrapper.findAll('button').find(b => b.text() === '重试').trigger('click'); await flushPromises()
    expect(toggle.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('readback failed')
    readFails = false
    await wrapper.findAll('button').find(b => b.text() === '重试').trigger('click'); await flushPromises()
    expect(toggle.attributes('disabled')).toBeUndefined()
    expect(toggle.attributes('aria-checked')).toBe(String(!initialRequired))
    await toggle.trigger('click')
    if (!initialRequired) {
      const accept = store.state.dialog.onAccept
      store.closeDialog(); await accept()
    }
    await flushPromises()
    expect(bridge.calls.filter(([m]) => m === 'settings.save').at(-1)[1]).toEqual({ tokenRequired: initialRequired })
    expect(toggle.attributes('aria-checked')).toBe(String(initialRequired))
    expect(store.state.toast.kind).toBe('ok')
  })

  it('复制失败显示真实错误，不假报已复制', async () => {
    const { wrapper, store } = await mountPage({ 'app.bootstrap': async () => BOOTSTRAP, 'tools.list': async () => TOOLS, 'connection.copy': async () => ({ copied: false }) })
    await wrapper.findAll('button').find(b => b.text() === '复制令牌').trigger('click'); await flushPromises()
    expect(store.state.toast.kind).toBe('err')
    expect(store.state.toast.text).not.toContain('已复制')
  })

  it('共享设置保存期间首页开关禁用，即使页面重挂也不重复操作', async () => {
    const { wrapper, store, bridge } = await mountPage({
      'app.bootstrap': async () => ({ ...BOOTSTRAP, capabilities: { tokenRequiredEditable: true } }), 'tools.list': async () => TOOLS
    })
    store.state.settingsSaving = true
    await flushPromises()
    expect(wrapper.get('[aria-label="访问令牌校验"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[aria-label="访问令牌校验"]').trigger('click')
    expect(store.state.dialog).toBeNull()
    expect(bridge.calls.some(([m]) => m === 'settings.save')).toBe(false)
  })

  it('系统返回保护未保存内容，取消保留、确认才退出', async () => {
    const { wrapper, store } = await mountPage({ 'app.bootstrap': async () => BOOTSTRAP, 'tools.list': async () => TOOLS })
    await wrapper.find('.tool-row').trigger('click'); await flushPromises()
    await wrapper.find('textarea').setValue('未保存草稿')
    expect(wrapper.vm.handleBack()).toBe(true)
    expect(store.state.dialog?.kind).toBe('discard')
    store.closeDialog()
    expect(wrapper.find('textarea').element.value).toBe('未保存草稿')
    expect(wrapper.vm.handleBack()).toBe(true)
    const accept = store.state.dialog.onAccept
    store.closeDialog(); await accept(); await flushPromises()
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('.tool-row').isVisible()).toBe(true)
  })

  it('详情独立展示，顶部返回恢复列表与滚动且不聚焦表单', async () => {
    const { wrapper } = await mountPage({ 'app.bootstrap': async () => BOOTSTRAP, 'tools.list': async () => TOOLS })
    const page = wrapper.find('.page').element
    page.scrollTop = 320
    const list = wrapper.find('.tool-row').element.parentElement
    list.scrollTop = 75
    await wrapper.find('.tool-row').trigger('click'); await flushPromises()
    expect(wrapper.find('.tool-row').isVisible()).toBe(false)
    const back = wrapper.find('[data-detail-back]')
    expect(back.exists()).toBe(true)
    expect(back.isVisible()).toBe(true)
    expect(page.scrollTop).toBe(0)
    expect(document.activeElement?.tagName).not.toMatch(/INPUT|TEXTAREA/)
    await back.trigger('click'); await flushPromises()
    expect(wrapper.find('.tool-row').isVisible()).toBe(true)
    expect(page.scrollTop).toBe(320)
    expect(list.scrollTop).toBe(75)
    expect(wrapper.vm.handleBack()).toBe(false)
  })

  it('未连接时不假装已知服务停止或工具数量为零', async () => {
    const bridge = fakeBridge({})
    bridge.connected = false
    const store = createStore({ bridge, storage: null })
    await store.bootstrap()
    const wrapper = mount(McpPage, { attachTo: document.body, props: { store } })
    await flushPromises()
    expect(wrapper.find('.stat-value').text()).toBe('状态未知')
    expect(wrapper.text()).toContain('未加载')
  })

  it('显示真实服务状态、端口与端点，不显示 token', async () => {
    const { wrapper } = await mountPage({
      'app.bootstrap': async () => BOOTSTRAP,
      'tools.list': async () => TOOLS
    })
    expect(wrapper.text()).toContain('运行中')
    expect(wrapper.text()).toContain('8787')
    expect(wrapper.text()).toContain('http://127.0.0.1:8787/mcp')
    expect(wrapper.findAll('code').some(code => code.text() === ['Authorization:', 'Bearer', '<令牌>'].join(' '))).toBe(true)
    expect(wrapper.text()).toContain('纯令牌')
    expect(wrapper.text()).toContain('无需 OAuth')
    expect(wrapper.html()).not.toMatch(/token=|type="password"/i)
  })

  it('启动/停止按钮调用 service.set', async () => {
    const { wrapper, bridge } = await mountPage({
      'app.bootstrap': async () => BOOTSTRAP,
      'tools.list': async () => TOOLS,
      'service.set': async () => ({ ...BOOTSTRAP.service, running: false })
    })
    const stop = wrapper.findAll('button').find((b) => b.text().includes('停止'))
    await stop.trigger('click')
    await flushPromises()
    expect(bridge.calls.some(([m, p]) => m === 'service.set' && p.action === 'stop')).toBe(true)
  })

  it('复制令牌只调用 connection.copy，不把内容放进 DOM', async () => {
    const { wrapper, bridge } = await mountPage({
      'app.bootstrap': async () => BOOTSTRAP,
      'tools.list': async () => TOOLS,
      'connection.copy': async () => ({ copied: true })
    })
    const beforeCopy = wrapper.html()
    const copyToken = wrapper.findAll('button').find((b) => b.text().includes('复制令牌'))
    await copyToken.trigger('click')
    await flushPromises()
    expect(bridge.calls.some(([m, p]) => m === 'connection.copy' && p.kind === 'token')).toBe(true)
    expect(wrapper.html()).toBe(beforeCopy)
    // “Bearer Token” is the client field label, never a credential.
    expect(wrapper.html()).not.toMatch(/Bearer\s+(?!Token\b)[A-Za-z0-9]/)
  })

  it('工具目录来自 tools.list，可按名称搜索', async () => {
    const { wrapper } = await mountPage({
      'app.bootstrap': async () => BOOTSTRAP,
      'tools.list': async () => TOOLS
    })
    expect(wrapper.text()).toContain('list_sources')
    expect(wrapper.text()).toContain('2 / 73')
    await wrapper.find('input[type="search"]').setValue('delete')
    expect(wrapper.text()).toContain('delete_projects')
    expect(wrapper.text()).not.toContain('list_sources')
  })

  it('工具调用器：参数 JSON 非法时本地报错，不调用工具', async () => {
    const { wrapper, bridge } = await mountPage({
      'app.bootstrap': async () => BOOTSTRAP,
      'tools.list': async () => TOOLS
    })
    const row = wrapper.findAll('.tool-row')[0]
    await row.trigger('click')
    const textarea = wrapper.find('textarea')
    await textarea.setValue('{bad json')
    const run = wrapper.findAll('button').find((b) => b.text().includes('调用'))
    await run.trigger('click')
    await flushPromises()
    expect(bridge.calls.some(([m]) => m === 'tools.call')).toBe(false)
    expect(wrapper.text()).toMatch(/JSON/)
  })

  it('工具调用器：合法参数调用 tools.call 并显示返回', async () => {
    const { wrapper, bridge } = await mountPage({
      'app.bootstrap': async () => BOOTSTRAP,
      'tools.list': async () => TOOLS,
      'tools.call': async () => ({ content: [{ type: 'text', text: '{"items":[]}' }], isError: false })
    })
    await wrapper.findAll('.tool-row')[0].trigger('click')
    await wrapper.find('textarea').setValue('{"limit":5}')
    await wrapper.findAll('button').find((b) => b.text().includes('调用')).trigger('click')
    await flushPromises()
    const call = bridge.calls.find(([m]) => m === 'tools.call')
    expect(call[1]).toEqual({ name: 'list_sources', arguments: { limit: 5 } })
    expect(JSON.parse(wrapper.find('pre').text())).toEqual({ items: [] })
  })

  it('破坏性工具调用前要求确认', async () => {
    const { wrapper, bridge } = await mountPage({
      'app.bootstrap': async () => BOOTSTRAP,
      'tools.list': async () => TOOLS,
      'tools.call': async () => ({ content: [{ type: 'text', text: 'ok' }], isError: false })
    })
    await wrapper.findAll('.tool-row')[1].trigger('click')
    await wrapper.findAll('button').find((b) => b.text().includes('调用')).trigger('click')
    await flushPromises()
    expect(bridge.calls.some(([m]) => m === 'tools.call')).toBe(false)
    expect(wrapper.text()).toMatch(/确认|破坏/)
  })

  it('工具目录读取失败时如实显示错误，不显示假目录', async () => {
    const { wrapper } = await mountPage({
      'app.bootstrap': async () => BOOTSTRAP,
      'tools.list': async () => {
        throw Object.assign(new Error('registry down'), { code: 'internal' })
      }
    })
    expect(wrapper.text()).toContain('registry down')
    expect(wrapper.findAll('.tool-row')).toHaveLength(0)
  })
})
