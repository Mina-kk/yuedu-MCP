// 主题应用：CSS 变量 + data 属性 + 宿主系统栏同步。纯函数式，方便测试。
import { backgroundHex, palette, resolvedDark } from './lib/palettes.js'

export function prefersDark(win) {
  try {
    const w = win || (typeof window !== 'undefined' ? window : null)
    const mql = w && typeof w.matchMedia === 'function' ? w.matchMedia('(prefers-color-scheme: dark)') : null
    return Boolean(mql && mql.matches)
  } catch {
    return false
  }
}

/**
 * @param {object} opts
 * @param {(dark:boolean)=>void} [opts.onDark] 宿主系统栏同步回调（app.theme）
 */
export function applyTheme({ idx, mode, root, win, doc, onDark } = {}) {
  const w = win || (typeof window !== 'undefined' ? window : null)
  const d = doc || (w && w.document) || (typeof document !== 'undefined' ? document : null)
  const el = root || (d && d.documentElement)
  if (!el) return { dark: false, background: '#F3F3F0' }

  const p = palette(idx)
  const dark = resolvedDark(mode, prefersDark(w))
  const background = backgroundHex(idx, dark)

  el.style.setProperty('--c1', p.c1)
  el.style.setProperty('--c2', p.c2)
  el.style.setProperty('--on-c1', p.onC1)
  el.style.setProperty('--action-bg', p.actionBg || p.c1)
  el.style.setProperty('--bg', background)
  el.setAttribute('data-mode', dark ? 'dark' : 'light')
  el.setAttribute('data-mode-pref', mode)
  el.setAttribute('data-theme', p.key)

  if (d && typeof d.querySelector === 'function') {
    const meta = d.querySelector('meta[name=theme-color]')
    if (meta) meta.setAttribute('content', background)
  }
  if (typeof onDark === 'function') {
    try {
      onDark(dark)
    } catch {
      // 宿主同步失败不影响渲染
    }
  }
  return { dark, background }
}

export function watchSystem(onChange, win) {
  const w = win || (typeof window !== 'undefined' ? window : null)
  try {
    const mql = w && typeof w.matchMedia === 'function' ? w.matchMedia('(prefers-color-scheme: dark)') : null
    if (!mql || typeof mql.addEventListener !== 'function') return () => {}
    const handler = () => onChange(Boolean(mql.matches))
    mql.addEventListener('change', handler)
    return () => mql.removeEventListener('change', handler)
  } catch {
    return () => {}
  }
}
