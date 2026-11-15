<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
const props = defineProps({ state: { type: Object, required: true } })
const emit = defineEmits(['pick', 'cancel', 'retry'])
const panel = ref(null)
let previous
const focusable = () => [...(panel.value?.querySelectorAll('button:not([disabled])') || [])]
function keydown(event) {
  if (event.key === 'Escape') {
    event.preventDefault(); event.stopImmediatePropagation()
    if (props.state.phase !== 'launching') emit('cancel')
  } else if (event.key === 'Tab') {
    const nodes = focusable()
    const first = nodes[0], last = nodes.at(-1)
    if (!first) { event.preventDefault(); panel.value?.focus() }
    else if (!panel.value.contains(document.activeElement) || (event.shiftKey ? document.activeElement === first : document.activeElement === last)) {
      event.preventDefault(); (event.shiftKey ? last : first).focus()
    }
  }
}
onMounted(async () => {
  previous = document.activeElement
  document.addEventListener('keydown', keydown, true)
  await nextTick()
  ;(focusable()[0] || panel.value)?.focus({ preventScroll: true })
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', keydown, true)
  if (previous?.isConnected) previous.focus({ preventScroll: true })
})
</script>
<template>
  <Teleport to="body">
    <div class="reader-import-layer" @click.self="state.phase !== 'launching' && emit('cancel')">
      <section ref="panel" class="reader-import-dialog" role="dialog" aria-modal="true" aria-label="导入阅读" tabindex="-1" :aria-busy="state.phase === 'resolving' || state.phase === 'launching'">
        <h3>导入阅读</h3>
        <p class="hint">仅唤起阅读勾选页，待用户确认，不代表已导入。</p>
        <p v-if="state.phase === 'resolving'" role="status">正在查找阅读客户端…</p>
        <p v-if="state.phase === 'launching'" role="status">正在唤起阅读，请稍候；已提交的操作不能取消。</p>
        <p v-if="state.error" class="err" role="alert">{{ state.error }}</p>
        <div class="reader-targets">
          <button v-for="target in state.targets" :key="target.packageName" class="btn btn-ghost reader-target" :data-reader-target="target.packageName" type="button" :disabled="state.phase === 'launching'" @click="emit('pick', target.packageName)">{{ target.label }} · {{ target.packageName }}</button>
        </div>
        <div class="actions">
          <button v-if="state.phase === 'error'" class="btn btn-ghost" type="button" @click="emit('retry')">重试</button>
          <button class="btn btn-ghost" data-reader-cancel type="button" :disabled="state.phase === 'launching'" @click="emit('cancel')">取消选择客户端</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
<style scoped>
.reader-import-layer { position:fixed; inset:0; z-index:1200; display:flex; align-items:center; justify-content:center; padding:16px; padding-top:max(16px, var(--safe-top, 0px)); padding-bottom:max(16px, var(--safe-bottom, 0px)); background:rgb(0 0 0 / .45); overscroll-behavior:contain; }
.reader-import-dialog { box-sizing:border-box; width:100%; max-width:440px; max-height:100%; overflow:auto; overscroll-behavior:contain; padding:20px; border:1px solid var(--line); border-radius:18px; background:var(--surface-solid, #fff); color:var(--text, #222); box-shadow:0 12px 48px rgb(0 0 0 / .24); overflow-wrap:anywhere; }
.reader-import-dialog h3 { margin:0 0 12px; }
.reader-targets { display:flex; flex-direction:column; gap:8px; }
.reader-import-dialog .btn { min-height:44px; height:auto; white-space:normal; padding:10px 12px; }
.reader-target { width:100%; text-align:left; }
.reader-import-dialog .actions { margin-top:16px; }
</style>
