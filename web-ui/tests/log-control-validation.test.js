import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import LogsPage from '../src/pages/LogsPage.vue'
import LogActions from '../src/components/LogActions.vue'
import Workbench from '../src/components/InteractiveCaptureWorkbench.vue'

const wrappers = []
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
const result = data => ({ content: [{ type: 'text', text: JSON.stringify(data) }] })
const button = (w, text) => w.findAll('button').find(b => b.text() === text)
const mounted = (component, props) => { const w = mount(component, { props, attachTo: document.body }); wrappers.push(w); return w }

it('capture ID lookup reports invalid input in Vue and never sends an invalid ID', async () => {
  const store = { state: { bridgeKind: 'android' }, bridge: { call: vi.fn() }, callTool: vi.fn(() => result({ items: [], total: 0 })) }
  const w = mounted(LogsPage, { store }); await flushPromises()
  await button(w, '抓包').trigger('click'); await flushPromises()
  const field = w.get('[aria-label="抓包会话 ID"]')
  const form = field.element.form
  for (const value of ['', 'cap:short', ' cap:12345678-1234-1234-1234-123456789abc']) {
    await field.setValue(value); store.callTool.mockClear()
    form.requestSubmit(); await flushPromises()
    expect(w.findAll('[role="alert"]').some(a => a.text().includes('完整抓包会话 ID'))).toBe(true)
    expect(store.callTool).not.toHaveBeenCalled()
    // Vue's submit guard also holds without browser validity enforcement.
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await flushPromises()
    expect(store.callTool).not.toHaveBeenCalled()
  }
  const id = 'cap:12345678-1234-1234-1234-123456789abc'
  await field.setValue(id); form.requestSubmit(); await flushPromises()
  expect(store.callTool).toHaveBeenCalledWith('get_capture', { contextId: id, limit: 50, offset: 0 })
  expect(form.noValidate).toBe(true)
})

it('single request uses Vue URL validation before any real request or recording lookup', async () => {
  const store = { callTool: vi.fn(name => result(name === 'get_http_log_recording' ? { enabled: true } : { contextId: 'cap:request' })) }
  const w = mounted(LogActions, { store })
  await button(w, '读取录制状态').trigger('click'); await flushPromises()
  const field = w.get('[aria-label="请求 URL"]')
  const form = field.element.form
  for (const value of ['', 'not-a-url', 'ftp://example.com', 'https://user:pass@example.com']) {
    await field.setValue(value); store.callTool.mockClear()
    form.requestSubmit(); await flushPromises()
    expect(w.get('[role="alert"]').text()).toContain('HTTP/HTTPS URL')
    expect(store.callTool).not.toHaveBeenCalled()
  }
  await field.setValue('https://example.com/path?q=1'); form.requestSubmit(); await flushPromises()
  expect(store.callTool).toHaveBeenCalledWith('capture_once', { url: 'https://example.com/path?q=1', method: 'GET', headers: '{}', timeoutSec: 30 })
  expect(form.noValidate).toBe(true)
})

it('browser capture blocks blank/malformed URLs in the submit handler without native tooltips', async () => {
  let phase = 'idle'
  const call = vi.fn(async action => {
    if (action === 'capture.browser.open') phase = 'active'
    return { phase, contextId: phase === 'active' ? 'cap:browser' : '', total: 0 }
  })
  const w = mounted(Workbench, { call }); await flushPromises()
  const field = w.get('[aria-label="抓包网址"]')
  const form = field.element.form
  for (const value of ['', '  ', 'bad-url', 'javascript:alert(1)', 'https://user:pass@example.com']) {
    await field.setValue(value); call.mockClear()
    form.requestSubmit(); await flushPromises()
    expect(w.get('[role="alert"]').text()).toContain('HTTP/HTTPS URL')
    expect(call).not.toHaveBeenCalled()
  }
  await field.setValue('https://example.com'); form.requestSubmit(); await flushPromises()
  expect(call).toHaveBeenCalledWith('capture.browser.open', { url: 'https://example.com' })
  await field.setValue('https://example.com/next'); form.requestSubmit(); await flushPromises()
  expect(call).toHaveBeenCalledWith('capture.browser.navigate', { url: 'https://example.com/next' })
  expect(form.noValidate).toBe(true)
})
