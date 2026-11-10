// One CSS-pixel rectangle for the shell and shared overlays. The Android host
// already resized its WebView for system bars/IME: never subtract raw insets.
export function observeViewport(win, root, onChange = () => {}) {
  const viewport = win.visualViewport
  const positive = (value, fallback) => Number.isFinite(value) && value > 0 ? value : fallback
  const offset = value => Number.isFinite(value) && value > 0 ? value : 0
  function sync() {
    const current = win.visualViewport
    const height = positive(current?.height, positive(win.innerHeight, root.clientHeight))
    const width = positive(current?.width, positive(win.innerWidth, root.clientWidth))
    const top = offset(current?.offsetTop)
    const left = offset(current?.offsetLeft)
    for (const [name, value] of Object.entries({
      '--vvh': height, '--vvw': width, '--vv-top': top, '--vv-left': left,
      '--usable-bottom': top + height
    })) root.style.setProperty(name, `${value}px`)
    root.setAttribute('data-short-viewport', height <= 400 ? '1' : '0')
    onChange()
  }
  sync()
  win.addEventListener('resize', sync)
  viewport?.addEventListener('resize', sync)
  viewport?.addEventListener('scroll', sync)
  return () => {
    win.removeEventListener('resize', sync)
    viewport?.removeEventListener('resize', sync)
    viewport?.removeEventListener('scroll', sync)
  }
}
