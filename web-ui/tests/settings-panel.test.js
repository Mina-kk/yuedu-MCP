import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SettingsPanel from '../src/components/SettingsPanel.vue'

describe('SettingsPanel async save', () => {
  it('保存期间禁用按钮，等待真实回包并显示失败', async () => {
    let reject
    const pending = new Promise((_, fail) => { reject = fail })
    const wrapper = mount(SettingsPanel, {
      props: { runtimeConnected: true, saveSettings: () => pending }
    })
    const button = wrapper.findAll('button').find(b => b.text().includes('保存设置'))
    await button.trigger('click')
    await flushPromises()
    expect(button.attributes('aria-busy')).toBe('true')
    reject(new Error('端口保存失败'))
    await flushPromises()
    expect(wrapper.text()).toContain('端口保存失败')
    expect(button.attributes('aria-busy')).toBe('false')
  })
})
