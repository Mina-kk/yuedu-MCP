import { afterEach, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import VerifyPage from '../src/pages/VerifyPage.vue'
let wrapper
const row = { id:'a', url:'https://example.com', status:'COMPLETED', finalUrl:'https://example.com/done', canOpen:true, kind:'webview', jobId:'mcp' }
afterEach(() => wrapper?.unmount())
async function setup(readback = { ...row, status:'WAITING', finalUrl:'' }) {
  let cleared = false
  const store = { state:{ bridgeKind:'android', verificationBrowser:{ open:false } }, toast:vi.fn(), confirmDialog:vi.fn(), bridge:{ call:vi.fn(async method => {
    if (method === 'verification.list') return { sessions:[cleared ? readback : row] }
    if (method === 'verification.clear') { cleared = true; return { cleared:true } }
    if (method === 'verification.external') return { launched:true, cookiesShared:false }
    throw Error(method)
  }) } }
  wrapper = mount(VerifyPage, { props:{ store } }); await flushPromises(); return store
}
const button = text => wrapper.findAll('button').find(x => x.text() === text)
it('clear requires confirmation and reads same retained WAITING row, never deletes', async () => {
  const store = await setup()
  expect(button('清 Cookie')).toBeDefined()
  await button('清 Cookie').trigger('click')
  expect(store.bridge.call).toHaveBeenCalledTimes(1)
  await store.confirmDialog.mock.calls[0][0].onAccept(); await flushPromises()
  expect(store.bridge.call.mock.calls.map(x=>x[0])).toEqual(['verification.list','verification.clear','verification.list'])
  expect(wrapper.get('.session').text()).toContain('待处理')
  expect(store.toast).toHaveBeenCalledWith('Cookie 已清除，记录已保留并重置为待验证')
})
it('clear mismatched readback never reports success', async () => {
  const store = await setup({ ...row, id:'other', status:'WAITING', finalUrl:'' })
  await button('清 Cookie').trigger('click'); await store.confirmDialog.mock.calls[0][0].onAccept(); await flushPromises()
  expect(store.toast).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('无法确认清 Cookie 结果')
})
it('external fallback explains non-sharing, confirms, never completes', async () => {
  const store = await setup()
  await button('外部浏览器（备用）').trigger('click')
  expect(store.confirmDialog.mock.calls[0][0].message).toContain('不共享 Cookie')
  await store.confirmDialog.mock.calls[0][0].onAccept(); await flushPromises()
  expect(store.bridge.call).toHaveBeenCalledWith('verification.external', { id:'a' })
  expect(store.bridge.call.mock.calls.some(x=>x[0] === 'verification.complete')).toBe(false)
})
