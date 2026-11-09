<script setup>
import { computed, onMounted, ref } from 'vue'
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
const includeCapture = ref(false)

const detail = ref(null)
const detailTitle = ref('')
const detailBusy = ref(false)
const detailError = ref('')

const currentTab = computed(() => TABS.find((t) => t.key === tab.value) || TABS[0])
const supportsPaging = computed(() => ['http', 'capture', 'operation'].includes(tab.value))

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

async function load(reset = true) {
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
    items.value = itemsOf(parsed)
    const t = parsed.data && Number.isFinite(Number(parsed.data.total)) ? Number(parsed.data.total) : null
    total.value = t
    hasMore.value = t !== null ? offset.value + items.value.length < t : items.value.length >= PAGE
  } catch (error) {
    items.value = []
    total.value = null
    hasMore.value = false
    listError.value = (error && error.message) || '日志读取失败'
  } finally {
    loading.value = false
  }
}

function switchTab(key) {
  if (tab.value === key) return
  tab.value = key
  query.value = ''
  level.value = ''
  category.value = ''
  date.value = ''
  includeCapture.value = false
  load(true)
}

function nextPage() {
  offset.value += PAGE
  load(false)
}

function prevPage() {
  offset.value = Math.max(0, offset.value - PAGE)
  load(false)
}

async function openDetail(item) {
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
    if (parsed.threw) throw new Error(parsed.threw)
    if (parsed.isError) throw new Error(parsed.text || `${tool} 返回错误`)
    detail.value = parsed.data ? prettyJson(redactForDisplay(parsed.data)) : parsed.text
  } catch (error) {
    detailError.value = (error && error.message) || '读取详情失败'
  } finally {
    detailBusy.value = false
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
  <section class="page">
    <div class="section">
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
          :class="{ on: tab === t.key }"
          @click="switchTab(t.key)"
        >
          {{ t.label }}
        </button>
      </div>

      <div v-if="tab === 'operation' || tab === 'http'" class="search">
        <AppIcon name="search" :size="16" class="search-ico" />
        <input
          v-model="query"
          type="search"
          class="input"
          :placeholder="tab === 'http' ? '按 URL 过滤' : '匹配 message / detail'"
          aria-label="过滤日志"
          @keyup.enter="load(true)"
        />
      </div>
      <div v-if="tab === 'operation'" class="filter-row">
        <select v-model="level" class="input" aria-label="级别" @change="load(true)">
          <option value="">全部级别</option>
          <option value="I">I</option>
          <option value="W">W</option>
          <option value="E">E</option>
        </select>
        <input v-model="category" class="input" placeholder="category（如 tool/mcp）" aria-label="分类" @keyup.enter="load(true)" />
      </div>
      <div v-if="tab === 'http'" class="filter-row">
        <input v-model="date" class="input" type="date" aria-label="日期" @change="load(true)" />
        <button class="btn btn-ghost btn-sm" type="button" :class="{ on: includeCapture }" @click="includeCapture = !includeCapture; load(true)">
          {{ includeCapture ? '含抓包事务' : '排除抓包事务' }}
        </button>
      </div>

      <div class="actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="loading" @click="load(true)">
          <AppIcon name="refresh" :size="15" /><span>刷新</span>
        </button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="!items.length && !detail" @click="exportCurrent">
          <AppIcon name="download" :size="15" /><span>导出</span>
        </button>
      </div>

      <p v-if="listError" class="code-block err">{{ listError }}</p>
      <EmptyState v-if="loading" title="正在读取日志" :text="currentTab.tool" :busy="true" />
      <EmptyState
        v-else-if="!items.length && !listError"
        title="没有日志"
        :text="`${currentTab.tool} 返回 0 条`"
        icon="list"
      />
      <div v-else class="log-list">
        <button v-for="(item, i) in items" :key="item.id ?? item.contextId ?? item.name ?? i" type="button" class="log-row" @click="openDetail(item)">
          <span class="log-title">{{ itemTitle(item) }}</span>
          <span class="log-sub muted small">{{ itemSub(item) }}</span>
        </button>
      </div>
      <div v-if="supportsPaging && (offset > 0 || hasMore)" class="pager">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="offset <= 0 || loading" @click="prevPage">
          <AppIcon name="chevron-left" :size="15" /><span>上一页</span>
        </button>
        <span class="muted small">偏移 {{ offset }}</span>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="!hasMore || loading" @click="nextPage">
          <span>下一页</span><AppIcon name="chevron-right" :size="15" />
        </button>
      </div>
    </div>

    <div v-if="detailBusy || detail || detailError" class="section">
      <div class="section-head">
        <h3 class="section-title">详情</h3>
        <span class="section-sub">{{ detailTitle }}</span>
      </div>
      <EmptyState v-if="detailBusy" title="正在读取详情" text="真实工具" :busy="true" />
      <p v-if="detailError" class="code-block err">{{ detailError }}</p>
      <pre v-if="detail" class="code-block tall">{{ detail }}</pre>
    </div>
  </section>
</template>

<style scoped>
.filter-row {
  display: flex;
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
