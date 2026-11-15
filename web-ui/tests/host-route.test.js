import { describe, it, expect, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '../src/App.vue'
import { core } from '../src/lib/core.js'

// 悬浮球/通知的路由修复：宿主 studio:route 的 detail 是字符串（MainActivity quoteJs(route)），
// 且原生侧用的路由名是 "verification"。前端必须两种 detail 形态都认，
// 并把 "verification" 归一到验证页键 "verify"，否则点球只停在默认 MCP 页。

function installStudioBridge(handlers = {}) {
  const bridge = {
    postMessage(raw) {
      const msg = JSON.parse(raw)
      const fn = handlers[msg.method]
      const reply = fn
        ? fn(msg.params)
        : Promise.reject(Object.assign(new Error(`unsupported ${msg.method}`), { code: 'unsupported' }))
      Promise.resolve(reply).then(
        (result) => bridge.onmessage && bridge.onmessage({ data: JSON.stringify({ id: msg.id, ok: true, result }) }),
        (error) =>
          bridge.onmessage &&
          bridge.onmessage({
            data: JSON.stringify({ id: msg.id, ok: false, error: { code: error.code || 'error', message: error.message } })
          })
      )
    },
    onmessage: null
  }
  window.StudioBridge = bridge
  return bridge
}

const BOOTSTRAP = {
  service: { running: true, port: 8787, endpoints: ['http://127.0.0.1:8787/mcp'], compact: false },
  runtime: { userAgent: '', bookSourceType: -1 },
  reader: { baseUrl: '', tokenSaved: false, revision: 0 },
  appearance: { theme: 'system', palette: 'lime' },
  capabilities: { toolCount: 73 }
}

function handlers() {
  return {
    'app.bootstrap': async () => BOOTSTRAP,
    'tools.list': async () => ({ tools: [] }),
    'list_sources': async () => ({ content: [{ type: 'text', text: '{"total":0,"items":[]}' }] }),
    'list_skills': async () => ({ content: [{ type: 'text', text: '[]' }] }),
    'get_logs': async () => ({ content: [{ type: 'text', text: '{"count":0,"items":[]}' }] }),
    'verification.list': async () => ({ sessions: [] }),
    'verification.browser.close': async () => ({ closed: true })
  }
}

afterEach(() => {
  delete window.StudioBridge
})

describe('studio:route from overlay/notification', () => {
  it.each([
    ['原生字符串路由 verification', 'verification'],
    ['对象 detail route=verification', { route: 'verification' }],
    ['对象 detail page=verify', { page: 'verify' }],
    ['字符串 verify', 'verify']
  ])('%s 打开验证中心', async (_label, detail) => {
    installStudioBridge(handlers())
    const wrapper = mount(App); await flushPromises()
    window.dispatchEvent(new CustomEvent('studio:route', { detail }))
    await flushPromises()
    expect(wrapper.text()).toContain('验证会话')
    expect(wrapper.findComponent({ name: 'VerifyPage' }).exists()).toBe(true)
    wrapper.unmount()
  })

  it('无待验证时点球路由 mcp 仍回 MCP 页', async () => {
    installStudioBridge(handlers())
    const wrapper = mount(App); await flushPromises()
    window.dispatchEvent(new CustomEvent('studio:route', { detail: 'mcp' }))
    await flushPromises()
    expect(wrapper.findComponent({ name: 'McpPage' }).exists()).toBe(true)
    wrapper.unmount()
  })
})
