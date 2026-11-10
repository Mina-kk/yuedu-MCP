import { afterEach, describe, it, expect } from 'vitest'
import { enableAutoUnmount, mount, flushPromises } from '@vue/test-utils'
enableAutoUnmount(afterEach)
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
  const wrapper = mount(SkillsPage, { attachTo: document.body, props: { store } })
  await flushPromises()
  return { wrapper, store, bridge }
}

describe('SkillsPage', () => {
  it('独立详情仍提供可见的单条删除入口', async () => {
    const { wrapper, store } = await mountPage({ get_skill: async () => ({ content: [{ type: 'text', text: '# 内容' }] }) })
    await wrapper.findAll('button').find(b => b.text() === '自定义').trigger('click')
    await wrapper.find('.skill-row').trigger('click'); await flushPromises()
    const remove = wrapper.findAll('button').find(b => b.isVisible() && b.text() === '删除技能')
    expect(remove).toBeTruthy()
    await remove.trigger('click')
    expect(store.state.dialog.kind).toBe('delete')
  })

  it.each([true, false])('加载时可返回，迟到回包不重开详情；失败可见=%s', async (fail) => {
    let finish
    const { wrapper } = await mountPage({ get_skill: () => new Promise(resolve => { finish = resolve }) })
    await wrapper.find('.skill-row').trigger('click')
    expect(wrapper.find('.skill-row').isVisible()).toBe(false)
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
    expect(wrapper.find('.skill-row').isVisible()).toBe(true)
  })

  it('系统返回保护未保存内容，取消保留、确认才退出', async () => {
    const { wrapper, store } = await mountPage({ get_skill: async () => ({ content: [{ type: 'text', text: '# 内容' }] }) })
    await wrapper.findAll('button').find(b => b.text() === '自定义').trigger('click')
    await wrapper.find('.skill-row').trigger('click'); await flushPromises()
    await wrapper.find('textarea').setValue('未保存草稿')
    expect(wrapper.vm.handleBack()).toBe(true)
    expect(store.state.dialog?.kind).toBe('discard')
    store.closeDialog()
    expect(wrapper.find('textarea').element.value).toBe('未保存草稿')
    expect(wrapper.vm.handleBack()).toBe(true)
    const accept = store.state.dialog.onAccept
    store.closeDialog(); await accept(); await flushPromises()
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('.skill-row').isVisible()).toBe(true)
  })

  it('详情独立展示，顶部返回恢复列表与滚动且不聚焦表单', async () => {
    const { wrapper } = await mountPage({ get_skill: async () => ({ content: [{ type: 'text', text: '# 内容' }] }) })
    const page = wrapper.find('.page').element
    page.scrollTop = 320
    const list = wrapper.find('.skill-row').element.parentElement
    list.scrollTop = 75
    await wrapper.find('.skill-row').trigger('click'); await flushPromises()
    expect(wrapper.find('.skill-row').isVisible()).toBe(false)
    const back = wrapper.find('[data-detail-back]')
    expect(back.exists()).toBe(true)
    expect(back.isVisible()).toBe(true)
    expect(page.scrollTop).toBe(0)
    expect(document.activeElement?.tagName).not.toMatch(/INPUT|TEXTAREA/)
    await back.trigger('click'); await flushPromises()
    expect(wrapper.find('.skill-row').isVisible()).toBe(true)
    expect(page.scrollTop).toBe(320)
    expect(list.scrollTop).toBe(75)
    expect(wrapper.vm.handleBack()).toBe(false)
  })

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
