import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import SourcesPage from '../src/pages/SourcesPage.vue'
import SourceManagement from '../src/components/sourceManagement.vue'
enableAutoUnmount(afterEach)
const raw = ' {"bookSourceUrl":"https://a.example","weight":12345678901234567890} '
const targets = [{ packageName: 'reader.one', label: '阅读一' }, { packageName: 'reader.two', label: '阅读二' }]
const launched = { launched: true, confirmed: false, confirmation: 'pending_user' }
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const button = (w, text) => w.findAll('button').find(b => b.isVisible() && b.text() === text)
const modal = () => document.querySelector('.reader-import-dialog[role="dialog"]')
function setup(deliver, component = SourcesPage, extra = {}) {
 const store = { state: { bridgeKind: 'android' }, toast: vi.fn(), markPageDirty: vi.fn(), confirmDialog: vi.fn(),
  callTool: vi.fn(async name => ({ content: [{ type: 'text', text: name === 'get_source' ? raw : JSON.stringify({ total: 2, hasMore: false, items: ['p1','p2'].map(projectId => ({ projectId, bookSourceName: projectId })) }) }] })),
  bridge: { call: vi.fn((method, params) => deliver(params, method)) } }
 return { store, wrapper: mount(component, { attachTo: document.body, props: { store, ...extra } }) }
}
async function triggerList(wrapper, index = 1) {
 await flushPromises()
 // URL-less fixtures share the unknown-domain group; reveal the exact row.
 for (const toggle of wrapper.findAll('[data-source-group-toggle][aria-expanded="false"]')) await toggle.trigger('click')
 await wrapper.findAll('[data-source-more]')[index].trigger('click')
 await button(wrapper, '导入阅读').trigger('click')
}
describe('visible reader import', () => {
 it('list More immediately opens a body modal; choices keep exact clicked project', async () => {
  const pending = deferred()
  const { wrapper, store } = setup(p => p.action === 'targets' ? pending.promise : launched)
  await triggerList(wrapper)
  expect(modal()).not.toBeNull()
  expect(modal().textContent).toContain('正在查找')
  expect(wrapper.element.contains(modal())).toBe(false)
  pending.resolve({ targets }); await flushPromises()
  expect(document.querySelectorAll('[data-reader-target]')).toHaveLength(2)
  document.querySelector('[data-reader-target="reader.two"]').click(); await flushPromises()
  expect(store.bridge.call).toHaveBeenLastCalledWith('sources.deliver', { projectIds: ['p2'], action: 'reader', packageName: 'reader.two' })
  expect(store.toast).toHaveBeenCalledWith(expect.stringContaining('待用户确认'))
 })
 it('single client auto launches exactly once without another click', async () => {
  const { wrapper, store } = setup(p => p.action === 'targets' ? { targets: [targets[1]] } : launched)
  await triggerList(wrapper); await flushPromises()
  expect(store.bridge.call).toHaveBeenLastCalledWith('sources.deliver', { projectIds: ['p2'], action: 'reader', packageName: 'reader.two' })
  expect(store.bridge.call).toHaveBeenCalledTimes(2)
  expect(modal()).toBeNull()
 })
 it('Back cancels resolving, and a late single-client reply cannot launch or reopen', async () => {
  const pending = deferred()
  const { wrapper, store } = setup(() => pending.promise)
  await triggerList(wrapper)
  expect(wrapper.vm.handleBack()).toBe(true)
  await flushPromises(); expect(modal()).toBeNull()
  pending.resolve({ targets: [targets[0]] }); await flushPromises()
  expect(modal()).toBeNull()
  expect(store.bridge.call).toHaveBeenCalledTimes(1)
  expect(store.toast).not.toHaveBeenCalled()
 })
 it('an old reply cannot replace a newer chooser', async () => {
  const pending = deferred(); let reads = 0
  const { wrapper, store } = setup(p => p.action === 'targets' ? (++reads === 1 ? pending.promise : { targets }) : launched)
  await triggerList(wrapper); wrapper.vm.handleBack(); await flushPromises()
  await triggerList(wrapper, 0); await flushPromises()
  pending.resolve({ targets: [targets[0]] }); await flushPromises()
  expect(document.querySelectorAll('[data-reader-target]')).toHaveLength(2)
  document.querySelector('[data-reader-target="reader.two"]').click(); await flushPromises()
  expect(store.bridge.call).toHaveBeenLastCalledWith('sources.deliver', { projectIds: ['p1'], action: 'reader', packageName: 'reader.two' })
 })
 it('draft chooser freezes original bytes; launching consumes Back and blocks duplicate submission', async () => {
  const pending = deferred()
  const { wrapper, store } = setup(p => p.action === 'targets' ? { targets } : pending.promise, SourceManagement, { source: raw })
  await button(wrapper, '导入阅读').trigger('click'); await flushPromises()
  await wrapper.setProps({ source: raw + ' ' })
  const choice = document.querySelector('[data-reader-target="reader.one"]')
  expect(choice).not.toBeNull()
  choice.click(); choice.click(); await flushPromises()
  expect(document.querySelector('[data-reader-cancel]').disabled).toBe(true)
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  expect(wrapper.vm.handleBack()).toBe(true)
  expect(modal()).not.toBeNull()
  expect(store.bridge.call).toHaveBeenCalledTimes(2)
  expect(store.bridge.call).toHaveBeenLastCalledWith('sources.deliver', { source: raw, action: 'reader', packageName: 'reader.one' })
  pending.resolve(launched); await flushPromises()
  expect(modal()).toBeNull()
 })
 it.each(['cancel', 'Escape'])('%s dismisses resolution without launching on a late reply', async mode => {
  const pending = deferred()
  const { wrapper, store } = setup(() => pending.promise)
  await triggerList(wrapper)
  if (mode === 'cancel') document.querySelector('[data-reader-cancel]').click()
  else document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  await flushPromises(); expect(modal()).toBeNull()
  pending.resolve({ targets }); await flushPromises()
  expect(modal()).toBeNull(); expect(store.bridge.call).toHaveBeenCalledTimes(1)
 })
 it.each(['empty', 'reject', 'malformed'])('empty or failed resolution stays visible with retry: %s', async mode => {
  let attempts = 0
  const { wrapper, store } = setup(p => {
   if (p.action === 'reader') return launched
   if (++attempts > 1) return { targets: [targets[0]] }
   if (mode === 'reject') throw new Error('客户端读取断线')
   if (mode === 'malformed') return { targets: [{ label: '坏回包' }] }
   return { targets: [] }
  })
  await triggerList(wrapper); await flushPromises()
  expect(modal()).not.toBeNull()
  expect(modal().querySelector('[role="alert"]')).not.toBeNull()
  if (mode === 'empty') expect(modal().textContent).toContain('未检测到可导入的阅读客户端')
  expect(store.toast).not.toHaveBeenCalled()
  ;[...modal().querySelectorAll('button')].find(b => b.textContent === '重试').click()
  await flushPromises()
  expect(store.bridge.call).toHaveBeenLastCalledWith('sources.deliver', { projectIds: ['p2'], action: 'reader', packageName: 'reader.one' })
 })
 it('launch failure remains in the modal and can retry the same frozen draft', async () => {
  let launches = 0
  const { wrapper, store } = setup(p => p.action === 'targets' ? { targets: [targets[0]] } : (++launches === 1 ? { launched: false } : launched), SourceManagement, { source: raw })
  await button(wrapper, '导入阅读').trigger('click'); await flushPromises()
  expect(modal().querySelector('[role="alert"]').textContent).toContain('唤起回执无效')
  expect(store.toast).not.toHaveBeenCalled()
  document.querySelector('[data-reader-target="reader.one"]').click(); await flushPromises()
  expect(modal()).toBeNull(); expect(launches).toBe(2)
 })
 it('page Back first closes the detail child picker and preserves dirty draft', async () => {
  const { wrapper, store } = setup(p => p.action === 'targets' ? { targets } : launched)
  await flushPromises(); await wrapper.find('.source-row').trigger('click'); await flushPromises()
  await wrapper.find('textarea').setValue(raw + '\n')
  await button(wrapper, '导入阅读').trigger('click'); await flushPromises()
  expect(modal()).not.toBeNull()
  expect(wrapper.vm.handleBack()).toBe(true); await flushPromises()
  expect(modal()).toBeNull()
  expect(wrapper.find('textarea').element.value).toBe(raw + '\n')
  expect(store.confirmDialog).not.toHaveBeenCalled()
  expect(wrapper.vm.handleBack()).toBe(true)
  expect(store.confirmDialog).toHaveBeenCalledWith(expect.objectContaining({ kind: 'discard' }))
 })
 it('advanced analysis uses its token and page Back reaches the child picker', async () => {
  const { wrapper, store } = setup((p, method) => method === 'sources.analyze' ? { token: 'snapshot-frozen', total: 2, keptCount: 2, excludedCount: 0, groups: [] } : p.action === 'targets' ? { targets } : launched)
  await flushPromises(); await wrapper.find('[data-advanced-toggle]').trigger('click')
  await button(wrapper, '全库分析与分组').trigger('click'); await flushPromises()
  await button(wrapper, '导入指定阅读客户端').trigger('click'); await flushPromises()
  expect(modal()).not.toBeNull()
  expect(wrapper.vm.handleBack()).toBe(true); await flushPromises()
  expect(modal()).toBeNull()
  await button(wrapper, '导入指定阅读客户端').trigger('click'); await flushPromises()
  document.querySelector('[data-reader-target="reader.two"]').click(); await flushPromises()
  expect(store.bridge.call).toHaveBeenLastCalledWith('sources.deliver', { token: 'snapshot-frozen', action: 'reader', packageName: 'reader.two' })
 })
 it('unmount invalidates resolution without a late automatic launch', async () => {
  const pending = deferred(); const { wrapper, store } = setup(() => pending.promise)
  await triggerList(wrapper); wrapper.unmount()
  pending.resolve({ targets: [targets[0]] }); await flushPromises()
  expect(modal()).toBeNull(); expect(store.bridge.call).toHaveBeenCalledTimes(1)
 })
})
