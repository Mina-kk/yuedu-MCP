import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import VerifyPage from '../src/pages/VerifyPage.vue'

const row = (id, status = 'WAITING') => ({ id, domain: `${id}.example`, url: `https://${id}.example`, status, kind: 'webview', canOpen: true, finalUrl: '', answer: '', addressIssue: '' })
const button = (wrapper, text) => wrapper.findAll('button').find(x => x.text() === text)
const mounted = []
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
let visibility = 'visible'
beforeEach(() => {
  vi.useFakeTimers()
  visibility = 'visible'
  vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
})
afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  vi.useRealTimers()
  vi.restoreAllMocks()
})
async function setup(initial = [row('first')], handlers = {}, state = {}) {
  let records = initial
  const store = {
    state: reactive({ bridgeKind: 'android', verificationBrowser: { open: false, action: '' }, ...state }),
    bridge: { call: vi.fn(async (method, params) => {
      if (handlers[method]) return handlers[method](params)
      if (method === 'verification.list') return { sessions: records }
      throw new Error(`unexpected ${method}`)
    }) }, confirmDialog: vi.fn(), toast: vi.fn()
  }
  const wrapper = mount(VerifyPage, { props: { store } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, store, setRows: rows => { records = rows }, reads: () => store.bridge.call.mock.calls.filter(([m]) => m === 'verification.list').length }
}
async function tick(ms = 2000) { await vi.advanceTimersByTimeAsync(ms); await flushPromises() }
async function visible(value) { visibility = value; document.dispatchEvent(new Event('visibilitychange')); await flushPromises() }

it('驻留前台自动显示第二个会话及同ID重置，不自动打开或完成', async () => {
  const { wrapper, store, setRows, reads } = await setup([row('first', 'COMPLETED')])
  setRows([row('first'), row('second')])
  await tick(1999)
  expect(reads()).toBe(1)
  await tick(1)
  expect(wrapper.findAll('.session')).toHaveLength(2)
  expect(wrapper.get('.session').text()).toContain('待处理')
  expect(store.bridge.call.mock.calls.every(([method]) => method === 'verification.list')).toBe(true)
  expect(store.toast).not.toHaveBeenCalled()
})

it('文档隐藏及隔离浏览器打开时暂停，恢复可见立即读取，卸载清理并忽略迟到快照', async () => {
  const pending = deferred()
  let slow = false
  const { wrapper, store, reads } = await setup(undefined, { 'verification.list': () => slow ? pending.promise : { sessions: [row('first')] } })
  await visible('hidden')
  await tick(10000)
  expect(reads()).toBe(1)
  await visible('visible')
  expect(reads()).toBe(2)
  store.state.verificationBrowser = { open: true, action: 'open' }
  await flushPromises()
  await tick(10000)
  expect(reads()).toBe(2)
  store.state.verificationBrowser = { open: false, action: 'closed' }
  await flushPromises()
  expect(reads()).toBe(3)
  slow = true
  await tick()
  expect(reads()).toBe(4)
  const state = wrapper.vm.$.setupState
  wrapper.unmount()
  mounted.splice(mounted.indexOf(wrapper), 1)
  pending.resolve({ sessions: [row('late')] })
  await flushPromises()
  expect(state.sessions.map(x => x.id)).toEqual(['first'])
  await tick(10000)
  expect(reads()).toBe(4)
  expect(vi.getTimerCount()).toBe(0)
})

it('慢请求单飞，手动及完成事件合并为一次后续新读，不展示旧快照', async () => {
  const pending = deferred()
  let calls = 0
  const { wrapper, store, reads } = await setup(undefined, { 'verification.list': () => {
    calls++
    if (calls === 2) return pending.promise
    return { sessions: [row('first', calls > 2 ? 'COMPLETED' : 'WAITING')] }
  } })
  await tick()
  expect(wrapper.text()).not.toContain('正在读取验证会话')
  await button(wrapper, '刷新').trigger('click')
  store.state.verificationBrowser = { open: false, action: 'completed' }
  await flushPromises()
  await tick(10000)
  expect(reads()).toBe(2)
  pending.resolve({ sessions: [row('old')] })
  await flushPromises()
  expect(reads()).toBe(3)
  expect(wrapper.text()).toContain('已完成')
  expect(wrapper.text()).not.toContain('old.example')
  await tick(1999)
  expect(reads()).toBe(3)
  await tick(1)
  expect(reads()).toBe(4)
})

it('静默刷新失败保留列表、草稿及未确认选择，标为过期且可恢复，不连续toast', async () => {
  const image = { ...row('image'), kind: 'image_code' }
  const broken = { ...row('broken'), url: '', canOpen: false }
  let fail = false
  let records = [image, broken]
  const { wrapper, store } = await setup(records, { 'verification.list': () => {
    if (fail) throw new Error('桥暂时不可用')
    return { sessions: records }
  } })
  await wrapper.get('input[placeholder="输入图片里的验证码"]').setValue('draft-answer')
  await wrapper.get('input[aria-label="验证地址"]').setValue('https://draft.example')
  await button(wrapper, '全选当前记录').trigger('click')
  fail = true
  await tick(6000)
  expect(wrapper.findAll('.session')).toHaveLength(2)
  expect(wrapper.text()).toContain('列表可能已过期')
  expect(wrapper.text()).toContain('桥暂时不可用')
  expect(wrapper.text()).toContain('已选 2 条')
  expect(wrapper.get('input[placeholder="输入图片里的验证码"]').element.value).toBe('draft-answer')
  expect(wrapper.get('input[aria-label="验证地址"]').element.value).toBe('https://draft.example')
  expect(store.toast).not.toHaveBeenCalled()
  fail = false
  records = [image, broken, row('second')]
  await tick()
  expect(wrapper.findAll('.session')).toHaveLength(3)
  expect(wrapper.text()).not.toContain('列表可能已过期')
  expect(wrapper.text()).toContain('已选 2 条')
  records = [image]
  await tick()
  expect(wrapper.text()).toContain('已选 2 条')
})

it.each(['完成', '删除'])('后台旧快照不能覆盖%s，写操作期间不轮询且回读单飞', async action => {
  const stale = deferred(), mutation = deferred()
  let calls = 0, records = [row('first')]
  const { wrapper, store, reads } = await setup(records, {
    'verification.list': () => ++calls === 2 ? stale.promise : { sessions: records },
    'verification.complete': async () => { await mutation.promise; records = [row('first', 'COMPLETED')]; return { completed: true } },
    'verification.close': async () => { await mutation.promise; records = []; return { closed: true } }
  })
  await button(wrapper, '全选当前记录').trigger('click')
  await tick()
  if (action === '完成') await button(wrapper, '标记完成').trigger('click')
  else {
    await button(wrapper, '批量删除').trigger('click')
    store.confirmDialog.mock.calls[0][0].onAccept()
  }
  await flushPromises()
  expect(store.bridge.call).toHaveBeenCalledWith(action === '完成' ? 'verification.complete' : 'verification.close', { id: 'first' })
  await tick(10000)
  expect(reads()).toBe(2)
  mutation.resolve()
  await flushPromises()
  expect(reads()).toBe(2)
  stale.resolve({ sessions: [row('first')] })
  await flushPromises()
  expect(reads()).toBe(3)
  if (action === '完成') expect(wrapper.get('.session').text()).toContain('已完成')
  else expect(wrapper.findAll('.session')).toHaveLength(0)
  await tick()
  expect(reads()).toBe(4)
})

it('close之后重复closed事件不停止驻留刷新', async () => {
  const { store, reads, wrapper, setRows } = await setup()
  store.state.verificationBrowser = { open: true, action: 'open' }
  await flushPromises()
  store.state.verificationBrowser = { open: false, action: 'close' }
  await flushPromises()
  expect(reads()).toBe(2)
  store.state.verificationBrowser = { open: false, action: 'closed' }
  await flushPromises()
  setRows([row('second')])
  await tick()
  expect(reads()).toBe(3)
  expect(wrapper.text()).toContain('second.example')
})

it.each(['hidden', 'browser'])('初始%s不读取，恢复后读取；格式错误保留已有结果', async blocked => {
  if (blocked === 'hidden') visibility = 'hidden'
  let malformed = false
  const { wrapper, store, reads } = await setup(undefined, { 'verification.list': () => malformed ? {} : { sessions: [row('first')] } }, blocked === 'browser' ? { verificationBrowser: { open: true, action: 'open' } } : {})
  expect(reads()).toBe(0)
  if (blocked === 'hidden') {
    await visible('visible')
  } else {
    await tick(10000)
    expect(reads()).toBe(0)
    store.state.verificationBrowser = { open: false, action: 'closed' }
    await flushPromises()
  }
  malformed = true
  await tick()
  expect(wrapper.get('.session').text()).toContain('first.example')
  expect(wrapper.text()).toContain('列表可能已过期')
  expect(wrapper.text()).toContain('会话列表格式无效')
})

it.each(['修复', '清Cookie'])('%s回读等待旧轮询，旧快照不能覆盖已确认的结果', async action => {
  const oldRow = { ...row('first', 'COMPLETED'), ...(action === '修复' ? { url: '', canOpen: false } : { finalUrl: 'https://first.example/done' }) }
  const newRow = row('first')
  let calls = 0
  const stale = deferred()
  const { wrapper, store, reads } = await setup([oldRow], {
    'verification.list': () => ++calls === 2 ? stale.promise : { sessions: [calls === 1 ? oldRow : newRow] },
    'verification.repairAddress': () => ({ repaired: true, session: newRow }),
    'verification.clear': () => ({ cleared: true })
  })
  if (action === '修复') await wrapper.get('input[aria-label="验证地址"]').setValue(newRow.url)
  await tick()
  await button(wrapper, action === '修复' ? '补全地址' : '清 Cookie').trigger('click')
  const running = store.confirmDialog.mock.calls[0][0].onAccept()
  await flushPromises()
  expect(reads()).toBe(2)
  stale.resolve({ sessions: [oldRow] })
  await running
  await flushPromises()
  expect(reads()).toBe(3)
  expect(wrapper.get('.session').text()).toContain('待处理')
  expect(wrapper.get('.session').text()).not.toContain('已完成')
  expect(store.toast).toHaveBeenCalledTimes(1)
})

it('手动刷新合并到在途轮询仍保留手动清理失效选择的语义', async () => {
  const stale = deferred()
  let calls = 0
  const { wrapper, reads } = await setup(undefined, { 'verification.list': () => ++calls === 2 ? stale.promise : { sessions: calls > 2 ? [] : [row('first')] } })
  await button(wrapper, '全选当前记录').trigger('click')
  await tick()
  await button(wrapper, '刷新').trigger('click')
  expect(reads()).toBe(2)
  stale.resolve({ sessions: [row('first')] })
  await flushPromises()
  expect(reads()).toBe(3)
  expect(wrapper.text()).toContain('已选 0 条')
})

it('非Android宿主不启动轮询或调用验证桥', async () => {
  const { wrapper, store } = await setup(undefined, {}, { bridgeKind: 'web' })
  await tick(10000)
  await visible('hidden')
  await visible('visible')
  expect(store.bridge.call).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('验证中心需要 Android 宿主')
  expect(vi.getTimerCount()).toBe(0)
})

it('写操作尚未结束时收到旧轮询响应也不得改动可见列表', async () => {
  const stale = deferred(), mutation = deferred()
  let calls = 0
  const { wrapper, store, reads } = await setup(undefined, {
    'verification.list': () => ++calls === 2 ? stale.promise : { sessions: [row('first', calls > 2 ? 'COMPLETED' : 'WAITING')] },
    'verification.complete': () => mutation.promise
  })
  await tick()
  await button(wrapper, '标记完成').trigger('click')
  stale.resolve({ sessions: [row('obsolete')] })
  await flushPromises()
  expect(wrapper.get('.session').text()).toContain('first.example')
  expect(wrapper.text()).not.toContain('obsolete.example')
  await tick(10000)
  expect(reads()).toBe(2)
  mutation.resolve({ completed: true })
  await flushPromises()
  expect(reads()).toBe(3)
  expect(wrapper.get('.session').text()).toContain('已完成')
  expect(store.toast).toHaveBeenCalledTimes(1)
})

it.each(['closed', 'completed'])('open回读挂起时外部%s立即失效旧WAITING并排队新读，不等待轮询', async action => {
  const stale = deferred(), fresh = deferred()
  let calls = 0
  const { wrapper, store, reads } = await setup(undefined, {
    'verification.list': () => {
      calls++
      if (calls === 2) return stale.promise
      if (calls === 3) return fresh.promise
      return { sessions: [row('first')] }
    },
    'verification.open': () => {
      store.state.verificationBrowser = { open: true, action: 'open', sessionId: 'first' }
      return { opened: true }
    }
  })
  await button(wrapper, '打开验证页').trigger('click')
  await flushPromises()
  expect(reads()).toBe(2)
  store.state.verificationBrowser = { open: false, action, sessionId: 'first' }
  await flushPromises()
  expect(reads()).toBe(2) // mutation read is still the only in-flight list
  stale.resolve({ sessions: [{ ...row('first'), purpose: 'obsolete WAITING snapshot' }] })
  await flushPromises()
  expect(wrapper.text()).not.toContain('obsolete WAITING snapshot')
  expect(reads()).toBe(3) // no timer advance: queued event must run immediately
  fresh.resolve({ sessions: [row('first', 'COMPLETED')] })
  await flushPromises()
  expect(wrapper.get('.session').text()).toContain('已完成')
  expect(wrapper.get('.session').text()).not.toContain('待处理')
  await tick(1999)
  expect(reads()).toBe(3)
})

it('卸载时的初始请求失败也不写错误、重启timer或发送toast', async () => {
  let reject
  const pending = new Promise((_, fail) => { reject = fail })
  const { wrapper, store } = await setup(undefined, { 'verification.list': () => pending })
  const state = wrapper.vm.$.setupState
  wrapper.unmount()
  mounted.splice(mounted.indexOf(wrapper), 1)
  reject(new Error('迟到失败'))
  await flushPromises()
  await tick(10000)
  expect(state.listError).toBe('')
  expect(store.bridge.call).toHaveBeenCalledTimes(1)
  expect(store.toast).not.toHaveBeenCalled()
  expect(vi.getTimerCount()).toBe(0)
})
