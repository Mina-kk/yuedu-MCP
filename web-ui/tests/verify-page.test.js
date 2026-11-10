import { afterEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import VerifyPage from '../src/pages/VerifyPage.vue'

const row = (id, status = 'WAITING') => ({
  id, jobId: 'mcp', domain: `${id}.example`, status, url: `https://${id}.example`,
  purpose: '', kind: 'webview', imageData: '', finalUrl: '', answer: '',
  createdAt: 1, updatedAt: 1, canOpen: true, addressIssue: ''
})
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

describe('VerifyPage address eligibility', () => {
  it.each([
    ['缺少验证地址', { url: '', domain: '', canOpen: false, addressIssue: 'missing_url' }],
    ['验证地址不受支持', { url: 'http://127.0.0.1/private', canOpen: false, addressIssue: 'unsupported_url' }],
    ['验证地址不受支持', { canOpen: false, addressIssue: 'unsupported_url' }],
    ['缺少验证地址', { url: '  ', canOpen: undefined, addressIssue: undefined }]
  ])('%s的网页会话不能打开或完成，删除仍可用', async (message, fields) => {
    const { wrapper, store } = await setup([{ ...row('broken'), ...fields }])
    expect(wrapper.text()).toContain(message)
    expect(button(wrapper, '打开验证页').element.disabled).toBe(true)
    expect(button(wrapper, '标记完成').element.disabled).toBe(true)
    expect(button(wrapper, '删除记录').element.disabled).toBe(false)
    await button(wrapper, '打开验证页').trigger('click')
    await button(wrapper, '标记完成').trigger('click')
    expect(store.bridge.call.mock.calls).toEqual([['verification.list', {}]])
    store.state.verificationBrowser = { open: true, sessionId: 'broken' }
    await flushPromises()
    expect(wrapper.findAll('button').filter(b => b.text() === '标记完成').every(b => b.element.disabled)).toBe(true)
  })

  it('旧宿主没有可打开字段时只按原始 url 是否为空兜底，finalUrl/domain 不替代缺失地址', async () => {
    const legacy = { ...row('legacy'), canOpen: undefined, addressIssue: undefined }
    const { wrapper } = await setup([
      legacy,
      { ...legacy, id: 'missing', url: '', finalUrl: 'https://last.example', domain: 'last.example' }
    ])
    const cards = wrapper.findAll('.session')
    expect(button(cards[0], '打开验证页').element.disabled).toBe(false)
    expect(button(cards[0], '标记完成').element.disabled).toBe(false)
    expect(button(cards[1], '打开验证页').element.disabled).toBe(true)
    expect(cards[1].text()).toContain('缺少验证地址')
  })

  it('无网络地址的图片验证码仍可填写并完成，不允许打开外站', async () => {
    const image = { ...row('image'), kind: 'image_code', url: '', domain: '', canOpen: false, addressIssue: 'missing_url' }
    const { wrapper, store } = await setup([image], {
      'verification.complete': ({ answer }) => ({ ...image, answer, status: 'COMPLETED' })
    })
    expect(button(wrapper, '打开验证页').element.disabled).toBe(true)
    expect(button(wrapper, '标记完成').element.disabled).toBe(false)
    await wrapper.get('input[placeholder="输入图片里的验证码"]').setValue('  abc42  ')
    await button(wrapper, '标记完成').trigger('click')
    await flushPromises()
    expect(store.bridge.call).toHaveBeenCalledWith('verification.complete', { id: 'image', answer: 'abc42' })
  })
})

describe('VerifyPage address repair', () => {
  it.each(['WAITING', 'COMPLETED'])('%s历史网页会话经Vue确认补全，同ID回读才允许打开且不自动打开', async status => {
    const broken = { ...row('history', status), url: '', domain: '', canOpen: false, addressIssue: 'missing_url' }
    const repaired = { ...broken, url: 'https://verify.example/challenge', domain: 'verify.example', status: 'WAITING', canOpen: true, addressIssue: '', updatedAt: 2 }
    let records = [broken]
    const { wrapper, store, cookies } = await setup(records, {
      'verification.list': () => ({ sessions: [...records] }),
      'verification.repairAddress': () => { records = [repaired]; return { repaired: true, session: repaired } },
      'verification.open': () => ({ opened: true })
    })
    const url = wrapper.find('input[aria-label="验证地址"]')
    expect(url.exists()).toBe(true)
    expect(url.element.value).toBe('')
    expect(button(wrapper, '补全地址').element.disabled).toBe(true)
    await url.setValue(repaired.url)
    await button(wrapper, '补全地址').trigger('click')
    expect(store.bridge.call.mock.calls).toEqual([['verification.list', {}]])
    const dialog = store.confirmDialog.mock.calls[0][0]
    expect(dialog.message).toContain('Cookie 不受影响')
    expect(dialog.message).toContain('不会自动打开')
    if (status === 'COMPLETED') expect(dialog.message).toContain('已完成记录重置为待验证')
    await dialog.onAccept()
    await flushPromises()
    expect(store.bridge.call.mock.calls).toEqual([
      ['verification.list', {}],
      ['verification.repairAddress', { id: 'history', url: repaired.url }],
      ['verification.list', {}]
    ])
    expect(wrapper.findAll('.session')).toHaveLength(1)
    expect(wrapper.text()).toContain(repaired.url)
    expect(button(wrapper, '打开验证页').element.disabled).toBe(false)
    expect(button(wrapper, '标记完成').element.disabled).toBe(false)
    expect(wrapper.find('input[aria-label="验证地址"]').exists()).toBe(false)
    expect(cookies.get('')).toBe('saved-cookie')
    expect(store.toast).toHaveBeenCalledWith('验证地址已补全，请手动打开验证页')
    await button(wrapper, '打开验证页').trigger('click')
    await flushPromises()
    expect(store.bridge.call).toHaveBeenCalledWith('verification.open', { id: 'history' })
  })
  it.each([
    ['缺少 repaired', session => ({ session })],
    ['非布尔 repaired', session => ({ repaired: 'true', session })],
    ['缺少 session', () => ({ repaired: true })],
    ['错误 ID', session => ({ repaired: true, session: { ...session, id: 'other' } })],
    ['错误 URL', session => ({ repaired: true, session: { ...session, url: 'https://other.example' } })],
    ['缺少 canOpen', session => ({ repaired: true, session: { ...session, canOpen: undefined } })],
    ['缺少状态', session => ({ repaired: true, session: { ...session, status: undefined } })],
    ['未重置完成状态', session => ({ repaired: true, session: { ...session, status: 'COMPLETED' } })],
    ['仍有旧观察地址', session => ({ repaired: true, session: { ...session, finalUrl: 'https://old.example/' } })],
    ['仍有旧答案', session => ({ repaired: true, session: { ...session, answer: 'old' } })],
    ['仍有地址问题', session => ({ repaired: true, session: { ...session, addressIssue: 'unsupported_url' } })]
  ])('补全回执%s时保留原记录和草稿，不误报或自动打开', async (_, receipt) => {
    const broken = { ...row('history', 'COMPLETED'), url: '', canOpen: false, addressIssue: 'missing_url' }
    const repaired = { ...row('history'), url: 'https://verify.example/challenge', updatedAt: 2 }
    let reads = 0
    const { wrapper, store } = await setup([broken], {
      'verification.list': () => ({ sessions: [++reads === 1 ? broken : repaired] }),
      'verification.repairAddress': () => receipt(repaired)
    })
    await wrapper.get('input[aria-label="验证地址"]').setValue(repaired.url)
    await button(wrapper, '补全地址').trigger('click')
    await store.confirmDialog.mock.calls[0][0].onAccept()
    await flushPromises()
    expect(wrapper.text()).toContain('地址补全回执无效')
    expect(button(wrapper, '打开验证页').element.disabled).toBe(true)
    expect(wrapper.get('input[aria-label="验证地址"]').element.value).toBe(repaired.url)
    expect(store.toast).not.toHaveBeenCalled()
    expect(store.bridge.call.mock.calls.map(([method]) => method)).toEqual([
      'verification.list', 'verification.repairAddress', 'verification.list'
    ])
  })
  it.each([
    ['缺少 sessions', () => ({})],
    ['回读抛错', () => { throw new Error('读取失败') }],
    ['记录消失', () => ({ sessions: [] })],
    ['另一条记录', session => ({ sessions: [{ ...session, id: 'other' }] })],
    ['URL 未修改', session => ({ sessions: [{ ...session, url: '' }] })],
    ['仍不可打开', session => ({ sessions: [{ ...session, canOpen: false }] })],
    ['状态仍已完成', session => ({ sessions: [{ ...session, status: 'COMPLETED' }] })],
    ['重复 ID', session => ({ sessions: [session, session] })],
    ['残留观察地址', session => ({ sessions: [{ ...session, finalUrl: 'https://old.example/' }] })],
    ['残留答案', session => ({ sessions: [{ ...session, answer: 'old' }] })],
    ['地址仍有问题', session => ({ sessions: [{ ...session, addressIssue: 'unsupported_url' }] })]
  ])('回读%s时补全回执不能代替持久化证据，保留草稿及选择', async (_, readback) => {
    const broken = { ...row('history', 'COMPLETED'), url: '', canOpen: false, addressIssue: 'missing_url' }
    const repaired = { ...row('history'), url: 'https://verify.example/challenge', updatedAt: 2 }
    let reads = 0
    const { wrapper, store } = await setup([broken], {
      'verification.list': () => ++reads === 1 ? { sessions: [broken] } : readback(repaired),
      'verification.repairAddress': () => ({ repaired: true, session: repaired })
    })
    await button(wrapper, '全选当前记录').trigger('click')
    await wrapper.get('input[aria-label="验证地址"]').setValue(repaired.url)
    await button(wrapper, '补全地址').trigger('click')
    await store.confirmDialog.mock.calls[0][0].onAccept()
    await flushPromises()
    expect(wrapper.text()).toContain('无法确认地址补全结果')
    expect(wrapper.text()).toContain('已选 1 条')
    expect(wrapper.text()).toContain('缺少验证地址')
    expect(button(wrapper, '打开验证页').element.disabled).toBe(true)
    expect(wrapper.get('input[aria-label="验证地址"]').element.value).toBe(repaired.url)
    expect(store.toast).not.toHaveBeenCalled()
  })

  it.each([
    ['等待且缺失', { status: 'WAITING' }, true],
    ['完成且缺失', { status: 'COMPLETED' }, true],
    ['等待且不支持', { url: 'file:///legacy.html', addressIssue: 'unsupported_url' }, true],
    ['旧宿主无字段', { canOpen: undefined, addressIssue: undefined }, true],
    ['已有可用地址', { url: 'https://ok.example', canOpen: true, addressIssue: '' }, false],
    ['图片验证码', { kind: 'image_code' }, false],
    ['已取消', { status: 'CANCELLED' }, false],
    ['未知状态', { status: 'PENDING' }, false]
  ])('%s按真实状态提供补全入口且不猜测地址', async (_, fields, eligible) => {
    const { wrapper } = await setup([{ ...row('history'), url: '', finalUrl: 'https://last.example', canOpen: false, addressIssue: 'missing_url', ...fields }])
    const input = wrapper.find('input[aria-label="验证地址"]')
    expect(input.exists()).toBe(eligible)
    if (eligible) expect(input.element.value).toBe('')
  })

  it.each(['not-a-url', 'http://127.0.0.1/private', 'javascript:alert(1)', ' https://verify.example '])('原样交宿主校验 %s，拒绝后保留输入且不绕过打开', async url => {
    const broken = { ...row('history'), url: '', canOpen: false, addressIssue: 'missing_url' }
    const { wrapper, store } = await setup([broken], {
      'verification.repairAddress': () => { throw Object.assign(new Error('验证地址不允许'), { code: 'bad_params' }) }
    })
    await wrapper.get('input[aria-label="验证地址"]').setValue(url)
    await button(wrapper, '补全地址').trigger('click')
    await store.confirmDialog.mock.calls[0][0].onAccept()
    await flushPromises()
    expect(store.bridge.call.mock.calls).toEqual([
      ['verification.list', {}], ['verification.repairAddress', { id: 'history', url }]
    ])
    expect(wrapper.text()).toContain('验证地址不允许')
    expect(wrapper.get('input[aria-label="验证地址"]').element.value).toBe(url)
    expect(button(wrapper, '打开验证页').element.disabled).toBe(true)
    expect(button(wrapper, '标记完成').element.disabled).toBe(true)
    expect(store.toast).not.toHaveBeenCalled()
  })

  it('取消补全不请求；确认冻结输入，保存与回读期间锁定重复提交和删除', async () => {
    const broken = { ...row('history'), url: '', canOpen: false, addressIssue: 'missing_url' }
    const repaired = { ...row('history'), url: 'https://verify.example/challenge', updatedAt: 2 }
    let resolveRepair, resolveReadback, reads = 0
    const repairPending = new Promise(resolve => { resolveRepair = resolve })
    const readbackPending = new Promise(resolve => { resolveReadback = resolve })
    const { wrapper, store } = await setup([broken], {
      'verification.list': () => ++reads === 1 ? { sessions: [broken] } : readbackPending,
      'verification.repairAddress': () => repairPending
    })
    await wrapper.get('input[aria-label="验证地址"]').setValue(repaired.url)
    await button(wrapper, '补全地址').trigger('click')
    expect(store.bridge.call.mock.calls).toEqual([['verification.list', {}]])
    // 不调用首个对话框的 onAccept 就是取消，第二次才确认。
    await button(wrapper, '补全地址').trigger('click')
    const accept = store.confirmDialog.mock.calls[1][0].onAccept
    await wrapper.get('input[aria-label="验证地址"]').setValue('https://different.example')
    const running = accept()
    await flushPromises()
    expect(wrapper.findAll('button, input').every(control => control.element.disabled)).toBe(true)
    await accept()
    expect(store.bridge.call).toHaveBeenCalledWith('verification.repairAddress', { id: 'history', url: repaired.url })
    resolveRepair({ repaired: true, session: repaired })
    await flushPromises()
    expect(wrapper.findAll('button, input').every(control => control.element.disabled)).toBe(true)
    expect(store.toast).not.toHaveBeenCalled()
    resolveReadback({ sessions: [repaired] })
    await running
    await flushPromises()
    await accept()
    expect(store.bridge.call.mock.calls.filter(([method]) => method === 'verification.repairAddress')).toHaveLength(1)
    expect(button(wrapper, '打开验证页').element.disabled).toBe(false)
  })
})

describe('VerifyPage record deletion', () => {
  it('缺地址的已完成历史记录仍可直接删除，不要求先补全、不清Cookie', async () => {
    const broken = { ...row('history', 'COMPLETED'), url: '', domain: '', canOpen: false, addressIssue: 'missing_url' }
    const { wrapper, store, cookies } = await setup([broken])
    await button(wrapper, '删除记录').trigger('click')
    await store.confirmDialog.mock.calls[0][0].onAccept()
    await flushPromises()
    expect(wrapper.findAll('.session')).toHaveLength(0)
    expect(cookies.get('')).toBe('saved-cookie')
    expect(store.bridge.call.mock.calls).toEqual([
      ['verification.list', {}], ['verification.close', { id: 'history' }], ['verification.list', {}]
    ])
    expect(store.toast).toHaveBeenCalledWith('已删除 1 条验证记录')
  })

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
