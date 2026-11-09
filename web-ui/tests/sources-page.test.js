import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
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
  const wrapper = mount(SourcesPage, { props: { store } })
  await flushPromises()
  return { wrapper, store, bridge }
}

describe('SourcesPage', () => {
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
