import { afterEach, describe, expect, it, vi } from 'vitest'
import { DOMWrapper, mount, flushPromises } from '@vue/test-utils'
import LogsPage from '../src/pages/LogsPage.vue'

// Teleported dialogs are deliberately outside the page's clipped scroll host.
const dialog = () => new DOMWrapper(document.querySelector('.delete-overlay [role="dialog"]'))
const button = (w, text) => w.findAll('button').find(b => b.text() === text)
  || [...document.querySelectorAll('.delete-overlay button')].map(el => new DOMWrapper(el)).find(b => b.text() === text)
const result = data => ({ content: [{ type: 'text', text: JSON.stringify(data) }] })
const mounted = []
afterEach(() => {
  mounted.splice(0).forEach(w => w.unmount())
  vi.useRealTimers()
})
async function setup(handler, bridgeHandler, bridgeKind = 'android') {
  const store = { state: { bridgeKind }, bridge: { call: vi.fn(bridgeHandler) }, callTool: vi.fn(handler), toast: vi.fn(), confirmDialog: vi.fn() }
  const host = document.createElement('main')
  host.className = 'page-body'
  document.body.appendChild(host)
  const wrapper = mount(LogsPage, { props: { store }, attachTo: host })
  mounted.push({ unmount: () => { wrapper.unmount(); host.remove() } })
  await flushPromises()
  return { wrapper, store, host }
}
const cases = [
  ['操作', 'get_logs', 'get_log', { id: 1, message: '操作记录' }, { id: 1 }],
  ['HTTP', 'get_http_logs', 'get_http_log', { id: 2, url: 'https://example.com' }, { id: 2 }],
  ['抓包', 'list_captures', 'get_capture', { contextId: 'cap:one' }, { contextId: 'cap:one', limit: 50, offset: 0 }],
  ['崩溃', 'get_crash_logs', 'get_crash_log', { name: 'crash.txt' }, { name: 'crash.txt' }],
  ['诊断', 'get_diagnostic_snapshots', 'get_diagnostic_snapshot', { id: 'snap-one' }, { id: 'snap-one' }]
]
describe('日志独立详情', () => {
  it('批次去重且最多 100，failed 不能计入成功', async () => {
    const rows = [...Array.from({ length: 105 }, (_, i) => ({ id: String(i + 1) })), { id: '1' }]
    const { wrapper, store } = await setup(() => result({ items: rows }), (_, args) => ({ kind: args.kind, deletedIds: args.ids, missingIds: [], failed: [{ id: '1', code: 'delete_unconfirmed' }] }))
    await button(wrapper, '全选已加载').trigger('click')
    expect(wrapper.text()).toContain('已选 100 条')
    await button(wrapper, '删除所选').trigger('click')
    await button(wrapper, '确认删除').trigger('click'); await flushPromises()
    const request = store.bridge.call.mock.calls[0][1]
    expect(request.ids).toHaveLength(100)
    expect(new Set(request.ids).size).toBe(100)
    expect(wrapper.text()).toContain('已删除 99 条，已不存在 0 条，失败 1 条')
    expect(wrapper.text()).toContain('已选 1 条')
  })
  it('桥异常保留单删目标选择，确认期间筛选不能变化', async () => {
    const { wrapper } = await setup(() => result({ items: [{ id: 1 }] }), () => { throw new Error('桥断线') })
    await button(wrapper, '删除').trigger('click')
    expect(button(wrapper, '警告').element.disabled).toBe(true)
    await button(wrapper, '确认删除').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('桥断线')
    expect(wrapper.text()).toContain('已选 1 条')
    expect(wrapper.find('.log-row').exists()).toBe(true)
  })
  it('HTTP 抓包事务不能单删，提供抓包页入口', async () => {
    const { wrapper, store } = await setup(() => result({ items: [{ id: 7, contextId: 'cap:12345678-1234-1234-1234-123456789abc' }] }))
    await button(wrapper, 'HTTP').trigger('click'); await flushPromises()
    expect(button(wrapper, '删除').attributes('disabled')).toBeDefined()
    await button(wrapper, '前往抓包页').trigger('click'); await flushPromises()
    expect(store.callTool).toHaveBeenLastCalledWith('list_captures', { limit: 20, offset: 0 })
    expect(store.bridge.call).not.toHaveBeenCalled()
  })
  it('无效或不精确 ID 不发送，非 Android 明确不支持', async () => {
    const { wrapper, store } = await setup(() => result({ items: [{ id: '01' }, { id: 9223372036854775807 }] }))
    expect(wrapper.findAll('button').filter(b => b.text() === '删除').every(b => b.attributes('disabled') !== undefined)).toBe(true)
    await button(wrapper, '全选已加载').trigger('click')
    expect(wrapper.text()).toContain('已选 0 条')
    expect(store.bridge.call).not.toHaveBeenCalled()
    const browser = await setup(() => result({ items: [{ id: 1 }] }), undefined, 'browser')
    expect(browser.wrapper.text()).toContain('不支持删除日志')
    expect(button(browser.wrapper, '删除')).toBeUndefined()
  })
  it.each([
    ['HTTP', 'http', { id: '12' }, '12'],
    ['抓包', 'capture', { contextId: 'cap:12345678-1234-1234-1234-123456789abc' }, 'cap:12345678-1234-1234-1234-123456789abc'],
    ['崩溃', 'crash', { name: 'crash-123.txt' }, 'crash-123.txt'],
    ['诊断', 'snapshot', { id: '12345678-1234-1234-1234-123456789abc' }, '12345678-1234-1234-1234-123456789abc']
  ])('%s 遵循真实 kind/ids，宿主拒绝后保留记录和选择', async (label, kind, row, id) => {
    const { wrapper, store } = await setup(() => result({ items: [row] }), () => ({ kind, deletedIds: [], missingIds: [], failed: [{ id, code: kind === 'capture' ? 'active_capture' : 'delete_failed' }] }))
    await button(wrapper, label).trigger('click'); await flushPromises()
    await button(wrapper, '删除').trigger('click')
    await button(wrapper, '确认删除').trigger('click'); await flushPromises()
    expect(store.bridge.call).toHaveBeenCalledWith('logs.delete', { kind, ids: [id] })
    expect(wrapper.text()).toContain('已删除 0 条')
    expect(wrapper.text()).toContain('已选 1 条')
    expect(wrapper.text()).toContain(kind === 'capture' ? 'active_capture' : 'delete_failed')
    expect(wrapper.find('.log-row').exists()).toBe(true)
  })
  it('批删仅选当前加载项，失败保留选择且刷新错误持久可见，请求期间锁定', async () => {
    let release
    let reads = 0
    const { wrapper, store } = await setup(() => {
      if (++reads > 1) throw new Error('刷新断线')
      return result({ items: [{ id: 1 }, { id: 2 }, { id: 3 }], total: 99 })
    }, () => new Promise(resolve => { release = resolve }))
    await button(wrapper, '全选已加载').trigger('click')
    expect(wrapper.text()).toContain('已选 3 条')
    await button(wrapper, '取消选择').trigger('click')
    expect(wrapper.text()).toContain('已选 0 条')
    await button(wrapper, '全选已加载').trigger('click')
    await button(wrapper, '删除所选').trigger('click')
    await button(wrapper, '确认删除').trigger('click')
    await button(wrapper, 'HTTP').trigger('click')
    await button(wrapper, '刷新').trigger('click')
    expect(store.callTool).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.handleBack()).toBe(true)
    release({ kind: 'operation', deletedIds: ['1'], missingIds: ['3'], failed: [{ id: '2', code: 'delete_unconfirmed' }] })
    await flushPromises()
    expect(store.bridge.call).toHaveBeenCalledWith('logs.delete', { kind: 'operation', ids: ['1', '2', '3'] })
    expect(wrapper.text()).toContain('已选 1 条')
    expect(wrapper.text()).toContain('已删除 1 条，已不存在 1 条，失败 1 条')
    expect(wrapper.text()).toContain('delete_unconfirmed')
    expect(wrapper.text()).toContain('刷新断线')
    expect(wrapper.findAll('.log-row')).toHaveLength(1)
    expect(wrapper.find('input[type="checkbox"]').element.checked).toBe(true)
  })
  it('单删需 Vue 确认，精确字符串 ID 走本地桥并刷新', async () => {
    let rows = [{ id: '9223372036854775807', message: '精确记录' }]
    const { wrapper, store } = await setup(() => result({ items: rows }), (method, args) => {
      rows = []
      return { kind: args.kind, deletedIds: args.ids, missingIds: [], failed: [] }
    })
    await button(wrapper, '删除').trigger('click')
    expect(dialog().text()).toContain('1 条')
    expect(store.bridge.call).not.toHaveBeenCalled()
    await button(wrapper, '取消').trigger('click')
    expect(store.bridge.call).not.toHaveBeenCalled()
    await button(wrapper, '删除').trigger('click')
    await button(wrapper, '确认删除').trigger('click')
    await flushPromises()
    expect(store.bridge.call).toHaveBeenCalledTimes(1)
    expect(store.bridge.call).toHaveBeenCalledWith('logs.delete', { kind: 'operation', ids: ['9223372036854775807'] })
    expect(wrapper.find('.log-row').exists()).toBe(false)
    expect(wrapper.text()).toContain('已删除 1 条')
  })
  it('Vue 确认框脱离主滚动区，焦点默认取消，Escape 和系统返回不删除', async () => {
    const { wrapper, store, host } = await setup(() => result({ items: [{ id: 1 }] }))
    const opener = button(wrapper, '删除')
    opener.element.focus()
    await opener.trigger('click'); await flushPromises()
    expect(host.contains(dialog().element)).toBe(false)
    expect(document.body.contains(dialog().element)).toBe(true)
    expect(dialog().attributes('aria-modal')).toBe('true')
    expect(document.activeElement).toBe(button(wrapper, '取消').element)
    await button(wrapper, '取消').trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(button(wrapper, '确认删除').element)
    await button(wrapper, '确认删除').trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(button(wrapper, '取消').element)
    await button(wrapper, '取消').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(button(wrapper, '确认删除').element)
    await button(wrapper, '取消').trigger('keydown', { key: 'Escape' }); await flushPromises()
    expect(document.querySelector('.delete-overlay')).toBeNull()
    expect(document.activeElement).toBe(opener.element)
    expect(store.bridge.call).not.toHaveBeenCalled()
    await opener.trigger('click')
    expect(wrapper.vm.handleBack()).toBe(true)
    await flushPromises()
    expect(document.querySelector('.delete-overlay')).toBeNull()
    expect(store.bridge.call).not.toHaveBeenCalled()
  })
  it('删除期间锁定确认、取消、Escape、系统返回及重复提交；失败项仍可重试', async () => {
    let release
    const { wrapper, store } = await setup(() => result({ items: [{ id: 1 }] }), () => new Promise(resolve => { release = resolve }))
    await button(wrapper, '删除').trigger('click')
    await button(wrapper, '确认删除').trigger('click'); await flushPromises()
    expect(dialog().attributes('aria-busy')).toBe('true')
    expect(button(wrapper, '取消').element.disabled).toBe(true)
    expect(button(wrapper, '正在删除').element.disabled).toBe(true)
    await button(wrapper, '正在删除').trigger('click')
    await dialog().trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(dialog().element)
    await dialog().trigger('keydown', { key: 'Escape' })
    expect(wrapper.vm.handleBack()).toBe(true)
    await flushPromises()
    expect(dialog().exists()).toBe(true)
    expect(store.bridge.call).toHaveBeenCalledTimes(1)
    release({ kind: 'operation', deletedIds: [], missingIds: [], failed: [{ id: '1', code: 'delete_failed' }] })
    await flushPromises()
    expect(document.querySelector('.delete-overlay')).toBeNull()
    expect(wrapper.text()).toContain('已选 1 条')
    expect(wrapper.text()).toContain('delete_failed')
    await button(wrapper, '删除所选').trigger('click')
    expect(dialog().text()).toContain('1 条')
    expect(button(wrapper, '确认删除').element.disabled).toBe(false)
  })
  it('确认框卸载恢复滚动和背景 inert，不遗留遮罩', async () => {
    const { wrapper, host } = await setup(() => result({ items: [{ id: 1 }] }))
    const shell = document.createElement('div')
    shell.className = 'app-shell'
    host.replaceWith(shell)
    shell.appendChild(host)
    mounted.push({ unmount: () => shell.remove() })
    host.style.overflowY = 'auto'
    await button(wrapper, '删除').trigger('click')
    expect(host.style.overflowY).toBe('hidden')
    expect(shell.hasAttribute('inert')).toBe(true)
    wrapper.unmount()
    expect(host.style.overflowY).toBe('auto')
    expect(shell.hasAttribute('inert')).toBe(false)
    expect(document.querySelector('.delete-overlay')).toBeNull()
  })
  it('操作日志不发送未支持的 offset，也不显示伪下一页', async () => {
    const { wrapper, store } = await setup(() => result({ items: Array.from({ length: 20 }, (_, i) => ({ id: i + 1 })) }))
    expect(store.callTool).toHaveBeenLastCalledWith('get_logs', { limit: 20 })
    expect(button(wrapper, '下一页')).toBeUndefined()
  })
  it('全部筛选采用 Vue 控件，日期上一天、今天、下一天显示本地年月日', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2024, 2, 1, 0, 15))
    const { wrapper, store } = await setup(async () => result({ items: [] }))
    expect(wrapper.find('select').exists()).toBe(false)
    await button(wrapper, '警告').trigger('click')
    await flushPromises()
    expect(store.callTool).toHaveBeenLastCalledWith('get_logs', expect.objectContaining({ level: 'W' }))
    await button(wrapper, 'HTTP').trigger('click')
    await flushPromises()
    expect(wrapper.find('input[type="date"]').exists()).toBe(false)
    expect(wrapper.find('input[aria-label="日期"]').exists()).toBe(false)
    expect(button(wrapper, '上一天')).toBeDefined()
    expect(button(wrapper, '今天')).toBeDefined()
    expect(button(wrapper, '下一天')).toBeDefined()
    expect(wrapper.get('[aria-label="日志日期"]').text()).toContain('2024-03-01')
    expect(store.callTool).toHaveBeenLastCalledWith('get_http_logs', { limit: 20, offset: 0, date: '2024-03-01' })
    await button(wrapper, '上一天').trigger('click')
    await flushPromises()
    expect(store.callTool).toHaveBeenLastCalledWith('get_http_logs', expect.objectContaining({ date: '2024-02-29' }))
    expect(wrapper.get('[aria-label="日志日期"]').text()).toContain('2024-02-29')
    await button(wrapper, '下一天').trigger('click'); await flushPromises()
    expect(store.callTool).toHaveBeenLastCalledWith('get_http_logs', expect.objectContaining({ date: '2024-03-01' }))
    await button(wrapper, '下一天').trigger('click'); await flushPromises()
    expect(store.callTool).toHaveBeenLastCalledWith('get_http_logs', expect.objectContaining({ date: '2024-03-02' }))
    await button(wrapper, '今天').trigger('click'); await flushPromises()
    expect(store.callTool).toHaveBeenLastCalledWith('get_http_logs', expect.objectContaining({ date: '2024-03-01' }))
  })
  it('日期切换重置分页、保留查询与抓包条件，全部日期和快速重复点击安全', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 0, 1, 0, 15))
    let release
    let hold = false
    const response = () => result({ items: Array.from({ length: 20 }, (_, i) => ({ id: i + 1 })), total: 99 })
    const { wrapper, store } = await setup(() => hold ? new Promise(resolve => { release = resolve }) : response())
    await button(wrapper, 'HTTP').trigger('click'); await flushPromises()
    await wrapper.get('input[type="search"]').setValue('  example.test  ')
    await button(wrapper, '排除抓包事务').trigger('click'); await flushPromises()
    await button(wrapper, '下一页').trigger('click'); await flushPromises()
    expect(store.callTool).toHaveBeenLastCalledWith('get_http_logs', { limit: 20, offset: 20, date: '2026-01-01', query: 'example.test', includeCapture: true })
    hold = true
    const count = store.callTool.mock.calls.length
    const prev = button(wrapper, '上一天').element
    prev.click(); prev.click(); prev.click()
    await flushPromises()
    expect(store.callTool).toHaveBeenCalledTimes(count + 1)
    expect(store.callTool).toHaveBeenLastCalledWith('get_http_logs', { limit: 20, offset: 0, date: '2025-12-31', query: 'example.test', includeCapture: true })
    expect(wrapper.get('[aria-label="日志日期"]').text()).toContain('2025-12-31')
    for (const label of ['上一天', '今天', '下一天', '全部日期']) expect(button(wrapper, label)?.element.disabled).toBe(true)
    hold = false
    release(response()); await flushPromises()
    await button(wrapper, '全部日期').trigger('click'); await flushPromises()
    expect(store.callTool).toHaveBeenLastCalledWith('get_http_logs', { limit: 20, offset: 0, query: 'example.test', includeCapture: true })
    expect(wrapper.get('[aria-label="日志日期"]').text()).toContain('全部日期')
    await button(wrapper, '上一天').trigger('click'); await flushPromises()
    expect(store.callTool).toHaveBeenLastCalledWith('get_http_logs', expect.objectContaining({ date: '2025-12-31' }))
    vi.setSystemTime(new Date(2026, 0, 2, 0, 15))
    await button(wrapper, '今天').trigger('click'); await flushPromises()
    expect(store.callTool).toHaveBeenLastCalledWith('get_http_logs', expect.objectContaining({ date: '2026-01-02' }))
  })
  it('详情加载期间可系统返回，迟到结果不能重开详情', async () => {
    let release
    const { wrapper, host } = await setup(name => name === 'get_log'
      ? new Promise(resolve => { release = resolve }) : result({ items: [{ id: 1 }] }))
    host.scrollTop = 123
    await wrapper.find('.log-row').trigger('click')
    expect(wrapper.text()).toContain('正在读取详情')
    expect(wrapper.vm.handleBack()).toBe(true)
    await flushPromises()
    release(result({ body: 'late' }))
    await flushPromises()
    expect(wrapper.find('[data-testid="log-detail"]').exists()).toBe(false)
    expect(host.scrollTop).toBe(123)
  })
  it.each(cases)('%s 有明确详情入口，打开独立视图并返回原位置', async (label, list, detail, row, args) => {
    const { wrapper, store, host } = await setup(async name => result(name === detail ? { body: '真实正文', token: 'private' } : { items: [row], total: 1 }))
    if (label !== '操作') { await button(wrapper, label).trigger('click'); await flushPromises() }
    host.scrollTop = 480
    expect(wrapper.find('.log-row').text()).toContain('查看详情')
    await wrapper.find('.log-row').trigger('click')
    await flushPromises()
    expect(store.callTool).toHaveBeenLastCalledWith(detail, args)
    expect(wrapper.find('.log-list').isVisible()).toBe(false)
    expect(wrapper.find('[data-testid="log-detail"]').isVisible()).toBe(true)
    expect(wrapper.find('pre').text()).toContain('真实正文')
    expect(wrapper.find('pre').text()).not.toContain('private')
    expect(host.scrollTop).toBe(0)
    await button(wrapper, '返回日志列表').trigger('click')
    await flushPromises()
    expect(wrapper.find('.log-list').isVisible()).toBe(true)
    expect(host.scrollTop).toBe(480)
    expect(store.callTool.mock.calls.filter(([name]) => name === list)).toHaveLength(1)
    expect(wrapper.vm.handleBack()).toBe(false)
  })
})
