// 十套双色主题：顺序 / 名称 / hex 与参考（apikey-vault frontend-vue/src/lib/palettes.js）逐字一致。
// 只沿用设计语言；本项目不使用其 API 密钥业务模型。

export const PALETTES = [
  { key: 'lime', name: '青柠与炭黑', c1: '#CCFF4D', c2: '#222222', onC1: '#16210A' },
  { key: 'lava', name: '熔岩与深海', c1: '#F06A35', c2: '#0D1B4F', onC1: '#230A02' },
  { key: 'royal', name: '帝王紫与柠檬', c1: '#FFF04D', c2: '#5B1AA0', onC1: '#2A2402' },
  { key: 'pink', name: '甜酷粉与黑曜石', c1: '#D94A7F', c2: '#1E1E22', onC1: '#FFFFFF', actionBg: '#C34372' },
  { key: 'neon', name: '霓虹蓝与电光紫', c1: '#6EE7FA', c2: '#9B1AF0', onC1: '#06232A' },
  { key: 'mint', name: '冰薄荷与樱桃红', c1: '#B4F7A6', c2: '#C42635', onC1: '#12260E' },
  { key: 'crystal', name: '晶石紫与电光荧', c1: '#7CFF4D', c2: '#5B1AA0', onC1: '#14260A' },
  { key: 'crimson', name: '霓虹赤与电光蓝', c1: '#E32D2D', c2: '#1E47F5', onC1: '#FFFFFF', actionBg: '#CC2929' },
  {
    key: 'porcelain',
    name: '科技蓝与瓷白',
    c1: '#4C70FF',
    c2: '#F5F7FF',
    onC1: '#FFFFFF',
    bgLight: '#F5F7FF',
    actionBg: '#4465E6'
  },
  {
    key: 'cherry',
    name: '樱桃粉与奶油白',
    c1: '#F34891',
    c2: '#FFF7F8',
    onC1: '#2B0A18',
    bgLight: '#FFF7F8'
  }
]

export const DEFAULT_INDEX = 0
export const LIGHT_BG = '#F3F3F0'
export const DARK_BG = '#0A0A0C'

export function palette(index) {
  const i = Number.isFinite(Number(index)) ? Number(index) : DEFAULT_INDEX
  return PALETTES[Math.min(Math.max(Math.trunc(i), 0), PALETTES.length - 1)] || PALETTES[DEFAULT_INDEX]
}

export function paletteIndex(value) {
  const raw = value == null ? '' : String(value).trim()
  if (!raw) return DEFAULT_INDEX
  if (/^\d+$/.test(raw)) {
    const i = Number(raw)
    return i >= 0 && i < PALETTES.length ? i : DEFAULT_INDEX
  }
  const key = raw.toLowerCase()
  const found = PALETTES.findIndex((p) => p.key.toLowerCase() === key)
  return found >= 0 ? found : DEFAULT_INDEX
}

// 三模式：light / dark / auto（跟随系统）
export const MODES = ['light', 'dark', 'auto']

export function modeFrom(value) {
  const v = value == null ? '' : String(value).trim().toLowerCase()
  return MODES.includes(v) ? v : 'auto'
}

export function resolvedDark(mode, prefersDark = false) {
  if (mode === 'dark') return true
  if (mode === 'light') return false
  return Boolean(prefersDark)
}

export function backgroundHex(index, dark) {
  const p = palette(index)
  if (dark) return p.bgDark || DARK_BG
  return p.bgLight || LIGHT_BG
}

export function modeLabel(mode) {
  return mode === 'light' ? '亮色' : mode === 'dark' ? '暗色' : '跟随系统'
}

export const THEME_STORAGE_KEY = 'studio.theme'
export const MODE_STORAGE_KEY = 'studio.mode'

export function loadTheme(storage) {
  let idx = DEFAULT_INDEX
  let mode = 'auto'
  try {
    const raw = storage && storage.getItem(THEME_STORAGE_KEY)
    idx = paletteIndex(raw)
    mode = modeFrom(storage && storage.getItem(MODE_STORAGE_KEY))
  } catch {
    // 隐私模式 localStorage 可能抛错：退回默认值。
  }
  return { idx, mode }
}

export function saveTheme(storage, idx, mode) {
  try {
    if (!storage) return
    storage.setItem(THEME_STORAGE_KEY, palette(idx).key)
    storage.setItem(MODE_STORAGE_KEY, mode)
  } catch {
    // 写入失败不影响当前会话。
  }
}
