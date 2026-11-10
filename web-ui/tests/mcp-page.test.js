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
    expect(wrapper.text()).not.toMatch(/Bearer|token=/i)
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
    const copyToken = wrapper.findAll('button').find((b) => b.text().includes('复制令牌'))
    await copyToken.trigger('click')
    await flushPromises()
    expect(bridge.calls.some(([m, p]) => m === 'connection.copy' && p.kind === 'token')).toBe(true)
    expect(wrapper.html()).not.toMatch(/Bearer\s+[A-Za-z0-9]/)
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
