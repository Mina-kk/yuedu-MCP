<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'

// call(action, payload) is the existing trusted UI bridge, NEVER the external WebView.
const props = defineProps({ call: { type: Function, required: true } })
const emit = defineEmits(['close', 'viewport'])
const url = ref(''), state = ref({ phase: 'idle', contextId: '' })
const rows = ref([]), detail = ref(null), error = ref(''), busy = ref(false)
const panel = ref(false), slot = ref(null), confirmExport = ref(false)
// Separate from transient errors: polling, navigation and failed retries must not hide this warning.
const exportResult = ref(null), exportNotice = ref('')
let timer, observer, disposed = false, sequence = 0
function viewport() {
  const r = slot.value?.getBoundingClientRect()
  emit('viewport', r && !panel.value && !detail.value && !confirmExport.value
    ? { x: r.x, y: r.y, width: r.width, height: r.height } : null)
}
async function refresh() {
  const ticket = ++sequence
  try {
    const next = await props.call('capture.browser.state', {})
    if (disposed || ticket !== sequence) return
    if (next.contextId !== state.value.contextId) { rows.value = []; detail.value = null }
    state.value = next
    if (next.contextId && panel.value && rows.value.length < next.total) {
      const page = await props.call('capture.browser.transactions', { offset: rows.value.length })
      if (!disposed && ticket === sequence) rows.value.push(...page.items)
    }
  } catch { if (!disposed && ticket === sequence) error.value = '无法读取抓包状态，请重试' }
}
async function act(action, args = {}) {
  if (busy.value) return
  busy.value = true; error.value = ''; ++sequence
  if (action === 'export') exportNotice.value = ''
  try {
    const result = await props.call(`capture.browser.${action}`, args)
    if (disposed) return
    if (action === 'detail') detail.value = result
    else if (action === 'transactions') rows.value.push(...result.items)
    else if (action === 'export') {
      exportNotice.value = ''
      if (result?.saved === false && result?.cancelled === true) {
        exportNotice.value = '已取消导出，未保存新文件'
        confirmExport.value = false
      } else {
        if (result?.saved !== true || result.cancelled === true ||
          !['count', 'total', 'missingResources', 'omittedResources'].every(k => Number.isSafeInteger(result[k]) && result[k] >= 0) ||
          typeof result.partial !== 'boolean' || ((result.missingResources > 0 || result.count !== result.total) && !result.partial))
          throw new Error('export not confirmed')
        exportResult.value = { ...result, contextId: state.value.contextId }
        confirmExport.value = false
      }
    } else if (action === 'close') {
      if (result.closed !== true) throw new Error('close not confirmed')
      emit('close', result); return
    }
    await refresh()
  } catch {
    if (!disposed) {
      error.value = '操作未完成，请重试；结束失败不代表已保存'
      await refresh()
    }
  }
  finally { busy.value = false; requestAnimationFrame(viewport) }
}
function togglePanel() { panel.value = !panel.value; refresh(); requestAnimationFrame(viewport) }
function back() {
  if (busy.value) return
  if (confirmExport.value) confirmExport.value = false
  else if (detail.value) detail.value = null
  else if (panel.value) panel.value = false
  else if (state.value.phase === 'active' && state.value.canGoBack) { act('back'); return }
  else { act('close'); return }
  requestAnimationFrame(viewport)
}
defineExpose({ back })
onMounted(() => {
  refresh()
  timer = setInterval(() => { if (!busy.value) refresh() }, 1000)
  if (typeof ResizeObserver !== 'undefined') { observer = new ResizeObserver(viewport); observer.observe(slot.value) }
  window.addEventListener('resize', viewport)
  requestAnimationFrame(viewport)
})
onBeforeUnmount(() => {
  disposed = true; ++sequence; clearInterval(timer); observer?.disconnect()
  window.removeEventListener('resize', viewport); emit('viewport', null)
  // Host lifecycle, not this best-effort UI callback, MUST settle and destroy on Activity teardown.
})
</script>

<template>
  <section class="capture-workbench">
    <header><button :disabled="busy" @click="back">返回</button><strong>浏览器抓包</strong><span>{{ state.phase === 'saved' ? '已保存' : state.phase === 'failed' ? '未确认保存' : state.phase === 'finishing' ? '正在结算' : state.phase === 'active' ? '录制中' : '未开始' }}</span></header>
    <p class="notice">真实可交互网页；GET/HEAD 为 OkHttp 供给，POST 与部分重定向仅观察，不是完整网络栈抓包。仅允许公网 HTTP(S)。</p>
    <form @submit.prevent="act(state.phase === 'active' ? 'navigate' : 'open', { url })">
      <input v-model="url" aria-label="抓包网址" placeholder="https://example.com" :disabled="busy || state.phase === 'finishing'" />
      <button :disabled="busy || !url.trim() || state.phase === 'finishing'">前往</button>
    </form>
    <nav>
      <button :disabled="busy || !state.canGoBack || state.phase !== 'active'" @click="act('back')">后退</button>
      <button :disabled="busy || !state.canGoForward || state.phase !== 'active'" @click="act('forward')">前进</button>
      <button :disabled="busy || state.phase !== 'active'" @click="act('reload')">刷新</button>
      <button :disabled="!state.contextId || busy" @click="togglePanel">{{ panel ? '网页' : '事务' }} ({{ state.total || 0 }})</button>
      <button :disabled="busy || !['active', 'finishing', 'failed'].includes(state.phase)" @click="act('finish')">结束并保存</button>
      <button :disabled="busy || state.phase !== 'saved'" @click="confirmExport = true; viewport()">导出会话</button>
    </nav>
    <p class="url">{{ state.url }} <small>{{ state.contextId }}</small></p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="state.diagnostic" role="status">{{ state.diagnostic }}</p>
    <p v-if="exportNotice" role="status">{{ exportNotice }}</p>
    <p v-if="exportResult" data-testid="capture-export-result" :role="exportResult.partial || exportResult.omittedResources > 0 ? 'alert' : 'status'">
      上次导出 {{ exportResult.contextId }}：文件已保存（{{ exportResult.count }} / {{ exportResult.total }} 条），仅表示文件落盘。
      <strong v-if="exportResult.partial || exportResult.omittedResources > 0">不是完整备份。缺失或无法读取 {{ exportResult.missingResources }} 个资源；省略 {{ exportResult.omittedResources }} 个资源。</strong>
      <span v-else>已存事务及可用资源已导出；抓取时截断或仅观察的数据无法补回。</span>
    </p>
    <div ref="slot" class="browser-slot" aria-label="隔离交互网页区域">
      <p v-if="state.phase !== 'active' && !panel && !detail">{{ state.phase === 'saved' ? '会话已结束，可查看事务或导出会话。' : '输入公网网址开始；网页由隔离 Android WebView 显示。' }}</p>
      <section v-if="detail" class="transactions"><button @click="detail = null; viewport()">返回事务</button><pre>{{ JSON.stringify(detail, null, 2) }}</pre></section>
      <section v-else-if="panel" class="transactions">
        <p>已加载 {{ rows.length }} / {{ state.total || 0 }} 条</p>
        <button v-for="row in rows" :key="row.id" class="transaction" :disabled="busy" @click="act('detail', { id: row.id })">{{ row.statusCode }} {{ row.method }} {{ row.url }}</button>
        <button v-if="rows.length < state.total" :disabled="busy" @click="act('transactions', { offset: rows.length })">加载更多</button>
      </section>
    </div>
    <div v-if="confirmExport" role="dialog" aria-modal="true" aria-label="导出风险确认" class="export-confirm">
      <p>导出包含全部已存事务与可用二进制正文，URL/query 和正文可能含敏感信息。抓取时截断或仅观察的数据无法补回。</p>
      <button :disabled="busy" @click="confirmExport = false; viewport()">取消</button>
      <button :disabled="busy" @click="act('export')">确认导出</button>
    </div>
  </section>
</template>

<style scoped>
.capture-workbench{display:flex;flex-direction:column;position:relative;height:100%;min-height:0;gap:8px;padding:8px;box-sizing:border-box;background:var(--bg,#fff);color:var(--text,#222)}
header,form,nav{display:flex;gap:8px;align-items:center;flex-wrap:wrap}header strong{flex:1}form input{flex:1;min-width:100px}button,input{font:inherit;padding:8px}button:disabled{opacity:.5}.notice,.url{font-size:12px;margin:0;overflow-wrap:anywhere}.browser-slot{flex:1;min-height:80px;position:relative}.transactions{height:100%;overflow:auto}.transaction{display:block;width:100%;text-align:left;overflow-wrap:anywhere;margin-bottom:6px}pre{white-space:pre-wrap;overflow-wrap:anywhere}.export-confirm{position:absolute;inset:0;z-index:5;background:var(--bg,#fff);padding:24px;overflow:auto}@media(max-height:500px){.notice{display:none}button,input{padding:4px}.capture-workbench{gap:4px}.url{max-height:30px;overflow:hidden}}
</style>
