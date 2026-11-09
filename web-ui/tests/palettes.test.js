import { describe, it, expect } from 'vitest'
import {
  PALETTES,
  DEFAULT_INDEX,
  palette,
  paletteIndex,
  modeFrom,
  resolvedDark,
  backgroundHex,
  loadTheme,
  saveTheme,
  THEME_STORAGE_KEY,
  MODE_STORAGE_KEY
} from '../src/lib/palettes.js'

// 参考（apikey-vault frontend-vue/src/lib/palettes.js）的 key/名称/hex/顺序必须逐字保持。
const REFERENCE = [
  ['lime', '青柠与炭黑', '#CCFF4D', '#222222', '#16210A'],
  ['lava', '熔岩与深海', '#F06A35', '#0D1B4F', '#230A02'],
  ['royal', '帝王紫与柠檬', '#FFF04D', '#5B1AA0', '#2A2402'],
  ['pink', '甜酷粉与黑曜石', '#D94A7F', '#1E1E22', '#FFFFFF'],
  ['neon', '霓虹蓝与电光紫', '#6EE7FA', '#9B1AF0', '#06232A'],
  ['mint', '冰薄荷与樱桃红', '#B4F7A6', '#C42635', '#12260E'],
  ['crystal', '晶石紫与电光荧', '#7CFF4D', '#5B1AA0', '#14260A'],
  ['crimson', '霓虹赤与电光蓝', '#E32D2D', '#1E47F5', '#FFFFFF'],
  ['porcelain', '科技蓝与瓷白', '#4C70FF', '#F5F7FF', '#FFFFFF'],
  ['cherry', '樱桃粉与奶油白', '#F34891', '#FFF7F8', '#2B0A18']
]

describe('palettes', () => {
  it('10 套主题的 key / 名称 / hex / 顺序与参考完全一致', () => {
    expect(PALETTES).toHaveLength(10)
    expect(PALETTES.map((p) => [p.key, p.name, p.c1, p.c2, p.onC1])).toEqual(REFERENCE)
  })

  it('默认是 lime（第 0 套）', () => {
    expect(DEFAULT_INDEX).toBe(0)
    expect(palette(undefined).key).toBe('lime')
    expect(palette(99).key).toBe('cherry')
    expect(palette(-3).key).toBe('lime')
  })

  it('粉色/红色主题带独立 actionBg，瓷白/奶油白带浅背景', () => {
    expect(palette(paletteIndex('pink')).actionBg).toBe('#C34372')
    expect(palette(paletteIndex('crimson')).actionBg).toBe('#CC2929')
    expect(palette(paletteIndex('porcelain')).bgLight).toBe('#F5F7FF')
    expect(palette(paletteIndex('cherry')).bgLight).toBe('#FFF7F8')
  })

  it('paletteIndex 接受 key 与数字下标，未知值回落 0', () => {
    expect(paletteIndex('royal')).toBe(2)
    expect(paletteIndex('ROYAL')).toBe(2)
    expect(paletteIndex('3')).toBe(3)
    expect(paletteIndex('42')).toBe(0)
    expect(paletteIndex('nope')).toBe(0)
    expect(paletteIndex(null)).toBe(0)
  })

  it('浅深自动：auto 跟随系统，light/dark 强制', () => {
    expect(modeFrom('DARK')).toBe('dark')
    expect(modeFrom('weird')).toBe('auto')
    expect(resolvedDark('auto', true)).toBe(true)
    expect(resolvedDark('auto', false)).toBe(false)
    expect(resolvedDark('light', true)).toBe(false)
    expect(resolvedDark('dark', false)).toBe(true)
  })

  it('背景色随主题与深浅切换', () => {
    expect(backgroundHex(0, false)).toBe('#F3F3F0')
    expect(backgroundHex(0, true)).toBe('#0A0A0C')
    expect(backgroundHex(paletteIndex('porcelain'), false)).toBe('#F5F7FF')
  })

  it('读写主题设置', () => {
    const store = new Map()
    const storage = {
      getItem: (k) => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => store.set(k, String(v))
    }
    expect(loadTheme(storage)).toEqual({ idx: 0, mode: 'auto' })
    saveTheme(storage, 5, 'dark')
    expect(store.get(THEME_STORAGE_KEY)).toBe('mint')
    expect(store.get(MODE_STORAGE_KEY)).toBe('dark')
    expect(loadTheme(storage)).toEqual({ idx: 5, mode: 'dark' })
  })
})
