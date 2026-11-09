import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createStore } from '../src/store.js'
import SkillsPage from '../src/pages/SkillsPage.vue'

// 技能页：内置只读、自定义可 save/delete（删除前确认），列表来自 list_skills。

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

const SKILLS = [
  { id: 'builtin-one', name: '内置技能', builtIn: true, enabled: true },
  { id: 'custom-one', name: '自定义技能', builtIn: false, enabled: true }
]

async function mountPage(handlers) {
  const bridge = fakeBridge({
    'app.bootstrap': async () => ({ service: {}, capabilities: {} }),
    'list_skills': async () => ({ content: [{ type: 'text', text: JSON.stringify(SKILLS) }] }),
    ...handlers
  })
  const store = createStore({ bridge, storage: null })
  await store.bootstrap()
  const wrapper = mount(SkillsPage, { props: { store } })
  await flushPromises()
  return { wrapper, store, bridge }
}

describe('SkillsPage', () => {
  it('默认显示内置技能，切 tab 显示自定义', async () => {
    const { wrapper } = await mountPage({})
    expect(wrapper.text()).toContain('内置技能')
    expect(wrapper.text()).not.toContain('自定义技能')
    const customTab = wrapper.findAll('button').find((b) => b.text() === '自定义')
    await customTab.trigger('click')
    expect(wrapper.text()).toContain('自定义技能')
  })

  it('内置技能只读，不提供保存', async () => {
    const { wrapper } = await mountPage({
      'get_skill': async () => ({ content: [{ type: 'text', text: '# 内置' }] })
    })
    await wrapper.find('.skill-row').trigger('click')
    await flushPromises()
    expect(wrapper.find('textarea').attributes('readonly')).toBeDefined()
    expect(wrapper.findAll('button').some((b) => b.text().includes('保存'))).toBe(false)
  })

  it('自定义技能保存调用 save_skill 并回读', async () => {
    const { wrapper, bridge } = await mountPage({
      'get_skill': async () => ({ content: [{ type: 'text', text: '# 自定义' }] }),
      'save_skill': async () => ({ content: [{ type: 'text', text: 'Skill 已保存' }] })
    })
    await wrapper.findAll('button').find((b) => b.text() === '自定义').trigger('click')
    await wrapper.find('.skill-row').trigger('click')
    await flushPromises()
    await wrapper.find('textarea').setValue('# 自定义 v2')
    await wrapper.findAll('button').find((b) => b.text().includes('保存')).trigger('click')
    await flushPromises()
    const save = bridge.calls.find(([m]) => m === 'save_skill')
    expect(save[1]).toEqual({ id: 'custom-one', markdown: '# 自定义 v2' })
    expect(bridge.calls.filter(([m]) => m === 'get_skill').length).toBeGreaterThanOrEqual(2)
  })

  it('确认放弃后可切换技能，不再次弹窗', async () => {
    const { wrapper, store } = await mountPage({
      get_skill: async () => ({ content: [{ type: 'text', text: '# 自定义' }] })
    })
    await wrapper.findAll('button').find(b => b.text() === '自定义').trigger('click')
    await wrapper.find('.skill-row').trigger('click')
    await flushPromises()
    await wrapper.find('textarea').setValue('# 修改')
    await wrapper.find('.skill-row').trigger('click')
    const accept = store.state.dialog.onAccept
    store.closeDialog()
    await accept()
    await flushPromises()
    expect(store.state.dialog).toBeNull()
  })

  it('删除自定义技能前必须确认', async () => {
    const { wrapper, store, bridge } = await mountPage({
      'delete_skill': async () => ({ content: [{ type: 'text', text: 'Skill 已删除' }] })
    })
    await wrapper.findAll('button').find((b) => b.text() === '自定义').trigger('click')
    const del = wrapper.find('.skill-row .icon-btn')
    await del.trigger('click')
    expect(store.state.dialog).toMatchObject({ kind: 'delete' })
    expect(bridge.calls.some(([m]) => m === 'delete_skill')).toBe(false)
  })

  it('列表失败如实显示错误', async () => {
    const { wrapper } = await mountPage({
      'list_skills': async () => ({ content: [{ type: 'text', text: 'skills dir unreadable' }], isError: true })
    })
    expect(wrapper.text()).toContain('skills dir unreadable')
    expect(wrapper.findAll('.skill-row')).toHaveLength(0)
  })
})
