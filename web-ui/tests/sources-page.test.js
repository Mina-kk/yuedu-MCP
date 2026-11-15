import { afterEach, describe, it, expect } from 'vitest'
import { enableAutoUnmount, mount, flushPromises } from '@vue/test-utils'
enableAutoUnmount(afterEach)
import { createStore } from '../src/store.js'
import SourcesPage from '../src/pages/SourcesPage.vue'

// 书源页：save_source 后必须 get_source 回读；冲突不自动覆盖；
// 保存提交的是用户原文（不做重新序列化，避免 >2^53 数字丢精度）。

function fakeBridge(handlers = {}) {
  const calls = []
  return {
    calls,
    kind: 'android',
    connected: true,
    call: async (method, params) => {
      const name = method === 'tools.call' ? params.name : method
      const args = method === 'tools.call' ? params.arguments : params
      calls.push([name, args])
      const fn = handlers[name]
      if (!fn) throw Object.assign(new Error(`unsupported ${name}`), { code: 'unsupported' })
      return fn(args)
    },
    onEvent: () => () => {},
    dispose: () => {},
    describe: () => ({ kind: 'android', connected: true })
  }
}

const BOOTSTRAP = { service: { running: true, port: 8787, endpoints: [] }, capabilities: { toolCount: 73 } }

const LIST = {
  total: 1,
  hasMore: false,
  items: [{ projectId: 'p1', bookSourceName: '测试源', bookSourceUrl: 'https://a.example', versionCount: 1, updatedAt: 1 }]
}

// 大整数：JSON.parse 会把它变成 1e+20，若前端重新序列化就会破坏原文。
const RAW = '{"bookSourceName":"测试源","bookSourceUrl":"https://a.example","weight":12345678901234567890}'

async function mountPage(handlers) {
  const bridge = fakeBridge({ 'app.bootstrap': async () => BOOTSTRAP, ...handlers })
  const store = createStore({ bridge, storage: null })
  await store.bootstrap()
  const wrapper = mount(SourcesPage, { attachTo: document.body, props: { store } })
  await flushPromises()
  return { wrapper, store, bridge }
}

const result = (data) => ({ content: [{ type: 'text', text: typeof data === 'string' ? data : JSON.stringify(data) }] })
const button = (wrapper, text) => wrapper.findAll('button').find((b) => b.isVisible() && b.text() === text)
async function selectAll(wrapper) {
  if (!wrapper.find('input[aria-label="全选当前页"]').exists()) await button(wrapper, '管理').trigger('click')
  await wrapper.find('input[aria-label="全选当前页"]').setValue(true)
}
async function requestDelete(wrapper) {
  if (!button(wrapper, '删除书源')) await button(wrapper, '更多').trigger('click')
  await button(wrapper, '删除书源').trigger('click')
}
async function acceptDialog(store) {
  const accept = store.state.dialog.onAccept
  store.closeDialog()
  await accept()
  await flushPromises()
}

describe('SourcesPage', () => {
  it('折叠再展开高级面板保留过滤与分析快照，不重置用户决定', async () => {
    const { wrapper, bridge } = await mountPage({
      list_sources: async () => result(LIST),
      'sources.analyze': async () => ({ token: 'snapshot-persist', total: 1, keptCount: 1, excludedCount: 0, groups: [], kept: [], excluded: [], clusters: [], conflicts: [] }),
      'sources.deliver': async () => ({ copied: true })
    })
    await wrapper.find('[data-advanced-toggle]').trigger('click')
    await wrapper.find('.options input').setValue(true)
    await button(wrapper, '全库分析与分组').trigger('click'); await flushPromises()
    await wrapper.find('[data-advanced-toggle]').trigger('click')
    expect(button(wrapper, '全库分析与分组')).toBeUndefined()
    await wrapper.find('[data-advanced-toggle]').trigger('click')
    expect(wrapper.find('.options input').element.checked).toBe(true)
    await button(wrapper, '复制完整数组').trigger('click'); await flushPromises()
    expect(bridge.calls.at(-1)).toEqual(['sources.deliver', { token: 'snapshot-persist', action: 'copy' }])
    expect(bridge.calls.filter(([method]) => method === 'sources.analyze')).toHaveLength(1)
  })
  it.each([
    ['copy', '复制', { copied: false }], ['save', '导出', { saved: 'true' }],
    ['share', '分享', { launched: false }], ['save', '导出', { cancelled: true, saved: true }]
  ])('单条%s失败/取消不报成功也不打开详情', async (action, label, receipt) => {
    const { wrapper, bridge, store } = await mountPage({ list_sources: async () => result(LIST), 'sources.deliver': async () => receipt })
    await button(wrapper.find('.source-entry'), label).trigger('click'); await flushPromises()
    expect(bridge.calls.at(-1)).toEqual(['sources.deliver', { projectIds: ['p1'], action }])
    expect(store.state.toast).toBeNull()
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
  })
  it('列表交付期间防重，返回列表保留选择和原滚动', async () => {
    let finish
    const { wrapper, bridge } = await mountPage({
      list_sources: async () => result(LIST), get_source: async () => result(RAW),
      'sources.deliver': () => new Promise(resolve => { finish = resolve })
    })
    await selectAll(wrapper)
    const page = wrapper.find('.page').element
    page.scrollTop = 230
    await wrapper.find('[data-source-action="save"]').trigger('click')
    await wrapper.find('[data-source-action="save"]').trigger('click')
    expect(bridge.calls.filter(([method]) => method === 'sources.deliver')).toHaveLength(1)
    expect(wrapper.find('[data-export-all]').attributes('disabled')).toBeDefined()
    finish({ saved: false, cancelled: true }); await flushPromises()
    await wrapper.find('.source-row').trigger('click'); await flushPromises()
    await wrapper.find('[data-detail-back]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('已选 1 条')
    expect(wrapper.find('input[aria-label="全选当前页"]').element.checked).toBe(true)
    expect(page.scrollTop).toBe(230)
  })
  it('单条更多中的导入阅读保留精确ID，选包只报告待确认', async () => {
    const { wrapper, bridge, store } = await mountPage({
      list_sources: async () => result(LIST),
      'sources.deliver': async ({ action }) => action === 'targets' ? { targets: [{ packageName: 'reader.test', label: '阅读测试' }, { packageName: 'reader.other', label: '另一阅读' }] } : { launched: true, confirmed: false, confirmation: 'pending_user' }
    })
    await wrapper.find('[data-source-more]').trigger('click')
    await button(wrapper, '导入阅读').trigger('click'); await flushPromises()
    expect(bridge.calls.at(-1)).toEqual(['sources.deliver', { projectIds: ['p1'], action: 'targets' }])
    expect(document.querySelector('[role="dialog"][aria-label="导入阅读"]')).not.toBeNull()
    expect(document.querySelectorAll('[data-reader-target]')).toHaveLength(2)
    document.querySelector('[data-reader-target="reader.test"]').click(); await flushPromises()
    expect(bridge.calls.at(-1)).toEqual(['sources.deliver', { projectIds: ['p1'], action: 'reader', packageName: 'reader.test' }])
    expect(store.state.toast.text).toContain('待用户确认')
    expect(bridge.calls.some(([method]) => ['get_source', 'sources.analyze'].includes(method))).toBe(false)
  })
  it('超过500条选择明确拒绝且不截断，仍可一键全库导出', async () => {
    const { wrapper, bridge } = await mountPage({
      list_sources: async ({ offset }) => result({ total: 501, hasMore: offset === 0, items: Array.from({ length: offset === 0 ? 500 : 1 }, (_, i) => ({ ...LIST.items[0], projectId: offset === 0 ? `p${i}` : 'p500' })) }),
      'sources.deliver': async () => ({ saved: true })
    })
    await wrapper.find('[data-selection-toggle]').trigger('click')
    await wrapper.find('input[aria-label="全选当前页"]').setValue(true)
    await wrapper.find('.pager button:last-child').trigger('click'); await flushPromises()
    await wrapper.find('input[aria-label="全选当前页"]').setValue(true)
    await wrapper.find('[data-export-selected]').trigger('click'); await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('500')
    expect(wrapper.text()).toContain('已选 501 条')
    expect(bridge.calls.some(([method]) => method === 'sources.deliver')).toBe(false)
    await wrapper.find('[data-export-all]').trigger('click'); await flushPromises()
    expect(bridge.calls.at(-1)).toEqual(['sources.deliver', { scope: 'all', action: 'save' }])
  }, 30000)
  it('详情常用动作位于JSON之前，交付当前未保存草稿且不偷偷保存或重排大整数', async () => {
    const { wrapper, bridge, store } = await mountPage({
      list_sources: async () => result(LIST), get_source: async () => result(RAW),
      'sources.deliver': async ({ action }) => ({ copied: action === 'copy', saved: action === 'save', launched: action === 'share' })
    })
    await wrapper.find('.source-row').trigger('click'); await flushPromises()
    const draft = ` \n${RAW}  `
    await wrapper.find('textarea').setValue(draft)
    expect(wrapper.text()).toContain('当前草稿')
    for (const [label, action] of [['复制', 'copy'], ['导出', 'save'], ['分享', 'share']]) {
      const control = button(wrapper, label)
      expect(control?.isVisible()).toBe(true)
      expect(control.element.compareDocumentPosition(wrapper.find('textarea').element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      await control.trigger('click'); await flushPromises()
      expect(bridge.calls.at(-1)).toEqual(['sources.deliver', { source: draft, action }])
    }
    expect(bridge.calls.some(([method]) => method === 'save_source')).toBe(false)
    expect(wrapper.find('textarea').element.value).toBe(draft)
    expect(wrapper.vm.handleBack()).toBe(true)
    expect(store.state.dialog.kind).toBe('discard')
  })
  it('全库直接交付不受搜索、分页、版本与高级过滤影响，默认全部版本', async () => {
    const { wrapper, bridge } = await mountPage({
      list_sources: async () => result({ ...LIST, total: 41, hasMore: true }),
      'sources.deliver': async ({ action }) => ({ saved: action === 'save', launched: action === 'share' })
    })
    expect(bridge.calls.find(([method]) => method === 'list_sources')[1].latestOnly).not.toBe(true)
    await wrapper.find('input[type="search"]').setValue('only-one')
    await wrapper.find('button[aria-expanded]').trigger('click')
    await button(wrapper, '列出全部版本').trigger('click'); await flushPromises()
    await wrapper.find('.options input').setValue(true)
    await button(wrapper, '下一页').trigger('click'); await flushPromises()
    for (const [label, action] of [['导出全部', 'save'], ['分享全部', 'share']]) {
      await button(wrapper, label).trigger('click'); await flushPromises()
      expect(bridge.calls.at(-1)).toEqual(['sources.deliver', { scope: 'all', action }])
    }
    expect(bridge.calls.some(([method]) => ['get_source', 'sources.analyze', 'file.export'].includes(method))).toBe(false)
  })
  it('跨页选择只交付精确ID，取消选择清空；取消或失败不显示成功', async () => {
    let receipt = { saved: false, cancelled: true }
    const { wrapper, bridge, store } = await mountPage({
      list_sources: async ({ offset }) => result({ total: 21, hasMore: offset === 0, items: [{ ...LIST.items[0], projectId: offset === 0 ? 'p1' : 'p2' }] }),
      'sources.deliver': async () => receipt
    })
    await selectAll(wrapper)
    await button(wrapper, '下一页').trigger('click'); await flushPromises()
    await selectAll(wrapper)
    expect(wrapper.text()).toContain('跨页保留选择')
    expect(wrapper.text()).toContain('已选 2 条')
    for (const [label, action] of [['导出选中', 'save'], ['分享选中', 'share']]) {
      await button(wrapper, label).trigger('click'); await flushPromises()
      expect(bridge.calls.at(-1)).toEqual(['sources.deliver', { projectIds: ['p1', 'p2'], action }])
      expect(store.state.toast).toBeNull()
    }
    receipt = { saved: false }
    await button(wrapper, '导出选中').trigger('click'); await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('文件保存未确认')
    expect(store.state.toast).toBeNull()
    expect(wrapper.text()).toContain('已选 2 条')
    await button(wrapper, '取消选择').trigger('click')
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(0)
    await button(wrapper, '管理').trigger('click')
    expect(wrapper.text()).toContain('已选 0 条')
  })
  it('首屏只保留常用操作，单条复制导出分享直接按精确ID交付，不读JSON或分析', async () => {
    const { wrapper, bridge } = await mountPage({
      list_sources: async () => result({ ...LIST, total: 41, hasMore: true, items: [{ ...LIST.items[0], stage: 'VALIDATED', versionCount: 3 }] }),
      'sources.deliver': async ({ action }) => ({ copied: action === 'copy', saved: action === 'save', launched: action === 'share' })
    })
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(0)
    for (const label of ['导入', '导出全部', '分享全部', '管理']) expect(button(wrapper, label)?.isVisible()).toBe(true)
    expect(wrapper.find('[aria-expanded="false"]').text()).toContain('高级分析与过滤')
    const row = wrapper.find('.source-entry')
    expect(row.text()).toContain('静态校验通过')
    expect(row.findAll('button button')).toHaveLength(0)
    expect(button(row, '删除书源')).toBeUndefined()
    for (const [label, action] of [['复制', 'copy'], ['导出', 'save'], ['分享', 'share']]) {
      await button(row, label).trigger('click'); await flushPromises()
      expect(bridge.calls.at(-1)).toEqual(['sources.deliver', { projectIds: ['p1'], action }])
      expect(wrapper.find('textarea').exists()).toBe(false)
    }
    expect(bridge.calls.some(([method]) => ['get_source', 'sources.analyze', 'save_source'].includes(method))).toBe(false)
  })
  it('详情删除失败仍显示持久错误', async () => {
    const { wrapper, store } = await mountPage({ list_sources: async () => result(LIST), get_source: async () => result(RAW), delete_projects: async () => { throw new Error('删除断线') } })
    await wrapper.find('.source-row').trigger('click'); await flushPromises()
    await wrapper.findAll('button').find(b => b.isVisible() && b.text() === '删除书源').trigger('click')
    await acceptDialog(store)
    expect(wrapper.findAll('[role="alert"]').some(e => e.isVisible() && e.text().includes('删除未完全确认'))).toBe(true)
  })
  it('独立详情仍提供可见的单条删除入口', async () => {
    const { wrapper, store } = await mountPage({ list_sources: async () => result(LIST), get_source: async () => result(RAW) })
    await wrapper.find('.source-row').trigger('click'); await flushPromises()
    const remove = wrapper.findAll('button').find(b => b.isVisible() && b.text() === '删除书源')
    expect(remove).toBeTruthy()
    await remove.trigger('click')
    expect(store.state.dialog.kind).toBe('delete')
  })

  it.each([true, false])('加载时可返回，迟到回包不重开详情；失败可见=%s', async (fail) => {
    let finish
    const { wrapper } = await mountPage({ list_sources: async () => result(LIST), get_source: () => new Promise(resolve => { finish = resolve }) })
    await wrapper.find('.source-row').trigger('click')
    expect(wrapper.find('.source-row').isVisible()).toBe(false)
    expect(wrapper.find('[data-detail-back]').exists()).toBe(true)
    if (!fail) {
      expect(wrapper.vm.handleBack()).toBe(true)
      await flushPromises()
    }
    finish({ content: [{ type: 'text', text: fail ? '读取失败证据' : '迟到内容' }], isError: fail })
    await flushPromises()
    if (fail) {
      expect(wrapper.text()).toContain('读取失败证据')
      expect(wrapper.vm.handleBack()).toBe(true)
      await flushPromises()
    }
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('.source-row').isVisible()).toBe(true)
  })

  it('系统返回保护未保存内容，取消保留、确认才退出', async () => {
    const { wrapper, store } = await mountPage({ list_sources: async () => result(LIST), get_source: async () => result(RAW) })
    await wrapper.find('.source-row').trigger('click'); await flushPromises()
    await wrapper.find('textarea').setValue('未保存草稿')
    expect(wrapper.vm.handleBack()).toBe(true)
    expect(store.state.dialog?.kind).toBe('discard')
    store.closeDialog()
    expect(wrapper.find('textarea').element.value).toBe('未保存草稿')
    expect(wrapper.vm.handleBack()).toBe(true)
    const accept = store.state.dialog.onAccept
    store.closeDialog(); await accept(); await flushPromises()
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('.source-row').isVisible()).toBe(true)
  })

  it('详情独立展示，顶部返回恢复列表与滚动且不聚焦表单', async () => {
    const { wrapper } = await mountPage({ list_sources: async () => result(LIST), get_source: async () => result(RAW) })
    const page = wrapper.find('.page').element
    page.scrollTop = 320
    const list = wrapper.find('.source-row').element.parentElement
    list.scrollTop = 75
    await wrapper.find('.source-row').trigger('click'); await flushPromises()
    expect(wrapper.find('.source-row').isVisible()).toBe(false)
    const back = wrapper.find('[data-detail-back]')
    expect(back.exists()).toBe(true)
    expect(back.isVisible()).toBe(true)
    expect(page.scrollTop).toBe(0)
    expect(document.activeElement?.tagName).not.toMatch(/INPUT|TEXTAREA/)
    await back.trigger('click'); await flushPromises()
    expect(wrapper.find('.source-row').isVisible()).toBe(true)
    expect(page.scrollTop).toBe(320)
    expect(list.scrollTop).toBe(75)
    expect(wrapper.vm.handleBack()).toBe(false)
  })

  it('回读总数与枚举不一致不能清除选择或报成功', async () => {
    let deleted = false
    const { wrapper, store } = await mountPage({
      list_sources: async () => result(deleted ? { total: 1, hasMore: false, items: [] } : LIST),
      delete_projects: async () => { deleted = true; return result('已删除 1 个项目') }
    })
    await selectAll(wrapper)
    await button(wrapper, '删除选中').trigger('click'); await acceptDialog(store)
    expect(store.state.toast.kind).toBe('err')
    expect(wrapper.text()).toContain('已选 1 条')
  })
  it('回读重复分页时停止并保留选择，不把分页不完整当作删除成功', async () => {
    let deleted = false
    let reads = 0
    const { wrapper, store } = await mountPage({
      list_sources: async () => {
        if (!deleted) return result(LIST)
        reads += 1
        // 第三页是保护测试的有限边界；实现应在第二次重复页即停止。
        return result({ total: 3, hasMore: reads < 3, items: [{ ...LIST.items[0], projectId: 'other' }] })
      },
      delete_projects: async () => { deleted = true; return result('已删除 1 个项目') }
    })
    await selectAll(wrapper)
    await button(wrapper, '删除选中').trigger('click'); await acceptDialog(store)
    expect(store.state.toast.kind).toBe('err')
    expect(reads).toBe(2)
    expect(wrapper.text()).toContain('已选 1 条')
  })
  it('列表刷新返回非 JSON 不可宣称删除成功', async () => {
    let deleted = false
    let reads = 0
    const { wrapper, store } = await mountPage({
      list_sources: async () => !deleted ? result(LIST) : (++reads === 1 ? result({ total: 0, hasMore: false, items: [] }) : result('非法回包')),
      delete_projects: async () => { deleted = true; return result('已删除 1 个项目') }
    })
    await requestDelete(wrapper); await acceptDialog(store)
    expect(store.state.toast.kind).toBe('err')
    expect(wrapper.text()).toContain('列表刷新失败')
  })
  it('未保存编辑必须先确认丢弃，取消保留草稿，删除与回读期间锁定操作并防重复', async () => {
    let resolveDelete
    let deleted = false
    const { wrapper, store, bridge } = await mountPage({
      list_sources: async () => result(deleted ? { total: 0, hasMore: false, items: [] } : LIST),
      get_source: async () => result(RAW),
      delete_projects: () => new Promise((resolve) => { resolveDelete = () => { deleted = true; resolve(result('已删除 1 个项目')) } })
    })
    await wrapper.find('.source-row').trigger('click'); await flushPromises()
    await wrapper.find('textarea').setValue(RAW + ' ')
    await requestDelete(wrapper)
    expect(store.state.dialog.kind).toBe('discard')
    store.closeDialog()
    expect(wrapper.find('textarea').element.value).toBe(RAW + ' ')
    await requestDelete(wrapper); await acceptDialog(store)
    expect(store.state.dialog.kind).toBe('delete')
    const accept = store.state.dialog.onAccept
    store.closeDialog()
    const pending = accept(); await flushPromises()
    expect(wrapper.find('fieldset').element.disabled).toBe(true)
    await accept()
    expect(bridge.calls.filter(([m]) => m === 'delete_projects')).toHaveLength(1)
    resolveDelete(); await pending; await flushPromises()
    expect(wrapper.find('fieldset').element.disabled).toBe(false)
    expect(wrapper.find('textarea').exists()).toBe(false)
  })
  it.each(['partial', 'error', 'readback', 'malformed', 'still-present'])('删除 %s 不假成功，保留未确认选择并显示持久错误', async (mode) => {
    let attempted = false
    const rows = [LIST.items[0], { ...LIST.items[0], projectId: 'p2' }]
    const { wrapper, store } = await mountPage({
      list_sources: async () => {
        if (attempted && mode === 'readback') throw new Error('回读断线')
        const remaining = attempted && mode === 'partial' ? [rows[1]] : rows
        return result({ total: remaining.length, hasMore: false, items: remaining })
      },
      delete_projects: async () => {
        attempted = true
        if (mode === 'error') throw new Error('删除断线')
        return result(mode === 'malformed' ? '{"deleted":2}' : `已删除 ${mode === 'partial' ? 1 : 2} 个项目`)
      }
    })
    await selectAll(wrapper)
    await button(wrapper, '删除选中').trigger('click'); await acceptDialog(store)
    expect(store.state.toast.kind).toBe('err')
    expect(wrapper.text()).toContain('删除未完全确认')
    expect(wrapper.text()).toContain(mode === 'partial' ? '已选 1 条' : '已选 2 条')
  })
  it('删除末页后回退有效页，全库回读不受搜索和最新版本折叠影响', async () => {
    let deleted = false
    const { wrapper, store, bridge } = await mountPage({
      list_sources: async ({ offset }) => result({ total: deleted ? 20 : 21, hasMore: offset === 0 && !deleted, items: offset === 20 ? (deleted ? [] : LIST.items) : Array.from({ length: 20 }, (_, i) => ({ ...LIST.items[0], projectId: `other-${i}` })) }),
      delete_projects: async () => { deleted = true; return result('已删除 1 个项目') }
    })
    await button(wrapper, '下一页').trigger('click'); await flushPromises()
    await requestDelete(wrapper); await acceptDialog(store)
    expect(wrapper.text()).toContain('偏移 0')
    expect(wrapper.findAll('.source-row')).toHaveLength(20)
    const calls = bridge.calls.slice(bridge.calls.findIndex(([m]) => m === 'delete_projects') + 1)
    expect(calls[0]).toEqual(['list_sources', { limit: 20, offset: 0, latestOnly: false }])
  })
  it('全选仅当前页、取消和计数，批量确认后精确删除选中项目', async () => {
    const rows = [LIST.items[0], { ...LIST.items[0], projectId: 'p2' }]
    let deleted = false
    const { wrapper, store, bridge } = await mountPage({
      list_sources: async () => result({ total: deleted ? 1 : 3, hasMore: !deleted, items: deleted ? [{ ...rows[0], projectId: 'unloaded' }] : rows }),
      delete_projects: async () => { deleted = true; return result('已删除 2 个项目') }
    })
    await button(wrapper, '管理').trigger('click')
    expect(wrapper.find('input[aria-label="全选当前页"]').exists()).toBe(true)
    await selectAll(wrapper)
    expect(wrapper.text()).toContain('已选 2 条')
    await button(wrapper, '取消选择').trigger('click')
    expect(wrapper.find('input[aria-label="全选当前页"]').exists()).toBe(false)
    await button(wrapper, '管理').trigger('click')
    expect(wrapper.text()).toContain('已选 0 条')
    await selectAll(wrapper)
    await button(wrapper, '删除选中').trigger('click')
    expect(store.state.dialog.message).toContain('2 条')
    await acceptDialog(store)
    expect(bridge.calls.find(([m]) => m === 'delete_projects')[1]).toEqual({ ids: ['p1', 'p2'] })
    expect(wrapper.text()).toContain('已选 0 条')
  })
  it('单条删除精确 projectId，确认不可恢复且不删除阅读端，真实文本回执后回读', async () => {
    let deleted = false
    const { wrapper, store, bridge } = await mountPage({
      list_sources: async () => result(deleted ? { total: 0, hasMore: false, items: [] } : LIST),
      delete_projects: async ({ ids }) => { expect(ids).toEqual(['p1']); deleted = true; return result('已删除 1 个项目') }
    })
    await button(wrapper, '更多').trigger('click')
    expect(button(wrapper, '删除书源')).toBeTruthy()
    await requestDelete(wrapper)
    expect(store.state.dialog.message).toContain('不可恢复')
    expect(store.state.dialog.message).toContain('不删除阅读端')
    expect(store.state.dialog.message).toContain('p1')
    expect(deleted).toBe(false)
    store.closeDialog()
    expect(deleted).toBe(false)
    await requestDelete(wrapper)
    await acceptDialog(store)
    expect(bridge.calls.find(([m]) => m === 'delete_projects')[1]).toEqual({ ids: ['p1'] })
    expect(bridge.calls.filter(([m]) => m === 'list_sources').length).toBeGreaterThan(1)
    expect(wrapper.findAll('.source-row')).toHaveLength(0)
    expect(store.state.toast.text).toContain('已删除')
  })
  it('导入数组逐项提交原文字符串，保留大整数与转义文本', async () => {
    const SECOND = '{ "bookSourceName": "另一源", "bookSourceUrl": "https://b.example", "weight": 12345678901234567891 }'
    const { wrapper, bridge } = await mountPage({
      list_sources: async () => ({ content: [{ type: 'text', text: JSON.stringify(LIST) }] }),
      'file.import': async () => ({ name: 'sources.json', text: `[\n${RAW},\n${SECOND}\n]` }),
      save_source: async () => ({ content: [{ type: 'text', text: '{"projectId":"p2"}' }] })
    })
    await wrapper.findAll('button').find((b) => b.text() === '导入').trigger('click')
    await flushPromises()
    const saved = bridge.calls.filter(([m]) => m === 'save_source').map(([,p]) => p.source)
    expect(saved).toEqual([RAW, SECOND])
  })
  it('列表来自 list_sources，点开用 get_source 读取原文', async () => {
    const { wrapper, bridge } = await mountPage({
      'list_sources': async () => ({ content: [{ type: 'text', text: JSON.stringify(LIST) }] }),
      'get_source': async () => ({ content: [{ type: 'text', text: RAW }] })
    })
    expect(wrapper.text()).toContain('测试源')
    await wrapper.find('.source-row').trigger('click')
    await flushPromises()
    const ta = wrapper.find('textarea')
    expect(ta.element.value).toBe(RAW)
    expect(bridge.calls.some(([m, p]) => m === 'get_source' && p.key === 'p1')).toBe(true)
  })

  it('保存提交用户原文，保存后 get_source 回读', async () => {
    const { wrapper, bridge } = await mountPage({
      'list_sources': async () => ({ content: [{ type: 'text', text: JSON.stringify(LIST) }] }),
      'get_source': async () => ({ content: [{ type: 'text', text: RAW }] }),
      'save_source': async () => ({ content: [{ type: 'text', text: '{"projectId":"p1"}' }] })
    })
    await wrapper.find('.source-row').trigger('click')
    await flushPromises()
    await wrapper.find('textarea').setValue(RAW)
    const saveBtn = wrapper.findAll('button').find((b) => b.text().includes('保存并回读'))
    await saveBtn.trigger('click')
    await flushPromises()
    const save = bridge.calls.find(([m]) => m === 'save_source')
    expect(save).toBeTruthy()
    // 原文原样提交：字符串里仍是大整数文本，没有被 JSON.stringify 重排
    expect(save[1].source).toBe(RAW)
    const reads = bridge.calls.filter(([m]) => m === 'get_source')
    expect(reads.length).toBeGreaterThanOrEqual(2)
  })

  it('冲突回执已保存新版本，按回执 projectId 回读而不再次保存', async () => {
    const { wrapper, bridge } = await mountPage({
      'list_sources': async () => ({ content: [{ type: 'text', text: JSON.stringify(LIST) }] }),
      'get_source': async () => ({ content: [{ type: 'text', text: RAW }] }),
      'save_source': async () => ({
        content: [{ type: 'text', text: '{"conflict":true,"projectId":"p2","message":"同 URL 不同内容"}' }]
      })
    })
    await wrapper.find('.source-row').trigger('click')
    await flushPromises()
    await wrapper.find('textarea').setValue('{"bookSourceUrl":"https://a.example","weight":1}')
    await wrapper.findAll('button').find((b) => b.text().includes('保存并回读')).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('版本冲突')
    expect(bridge.calls.filter(([m]) => m === 'get_source').at(-1)[1]).toEqual({ key: 'p2' })
    expect(wrapper.text()).toContain('已保留为新版本')
    // 没有自动带 newVersion 再写一次
    expect(bridge.calls.filter(([m, p]) => m === 'save_source' && p.newVersion).length).toBe(0)
  })

  it('回读新版本而不是仍读取编辑前的旧 projectId', async () => {
    const NEW_RAW = RAW.replace('测试源', '更新的源')
    const { wrapper, bridge } = await mountPage({
      list_sources: async () => ({ content: [{ type: 'text', text: JSON.stringify(LIST) }] }),
      get_source: async ({ key }) => ({ content: [{ type: 'text', text: key === 'p2' ? NEW_RAW : RAW }] }),
      save_source: async () => ({ content: [{ type: 'text', text: '{"projectId":"p2","action":"new_version"}' }] })
    })
    await wrapper.find('.source-row').trigger('click')
    await flushPromises()
    await wrapper.find('textarea').setValue(NEW_RAW)
    await wrapper.findAll('button').find((b) => b.text().includes('保存并回读')).trigger('click')
    await flushPromises()
    expect(bridge.calls.filter(([m]) => m === 'get_source').at(-1)[1]).toEqual({ key: 'p2' })
    expect(wrapper.find('textarea').element.value).toBe(NEW_RAW)
  })

  it('确认放弃后切换书源，不重复弹出确认', async () => {
    const { wrapper, store, bridge } = await mountPage({
      list_sources: async () => ({ content: [{ type: 'text', text: JSON.stringify(LIST) }] }),
      get_source: async () => ({ content: [{ type: 'text', text: RAW }] })
    })
    await wrapper.find('.source-row').trigger('click')
    await flushPromises()
    await wrapper.find('textarea').setValue(RAW + ' ')
    await wrapper.find('.source-row').trigger('click')
    const accept = store.state.dialog.onAccept
    store.closeDialog()
    await accept()
    await flushPromises()
    expect(store.state.dialog).toBeNull()
    expect(bridge.calls.filter(([m]) => m === 'get_source')).toHaveLength(2)
  })

  it('JSON 非法时本地报错，不调用 save_source', async () => {
    const { wrapper, bridge } = await mountPage({
      'list_sources': async () => ({ content: [{ type: 'text', text: JSON.stringify(LIST) }] }),
      'get_source': async () => ({ content: [{ type: 'text', text: RAW }] })
    })
    await wrapper.find('.source-row').trigger('click')
    await flushPromises()
    await wrapper.find('textarea').setValue('{bad json')
    await wrapper.findAll('button').find((b) => b.text().includes('保存并回读')).trigger('click')
    await flushPromises()
    expect(bridge.calls.some(([m]) => m === 'save_source')).toBe(false)
    expect(wrapper.text()).toMatch(/JSON/)
  })

  it('列表读取失败如实显示错误，不造条目', async () => {
    const { wrapper } = await mountPage({
      'list_sources': async () => ({ content: [{ type: 'text', text: '数据库不可用' }], isError: true })
    })
    expect(wrapper.text()).toContain('数据库不可用')
    expect(wrapper.findAll('.source-row')).toHaveLength(0)
  })
})
