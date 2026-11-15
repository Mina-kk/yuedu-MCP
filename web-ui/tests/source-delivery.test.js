import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { Blob as NodeBlob } from 'node:buffer'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import SourcesPage from '../src/pages/SourcesPage.vue'
import { deliverSources } from '../src/lib/source-delivery.js'
import { createWebRuntimeBridge } from '../src/lib/bridge.js'
import * as files from '../src/lib/files.js'

const RAW = ' \n{ "bookSourceName": "草稿", "weight": 12345678901234567891 }  \n'
let createObjectURL, revokeObjectURL, click
beforeEach(() => {
  vi.useFakeTimers()
  createObjectURL = vi.fn(() => 'blob:source-draft')
  revokeObjectURL = vi.fn()
  vi.stubGlobal('URL', class extends URL {
    static createObjectURL = createObjectURL
    static revokeObjectURL = revokeObjectURL
  })
  vi.stubGlobal('Blob', NodeBlob)
  click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
})
afterEach(() => {
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it.each(['http://runtime.example/mcp', ''])('浏览器%s仅下载已有草稿，复用exportText Blob并保留原文大整数', async endpoint => {
  const fetchImpl = vi.fn()
  const bridge = createWebRuntimeBridge({ endpoint, fetchImpl })
  const call = vi.spyOn(bridge, 'call')
  const store = { state: { bridgeKind: 'web' }, bridge, toast: vi.fn() }
  const exportText = vi.spyOn(files, 'exportText')
  const result = await deliverSources(store, { source: RAW }, 'save')
  expect(result.saved).toBe(true)
  expect(exportText).toHaveBeenCalledWith(store, expect.objectContaining({ text: RAW, mime: 'application/json' }))
  expect(createObjectURL).toHaveBeenCalledTimes(1)
  expect(await createObjectURL.mock.calls[0][0].text()).toBe(RAW)
  expect(click).toHaveBeenCalledTimes(1)
  expect(click.mock.instances[0].download).toMatch(/\.json$/)
  expect(call).not.toHaveBeenCalled()
  expect(fetchImpl).not.toHaveBeenCalled()
  expect(store.toast).toHaveBeenCalledWith(expect.stringContaining('当前草稿'))
  await vi.advanceTimersByTimeAsync(4000)
  expect(revokeObjectURL).toHaveBeenCalledWith('blob:source-draft')
  bridge.dispose()
})

it('真实浏览器详情按钮下载当前未保存草稿，不调用宿主交付或保存工具', async () => {
  const bridge = createWebRuntimeBridge({ endpoint: 'http://runtime.example/mcp', fetchImpl: vi.fn() })
  const call = vi.spyOn(bridge, 'call')
  const store = {
    state: reactive({ bridgeKind: 'web', runtimeConnected: true }), bridge,
    toast: vi.fn(), markPageDirty: vi.fn(), confirmDialog: vi.fn(),
    callTool: vi.fn(async name => ({ content: [{ type: 'text', text: name === 'list_sources'
      ? JSON.stringify({ items: [{ projectId: 'p1', bookSourceName: '草稿', bookSourceUrl: 'https://source.example' }], total: 1, hasMore: false })
      : RAW }] }))
  }
  const wrapper = mount(SourcesPage, { props: { store } })
  try {
    await flushPromises()
    await wrapper.get('.source-row').trigger('click')
    await flushPromises()
    const draft = `${RAW}  `
    await wrapper.get('textarea').setValue(draft)
    await wrapper.findAll('button').find(b => b.isVisible() && b.text() === '导出').trigger('click')
    await flushPromises()
    expect(await createObjectURL.mock.calls[0][0].text()).toBe(draft)
    expect(wrapper.get('textarea').element.value).toBe(draft)
    expect(store.callTool.mock.calls.map(([name]) => name)).toEqual(['list_sources', 'get_source'])
    expect(call).not.toHaveBeenCalled()
    expect(store.toast).toHaveBeenCalledWith('已导出当前草稿')
  } finally { wrapper.unmount(); bridge.dispose() }
})

it.each([
  [{ scope: 'all' }, 'save'], [{ projectIds: ['p1'] }, 'save'], [{ token: 's1' }, 'save'],
  [{ source: RAW, scope: 'all' }, 'save'], [{ source: RAW, projectIds: ['p1'] }, 'save'],
  [{ source: RAW, token: 's1' }, 'save'], [{}, 'save'], [{ source: {} }, 'save'],
  [{ source: null }, 'save'], [{ source: RAW }, 'copy'], [{ source: RAW }, 'share'],
  [{ source: RAW }, 'targets'], [{ source: RAW }, 'reader']
])('浏览器不把选择/全库/其他动作伪装成当前页下载 %#', async (payload, action) => {
  const store = { state: { bridgeKind: 'web' }, bridge: { call: vi.fn() }, toast: vi.fn() }
  await expect(deliverSources(store, payload, action)).rejects.toThrow('浏览器模式仅支持导出当前草稿')
  expect(store.bridge.call).not.toHaveBeenCalled()
  expect(createObjectURL).not.toHaveBeenCalled()
  expect(store.toast).not.toHaveBeenCalled()
})

it.each([{ source: RAW }, { projectIds: ['p1', 'p2'] }, { scope: 'all' }, { token: 's1' }])('Android保留宿主原文数组交付，不走file.export/Blob %#', async payload => {
  const store = { state: { bridgeKind: 'android' }, bridge: { call: vi.fn().mockResolvedValue({ saved: true }) }, toast: vi.fn() }
  await expect(deliverSources(store, payload, 'save')).resolves.toEqual({ saved: true })
  expect(store.bridge.call).toHaveBeenCalledTimes(1)
  expect(store.bridge.call).toHaveBeenCalledWith('sources.deliver', { ...payload, action: 'save' })
  expect(createObjectURL).not.toHaveBeenCalled()
  expect(store.toast).toHaveBeenCalledTimes(1)
})

it('Android使用生产saved:false/cancelled:true回执，不报告成功', async () => {
  const store = { state: { bridgeKind: 'android' }, bridge: { call: vi.fn().mockResolvedValue({ saved: false, cancelled: true }) }, toast: vi.fn() }
  await expect(deliverSources(store, { source: RAW }, 'save')).resolves.toMatchObject({ cancelled: true })
  expect(store.toast).not.toHaveBeenCalled()
})

it.each([{ saved: false }, { saved: false, cancelled: false }, { saved: 'true' }, {}, { saved: true, cancelled: true }, { cancelled: true }])('保存未确认或矛盾回执不视作取消/成功 %#', async receipt => {
  const store = { state: { bridgeKind: 'android' }, bridge: { call: vi.fn().mockResolvedValue(receipt) }, toast: vi.fn() }
  await expect(deliverSources(store, { source: RAW }, 'save')).rejects.toThrow('文件保存')
  expect(store.toast).not.toHaveBeenCalled()
})
