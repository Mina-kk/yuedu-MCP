import { afterEach, describe, expect, it } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createStore } from '../src/store.js'
import SourcesPage from '../src/pages/SourcesPage.vue'
enableAutoUnmount(afterEach)
const result = data => ({ content: [{ type: 'text', text: JSON.stringify(data) }] })
const row = (projectId, bookSourceUrl, updatedAt = 1) => ({ projectId, bookSourceUrl, updatedAt, bookSourceName: '同名书源', versionCount: 4 })
async function page(rows, list, readResult) {
  const calls = []
  const bridge = { kind: 'android', connected: true, onEvent: () => () => {}, describe: () => ({ kind: 'android', connected: true }), call: async (method, params) => {
    const name = method === 'tools.call' ? params.name : method
    const args = method === 'tools.call' ? params.arguments : params
    calls.push([name, args])
    if (name === 'app.bootstrap') return { service: { running: true }, capabilities: {} }
    if (name === 'list_sources') return list ? list(args) : result({ total: rows.length, hasMore: args.offset + args.limit < rows.length, items: rows.slice(args.offset, args.offset + args.limit) })
    if (name === 'read_result') return readResult(args)
    if (name === 'get_source') return result({ bookSourceName: args.key, bookSourceUrl: 'https://axxsw.net/' })
    if (name === 'sources.deliver') return { copied: true, saved: true, launched: true }
    throw new Error(name)
  } }
  const store = createStore({ bridge, storage: null }); await store.bootstrap()
  const wrapper = mount(SourcesPage, { props: { store }, attachTo: document.body }); await flushPromises()
  return { wrapper, calls, store }
}
describe('domain source groups', () => {
  it('reads real boundedResult references through read_result body chunks before grouping', async () => {
    const rows = [row('one', 'https://www.a.net', 1), row('two', 'https://m.a.net', 2)].map(r => ({ ...r, bookSourceName: '长名称'.repeat(5000) }))
    const raw = JSON.stringify({ total: 2, hasMore: false, items: rows })
    const { wrapper, calls } = await page([], () => result({ id: 'result:one', kind: 'result', resultId: 'result:one', resultContextId: 'ctx:one', contextId: 'ctx:one', resultChars: raw.length, totalChars: raw.length, preview: raw.slice(0, 3000), nextOffset: 3000, hasMore: true }), ({ resultId, contextId, offset, limit }) => {
      expect(resultId).toBe('result:one'); expect(contextId).toBe('ctx:one')
      const end = Math.min(offset + limit, raw.length)
      return result({ id: resultId, contextId, kind: 'result', found: true, body: raw.slice(offset, end), offset, nextOffset: end, totalChars: raw.length, hasMore: end < raw.length })
    })
    expect(calls.filter(([name]) => name === 'read_result').length).toBeGreaterThan(1)
    expect(wrapper.get('.source-group').text()).toContain('2 条记录')
    expect(wrapper.get('.source-entry').attributes('data-project-id')).toBe('two')
  })
  it.each(['size', 'progress', 'offset', 'total', 'hasMore', 'identity'])('rejects malformed result reference chunks: %s', async mode => {
    const { wrapper } = await page([], () => result({ resultId: 'r', resultContextId: 'c', resultChars: mode === 'size' ? 999999999 : 50, totalChars: mode === 'size' ? 999999999 : 50 }), () => result({ id: mode === 'identity' ? 'other' : 'r', contextId: 'c', kind: 'result', found: true, body: mode === 'progress' ? '' : 'x', offset: mode === 'offset' ? 1 : 0, nextOffset: mode === 'progress' ? 0 : 1, totalChars: mode === 'total' ? 51 : 50, hasMore: mode === 'hasMore' ? false : true }))
    expect(wrapper.findAll('.source-group')).toHaveLength(0)
    expect(wrapper.get('[data-list-error]').text()).toBeTruthy()
  })
  it('groups 1001 summaries across bounded batches, expands history and delivers exact full historical ID', async () => {
    const rows = Array.from({ length: 1001 }, (_, i) => row(`project-${i}-same-tail`, i % 2 ? 'https://m.axxsw.net/' : 'https://www.axxsw.net/', i))
    const { wrapper, calls } = await page(rows)
    expect(calls.filter(([name]) => name === 'list_sources').map(([, args]) => [args.offset, args.limit])).toEqual(Array.from({ length: Math.ceil(rows.length / 20) }, (_, i) => [i * 20, 20]))
    const group = wrapper.get('.source-group[data-source-domain="axxsw.net"]')
    expect(group.text()).toContain('1001 条记录')
    expect(group.findAll('.source-entry')).toHaveLength(1)
    expect(group.get('.source-entry').attributes('data-project-id')).toBe('project-1000-same-tail')
    expect(group.text()).toContain('最新保存')
    expect(group.text()).not.toContain('4 版本')
    await group.get('[data-source-group-toggle]').trigger('click')
    expect(group.get('[data-source-group-toggle]').attributes('aria-expanded')).toBe('true')
    expect(group.findAll('.source-entry')).toHaveLength(20)
    const historical = group.get('[data-project-id="project-999-same-tail"]')
    expect(historical.text()).toContain('project-999-same-tail')
    await historical.get('[data-source-action="copy"]').trigger('click'); await flushPromises()
    expect(calls.at(-1)).toEqual(['sources.deliver', { projectIds: ['project-999-same-tail'], action: 'copy' }])
    await historical.get('.source-row').trigger('click'); await flushPromises()
    await wrapper.get('[data-detail-back]').trigger('click'); await flushPromises()
    expect(group.get('[data-source-group-toggle]').attributes('aria-expanded')).toBe('true')
    await group.get('[data-group-more]').trigger('click')
    expect(group.findAll('.source-entry')).toHaveLength(40)
    await wrapper.get('[data-selection-toggle]').trigger('click')
    await wrapper.get('input[aria-label="全选当前页"]').setValue(true)
    expect(wrapper.text()).toContain('已选 40 条')
    await wrapper.get('[data-export-selected]').trigger('click'); await flushPromises()
    expect(calls.at(-1)[1].projectIds).toEqual(rows.slice(-40).reverse().map(r => r.projectId))
  })
  it('uses original compound suffix/root domain rules, retains IP and unknown labels, orders latest saves', async () => {
    const { wrapper } = await page([
      row('old', 'https://www.axxsw.net/', 1), row('new', 'https://m.axxsw.net/', 20),
      row('same-name-other', 'https://another.net/', 19), row('cn-a', 'https://m.a.com.cn', 18), row('cn-b', 'https://www.b.com.cn', 17),
      row('uk', 'https://www.a.co.uk', 16), row('ip-a', 'http://192.0.2.1', 15), row('ip-b', 'http://192.0.2.2', 14),
      row('label-a', '标签 甲', 13), row('label-b', '标签 乙', 12), row('unknown', '', 11)
    ])
    expect(wrapper.findAll('.source-group').map(g => g.attributes('data-source-domain'))).toEqual(['axxsw.net', 'another.net', 'a.com.cn', 'b.com.cn', 'a.co.uk', '192.0.2.1', '192.0.2.2', '标签 甲', '标签 乙', '未识别域名'])
    expect(wrapper.findAll('.source-entry').map(g => g.attributes('data-project-id'))).toEqual(['new', 'same-name-other', 'cn-a', 'cn-b', 'uk', 'ip-a', 'ip-b', 'label-a', 'label-b', 'unknown'])
  })
  it('paginates 20 sites, select-all excludes hidden history and off-page sites', async () => {
    const rows = Array.from({ length: 21 }, (_, i) => row(`p${i}`, `https://site${i}.net`, 100 - i))
    rows.push(row('history', 'https://site0.net', 0))
    const { wrapper, calls } = await page(rows)
    expect(wrapper.findAll('.source-group')).toHaveLength(20)
    await wrapper.get('[data-selection-toggle]').trigger('click'); await wrapper.get('input[aria-label="全选当前页"]').setValue(true)
    expect(wrapper.text()).toContain('已选 20 条')
    await wrapper.get('.pager button:last-child').trigger('click')
    expect(wrapper.findAll('.source-group')).toHaveLength(1)
    await wrapper.get('input[aria-label="全选当前页"]').setValue(true)
    await wrapper.get('[data-export-selected]').trigger('click'); await flushPromises()
    expect(calls.at(-1)[1].projectIds).toEqual(rows.slice(0, 21).map(r => r.projectId))
  })
  it('preserves selection while a pending query hides expanded old history', async () => {
    let finish
    const oldRows = [row('kept', 'https://old.net', 2), row('history', 'https://old.net', 1)]
    const { wrapper, calls } = await page([], ({ query }) => query === 'new'
      ? new Promise(resolve => { finish = resolve })
      : result({ total: oldRows.length, hasMore: false, items: oldRows }))
    await wrapper.get('[data-source-group-toggle]').trigger('click')
    await wrapper.get('[data-selection-toggle]').trigger('click')
    await wrapper.get('[data-project-id="kept"] input').setValue(true)
    expect(wrapper.findAll('.source-entry')).toHaveLength(2)
    expect(wrapper.vm.$.setupState.selectedIds).toEqual(['kept'])
    await wrapper.get('input[type="search"]').setValue('new')
    await wrapper.get('input[type="search"]').trigger('keyup.enter')
    await flushPromises()
    expect(wrapper.findAll('.source-entry')).toHaveLength(0)
    expect(wrapper.text()).toContain('正在读取书源')
    const selectAll = wrapper.get('input[aria-label="全选当前页"]')
    expect.soft(selectAll.element.disabled).toBe(true)
    selectAll.element.click()
    await flushPromises()
    expect.soft(wrapper.vm.$.setupState.selectedIds).toEqual(['kept'])
    // dispatchEvent deliberately bypasses disabled-control click suppression,
    // exercising the real Vue change handler for both select and deselect.
    for (const checked of [true, false]) {
      selectAll.element.checked = checked
      selectAll.element.dispatchEvent(new Event('change', { bubbles: true }))
      await flushPromises()
      expect.soft(wrapper.vm.$.setupState.selectedIds).toEqual(['kept'])
    }
    finish(result({ total: 1, hasMore: false, items: [row('new', 'https://new.net')] }))
    await flushPromises()
    expect(selectAll.element.disabled).toBe(false)
    expect(wrapper.findAll('.source-entry').map(r => r.attributes('data-project-id'))).toEqual(['new'])
    await wrapper.get('[data-export-selected]').trigger('click'); await flushPromises()
    expect(calls.at(-1)).toEqual(['sources.deliver', { projectIds: ['kept'], action: 'save' }])
  })
  it.each(['duplicate', 'count', 'progress', 'total', 'hasMore', 'error'])('rejects incomplete pagination: %s without displaying partial groups', async mode => {
    const { wrapper, calls } = await page([], async ({ offset }) => {
      if (!offset) return result({ total: 2, hasMore: true, items: [row('one', 'https://a.net')] })
      if (mode === 'error') throw new Error('分页失败')
      return result({ total: mode === 'total' ? 3 : 2, hasMore: mode === 'progress' ? true : mode === 'hasMore' ? 'false' : false, items: ['count', 'progress'].includes(mode) ? [] : [row(mode === 'duplicate' ? 'one' : 'two', 'https://a.net')] })
    })
    expect(calls.filter(([name]) => name === 'list_sources')).toHaveLength(2)
    expect(wrapper.findAll('.source-group')).toHaveLength(0)
    expect(wrapper.get('[data-list-error]').text()).toBeTruthy()
  })
  it.each([false, true])('late old-query response cannot overwrite current list (failure=%s)', async fail => {
    let finish
    const { wrapper } = await page([], ({ query }) => query === 'new' ? result({ total: 1, hasMore: false, items: [row('new', 'https://new.net')] }) : new Promise(resolve => { finish = resolve }))
    await wrapper.get('input[type="search"]').setValue('new'); await wrapper.get('input[type="search"]').trigger('keyup.enter'); await flushPromises()
    finish(fail ? { isError: true, content: [{ type: 'text', text: 'old error' }] } : result({ total: 1, hasMore: false, items: [row('old', 'https://old.net')] })); await flushPromises()
    expect(wrapper.findAll('.source-entry').map(r => r.attributes('data-project-id'))).toEqual(['new'])
    expect(wrapper.text()).not.toContain('old error')
  })
  it('single-label domains never collide with object prototype state', async () => {
    const { wrapper } = await page([row('a', 'http://constructor', 2), row('b', 'http://constructor', 1)])
    expect(wrapper.findAll('.source-entry')).toHaveLength(1)
    expect(wrapper.get('[data-source-group-toggle]').attributes('aria-expanded')).toBe('false')
    await wrapper.get('[data-source-group-toggle]').trigger('click')
    expect(wrapper.findAll('.source-entry')).toHaveLength(2)
  })
  it('late reference chunks cannot restart an unmounted list', async () => {
    let finish
    const { wrapper, calls } = await page([], () => result({ resultId: 'r', resultContextId: 'c', resultChars: 20, totalChars: 20 }), () => new Promise(resolve => { finish = resolve }))
    wrapper.unmount()
    finish(result({ id: 'r', contextId: 'c', kind: 'result', found: true, offset: 0, nextOffset: 1, totalChars: 20, body: 'x', hasMore: true })); await flushPromises()
    expect(calls.filter(([name]) => name === 'read_result')).toHaveLength(1)
  })
  it('unmount invalidates pending pagination before another batch starts', async () => {
    let finish
    const { wrapper, calls } = await page([], () => new Promise(resolve => { finish = resolve }))
    wrapper.unmount(); finish(result({ total: 2, hasMore: true, items: [row('one', 'https://a.net')] })); await flushPromises()
    expect(calls.filter(([name]) => name === 'list_sources')).toHaveLength(1)
  })
})
