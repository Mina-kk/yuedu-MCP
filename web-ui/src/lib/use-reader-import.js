import { computed, onBeforeUnmount, reactive } from 'vue'
import { deliverSources } from './source-delivery.js'

// Each attempt owns an immutable delivery scope. Cancelling resolution invalidates
// its replies; a submitted launch cannot honestly be cancelled from this UI.
export function useReaderImport(store) {
  const state = reactive({ phase: 'idle', targets: [], error: '' })
  let revision = 0
  let payload = null
  const visible = computed(() => state.phase !== 'idle')
  function cancel() {
    if (!visible.value || state.phase === 'launching') return
    revision += 1
    state.phase = 'idle'
    state.targets = []
    state.error = ''
    payload = null
  }
  function handleBack() {
    if (!visible.value) return false
    cancel()
    return true
  }
  async function launch(packageName) {
    if (state.phase !== 'choosing' || !state.targets.some(t => t.packageName === packageName)) return
    const current = revision
    state.phase = 'launching'
    state.error = ''
    try {
      const receipt = await deliverSources({ ...store, toast: (...args) => {
        if (current === revision) store.toast(...args)
      } }, payload, 'reader', packageName)
      if (current !== revision) return
      if (receipt.cancelled) throw new Error('阅读唤起未确认，请核对客户端后重试')
      state.phase = 'idle'
      payload = null
      state.targets = []
    } catch (error) {
      if (current !== revision) return
      state.error = error.message || '唤起阅读失败'
      state.phase = 'choosing'
    }
  }
  async function resolve() {
    const current = ++revision
    state.phase = 'resolving'
    state.error = ''
    state.targets = []
    try {
      const result = await deliverSources(store, payload, 'targets')
      if (current !== revision) return
      if (result.cancelled) throw new Error('客户端查询未完成，请重试')
      const targets = result.targets
      if (targets.some(t => !t || typeof t.packageName !== 'string' || !t.packageName.trim()) ||
          new Set(targets.map(t => t.packageName)).size !== targets.length) throw new Error('客户端列表回执无效')
      state.targets = targets.map(t => ({ packageName: t.packageName, label: t.label || t.packageName }))
      if (!targets.length) throw new Error('未检测到可导入的阅读客户端')
      state.phase = 'choosing'
      if (targets.length === 1) await launch(targets[0].packageName)
    } catch (error) {
      if (current !== revision) return
      state.error = error.message || '读取阅读客户端失败'
      state.phase = 'error'
    }
  }
  function start(scope) {
    if (visible.value) return
    payload = Object.freeze({ ...scope, ...(scope.projectIds ? { projectIds: Object.freeze([...scope.projectIds]) } : {}) })
    return resolve()
  }
  function retry() { if (state.phase === 'error') return resolve() }
  onBeforeUnmount(() => { revision += 1; payload = null })
  return { state, visible, start, launch, cancel, handleBack, retry }
}
