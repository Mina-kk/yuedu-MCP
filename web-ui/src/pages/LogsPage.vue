<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import AppIcon from '../components/Icon.vue'
import EmptyState from '../components/EmptyState.vue'
import { callToolParsed, formatTime, itemsOf, prettyJson, redactForDisplay } from '../lib/tools.js'
import { exportText } from '../lib/files.js'

// 日志页：真实工具分页读取 + 详情 + 导出。
//  operation → get_logs(limit, category, level, query)  {count, items[]}
//  http      → get_http_logs(limit, offset, query, date, includeCapture) {count,total,offset,limit,items[]}
//  capture   → list_captures(limit, offset) {count,total,items[]}，点会话用 get_capture(contextId, limit, offset)
//  crash     → get_crash_logs(limit) {count, items[]}，点条目用 get_crash_log(name)
//  diagnostic→ get_diagnostic_snapshots() → get_diagnostic_snapshot(id)
// 空结果如实显示空状态，绝不填假日志。
const props = defineProps({ store: { type: Object, required: true } })
const s = props.store.state

const TABS = [
  { key: 'operation', label: '操作', tool: 'get_logs' },
  { key: 'http', label: 'HTTP', tool: 'get_http_logs' },
  { key: 'capture', label: '抓包', tool: 'list_captures' },
  { key: 'crash', label: '崩溃', tool: 'get_crash_logs' },
  { key: 'diagnostic', label: '诊断', tool: 'get_diagnostic_snapshots' }
]

const PAGE = 20
const tab = ref('operation')
const items = ref([])
const total = ref(null)
const offset = ref(0)
const hasMore = ref(false)
const loading = ref(false)
const listError = ref('')
const query = ref('')
const level = ref('')
const category = ref('')
const date = ref('')
const dateInput = ref('')
const dateError = ref('')
const includeCapture = ref(false)
const selected = ref([])
const pendingDelete = ref(null)
const deleting = ref(false)
const deleteMessage = ref('')
const localDelete = computed(() => s.bridgeKind === 'android')
const locked = computed(() => loading.value || deleting.value || !!pendingDelete.value)
const deleteKind = computed(() => tab.value === 'diagnostic' ? 'snapshot' : tab.value)

function isCaptureHttp(item) {
  return tab.value === 'http' && /^cap:/i.test(item.contextId || '')
}

function logId(item) {
  const id = tab.value === 'capture' ? item.contextId : tab.value === 'crash' ? item.name : item.id
  if (typeof id === 'number' && !Number.isSafeInteger(id)) return ''
  if (typeof id !== 'string' && typeof id !== 'number') return ''
  const exact = String(id)
  const uuid = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
  if (tab.value === 'operation' || tab.value === 'http') {
    if (isCaptureHttp(item) || !/^[1-9][0-9]{0,18}$/.test(exact)) return ''
    return BigInt(exact) <= 9223372036854775807n ? exact : ''
  }
  const pattern = tab.value === 'capture' ? `^cap:${uuid}$` : tab.value === 'diagnostic' ? `^${uuid}$` : '^(?:crash|fault)-[0-9]{1,19}\\.txt$'
  return new RegExp(pattern).test(exact) ? exact : ''
}

function selectLoaded() {
  if (!locked.value) selected.value = [...new Set(items.value.map(logId).filter(Boolean))].slice(0, 100)
}
function askDelete(item) {
  if (!localDelete.value || locked.value) return
  const loaded = new Set(items.value.map(logId).filter(Boolean))
  const ids = [...new Set(item ? [logId(item)] : selected.value)].filter(id => loaded.has(id)).slice(0, 100)
  if (ids.length) pendingDelete.value = { kind: deleteKind.value, ids }
}

async function confirmDelete() {
  if (deleting.value || !pendingDelete.value) return
  deleting.value = true
  const request = pendingDelete.value
  selected.value = [...request.ids]
  deleteMessage.value = ''
  try {
    const result = await props.store.bridge.call('logs.delete', request)
    if (result?.kind !== request.kind || !Array.isArray(result.deletedIds) || !Array.isArray(result.missingIds) || !Array.isArray(result.failed)) throw new Error('删除结果无效，未确认删除')
    const failed = new Set(result.failed.map(entry => entry.id))
    const deleted = request.ids.filter(id => result.deletedIds.includes(id) && !failed.has(id))
    const missing = request.ids.filter(id => result.missingIds.includes(id) && !failed.has(id) && !deleted.includes(id))
    const confirmed = new Set([...deleted, ...missing])
    const remaining = request.ids.filter(id => !confirmed.has(id))
    selected.value = remaining
    items.value = items.value.filter(item => !confirmed.has(logId(item)))
    deleteMessage.value = `已删除 ${deleted.length} 条，已不存在 ${missing.length} 条，失败 ${remaining.length} 条`
    if (result.failed.length) deleteMessage.value += '：' + result.failed.map(entry => `${entry.id}: ${entry.code}`).join('；')
    if (remaining.length > result.failed.length) deleteMessage.value += '；部分结果未确认'
    await load(false, true)
  } catch (error) {
    deleteMessage.value = error?.message || '删除失败'
  } finally {
    deleting.value = false
    pendingDelete.value = null
  }
}


function applyDate() {
  if (locked.value) return
  const value = dateInput.value.trim()
  const parsed = new Date(`${value}T00:00:00Z`)
  if (value && (!/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value)) {
    dateError.value = '请输入有效日期（YYYY-MM-DD）'
    return
  }
  dateError.value = ''
  date.value = value
  load(true)
}

const detail = ref(null)
const detailTitle = ref('')
const detailBusy = ref(false)
const detailError = ref('')
const pageRoot = ref(null)
const detailOpen = ref(false)
let listScroll = 0
let detailRequest = 0

function scrollHost() {
  return pageRoot.value?.closest('.page-body') || document.scrollingElement
}

function handleBack() {
  if (deleting.value) return true
  if (pendingDelete.value) { pendingDelete.value = null; return true }
  if (!detailOpen.value) return false
  detailOpen.value = false
  detailRequest++
  detailBusy.value = false
  nextTick(() => { const host = scrollHost(); if (host) host.scrollTop = listScroll })
  return true
}
defineExpose({ handleBack })

const currentTab = computed(() => TABS.find((t) => t.key === tab.value) || TABS[0])
const supportsPaging = computed(() => ['http', 'capture'].includes(tab.value))

onMounted(() => load())

function params() {
  const base = { limit: PAGE }
  if (supportsPaging.value) base.offset = offset.value
  if (tab.value === 'operation') {
    if (query.value.trim()) base.query = query.value.trim()
    if (level.value) base.level = level.value
    if (category.value.trim()) base.category = category.value.trim()
  }
  if (tab.value === 'http') {
    if (query.value.trim()) base.query = query.value.trim()
    if (date.value) base.date = date.value
    if (includeCapture.value) base.includeCapture = true
  }
  if (tab.value === 'crash') base.limit = 20
  return base
}

async function load(reset = true, afterDelete = false) {
  if (locked.value && !afterDelete) return
  if (!afterDelete) selected.value = []
  if (reset) {
    offset.value = 0
    detail.value = null
    detailTitle.value = ''
  }
  loading.value = true
  listError.value = ''
  try {
    const parsed = await callToolParsed(props.store, currentTab.value.tool, params())
    if (parsed.threw) throw new Error(parsed.threw)
    if (parsed.isError) throw new Error(parsed.text || `${currentTab.value.tool} 返回错误`)
    const fresh = itemsOf(parsed)
    // Keep failed targets visible and selected even if the latest page shifted.
    items.value = afterDelete
      ? [...fresh, ...items.value.filter(item => selected.value.includes(logId(item)) && !fresh.some(row => logId(row) === logId(item)))]
      : fresh
    const t = parsed.data && Number.isFinite(Number(parsed.data.total)) ? Number(parsed.data.total) : null
    total.value = t
    hasMore.value = t !== null ? offset.value + items.value.length < t : items.value.length >= PAGE
  } catch (error) {
    if (!afterDelete) items.value = []
    total.value = null
    hasMore.value = false
    listError.value = (error && error.message) || '日志读取失败'
  } finally {
    loading.value = false
  }
}

function switchTab(key) {
  if (locked.value || tab.value === key) return
  tab.value = key
  query.value = ''
  level.value = ''
  category.value = ''
  date.value = ''
  dateInput.value = ''
  dateError.value = ''
  includeCapture.value = false
  load(true)
}

function nextPage() {
  if (locked.value) return
  offset.value += PAGE
  load(false)
}

function prevPage() {
  if (locked.value) return
  offset.value = Math.max(0, offset.value - PAGE)
  load(false)
}

async function openDetail(item) {
  if (locked.value) return
  const request = ++detailRequest
  listScroll = scrollHost()?.scrollTop || 0
  detailOpen.value = true
  nextTick(() => { const host = scrollHost(); if (host) host.scrollTop = 0 })
  detailError.value = ''
  detail.value = null
  detailTitle.value = ''
  detailBusy.value = true
  try {
    let tool = ''
    let args = {}
    if (tab.value === 'operation') {
      tool = 'get_log'
      args = { id: item.id }
      detailTitle.value = `日志 #${item.id}`
    } else if (tab.value === 'http') {
      tool = 'get_http_log'
      args = { id: item.id }
      detailTitle.value = `HTTP #${item.id}`
    } else if (tab.value === 'capture') {
      tool = 'get_capture'
      args = { contextId: item.contextId, limit: 50, offset: 0 }
      detailTitle.value = `抓包 ${item.contextId}`
    } else if (tab.value === 'crash') {
      tool = 'get_crash_log'
      args = { name: item.name }
      detailTitle.value = item.name
    } else if (tab.value === 'diagnostic') {
      tool = 'get_diagnostic_snapshot'
      args = { id: item.id }
      detailTitle.value = `诊断 #${item.id}`
    }
    const parsed = await callToolParsed(props.store, tool, args)
    if (request !== detailRequest) return
    if (parsed.threw) throw new Error(parsed.threw)
    if (parsed.isError) throw new Error(parsed.text || `${tool} 返回错误`)
    detail.value = parsed.data ? prettyJson(redactForDisplay(parsed.data)) : parsed.text
  } catch (error) {
    if (request !== detailRequest) return
    detailError.value = (error && error.message) || '读取详情失败'
  } finally {
    if (request === detailRequest) detailBusy.value = false
  }
}

async function exportCurrent() {
  const text = detail.value || prettyJson(items.value)
  try {
    const res = await exportText(props.store, {
      name: `logs-${tab.value}-${Date.now()}.json`,
      text
    })
    if (res && res.saved) props.store.toast('已导出')
  } catch (error) {
    props.store.toast((error && error.message) || '导出失败', 'err')
  }
}

function itemTitle(item) {
  if (tab.value === 'operation') return `[${item.level || 'I'}] ${item.category || ''} ${item.message || ''}`
  if (tab.value === 'http') return `${item.method || ''} ${item.statusCode || ''} ${item.url || ''}`
  if (tab.value === 'capture') return `${item.kind || '会话'} ${item.contextId}（${item.totalCount ?? '?'} 条）`
  if (tab.value === 'crash') return `${item.name}（${item.size ?? '?'} 字节）`
  return `${item.title || item.id}`
}

function itemSub(item) {
  if (tab.value === 'operation') return formatTime(item.createdAt)
  if (tab.value === 'http') return `${formatTime(item.createdAt)} · ${item.durationMs ?? '?'}ms · ${item.finalUrl || ''}`
  if (tab.value === 'capture') return `${formatTime(item.firstAt)} → ${formatTime(item.lastAt)}`
  if (tab.value === 'crash') return formatTime(item.createdAt)
  return formatTime(item.createdAt)
}
</script>

<template>
  <section ref="pageRoot" class="page">
    <div v-show="!detailOpen" class="section">
      <div class="section-head">
        <h3 class="section-title">日志</h3>
        <span class="section-sub">{{ total === null ? items.length : `${items.length} / ${total}` }}</span>
      </div>
      <div class="segmented wrap">
        <button
          v-for="t in TABS"
          :key="t.key"
          type="button"
          class="seg"
          :disabled="locked"
          :class="{ on: tab === t.key }"
          @click="switchTab(t.key)"
        >
          {{ t.label }}
        </button>
      </div>

      <div v-if="tab === 'operation' || tab === 'http'" class="search">
        <AppIcon name="search" :size="16" class="search-ico" />
        <input
          v-model="query" :disabled="locked"
          type="search"
          class="input"
          :placeholder="tab === 'http' ? '按 URL 过滤' : '匹配 message / detail'"
          aria-label="过滤日志"
          @keyup.enter="load(true)"
        />
      </div>
      <div v-if="tab === 'operation'" class="filter-row">
        <div class="segmented wrap" role="group" aria-label="级别">
          <button v-for="option in [{ value: '', label: '全部级别' }, { value: 'I', label: '信息' }, { value: 'W', label: '警告' }, { value: 'E', label: '错误' }]"
            :key="option.value" :disabled="locked" type="button" class="seg" :class="{ on: level === option.value }" :aria-pressed="level === option.value"
            @click="level = option.value; load(true)">{{ option.label }}</button>
        </div>
        <input v-model="category" :disabled="locked" class="input" placeholder="category（如 tool/mcp）" aria-label="分类" @keyup.enter="load(true)" />
      </div>
      <div v-if="tab === 'http'" class="filter-row">
        <input v-model="dateInput" :disabled="locked" class="input" type="text" placeholder="YYYY-MM-DD（留空不限）" aria-label="日期" :aria-invalid="!!dateError" @keyup.enter="applyDate" />
        <button class="btn btn-ghost btn-sm" type="button" :disabled="locked" @click="applyDate">应用日期</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="locked" :class="{ on: includeCapture }" @click="includeCapture = !includeCapture; load(true)">
          {{ includeCapture ? '含抓包事务' : '排除抓包事务' }}
        </button>
      </div>

      <p v-if="dateError" class="code-block err" role="alert">{{ dateError }}</p>
      <div class="actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="locked" @click="load(true)">
          <AppIcon name="refresh" :size="15" /><span>刷新</span>
        </button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="!items.length && !detail" @click="exportCurrent">
          <AppIcon name="download" :size="15" /><span>导出</span>
        </button>
      </div>

      <div v-if="localDelete" class="actions" aria-label="日志选择">
        <span>已选 {{ selected.length }} 条（最多 100 条）</span>
        <button class="btn btn-ghost btn-sm" :disabled="locked || !items.length" @click="selectLoaded">全选已加载</button>
        <button class="btn btn-ghost btn-sm" :disabled="locked || !selected.length" @click="selected = []">取消选择</button>
        <button class="btn btn-ghost btn-sm" :disabled="locked || !selected.length" @click="askDelete()">删除所选</button>
      </div>
      <p v-if="listError" class="code-block err" role="alert">{{ listError }}</p>
      <EmptyState v-if="loading" title="正在读取日志" :text="currentTab.tool" :busy="true" />
      <EmptyState
        v-else-if="!items.length && !listError"
        title="没有日志"
        :text="`${currentTab.tool} 返回 0 条`"
        icon="list"
      />
      <div v-else class="log-list">
        <div v-for="(item, i) in items" :key="item.id ?? item.contextId ?? item.name ?? i" class="log-entry">
          <input v-if="localDelete" v-model="selected" type="checkbox" :value="logId(item)" :aria-label="`选择日志 ${logId(item)}`" :disabled="locked || !logId(item) || (selected.length >= 100 && !selected.includes(logId(item)))" />
          <button type="button" class="log-row" :disabled="locked" @click="openDetail(item)">
            <span class="log-title">{{ itemTitle(item) }}</span>
            <span class="log-sub muted small">{{ itemSub(item) }}</span>
            <span class="detail-link">查看详情 <AppIcon name="chevron-right" :size="15" /></span>
          </button>
          <span v-if="isCaptureHttp(item)" class="muted small">抓包事务不能单删
            <button class="btn btn-ghost btn-sm" :disabled="locked" @click="switchTab('capture')">前往抓包页</button>
          </span>
          <button v-if="localDelete" class="btn btn-ghost btn-sm" type="button" :disabled="locked || !logId(item)" @click="askDelete(item)">删除</button>
        </div>
      </div>
      <div v-if="supportsPaging && (offset > 0 || hasMore)" class="pager">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="offset <= 0 || locked" @click="prevPage">
          <AppIcon name="chevron-left" :size="15" /><span>上一页</span>
        </button>
        <span class="muted small">偏移 {{ offset }}</span>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="!hasMore || locked" @click="nextPage">
          <span>下一页</span><AppIcon name="chevron-right" :size="15" />
        </button>
      </div>
    </div>

    <p v-if="!localDelete" class="muted small">当前非 Android 宿主，不支持删除日志</p>
    <p v-if="deleteMessage" role="status" class="code-block">{{ deleteMessage }}</p>
    <div v-if="pendingDelete" class="delete-overlay" @keydown.esc.stop.prevent="handleBack">
      <div role="dialog" aria-modal="true" aria-label="确认删除日志" class="section delete-dialog">
        <h3>删除 {{ pendingDelete.ids.length }} 条日志？</h3>
        <p>仅删除确认的已加载记录，此操作不可撤销。</p>
        <button class="btn btn-ghost" :disabled="deleting" @click="pendingDelete = null">取消</button>
        <button class="btn" :disabled="deleting" @click="confirmDelete">{{ deleting ? '正在删除' : '确认删除' }}</button>
      </div>
    </div>
    <div v-if="detailOpen" class="section log-detail" data-testid="log-detail">
      <button class="btn btn-ghost btn-sm" type="button" @click="handleBack">返回日志列表</button>
      <div class="section-head">
        <h3 class="section-title">详情</h3>
        <span class="section-sub">{{ detailTitle }}</span>
      </div>
      <EmptyState v-if="detailBusy" title="正在读取详情" text="真实工具" :busy="true" />
      <p v-if="detailError" class="code-block err">{{ detailError }}</p>
      <pre v-if="detail" class="code-block tall">{{ detail }}</pre>
      <EmptyState v-if="!detailBusy && !detailError && !detail" title="详情为空" text="工具未返回正文" />
      <button class="btn btn-ghost btn-sm" type="button" :disabled="detailBusy || !detail" @click="exportCurrent">导出详情</button>
    </div>
  </section>
</template>

<style scoped>
.delete-overlay { position: fixed; inset: 0; z-index: 80; display: grid; place-items: center; background: #0008; padding: 20px; }
.delete-dialog { width: min(100%, 420px); background: var(--surface); }
.log-entry { display: flex; align-items: center; gap: 8px; }
.log-entry .log-row { flex: 1; min-width: 0; }
.detail-link { display: flex; align-items: center; align-self: flex-end; gap: 4px; color: var(--accent); }
.log-detail .code-block { max-height: none; overflow-wrap: anywhere; white-space: pre-wrap; }
.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.filter-row .input {
  flex: 1;
  min-width: 0;
}

.log-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.log-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 9px 11px;
  text-align: left;
  border-radius: 12px;
  border: 1px solid var(--line);
  background: var(--surface);
}

.log-title {
  font-size: 12.5px;
  overflow-wrap: anywhere;
}

.log-sub {
  overflow-wrap: anywhere;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
}
</style>
