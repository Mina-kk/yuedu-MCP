// 打开 Sheet / 对话框后：聚焦第一个控件、Tab 循环限制在面板内、关闭时还原焦点。
import { onBeforeUnmount, onMounted } from 'vue'

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

export function useSheetFocus(panelRef, preferredSelector = '[data-sheet-focus]') {
  let previous = null
  let frame = 0

  function focusFirst() {
    const panel = panelRef.value
    if (!panel) return
    const preferred = panel.querySelector(preferredSelector)
    const target = preferred || panel.querySelector(FOCUSABLE) || panel
    if (target && typeof target.focus === 'function') target.focus()
  }

  function onKeydown(event) {
    if (event.key !== 'Tab') return
    const panel = panelRef.value
    if (!panel) return
    // 只让最上层的 Sheet / 对话框处理 Tab，避免背景 Sheet 抢焦点。
    const layers = document.querySelectorAll('.sheet-layer')
    const top = layers[layers.length - 1]
    if (top && !top.contains(panel)) return
    const nodes = [...panel.querySelectorAll(FOCUSABLE)].filter(
      (el) => el.offsetParent !== null || el === document.activeElement
    )
    if (nodes.length === 0) {
      event.preventDefault()
      panel.focus()
      return
    }
    const first = nodes[0]
    const last = nodes[nodes.length - 1]
    if (event.shiftKey && (document.activeElement === first || !panel.contains(document.activeElement))) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  onMounted(() => {
    previous = document.activeElement
    document.addEventListener('keydown', onKeydown, true)
    frame = window.requestAnimationFrame(() => {
      frame = window.requestAnimationFrame(focusFirst)
    })
  })

  onBeforeUnmount(() => {
    document.removeEventListener('keydown', onKeydown, true)
    if (frame) window.cancelAnimationFrame(frame)
    // 还有上层弹层时不要抢回焦点；触发元素已被重新渲染时也不要强行聚焦。
    if (!document.querySelector('.sheet-layer') && previous && previous.isConnected) {
      previous.focus()
    }
  })
}
