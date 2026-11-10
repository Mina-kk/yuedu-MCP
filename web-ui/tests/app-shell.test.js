import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '../src/App.vue'
import { core } from '../src/lib/core.js'

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
  it.each(['loaded', 'failed'])('Rust %s时页头仍显示bootstrap的APK版本，不混用Rust版本', async status => {
    vi.spyOn(core, 'load').mockResolvedValue(status === 'loaded' ? {} : null)
    vi.spyOn(core, 'status').mockReturnValue(status)
    vi.spyOn(core, 'error').mockReturnValue('')
    vi.spyOn(core, 'version').mockReturnValue('2.0.0-core-fixture')
    installStudioBridge(handlers({
      'app.bootstrap': async () => ({ ...BOOTSTRAP, versionName: '7.4.2', versionCode: 70402, buildRevision: 'revision-fixture' })
    }))
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.find('.app-head').text()).toContain('APK 7.4.2')
    expect(wrapper.find('.app-head').text()).not.toContain('2.0.0-core-fixture')
    expect(wrapper.find('.app-head').text()).not.toContain('Rust')
    wrapper.unmount()
  })

  it.each([undefined, null, '', '  '])('应用versionName为%s时明确显示版本未知，不回退到核心或写死版本', async versionName => {
    vi.spyOn(core, 'load').mockResolvedValue({})
    vi.spyOn(core, 'status').mockReturnValue('loaded')
    vi.spyOn(core, 'error').mockReturnValue('')
    vi.spyOn(core, 'version').mockReturnValue('2.0.0-core-fixture')
    installStudioBridge(handlers({ 'app.bootstrap': async () => ({ ...BOOTSTRAP, versionName }) }))
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.find('.app-head').text()).toContain('版本未知')
    expect(wrapper.find('.app-head').text()).not.toContain('2.0.0')
    wrapper.unmount()
  })

  it('历史已完成记录通过真实Vue确认补全，取消无写入、同ID回读后仅手动打开', async () => {
    const alert = vi.spyOn(window, 'alert').mockImplementation(() => { throw new Error('禁止原生 alert') })
    const confirm = vi.spyOn(window, 'confirm').mockImplementation(() => { throw new Error('禁止原生 confirm') })
    const prompt = vi.spyOn(window, 'prompt').mockImplementation(() => { throw new Error('禁止原生 prompt') })
    const broken = {
      id: 'completed-history', jobId: 'mcp', url: '', domain: '', status: 'COMPLETED', kind: 'webview',
      purpose: '历史验证', finalUrl: '', answer: '', imageData: '', createdAt: 1, updatedAt: 1,
      canOpen: false, addressIssue: 'missing_url'
    }
    const repaired = { ...broken, url: 'https://verify.example/challenge', domain: 'verify.example', status: 'WAITING', canOpen: true, addressIssue: '', updatedAt: 2 }
    let records = [broken]
    const bridge = installStudioBridge(handlers({
      'verification.list': async () => ({ sessions: records }),
      'verification.repairAddress': async ({ id, url }) => {
        expect({ id, url }).toEqual({ id: broken.id, url: repaired.url })
        records = [repaired]
        return { repaired: true, session: repaired }
      },
      'verification.open': async () => ({ opened: true })
    }))
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.findAll('.tab')[3].trigger('click')
    await flushPromises()
    const click = async text => {
      await wrapper.findAll('button').find(button => button.text() === text).trigger('click')
      await flushPromises()
    }
    await wrapper.get('input[aria-label="验证地址"]').setValue(repaired.url)
    await click('补全地址')
    expect(wrapper.get('[role="alertdialog"]').text()).toContain('已完成记录重置为待验证')
    await click('取消')
    expect(bridge.calls.some(([method]) => method === 'verification.repairAddress')).toBe(false)
    await click('补全地址')
    await click('确认补全')
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
    expect(wrapper.findAll('.session')).toHaveLength(1)
    expect(wrapper.get('.session').text()).toContain(repaired.url)
    expect(wrapper.get('.session').text()).toContain('待处理')
    expect(bridge.calls.filter(([method]) => method.startsWith('verification.'))).toEqual([
      ['verification.list', {}], ['verification.repairAddress', { id: broken.id, url: repaired.url }], ['verification.list', {}]
    ])
    await click('打开验证页')
    expect(bridge.calls.some(([method, params]) => method === 'verification.open' && params.id === broken.id)).toBe(true)
    expect(bridge.calls.some(([method]) => method === 'verification.clear')).toBe(false)
    expect(alert).not.toHaveBeenCalled()
    expect(confirm).not.toHaveBeenCalled()
    expect(prompt).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('系统返回先关闭当前工具详情而不是退出应用', async () => {
    installStudioBridge(handlers({ 'tools.list': async () => ({ tools: [{ name: 'get_app_info', description: '应用信息', inputSchema: { type: 'object', properties: {} } }] }) }))
    const wrapper = mount(App)
    await flushPromises()
    const tool = wrapper.findAll('button').find(b => b.text().includes('get_app_info'))
    expect(tool).toBeTruthy()
    await tool.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('返回列表')
    dispatchHostEvent('studio:back', {})
    await flushPromises()
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
    expect(wrapper.findAll('button').some(b => b.text().includes('返回列表'))).toBe(false)
    wrapper.unmount()
  })
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
