import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SettingsPanel from '../src/components/SettingsPanel.vue'

describe('SettingsPanel version identity', () => {
  it('应用三项构建信息与Rust完整版本及状态分开标明，bootstrap更新后同步显示', async () => {
    const revision = '1234567890abcdef1234567890abcdef12345678'
    const rustVersion = 'studio-ui-core/2.0.0+wasm-build-fixture'
    const wrapper = mount(SettingsPanel, {
      props: {
        bootstrap: { versionName: '7.4.2', versionCode: 70402, buildRevision: revision },
        coreStatus: 'loaded', coreVersion: rustVersion
      }
    })
    const app = wrapper.findAll('.section').find(section => section.find('h3').exists() && section.find('h3').text() === '应用版本（APK）')
    expect(app).toBeTruthy()
    expect(app.text()).toContain('versionName')
    expect(app.text()).toContain('7.4.2')
    expect(app.text()).toContain('versionCode')
    expect(app.text()).toContain('70402')
    expect(app.text()).toContain('buildRevision')
    expect(app.text()).toContain(revision)
    expect(app.text()).not.toContain(rustVersion)
    const rust = wrapper.findAll('.section').find(section => section.find('h3').exists() && section.find('h3').text() === 'Rust 前端核')
    expect(rust.text()).toContain(rustVersion)
    expect(rust.text()).toContain('已加载')
    expect(rust.text()).not.toContain('7.4.2')
    await wrapper.setProps({ bootstrap: { versionName: '8.1.0', versionCode: 80100, buildRevision: 'new-revision' } })
    expect(app.text()).toContain('8.1.0')
    expect(app.text()).toContain('80100')
    expect(app.text()).toContain('new-revision')
    expect(app.text()).not.toContain(revision)
    wrapper.unmount()
  })

  it.each([null, {}, { versionName: ' ', versionCode: null, buildRevision: '' }])('版本信息缺失时显示未知，不用Rust版本冒充APK：%s', bootstrap => {
    const wrapper = mount(SettingsPanel, { props: { bootstrap, coreStatus: 'failed', coreVersion: '' } })
    const app = wrapper.findAll('.section').find(section => section.find('h3').exists() && section.find('h3').text() === '应用版本（APK）')
    expect(app).toBeTruthy()
    expect(app.text()).toContain('版本未知')
    expect(app.findAll('dd').map(value => value.text())).toEqual(['版本未知', '未知', '未知'])
    expect(wrapper.text()).toContain('Rust 核不可用')
    wrapper.unmount()
  })

  it.each([
    ['idle', '尚未加载'],
    ['loading', '加载中'],
    ['loaded', '已加载'],
    ['failed', '加载失败']
  ])('Rust %s状态独立展示，缺少版本不虚构', (coreStatus, label) => {
    const wrapper = mount(SettingsPanel, { props: { coreStatus, coreVersion: '' } })
    const rust = wrapper.findAll('.section').find(section => section.find('h3').exists() && section.find('h3').text() === 'Rust 前端核')
    expect(rust.text()).toContain(label)
    expect(rust.text()).toContain('版本未知')
    wrapper.unmount()
  })
})

describe('SettingsPanel async save', () => {
  it.each(['web', 'missing-capability', 'disconnected', 'unknown'])('新令牌编辑门禁 %s', mode => {
    const wrapper = mount(SettingsPanel, { props: {
      bridgeKind: mode === 'web' ? 'web' : 'android', runtimeConnected: mode !== 'disconnected', bootstrapOk: mode !== 'unknown',
      bootstrap: { capabilities: { mcpTokenEditable: mode !== 'missing-capability' } }
    } })
    expect(wrapper.get('#set-mcp-token').attributes('disabled')).toBeDefined()
    expect(wrapper.get('#set-mcp-token').element.value).toBe('')
    wrapper.unmount()
  })

  it('新令牌遵循IME composition，忙碌锁定输入和返回，卸载重开为空', async () => {
    let resolveSave
    const calls = []
    const bootstrap = { service: { tokenRequired: false }, capabilities: { mcpTokenEditable: true } }
    const props = { bootstrap, bootstrapOk: true, runtimeConnected: true, bridgeKind: 'android',
      saveSettings: p => { calls.push(p); return new Promise(resolve => { resolveSave = resolve }) } }
    const wrapper = mount(SettingsPanel, { props })
    const input = wrapper.get('#set-mcp-token')
    await input.trigger('compositionstart')
    input.element.value = 'QA-composed'
    await input.trigger('input')
    expect(wrapper.emitted('dirty')).toBeUndefined()
    await input.trigger('compositionend')
    expect(wrapper.emitted('dirty').at(-1)).toEqual([true])
    const save = wrapper.findAll('button').find(b => b.text() === '保存设置')
    await save.trigger('click')
    expect(input.attributes('disabled')).toBeDefined()
    expect(wrapper.vm.handleBack()).toBe(true)
    await save.trigger('click')
    expect(calls).toHaveLength(1)
    resolveSave({ ...bootstrap, settingsResult: { mcpTokenApplied: true } }); await flushPromises()
    expect(input.element.value).toBe('')
    wrapper.unmount()
    const reopened = mount(SettingsPanel, { props })
    expect(reopened.get('#set-mcp-token').element.value).toBe('')
    reopened.unmount()
  })
  it.each(['missing', 'false', 'partial', 'failed', 'reject'])('新令牌%s回执保留草稿且错误不包含输入', async outcome => {
    const bootstrap = { service: { tokenRequired: false }, capabilities: { mcpTokenEditable: true } }
    const wrapper = mount(SettingsPanel, { props: {
      bootstrap, bootstrapOk: true, runtimeConnected: true, bridgeKind: 'android',
      saveSettings: async () => {
        if (outcome === 'reject') throw new Error('QA-private')
        return { ...bootstrap, settingsResult: { mcpTokenApplied: outcome !== 'false' && outcome !== 'missing' }, partial: outcome === 'partial', failed: outcome === 'failed' ? ['compact'] : [] }
      }
    } })
    await wrapper.get('#set-mcp-token').setValue('QA-private')
    await wrapper.findAll('button').find(b => b.text() === '保存设置').trigger('click'); await flushPromises()
    expect(wrapper.get('#set-mcp-token').element.value).toBe('QA-private')
    expect(wrapper.emitted('dirty').at(-1)).toEqual([true])
    expect(wrapper.text()).toMatch(/令牌.*未知/)
    expect(wrapper.text()).not.toContain('QA-private')
    wrapper.unmount()
  })
  it.each([true, false])('新令牌在鉴权%s时独立保存，默认空白，成功清理并不重发', async tokenRequired => {
    const calls = []
    const bootstrap = { service: { port: 8787, tokenRequired }, capabilities: { mcpTokenEditable: true } }
    const wrapper = mount(SettingsPanel, { props: {
      bootstrap, bootstrapOk: true, runtimeConnected: true, bridgeKind: 'android',
      saveSettings: async p => { calls.push({ ...p }); return { ...bootstrap, settingsResult: { mcpTokenApplied: true } } }
    } })
    const input = wrapper.find('#set-mcp-token')
    expect(input.exists()).toBe(true)
    expect(input.element.value).toBe('')
    expect(input.attributes('type')).toBe('password')
    expect(input.attributes('disabled')).toBeUndefined()
    await input.setValue('QA-token_206+/==')
    expect(wrapper.emitted('dirty').at(-1)).toEqual([true])
    const save = wrapper.findAll('button').find(b => b.text() === '保存设置')
    await save.trigger('click'); await flushPromises()
    expect(calls[0].mcpToken).toBe('QA-token_206+/==')
    expect(calls[0]).not.toHaveProperty('tokenRequired')
    expect(input.element.value).toBe('')
    expect(wrapper.emitted('dirty').at(-1)).toEqual([false])
    await save.trigger('click'); await flushPromises()
    expect(calls[1]).not.toHaveProperty('mcpToken')
    wrapper.unmount()
  })

  it('令牌开关是草稿，关闭保存需Vue确认，取消不写，回读成功清dirty', async () => {
    let dialog, resolveSave
    const calls = []
    const bootstrap = { service: { port: 8787, compact: false, tokenRequired: true }, capabilities: { tokenRequiredEditable: true } }
    const wrapper = mount(SettingsPanel, { props: {
      bootstrap, bootstrapOk: true, runtimeConnected: true, bridgeKind: 'android',
      confirmDialog: options => { dialog = options },
      saveSettings: payload => { calls.push(payload); return new Promise(resolve => { resolveSave = resolve }) }
    } })
    const toggle = wrapper.find('[role="switch"][aria-label="访问令牌校验"]')
    expect(toggle.exists()).toBe(true)
    const save = wrapper.findAll('button').find(b => b.text() === '保存设置')
    await toggle.trigger('click')
    expect(calls).toEqual([])
    expect(wrapper.emitted('dirty').at(-1)).toEqual([true])
    await save.trigger('click')
    expect(dialog.message).toMatch(/无需令牌/)
    expect(calls).toEqual([])
    dialog = null // Vue cancel/Back discards the callback, not the draft.
    expect(toggle.attributes('aria-checked')).toBe('false')
    await save.trigger('click')
    const pending = dialog.onAccept()
    await flushPromises()
    expect(calls).toHaveLength(1)
    expect(calls[0].tokenRequired).toBe(false)
    expect(toggle.attributes('disabled')).toBeDefined()
    expect(wrapper.vm.handleBack()).toBe(true)
    await save.trigger('click')
    expect(calls).toHaveLength(1)
    resolveSave({ ...bootstrap, service: { ...bootstrap.service, tokenRequired: false } })
    await pending; await flushPromises()
    expect(wrapper.emitted('dirty').at(-1)).toEqual([false])
    expect(wrapper.vm.handleBack()).toBe(false)
    wrapper.unmount()
  })

  it('未改动鉴权的旧设置草稿不发送鉴权字段，宿主更新不污染其他草稿', async () => {
    const calls = []
    const bootstrap = { service: { port: 8787, compact: false, tokenRequired: true }, capabilities: { tokenRequiredEditable: true } }
    const wrapper = mount(SettingsPanel, { props: { bootstrap, bootstrapOk: true, runtimeConnected: true, bridgeKind: 'android', saveSettings: async p => { calls.push(p); return bootstrap } } })
    const port = wrapper.find('input[inputmode="numeric"]')
    await port.setValue('8788')
    await wrapper.setProps({ bootstrap: { ...bootstrap, service: { ...bootstrap.service, tokenRequired: false } } })
    expect(port.element.value).toBe('8788')
    const toggle = wrapper.find('[aria-label="访问令牌校验"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.attributes('aria-checked')).toBe('false')
    await wrapper.findAll('button').find(b => b.text() === '保存设置').trigger('click'); await flushPromises()
    expect(calls[0]).not.toHaveProperty('tokenRequired')
    expect(calls[0].port).toBe(8788)
    wrapper.unmount()
  })

  it.each(['missing', 'partial', 'mismatch', 'rejected'])('设置鉴权保存%s保留草稿和dirty，不假报成功', async outcome => {
    let dialog
    const bootstrap = { service: { port: 8787, tokenRequired: true }, capabilities: { tokenRequiredEditable: true } }
    const wrapper = mount(SettingsPanel, { props: {
      bootstrap, bootstrapOk: true, runtimeConnected: true, bridgeKind: 'android', confirmDialog: d => { dialog = d },
      saveSettings: async () => {
        if (outcome === 'missing') return {}
        if (outcome === 'partial') return { service: { tokenRequired: false }, partial: true }
        if (outcome === 'mismatch') return bootstrap
        throw new Error('host save failed')
      }
    } })
    await wrapper.get('[aria-label="访问令牌校验"]').trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '保存设置').trigger('click')
    await dialog.onAccept(); await flushPromises()
    expect(wrapper.emitted('dirty').at(-1)).toEqual([true])
    expect(wrapper.text()).toMatch(/回读|host save failed/)
    expect(wrapper.get('[aria-label="访问令牌校验"]').attributes('aria-checked')).toBe('false')
    wrapper.unmount()
  })

  it.each([['android', {}], ['web', { tokenRequiredEditable: true }]])('设置不支持%s/%j时开关禁用且保存不夹带鉴权', async (bridgeKind, capabilities) => {
    const calls = []
    const wrapper = mount(SettingsPanel, { props: { bootstrapOk: true, runtimeConnected: true, bridgeKind, bootstrap: { capabilities }, saveSettings: async p => { calls.push(p); return {} } } })
    const toggle = wrapper.get('[aria-label="访问令牌校验"]')
    expect(toggle.attributes('aria-checked')).toBe('true')
    expect(toggle.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('不支持更改')
    await wrapper.findAll('button').find(b => b.text() === '保存设置').trigger('click'); await flushPromises()
    expect(calls[0]).not.toHaveProperty('tokenRequired')
    wrapper.unmount()
  })

  it('设置开启无需关闭确认，成功清dirty，再次保存不重复提交鉴权', async () => {
    const calls = [], dialogs = []
    const bootstrap = { service: { port: 8787, tokenRequired: false }, capabilities: { tokenRequiredEditable: true } }
    const wrapper = mount(SettingsPanel, { props: {
      bootstrap, bootstrapOk: true, runtimeConnected: true, bridgeKind: 'android', confirmDialog: d => dialogs.push(d),
      saveSettings: async p => { calls.push(p); return { ...bootstrap, service: { ...bootstrap.service, tokenRequired: true } } }
    } })
    const toggle = wrapper.get('[aria-label="访问令牌校验"]')
    await toggle.trigger('click')
    const save = wrapper.findAll('button').find(b => b.text() === '保存设置')
    await save.trigger('click'); await flushPromises()
    expect(dialogs).toEqual([])
    expect(calls[0].tokenRequired).toBe(true)
    expect(wrapper.emitted('dirty').at(-1)).toEqual([false])
    await save.trigger('click'); await flushPromises()
    expect(calls[1]).not.toHaveProperty('tokenRequired')
    await toggle.trigger('click'); await toggle.trigger('click')
    expect(wrapper.emitted('dirty').at(-1)).toEqual([false])
    wrapper.unmount()
  })

  it('保存其他设置回读更新未触碰的鉴权状态，不留下过期草稿', async () => {
    const bootstrap = { service: { port: 8787, tokenRequired: true }, capabilities: { tokenRequiredEditable: true } }
    const wrapper = mount(SettingsPanel, { props: {
      bootstrap, bootstrapOk: true, runtimeConnected: true, bridgeKind: 'android',
      saveSettings: async () => ({ ...bootstrap, service: { ...bootstrap.service, tokenRequired: false } })
    } })
    await wrapper.find('input[inputmode="numeric"]').setValue('8788')
    await wrapper.findAll('button').find(b => b.text() === '保存设置').trigger('click'); await flushPromises()
    expect(wrapper.get('[aria-label="访问令牌校验"]').attributes('aria-checked')).toBe('false')
    expect(wrapper.emitted('dirty').at(-1)).toEqual([false])
    wrapper.unmount()
  })

  it('保存期间禁用按钮，等待真实回包并显示失败', async () => {
    let reject
    const pending = new Promise((_, fail) => { reject = fail })
    const wrapper = mount(SettingsPanel, {
      props: { bootstrapOk: true, runtimeConnected: true, saveSettings: () => pending }
    })
    const button = wrapper.findAll('button').find(b => b.text().includes('保存设置'))
    await button.trigger('click')
    await flushPromises()
    expect(button.attributes('aria-busy')).toBe('true')
    reject(new Error('端口保存失败'))
    await flushPromises()
    expect(wrapper.text()).toContain('端口保存失败')
    expect(button.attributes('aria-busy')).toBe('false')
  })
})
