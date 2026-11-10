import { nextTick, ref } from 'vue'

// Keep the list mounted (v-show) so filters, selection and nested scroll survive.
export function useDetailNavigation() {
  const pageRef = ref(null)
  const detailOpen = ref(false)
  let positions = []
  let returnFocus = null
  let revision = 0

  function enterDetail() {
    if (detailOpen.value) return
    const root = pageRef.value
    const nodes = new Set(root ? [root, ...root.querySelectorAll('*')] : [])
    for (let node = root?.parentElement; node; node = node.parentElement) nodes.add(node)
    if (document.scrollingElement) nodes.add(document.scrollingElement)
    positions = [...nodes].map(node => [node, node.scrollTop, node.scrollLeft])
    returnFocus = document.activeElement
    detailOpen.value = true
    const current = ++revision
    nextTick(() => {
      if (current !== revision) return
      for (const [node] of positions) {
        if (node === root || node.contains(root)) { node.scrollTop = 0; node.scrollLeft = 0 }
      }
      root?.querySelector('[data-detail-back]')?.focus({ preventScroll: true })
    })
  }

  function leaveDetail() {
    detailOpen.value = false
    const current = ++revision
    nextTick(() => {
      if (current !== revision) return
      // Never reopen a mobile keyboard when restoring list focus.
      if (returnFocus?.isConnected && returnFocus.tagName === 'BUTTON') returnFocus.focus({ preventScroll: true })
      for (const [node, top, left] of positions) { node.scrollTop = top; node.scrollLeft = left }
    })
  }
  return { pageRef, detailOpen, enterDetail, leaveDetail }
}
