import { afterEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import VerifyPage from '../src/pages/VerifyPage.vue'

const row = (id, status = 'WAITING') => ({ id, domain: `${id}.example`, status, url: `https://${id}.example` })
const button = (wrapper, text) => wrapper.findAll('button').find(b => b.text() === text)
const mounted = []
afterEach(() => {
  const calls = mounted.flatMap(({ store }) => store.bridge.call.mock.calls)
  mounted.splice(0).forEach(({ wrapper }) => wrapper.unmount())
  expect(calls.filter(([method]) => method === 'verification.clear')).toEqual([])
})
async function setup(rows = [row('a')], overrides = {}) {
  let records = [...rows]
  const cookies = new Map(rows.map(r => [r.url, 'saved-cookie']))
  const handlers = {
    'verification.list': () => ({ sessions: [...records] }),
    // Match VerificationCoordinator: clear resets a session; close deletes it without clearing cookies.
    'verification.clear': ({ id }) => {
      const session = records.find(r => r.id === id)
      if (session) {
        cookies.delete(session.url)
        records = records.map(r => r.id === id ? { ...r, status: 'WAITING', finalUrl: '', updatedAt: Date.now() } : r)
      }
      return { cleared: true }
    },
    'verification.close': ({ id }) => { records = records.filter(r => r.id !== id); return { closed: true } },
    'verification.browser.close': () => ({ closed: true }),
    ...overrides
  }
  const store = {
    state: reactive({ bridgeKind: 'android', verificationBrowser: { open: false, sessionId: '' } }),
    bridge: { call: vi.fn(async (method, params) => {
      if (!handlers[method]) throw new Error(`unexpected method: ${method}`)
      return handlers[method](params)
    }) },
    confirmDialog: vi.fn(), toast: vi.fn()
  }
  const wrapper = mount(VerifyPage, { props: { store } })
  mounted.push({ wrapper, store })
  await flushPromises()
  return { wrapper, store, cookies }
}

describe('VerifyPage record deletion', () => {
  it('不提供重复的关闭会话删除入口，关闭验证页仅关闭浏览器并保留记录', async () => {
    const { wrapper, store, cookies } = await setup()
    expect(button(wrapper, '关闭')).toBeUndefined()
    expect(button(wrapper, '关闭会话')).toBeUndefined()
    store.state.verificationBrowser = { open: true, sessionId: 'a' }
    await flushPromises()
    await button(wrapper, '关闭验证页').trigger('click')
    await flushPromises()
    expect(store.bridge.call.mock.calls).toEqual([
      ['verification.list', {}], ['verification.browser.close', {}], ['verification.list', {}]
    ])
    expect(wrapper.findAll('.session')).toHaveLength(1)
    expect(cookies.get('https://a.example')).toBe('saved-cookie')
  })

  it('刷新后移除已不存在记录的选择，取消确认不删除', async () => {
    let records = [row('a'), row('b')]
    const { wrapper, store } = await setup(records, {
      'verification.list': () => ({ sessions: [...records] })
    })
    await button(wrapper, '全选当前记录').trigger('click')
    await button(wrapper, '批量删除').trigger('click')
    // 不执行 onAccept 即取消；未发生任何删除请求。
    records = [row('b')]
    await button(wrapper, '刷新').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已选 1 条')
    expect(store.bridge.call.mock.calls.every(([m]) => m === 'verification.list')).toBe(true)
  })

  it('删除打开中会话先关闭浏览器，执行中禁止重复提交与选择变化', async () => {
    let release
    const pending = new Promise(resolve => { release = resolve })
    let records = [row('a'), row('b')]
    const { wrapper, store } = await setup(records, {
      'verification.list': () => ({ sessions: [...records] }),
      'verification.close': async ({ id }) => { await pending; records = records.filter(r => r.id !== id); return { closed: true } }
    })
    store.state.verificationBrowser = { open: true, sessionId: 'a' }
    await button(wrapper, '全选当前记录').trigger('click')
    await button(wrapper, '批量删除').trigger('click')
    const accept = store.confirmDialog.mock.calls[0][0].onAccept
    const running = accept()
    await flushPromises()
    expect(store.bridge.call.mock.calls.slice(1)).toEqual([
      ['verification.browser.close', {}], ['verification.close', { id: 'a' }]
    ])
    expect(wrapper.findAll('button').every(b => b.element.disabled)).toBe(true)
    expect(wrapper.findAll('input[type="checkbox"]').every(c => c.element.disabled)).toBe(true)
    await accept()
    release()
    await running
    await accept()
    expect(store.bridge.call.mock.calls.filter(([m]) => m === 'verification.close')).toHaveLength(2)
  })

  it('已完成记录不再出现可点击的标记完成，包括打开中的会话', async () => {
    const { wrapper, store } = await setup([row('a', 'COMPLETED')])
    store.state.verificationBrowser = { open: true, sessionId: 'a' }
    await flushPromises()
    expect(wrapper.findAll('button').filter(b => b.text() === '标记完成' && !b.element.disabled)).toHaveLength(0)
  })

  it.each(['抛错', '缺失 sessions'])('回读%s不能误报成功，保留记录和选择', async kind => {
    let reads = 0
    const { wrapper, store } = await setup([row('a')], {
      'verification.list': () => {
        if (++reads === 1) return { sessions: [row('a')] }
        if (kind === '抛错') throw new Error('离线')
        return {}
      }
    })
    await button(wrapper, '全选当前记录').trigger('click')
    await button(wrapper, '批量删除').trigger('click')
    await store.confirmDialog.mock.calls[0][0].onAccept()
    await flushPromises()
    expect(store.toast).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('无法确认删除结果')
    expect(wrapper.text()).toContain('已选 1 条')
    expect(wrapper.findAll('.session')).toHaveLength(1)
  })

  it('关闭浏览器失败不得继续删除当前会话', async () => {
    const { wrapper, store } = await setup([row('a')], {
      'verification.browser.close': () => { throw new Error('关闭失败') }
    })
    store.state.verificationBrowser = { open: true, sessionId: 'a' }
    await button(wrapper, '删除记录').trigger('click')
    await store.confirmDialog.mock.calls[0][0].onAccept()
    await flushPromises()
    expect(store.bridge.call.mock.calls.some(([m]) => m === 'verification.close')).toBe(false)
    expect(wrapper.text()).toContain('关闭失败')
    expect(store.toast).not.toHaveBeenCalled()
  })

  it('全选已加载记录、取消选择、逐条批量删除，部分失败保留选择和原因', async () => {
    let records = [row('a'), row('b'), row('c')]
    const { wrapper, store } = await setup(records, {
      'verification.list': () => ({ sessions: [...records] }),
      'verification.close': ({ id }) => {
        if (id === 'b') throw new Error('存储不可用')
        if (id === 'a') records = records.filter(r => r.id !== id)
        return { closed: true }
      }
    })
    expect(wrapper.findAll('.session input[type="checkbox"]')).toHaveLength(3)
    await button(wrapper, '全选当前记录').trigger('click')
    expect(wrapper.text()).toContain('已选 3 条')
    await button(wrapper, '取消选择').trigger('click')
    expect(wrapper.text()).toContain('已选 0 条')
    await wrapper.find('.session input[type="checkbox"]').setValue(true)
    expect(wrapper.text()).toContain('已选 1 条')
    await button(wrapper, '全选当前记录').trigger('click')
    await button(wrapper, '批量删除').trigger('click')
    expect(store.bridge.call).toHaveBeenCalledTimes(1)
    const dialog = store.confirmDialog.mock.calls[0][0]
    expect(dialog.message).toContain('3')
    await dialog.onAccept()
    await flushPromises()
    expect(store.bridge.call.mock.calls.slice(1)).toEqual([
      ['verification.close', { id: 'a' }], ['verification.list', {}],
      ['verification.close', { id: 'b' }], ['verification.list', {}],
      ['verification.close', { id: 'c' }], ['verification.list', {}]
    ])
    expect(store.toast).toHaveBeenCalledWith('已删除 1 条验证记录')
    expect(wrapper.text()).toContain('已选 2 条')
    expect(wrapper.text()).toContain('b.example：存储不可用')
    expect(wrapper.text()).toContain('c.example：回读后记录仍存在')
    expect(wrapper.findAll('.session input[type="checkbox"]').every(c => c.element.checked)).toBe(true)
  })

  it('单条删除有明确按钮与确认，确认后 close 并 list 回读才报成功且保留 Cookie', async () => {
    const { wrapper, store, cookies } = await setup([row('a', 'COMPLETED')])
    const remove = button(wrapper, '删除记录')
    expect(remove).toBeTruthy()
    await remove.trigger('click')
    expect(store.bridge.call.mock.calls).toEqual([['verification.list', {}]])
    const dialog = store.confirmDialog.mock.calls[0][0]
    expect(dialog.danger).toBe(true)
    expect(dialog.message).toContain('Cookie 不受影响')
    await dialog.onAccept()
    await flushPromises()
    expect(store.bridge.call.mock.calls).toEqual([
      ['verification.list', {}], ['verification.close', { id: 'a' }], ['verification.list', {}]
    ])
    expect(wrapper.findAll('.session')).toHaveLength(0)
    expect(cookies.get('https://a.example')).toBe('saved-cookie')
    expect(store.toast).toHaveBeenCalledWith('已删除 1 条验证记录')
  })
})
