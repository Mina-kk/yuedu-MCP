import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SettingsPanel from '../src/components/SettingsPanel.vue'
import { createStore } from '../src/store.js'

const props = { bridgeKind: 'android', runtimeConnected: true, bootstrapOk: true,
  bootstrap: { capabilities: { deviceSettings: true, overlayEnabled: false, overlayPermission: false } } }
const button = (w, text) => w.findAll('button').find(b => b.text() === text)
describe('exit contract', () => {
  it('defaults to keep and only explicitly sends stop on selection', async () => {
    const call = vi.fn().mockResolvedValue({ closing: true })
    const store = createStore({ bridge: { kind: 'android', connected: true, call } })
    store.requestExit()
    expect(store.state.dialog.stopService).toBe(false)
    expect(store.state.dialog.message).toContain('保持')
    await store.exitApp()
    expect(call).toHaveBeenLastCalledWith('app.exit', { stopService: false })
    await store.exitApp(true)
    expect(call).toHaveBeenLastCalledWith('app.exit', { stopService: true })
  })
})
describe('reader configuration', () => {
  it('saves only reader draft before testing; invalid draft never tests old address', async () => {
    const saveSettings = vi.fn().mockResolvedValue({ reader: { baseUrl: 'http://127.0.0.1:1234', tokenSaved: true } })
    const readerStatus = vi.fn().mockResolvedValue({ reachable: true, authRequired: true })
    const w = mount(SettingsPanel, { props: { ...props, bootstrap: { capabilities: { readerConfigActions: true } }, saveSettings, readerStatus } })
    await button(w, '保存并测试连接').trigger('click'); await flushPromises()
    expect(readerStatus).not.toHaveBeenCalled()
    await w.get('[placeholder="https://reader.example"]').setValue('http://127.0.0.1:1234')
    await button(w, '保存并测试连接').trigger('click'); await flushPromises()
    expect(saveSettings).toHaveBeenCalledWith({ readerBaseUrl: 'http://127.0.0.1:1234' })
    expect(readerStatus).toHaveBeenCalledOnce()
    expect(w.text()).toContain('authRequired')
    w.unmount()
  })
  it('does not probe after partial save and preserves reader draft after clear failure', async () => {
    let confirm
    const readerStatus = vi.fn()
    const w = mount(SettingsPanel, { props: { ...props, bootstrap: { reader: { baseUrl: 'http://old.example' }, capabilities: { readerConfigActions: true } },
      saveSettings: async () => ({ partial: true, reader: { baseUrl: 'http://old.example', tokenSaved: false } }), readerStatus,
      hostAction: async () => ({ reader: { baseUrl: 'http://old.example', tokenSaved: true } }), confirmDialog: d => { confirm = d } } })
    await button(w, '保存并测试连接').trigger('click'); await flushPromises()
    expect(readerStatus).not.toHaveBeenCalled()
    await button(w, '清除阅读配置').trigger('click'); await confirm.onAccept(); await flushPromises()
    expect(w.get('[placeholder="https://reader.example"]').element.value).toBe('http://old.example')
    expect(w.text()).toContain('阅读配置清除未确认')
    w.unmount()
  })
  it('requires confirmation then verifies clear before clearing drafts', async () => {
    let confirm
    const hostAction = vi.fn().mockResolvedValue({ reader: { baseUrl: '', tokenSaved: false } })
    const w = mount(SettingsPanel, { props: { ...props, bootstrap: { reader: { baseUrl: 'http://old.example' }, capabilities: { readerConfigActions: true } }, hostAction, confirmDialog: d => { confirm = d } } })
    await button(w, '清除阅读配置').trigger('click')
    expect(hostAction).not.toHaveBeenCalled()
    await confirm.onAccept(); await flushPromises()
    expect(hostAction).toHaveBeenCalledWith('reader.clear', {})
    expect(w.get('[placeholder="https://reader.example"]').element.value).toBe('')
    w.unmount()
  })
})
describe('device settings integration', () => {
  it('requests permissions explicitly and does not label opened settings as granted', async () => {
    const hostAction = vi.fn().mockResolvedValue({ opened: true })
    const w = mount(SettingsPanel, { props: { ...props, hostAction } })
    await button(w, '申请通知权限').trigger('click'); await flushPromises()
    expect(hostAction).toHaveBeenCalledWith('notification.request', {})
    expect(w.text()).toContain('悬浮窗权限：未开启')
    await button(w, '启用悬浮球').trigger('click'); await flushPromises()
    expect(hostAction).toHaveBeenLastCalledWith('overlay.setEnabled', { enabled: true })
    expect(button(w, '启用悬浮球')).toBeTruthy()
    w.unmount()
  })
})
