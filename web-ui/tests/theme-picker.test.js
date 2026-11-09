import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createStore } from '../src/store.js'
import ThemePicker from '../src/components/ThemePicker.vue'
import { PALETTES } from '../src/lib/palettes.js'

function fakeBridge(handlers = {}) {
  return {
    kind: 'android',
    connected: true,
    call: async (method, params) => {
      const fn = handlers[method]
      if (!fn) throw Object.assign(new Error('unsupported'), { code: 'unsupported' })
      return fn(params)
    },
    onEvent: () => () => {},
    dispose: () => {},
    describe: () => ({ kind: 'android', connected: true })
  }
}

describe('ThemePicker', () => {
  it('渲染 10 个 SVG 双色主题球，点击发出下标', async () => {
    const wrapper = mount(ThemePicker, { props: { themeIdx: 0, mode: 'auto' } })
    const buttons = wrapper.findAll('.palette')
    expect(buttons).toHaveLength(10)
    expect(wrapper.findAll('svg.swatch')).toHaveLength(10)
    // 每套主题的渐变 id 唯一，避免多实例冲突
    const ids = wrapper.findAll('linearGradient').map((g) => g.attributes('id'))
    expect(new Set(ids).size).toBe(10)
    await buttons[3].trigger('click')
    expect(wrapper.emitted('pick-theme')[0]).toEqual([3])
  })

  it('三模式按钮：亮色/暗色/跟随系统', async () => {
    const wrapper = mount(ThemePicker, { props: { themeIdx: 2, mode: 'dark' } })
    const segs = wrapper.findAll('.segmented .seg')
    expect(segs).toHaveLength(3)
    expect(segs.map((s) => s.text())).toEqual(['亮色', '暗色', '跟随系统'])
    expect(segs[1].classes()).toContain('on')
    await segs[2].trigger('click')
    expect(wrapper.emitted('pick-mode')[0]).toEqual(['auto'])
  })

  it('主题名与参考一致', () => {
    const wrapper = mount(ThemePicker, { props: { themeIdx: 0, mode: 'auto' } })
    const names = wrapper.findAll('.palette-name').map((n) => n.text())
    expect(names).toEqual(PALETTES.map((p) => p.name))
  })
})

describe('store 主题联动', () => {
  it('setTheme 持久化并立即改变当前主题', () => {
    const map = new Map()
    const storage = { getItem: (k) => (map.has(k) ? map.get(k) : null), setItem: (k, v) => map.set(k, String(v)) }
    const store = createStore({ bridge: fakeBridge(), storage })
    store.setTheme(7)
    expect(store.state.themeIdx).toBe(7)
    expect(map.get('studio.theme')).toBe('crimson')
    store.setMode('dark')
    expect(map.get('studio.mode')).toBe('dark')
  })
})
