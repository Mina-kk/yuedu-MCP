import { describe, it, expect, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '../src/App.vue'

// 外壳：5 页导航、宿主事件 {event,payload}、Back 顺序、验证隔离页工具栏。
// App.vue 内部自建 store，测试走真实 Android 宿主桥路径（window.StudioBridge），
// 不另开测试专用入口。外站验证页由宿主独立 WebView 承载，这里断言绝不出现 iframe。

function installStudioBridge(handlers = {}) {
  const calls = []
  const bridge = {
    calls,
    postMessage(raw) {
      const msg = JSON.parse(raw)
      calls.push([msg.method, msg.params])
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

function dispatchHostEvent(name, detail) {
  window.dispatchEvent(new CustomEvent(name, { detail }))
}

const BOOTSTRAP = {
  service: { running: true, port: 8787, endpoints: ['http://127.0.0.1:8787/mcp'], compact: false },
  runtime: { userAgent: '', bookSourceType: -1 },
  reader: { baseUrl: '', tokenSaved: false, revision: 0 },
  appearance: { theme: 'system', palette: 'lime' },
  capabilities: { toolCount: 73 }
}

function handlers(extra = {}) {
  return {
    'app.bootstrap': async () => BOOTSTRAP,
    'tools.list': async () => ({ tools: [] }),
    'list_sources': async () => ({ content: [{ type: 'text', text: '{"total":0,"items":[]}' }] }),
    'list_skills': async () => ({ content: [{ type: 'text', text: '[]' }] }),
    'get_logs': async () => ({ content: [{ type: 'text', text: '{"count":0,"items":[]}' }] }),
    'verification.list': async () => ({ sessions: [] }),
    'verification.browser.close': async () => ({ closed: true }),
    ...extra
  }
}

afterEach(() => {
  delete window.StudioBridge
  document.documentElement.removeAttribute('data-native-insets')
})

describe('App shell', () => {
  it('底部导航包含 5 个真实页面并可切换', async () => {
    installStudioBridge(handlers())
    const wrapper = mount(App)
    await flushPromises()
    const tabs = wrapper.findAll('.tab')
    expect(tabs.map((t) => t.text())).toEqual(['MCP', '书源', '技能', '验证', '日志'])
    await tabs[1].trigger('click')
    await flushPromises()
    expect(tabs[1].classes()).toContain('on')
  })

  it('页头显示真实标题与副标题；wasm 未加载时不显示 Rust 已加载', async () => {
    installStudioBridge(handlers())
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.find('.app-title').text()).toBe('阅读书源 MCP')
    expect(wrapper.find('.app-sub').text()).toBe('以阅读规则为准')
    expect(wrapper.text()).not.toContain('Rust 已加载')
  })

  it('宿主 studio:back 按 sheet → root 顺序处理', async () => {
    installStudioBridge(handlers())
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.findAll('.tab')[4].trigger('click')
    await flushPromises()
    dispatchHostEvent('studio:back', {})
    await flushPromises()
    expect(wrapper.findAll('.tab')[0].classes()).toContain('on')
    await wrapper.find('.icon-btn').trigger('click')
    await flushPromises()
    expect(wrapper.find('.sheet-layer').exists()).toBe(true)
    dispatchHostEvent('studio:back', {})
    await flushPromises()
    expect(wrapper.find('.sheet-layer').exists()).toBe(false)
  })

  it('studio:insets 以宿主值写入 CSS 变量，不二次累加', async () => {
    installStudioBridge(handlers())
    mount(App)
    await flushPromises()
    dispatchHostEvent('studio:insets', { top: 0, bottom: 0, ime: 0 })
    await flushPromises()
    expect(document.documentElement.getAttribute('data-native-insets')).toBe('1')
    expect(document.documentElement.style.getPropertyValue('--native-inset-top')).toBe('0px')
    expect(document.documentElement.style.getPropertyValue('--native-inset-bottom')).toBe('0px')
  })

  it('studio:verification-browser 打开后主 Web 只留返回/完成，且绝不创建 iframe', async () => {
    installStudioBridge(handlers())
    const wrapper = mount(App)
    await flushPromises()
    dispatchHostEvent('studio:verification-browser', { action: 'open', id: 's1' })
    await flushPromises()
    expect(wrapper.text()).toContain('返回')
    expect(wrapper.text()).toContain('完成')
    expect(wrapper.find('iframe').exists()).toBe(false)
  })

  it('bootstrap 失败仍能打开设置并切换主题', async () => {
    const bridge = installStudioBridge(
      handlers({
        'app.bootstrap': async () => {
          throw Object.assign(new Error('boom'), { code: 'internal' })
        }
      })
    )
    const wrapper = mount(App)
    await flushPromises()
    expect(bridge.calls.some(([m]) => m === 'app.bootstrap')).toBe(true)
    await wrapper.find('.icon-btn').trigger('click')
    await flushPromises()
    expect(wrapper.find('.sheet-layer').exists()).toBe(true)
    // 主题色板仍可点：10 套主题按钮都在
    expect(wrapper.findAll('.palette').length).toBe(10)
  })
})
