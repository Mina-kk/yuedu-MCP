import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SettingsPanel from '../src/components/SettingsPanel.vue'

describe('SettingsPanel version identity', () => {
  it('应用三项构建信息与Rust完整版本及状态分开标明，bootstrap更新后同步显示', async () => {
    const revision = '1234567890abcdef1234567890abcdef12345678'
    const rustVersion = 'studio-ui-core/2.0.0+wasm-build-fixture'
    const wrapper = mount(SettingsPanel, {
      props: {
        bootstrap: { versionName: '7.4.2', versionCode: 70402, buildRevision: revision },
        coreStatus: 'loaded', coreVersion: rustVersion
      }
    })
    const app = wrapper.findAll('.section').find(section => section.find('h3').exists() && section.find('h3').text() === '应用版本（APK）')
    expect(app).toBeTruthy()
    expect(app.text()).toContain('versionName')
    expect(app.text()).toContain('7.4.2')
    expect(app.text()).toContain('versionCode')
    expect(app.text()).toContain('70402')
    expect(app.text()).toContain('buildRevision')
    expect(app.text()).toContain(revision)
    expect(app.text()).not.toContain(rustVersion)
    const rust = wrapper.findAll('.section').find(section => section.find('h3').exists() && section.find('h3').text() === 'Rust 前端核')
    expect(rust.text()).toContain(rustVersion)
    expect(rust.text()).toContain('已加载')
    expect(rust.text()).not.toContain('7.4.2')
    await wrapper.setProps({ bootstrap: { versionName: '8.1.0', versionCode: 80100, buildRevision: 'new-revision' } })
    expect(app.text()).toContain('8.1.0')
    expect(app.text()).toContain('80100')
    expect(app.text()).toContain('new-revision')
    expect(app.text()).not.toContain(revision)
    wrapper.unmount()
  })

  it.each([null, {}, { versionName: ' ', versionCode: null, buildRevision: '' }])('版本信息缺失时显示未知，不用Rust版本冒充APK：%s', bootstrap => {
    const wrapper = mount(SettingsPanel, { props: { bootstrap, coreStatus: 'failed', coreVersion: '' } })
    const app = wrapper.findAll('.section').find(section => section.find('h3').exists() && section.find('h3').text() === '应用版本（APK）')
    expect(app).toBeTruthy()
    expect(app.text()).toContain('版本未知')
    expect(app.findAll('dd').map(value => value.text())).toEqual(['版本未知', '未知', '未知'])
    expect(wrapper.text()).toContain('Rust 核不可用')
    wrapper.unmount()
  })

  it.each([
    ['idle', '尚未加载'],
    ['loading', '加载中'],
    ['loaded', '已加载'],
    ['failed', '加载失败']
  ])('Rust %s状态独立展示，缺少版本不虚构', (coreStatus, label) => {
    const wrapper = mount(SettingsPanel, { props: { coreStatus, coreVersion: '' } })
    const rust = wrapper.findAll('.section').find(section => section.find('h3').exists() && section.find('h3').text() === 'Rust 前端核')
    expect(rust.text()).toContain(label)
    expect(rust.text()).toContain('版本未知')
    wrapper.unmount()
  })
})

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
