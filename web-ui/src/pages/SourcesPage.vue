<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useDetailNavigation } from '../lib/detail-navigation.js'
import AppIcon from '../components/Icon.vue'
import EmptyState from '../components/EmptyState.vue'
import SourceManagement from '../components/sourceManagement.vue'
import { callToolParsed, formatTime, prettyJson, redactForDisplay } from '../lib/tools.js'
import { core } from '../lib/core.js'
import { importText, sourceJsonSlices } from '../lib/files.js'
import { deliverSources } from '../lib/source-delivery.js'
import { groupSources, validateSourcePage, readSourceListResult } from '../lib/source-groups.js'
import ReaderImportDialog from '../components/ReaderImportDialog.vue'
import { useReaderImport } from '../lib/use-reader-import.js'

// 书源页：list_sources 分页摘要 + get_source 读取原文 + save_source 保存后 get_source 回读。
// 关键约束：
//  - 编辑与保存以用户原文为准，绝不用 Rust/JS 重新序列化的文本回写（>2^53 数字会丢精度）。
//  - Rust analyze/compare 只做校验与展示。
//  - conflict 是已保留新版本的回执，不是写入被拒；按 projectId 回读，不重复写。
const props = defineProps({ store: { type: Object, required: true } })
const s = props.store.state
const { pageRef, detailOpen, enterDetail, leaveDetail } = useDetailNavigation()

const PAGE = 20
const query = ref('')
const offset = ref(0)
const latestOnly = ref(false)
const items = ref([])
const total = ref(null)
const groups = computed(() => groupSources(items.value))
const visibleGroups = computed(() => groups.value.slice(offset.value, offset.value + PAGE))
const groupLimits = ref(new Map())
const groupRows = (group) => group.items.slice(0, groupLimits.value.get(group.domain) || 1)
const visibleRows = computed(() => visibleGroups.value.flatMap(groupRows))
const hasMore = computed(() => offset.value + PAGE < groups.value.length)
function toggleGroup(group) {
  groupLimits.value.set(group.domain, groupLimits.value.get(group.domain) ? 0 : PAGE)
}
function moreGroup(group) {
  groupLimits.value.set(group.domain, (groupLimits.value.get(group.domain) || PAGE) + PAGE)
}
let loadRevision = 0
let disposed = false
const loading = ref(false)
let openRevision = 0
const listError = ref('')

const editing = ref(null) // {projectId, bookSourceName, bookSourceUrl}
const draft = ref('')
const original = ref('')
const loadingSource = ref(false)
const saveBusy = ref(false)
const saveError = ref('')
const conflict = ref(null)
const analysis = ref(null)
const analysisError = ref('')
const readback = ref('')
const readbackDiff = ref(null)
const deleteBusy = ref(false)
const deleteError = ref('')
const selectedIds = ref([])
const selecting = ref(false)
const advancedOpen = ref(false)
const advancedVisited = ref(false)
watch(advancedOpen, (open) => { if (open) advancedVisited.value = true })
const moreId = ref(null)
const deliveryBusy = ref(false)
const deliveryError = ref('')
const readerImport = useReaderImport(props.store)
const { state: readerState, visible: readerVisible } = readerImport
const listManagement = ref(null)
const detailManagement = ref(null)
function cancelSelection() { selectedIds.value = []; selecting.value = false }
function stageLabel(stage) {
  return ({ DRAFT: '草稿', SAVED: '已保存', VALIDATED: '静态校验通过', TESTED: '已检测', FAILED: '检测失败', IMPORTED: '已导入', VERIFIED: '已验证' })[stage] || '状态待确认'
}
async function deliver(payload, action, packageName) {
  if (deliveryBusy.value || deleteBusy.value || saveBusy.value) return
  if (payload.projectIds && (!payload.projectIds.length || payload.projectIds.length > 500)) {
    deliveryError.value = '请选择 1–500 条书源；超过 500 条请使用导出全部或分享全部。'
    return
  }
  if (action === 'targets') return readerImport.start(payload)
  if (readerVisible.value) return
  deliveryBusy.value = true
  deliveryError.value = ''
  try {
    await deliverSources(props.store, payload, action, packageName)
  } catch (error) { deliveryError.value = error.message || '交付失败' }
  finally { deliveryBusy.value = false }
}
const allPageSelected = computed(() => visibleRows.value.length > 0 && visibleRows.value.every((row) => selectedIds.value.includes(row.projectId)))
function selectPage(event) {
  if (loading.value) return
  selectedIds.value = event.target.checked ? [...new Set([...selectedIds.value, ...visibleRows.value.map((row) => row.projectId)])] : selectedIds.value.filter((id) => !visibleRows.value.some((row) => row.projectId === id))
}
const importError = ref('')

const dirty = computed(() => editing.value !== null && draft.value !== original.value)
const coreReady = computed(() => s.coreStatus === 'loaded')

watch(dirty, (v) => props.store.markPageDirty(v))
onBeforeUnmount(() => { disposed = true; loadRevision += 1; openRevision += 1; props.store.markPageDirty(false) })
watch(editing, (value, previous) => { if (!value && previous) leaveDetail() })

onMounted(() => {
  load()
})

async function load(keepOffset = false) {
  if (disposed) return
  const revision = ++loadRevision
  const filter = { query: query.value.trim() || undefined, latestOnly: latestOnly.value || undefined }
  loading.value = true
  listError.value = ''
  try {
    const rows = []
    const seen = new Set()
    let expectedTotal = null
    let readOffset = 0
    while (true) {
      const parsed = await callToolParsed(props.store, 'list_sources', { limit: PAGE, offset: readOffset, ...filter })
      if (revision !== loadRevision || disposed) return
      if (parsed.threw) throw new Error(parsed.threw)
      if (parsed.isError) throw new Error(parsed.text || 'list_sources 返回错误')
      const data = await readSourceListResult(parsed, (args) => callToolParsed(props.store, 'read_result', args), () => revision === loadRevision && !disposed)
      if (revision !== loadRevision || disposed) return
      const page = validateSourcePage(data, { total: expectedTotal, offset: readOffset, seen, limit: PAGE })
      for (const row of page.items) { seen.add(row.projectId); rows.push(row) }
      expectedTotal = page.total
      readOffset = page.nextOffset
      if (!page.hasMore) break
    }
    if (rows.length !== expectedTotal) throw new Error('书源列表总数与完整读取数量不一致')
    items.value = rows
    total.value = expectedTotal
    offset.value = keepOffset ? Math.min(offset.value, Math.max(0, Math.ceil(groups.value.length / PAGE) - 1) * PAGE) : 0
  } catch (error) {
    if (revision !== loadRevision || disposed) return
    items.value = []
    total.value = null
    listError.value = (error && error.message) || '书源列表读取失败'
  } finally {
    if (revision === loadRevision && !disposed) loading.value = false
  }
}

function nextPage() {
  if (hasMore.value && !loading.value) offset.value += PAGE
}

function prevPage() {
  if (!loading.value) offset.value = Math.max(0, offset.value - PAGE)
}

async function open(row) {
  if (deleteBusy.value) return
  if (dirty.value) {
    props.store.confirmDialog({
      kind: 'discard',
      title: '放弃未保存的修改？',
      message: '当前书源还没保存，切换会丢失编辑内容。',
      confirmLabel: '放弃并切换',
      danger: true,
      onAccept: () => { original.value = draft.value; return open(row) }
    })
    return
  }
  const revision = ++openRevision
  enterDetail()
  loadingSource.value = true
  saveError.value = ''
  analysis.value = null
  analysisError.value = ''
  readback.value = ''
  readbackDiff.value = null
  try {
    const parsed = await callToolParsed(props.store, 'get_source', { key: row.projectId })
    if (revision !== openRevision) return
    if (parsed.threw) throw new Error(parsed.threw)
    if (parsed.isError) throw new Error(parsed.text || 'get_source 返回错误')
    editing.value = { projectId: row.projectId, name: row.bookSourceName, url: row.bookSourceUrl }
    // 原文优先：工具回的是 JSON 文本，直接作为编辑内容，不做任何重排。
    draft.value = parsed.raw
    original.value = parsed.raw
    props.store.markPageDirty(false)
  } catch (error) {
    if (revision !== openRevision) return
    saveError.value = (error && error.message) || '读取书源失败'
  } finally {
    if (revision === openRevision) loadingSource.value = false
  }
}

function handleBack() {
  if (readerImport.handleBack() || detailManagement.value?.handleBack?.() || listManagement.value?.handleBack?.()) return true
  if (!detailOpen.value) return false
  closeEditor()
  return true
}
defineExpose({ handleBack })

function closeEditor() {
  if (saveBusy.value) return
  openRevision += 1
  loadingSource.value = false
  if (deleteBusy.value) return
  if (dirty.value) {
    props.store.confirmDialog({
      kind: 'discard',
      title: '放弃未保存的修改？',
      message: '关闭后编辑内容不会保留。',
      confirmLabel: '放弃修改',
      danger: true,
      onAccept: () => {
        editing.value = null
        draft.value = ''
        original.value = ''
        props.store.markPageDirty(false)
      }
    })
    return
  }
  editing.value = null
  leaveDetail()
  props.store.markPageDirty(false)
}

function analyze() {
  analysisError.value = ''
  analysis.value = null
  if (!coreReady.value) {
    analysisError.value = `Rust 核不可用：${s.coreError || 'wasm 未加载'}`
    return
  }
  try {
    analysis.value = core.analyze(draft.value)
  } catch (error) {
    analysisError.value = (error && error.message) || 'Rust 分析失败'
  }
}

function compareWithOriginal() {
  analysisError.value = ''
  readbackDiff.value = null
  if (!coreReady.value) {
    analysisError.value = `Rust 核不可用：${s.coreError || 'wasm 未加载'}`
    return
  }
  try {
    readbackDiff.value = core.compare(original.value, draft.value)
  } catch (error) {
    analysisError.value = (error && error.message) || 'Rust 对比失败'
  }
}

function validateJson() {
  try {
    const v = JSON.parse(draft.value)
    if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error('书源必须是 JSON 对象')
    return v
  } catch (error) {
    throw new Error(`JSON 不合法：${(error && error.message) || '解析失败'}`)
  }
}

async function save() {
  if (deleteBusy.value) return
  saveError.value = ''
  conflict.value = null
  readback.value = ''
  let parsedSource
  try {
    parsedSource = validateJson()
  } catch (error) {
    saveError.value = error.message
    return
  }
  saveBusy.value = true
  try {
    const payload = { source: draft.value }
    const parsed = await callToolParsed(props.store, 'save_source', payload)
    if (parsed.threw) throw new Error(parsed.threw)
    if (parsed.isError) throw new Error(parsed.text || 'save_source 返回错误')

    // conflict 回执已经写入新版本，不再次调用 save_source。
    const data = parsed.data
    const receipt = data && (data.conflict || data.conflictId || data.conflictVersion ? data : data.result && data.result.conflict ? data.result : null)
    if (receipt) {
      conflict.value = { receipt }
    }
    await afterSave(parsedSource, receipt || data)
  } catch (error) {
    saveError.value = (error && error.message) || '保存失败'
  } finally {
    saveBusy.value = false
  }
}

/** 保存后 get_source 回读：确认宿主真的写入了，并把原文作为新的基线。 */
async function afterSave(savedSource, receipt) {
  const key = receipt && receipt.projectId
  const url = (savedSource && savedSource.bookSourceUrl) || (editing.value && editing.value.url)
  if (!key && !url) throw new Error('保存回执缺少 projectId，无法确认目标')
  const parsed = await callToolParsed(props.store, 'get_source', key ? { key } : { bookSourceUrl: url })
  if (parsed.threw || parsed.isError) {
    readback.value = ''
    saveError.value = `已提交保存，但回读失败：${parsed.threw || parsed.text || '未知原因'}`
    props.store.toast('保存已提交，回读失败', 'err')
    return
  }
  readback.value = parsed.raw
  if (key) editing.value = { ...editing.value, projectId: key, url, name: savedSource?.bookSourceName || editing.value?.name }
  original.value = parsed.raw
  draft.value = parsed.raw
  props.store.markPageDirty(false)
  props.store.toast('已保存并回读')
  load(true)
}


async function doImport() {
  if (deleteBusy.value) return
  importError.value = ''
  let picked
  try {
    picked = await importText(props.store)
  } catch (error) {
    importError.value = (error && error.message) || '导入失败'
    return
  }
  if (!picked || picked.cancelled) return
  try {
    const text = picked.text.trim()
    // 支持单个书源对象或书源数组；数组逐条保存，任一失败即停止并如实报告。
    const list = sourceJsonSlices(text)
    let okCount = 0
    const failures = []
    for (const src of list) {
      const r = await callToolParsed(props.store, 'save_source', { source: src })
      if (r.threw || r.isError) failures.push(`${JSON.parse(src).bookSourceName || '未命名'}：${r.threw || r.text}`)
      else okCount += 1
    }
    if (failures.length) {
      importError.value = `导入 ${okCount} 条成功，${failures.length} 条失败：\n${failures.join('\n')}`
    } else {
      props.store.toast(`已导入 ${okCount} 条书源`)
    }
    load()
  } catch (error) {
    importError.value = `导入文件不是合法 JSON：${(error && error.message) || ''}`
  }
}

function askDelete(row) {
  askDeleteIds([row.projectId])
}

function askDeleteIds(ids) {
  if (!ids.length || deleteBusy.value || loading.value || loadingSource.value || saveBusy.value) return
  const targets = [...new Set(ids)]
  if (dirty.value) {
    props.store.confirmDialog({
      kind: 'discard', title: '放弃未保存的修改？', danger: true, confirmLabel: '放弃并继续',
      message: '继续删除前将丢弃当前未保存的编辑；之后仍需确认删除。',
      onAccept: () => { draft.value = original.value; askDeleteIds(targets) }
    })
    return
  }
  props.store.confirmDialog({
    kind: 'delete', title: '删除书源？', danger: true, confirmLabel: '确认删除',
    message: `删除选中的 ${targets.length} 条书源项目：${targets.join('、')}，不可恢复；不删除阅读端已导入的书源。`,
    onAccept: () => deleteSources(targets)
  })
}

async function deleteSources(ids) {
  if (deleteBusy.value) return
  if (dirty.value) { askDeleteIds(ids); return }
  deleteBusy.value = true
  deleteError.value = ''
  const receipt = await callToolParsed(props.store, 'delete_projects', { ids })
  const match = /^已删除 (\d+) 个项目$/.exec(receipt.text)
  try {
    // 无过滤、无版本折叠地遍历摘要，不能把当前页未出现当成已删除。
    const remaining = new Set()
    let readOffset = 0
    let expectedTotal = null
    while (true) {
      const r = await callToolParsed(props.store, 'list_sources', { limit: PAGE, offset: readOffset, latestOnly: false })
      r.data = await readSourceListResult(r, (args) => callToolParsed(props.store, 'read_result', args), () => !disposed)
      if (disposed) return
      if (r.threw || r.isError || !Array.isArray(r.data?.items) || typeof r.data.hasMore !== 'boolean') {
        throw new Error(r.threw || r.text || '列表回读格式错误')
      }
      if (!Number.isInteger(r.data.total) || r.data.total < 0 || (expectedTotal !== null && expectedTotal !== r.data.total)) {
        throw new Error('列表总数无效或回读期间发生变化，请刷新后核对')
      }
      expectedTotal = r.data.total
      for (const row of r.data.items) {
        if (typeof row.projectId !== 'string' || !row.projectId || remaining.has(row.projectId)) {
          throw new Error('列表分页重复或项目 ID 无效，无法确认删除')
        }
        remaining.add(row.projectId)
      }
      if (!r.data.hasMore) {
        if (remaining.size !== expectedTotal) throw new Error('列表回读总数不一致，无法确认删除')
        break
      }
      if (remaining.size >= expectedTotal) throw new Error('列表分页与总数不一致')
      if (!r.data.items.length) throw new Error('列表分页未前进')
      readOffset += r.data.items.length
    }
    const absent = ids.filter((id) => !remaining.has(id))
    selectedIds.value = selectedIds.value.filter((id) => !absent.includes(id))
    if (absent.includes(editing.value?.projectId)) { editing.value = null; draft.value = ''; original.value = ''; props.store.markPageDirty(false) }
    await load(true)
    while (!listError.value && !items.value.length && offset.value > 0) {
      offset.value = Math.max(0, offset.value - PAGE)
      await load(true)
    }
    if (listError.value) throw new Error(`列表刷新失败：${listError.value}`)
    if (receipt.threw || receipt.isError || !match || Number(match[1]) !== ids.length || ids.some((id) => remaining.has(id))) {
      throw new Error(`${receipt.threw || receipt.text || '删除结果无法确认'}；请求 ${ids.length} 条，回读已不存在 ${absent.length} 条，仍存在 ${ids.length - absent.length} 条。未确认条目保留选择。`)
    }
    props.store.toast(`已删除 ${ids.length} 条书源并回读确认`)
  } catch (error) {
    deleteError.value = `删除未完全确认：${error.message}`
    props.store.toast(deleteError.value, 'err')
  } finally {
    deleteBusy.value = false
  }
}

function askArchive(row) {
  if (deleteBusy.value) return
  props.store.confirmDialog({
    kind: 'delete',
    title: '清理旧版本？',
    name: row.bookSourceName,
    message: `将按 bookSourceUrl 精确匹配删除旧版本，只保留最新 1 条。先 dryRun 预览再执行。`,
    confirmLabel: '先预览',
    danger: true,
    onAccept: () => archive(row, true)
  })
}

async function archive(row, dryRun) {
  const parsed = await callToolParsed(props.store, 'archive_source_versions', {
    bookSourceUrl: row.bookSourceUrl,
    keepLatest: 1,
    dryRun
  })
  if (parsed.threw || parsed.isError) {
    props.store.toast(parsed.threw || parsed.text || '清理失败', 'err')
    return
  }
  if (dryRun) {
    props.store.confirmDialog({
      kind: 'delete',
      title: '确认删除旧版本',
      message: `预览结果：\n${prettyJson(redactForDisplay(parsed.data))}`,
      confirmLabel: '确认删除',
      danger: true,
      onAccept: () => archive(row, false)
    })
  } else {
    props.store.toast('已清理旧版本')
    load(true)
  }
}

const analysisText = computed(() => (analysis.value ? prettyJson(analysis.value) : ''))
const diffText = computed(() => (readbackDiff.value ? prettyJson(readbackDiff.value) : ''))
</script>

<template>
  <section ref="pageRef" class="page sources-page">
    <div v-if="detailOpen" class="detail-navigation">
      <button data-detail-back class="btn btn-ghost" type="button" @click="handleBack"><AppIcon name="chevron-left" :size="16" /><span>返回列表</span></button>
    </div>
    <p v-if="detailOpen && deleteBusy" role="status">正在删除并回读校验，请勿重复操作…</p>
    <p v-if="detailOpen && deleteError" class="code-block err" role="alert">{{ deleteError }}</p>
    <fieldset class="source-controls" :disabled="deleteBusy" :aria-busy="deleteBusy">
    <div v-show="!detailOpen" class="section">
      <div class="section-head">
        <h3 class="section-title">书源</h3>
        <span class="section-sub">
          {{ groups.length }} 个站点 · {{ total ?? 0 }} 条记录
        </span>
      </div>
      <div class="source-search">
      <div class="search">
        <AppIcon name="search" :size="16" class="search-ico" />
        <input
          v-model="query"
          type="search"
          class="input"
          placeholder="名称或 URL"
          aria-label="过滤书源"
          @keyup.enter="load()"
        />
      </div>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="loading" @click="load()" aria-label="刷新书源">
          <AppIcon name="refresh" :size="15" /><span>刷新</span>
        </button>
      </div>
      <div class="source-toolbar">
        <button class="btn btn-ghost btn-sm" type="button" @click="doImport">
          <span>导入</span>
        </button>
        <button data-export-all class="btn btn-ghost btn-sm" type="button" :disabled="deliveryBusy" @click="deliver({ scope: 'all' }, 'save')">导出全部</button>
        <button data-share-all class="btn btn-ghost btn-sm" type="button" :disabled="deliveryBusy" @click="deliver({ scope: 'all' }, 'share')">分享全部</button>
        <button data-selection-toggle class="btn btn-ghost btn-sm" type="button" :aria-pressed="selecting" @click="selecting ? cancelSelection() : selecting = true">管理</button>
      </div>
      <div class="source-advanced">
        <button data-advanced-toggle class="btn btn-ghost btn-sm" type="button" :aria-expanded="advancedOpen" @click="advancedOpen = !advancedOpen">高级分析与过滤 <span aria-hidden="true">{{ advancedOpen ? '−' : '+' }}</span></button>
        <div v-if="advancedVisited" v-show="advancedOpen">
          <button class="btn btn-ghost btn-sm" type="button" :aria-pressed="latestOnly" @click="latestOnly = !latestOnly; load()">{{ latestOnly ? '只看最新版本' : '列出全部版本' }}</button>
          <p class="hint">全部导出 / 分享始终包含全库所有版本，不受搜索、当前页和下方过滤影响。</p>
          <SourceManagement v-if="!detailOpen" ref="listManagement" :store="store" @changed="load()" />
        </div>
      </div>
      <div v-if="selecting" class="actions source-selection">
        <label><input type="checkbox" aria-label="全选当前页" :disabled="loading" :checked="allPageSelected" :indeterminate="!allPageSelected && visibleRows.some((row) => selectedIds.includes(row.projectId))" @change="selectPage" />全选当前页（仅已显示记录）</label>
        <span>已选 {{ selectedIds.length }} 条</span>
        <button class="btn btn-ghost btn-sm" type="button" @click="cancelSelection">取消选择</button>
        <p class="hint selection-scope">跨页保留选择；全选只增加当前页已显示记录，不含折叠历史。取消选择会清空全部已选。</p>
        <button data-export-selected class="btn btn-ghost btn-sm" type="button" :disabled="!selectedIds.length || deliveryBusy" @click="deliver({ projectIds: [...selectedIds] }, 'save')">导出选中</button>
        <button data-share-selected class="btn btn-ghost btn-sm" type="button" :disabled="!selectedIds.length || deliveryBusy" @click="deliver({ projectIds: [...selectedIds] }, 'share')">分享选中</button>
        <button class="btn btn-ghost danger" type="button" :disabled="!selectedIds.length || loading || loadingSource || saveBusy" @click="askDeleteIds(selectedIds)">删除选中</button>
      </div>
      <p v-if="deliveryError" class="code-block err" role="alert">{{ deliveryError }}</p>

      <p v-if="importError" class="code-block err">{{ importError }}</p>
      <p v-if="deleteBusy" role="status">正在删除并回读校验，请勿重复操作…</p>
      <p v-if="deleteError" class="code-block err" role="alert">{{ deleteError }}</p>
      <p v-if="listError" data-list-error class="code-block err" role="alert">{{ listError }}</p>
      <EmptyState v-if="loading" title="正在读取书源" text="来自宿主项目库" :busy="true" />
      <EmptyState
        v-else-if="!items.length && !listError"
        title="没有书源"
        text="项目库里还没有书源，可以导入或先用 MCP 工具创建"
        icon="book"
      />
      <div v-else class="source-list">
        <section v-for="group in visibleGroups" :key="group.domain" class="source-group" :data-source-domain="group.domain">
        <header class="source-group-header">
          <strong class="source-group-domain">{{ group.domain }}</strong>
          <span class="muted small">{{ group.total }} 条记录 · 最近保存 {{ formatTime(group.latest.updatedAt) }}</span>
          <button v-if="group.total > 1" data-source-group-toggle class="btn btn-ghost btn-sm" type="button" :aria-expanded="Boolean(groupLimits.get(group.domain))" @click="toggleGroup(group)">{{ groupLimits.get(group.domain) ? '收起历史记录' : `展开历史记录（${group.total}条）` }}</button>
          <span v-if="groupLimits.get(group.domain)" class="muted small">已显示 {{ groupRows(group).length }} / {{ group.total }} 条</span>
        </header>
        <article v-for="row in groupRows(group)" :key="row.projectId" class="source-entry" :data-project-id="row.projectId" :class="{ selecting }">
        <label v-if="selecting" class="source-pick"><input v-model="selectedIds" type="checkbox" :value="row.projectId" :aria-label="`选择书源 ${row.bookSourceName || '未命名'}（${row.projectId}）`" /></label>
        <button
          type="button"
          class="source-row"
          :class="{ on: editing && editing.projectId === row.projectId }"
          @click="open(row)"
        >
          <span class="source-name">{{ row.bookSourceName || '（未命名）' }}</span>
          <span class="source-url mono">{{ row.bookSourceUrl }}</span>
          <span class="source-meta">
            <span v-if="row.stage" class="source-stage">{{ stageLabel(row.stage) }}</span>
            <span v-if="row.projectId === group.latest.projectId">最新保存</span>
            <span v-else>历史记录</span>
            <span class="muted small">保存时间 {{ formatTime(row.updatedAt) }}</span>
            <span class="source-project-id mono">项目 ID：{{ row.projectId }}</span>
          </span>
        </button>
        <div class="source-row-actions">
          <button data-source-action="copy" class="btn btn-ghost" type="button" :disabled="deliveryBusy" @click="deliver({ projectIds: [row.projectId] }, 'copy')">复制</button>
          <button data-source-action="save" class="btn btn-ghost" type="button" :disabled="deliveryBusy" @click="deliver({ projectIds: [row.projectId] }, 'save')">导出</button>
          <button data-source-action="share" class="btn btn-ghost" type="button" :disabled="deliveryBusy" @click="deliver({ projectIds: [row.projectId] }, 'share')">分享</button>
          <button data-source-more class="btn btn-ghost" type="button" :aria-expanded="moreId === row.projectId" @click="moreId = moreId === row.projectId ? null : row.projectId">更多</button>
        </div>
        <div v-if="moreId === row.projectId" class="source-row-more">
          <button data-reader-import class="btn btn-ghost" type="button" :disabled="deliveryBusy || readerVisible" @click="deliver({ projectIds: [row.projectId] }, 'targets')">导入阅读</button>
          <button class="btn btn-ghost danger" type="button" :disabled="loading || loadingSource || saveBusy || deliveryBusy" @click="askDelete(row)">删除书源</button>
        </div>
        </article>
        <button v-if="groupLimits.get(group.domain) && groupRows(group).length < group.total" data-group-more class="btn btn-ghost btn-sm" type="button" @click="moreGroup(group)">再显示 {{ Math.min(PAGE, group.total - groupRows(group).length) }} 条历史记录</button>
        </section>
      </div>
      <div class="pager">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="offset <= 0 || loading" @click="prevPage">
          <AppIcon name="chevron-left" :size="15" /><span>上一页</span>
        </button>
        <span class="muted small">站点偏移 {{ offset }} · 每页 20 个站点</span>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="!hasMore || loading" @click="nextPage">
          <span>下一页</span><AppIcon name="chevron-right" :size="15" />
        </button>
      </div>
    </div>

    <p v-if="detailOpen && !editing && saveError" class="code-block err" role="alert">{{ saveError }}</p>
    <div v-if="loadingSource" class="section">
      <EmptyState title="正在读取书源原文" text="get_source" :busy="true" />
    </div>

    <div v-if="editing" class="section">
      <div class="section-head">
        <h3 class="section-title">{{ editing.name || editing.projectId }}</h3>
        <span class="section-sub">{{ dirty ? '有未保存修改' : '与宿主一致' }}</span>
      </div>
      <p class="hint mono">{{ editing.url }}</p>
      <SourceManagement ref="detailManagement" :store="store" :source="draft" />
      <label class="field">
        <span class="flabel">书源 JSON（原文）</span>
        <textarea
          v-model="draft"
          class="textarea code-area"
          rows="16"
          spellcheck="false"
          aria-label="书源 JSON"
          @input="props.store.markPageDirty(true)"
        ></textarea>
      </label>
      <p class="fhint">保存时原样提交你的 JSON 文本，不做重新序列化，避免大整数精度丢失。</p>

      <div class="actions">
        <button class="btn btn-primary" type="button" :disabled="saveBusy || !s.runtimeConnected" @click="save">
          <span v-if="saveBusy" class="spin"></span>
          <AppIcon v-else name="check" :size="16" />
          <span>保存并回读</span>
        </button>
        <button class="btn btn-ghost" type="button" @click="analyze">
          <AppIcon name="sparkles" :size="16" /><span>Rust 校验</span>
        </button>
        <button class="btn btn-ghost" type="button" @click="compareWithOriginal">
          <AppIcon name="edit" :size="16" /><span>与原文对比</span>
        </button>

        <button class="btn btn-ghost danger" type="button" :disabled="saveBusy" @click="askDeleteIds([editing.projectId])">删除书源</button>
        <button class="btn btn-ghost" type="button" @click="closeEditor">
          <AppIcon name="x" :size="16" /><span>关闭</span>
        </button>
      </div>

      <p v-if="!coreReady" class="hint">Rust 核不可用（{{ s.coreError || 'wasm 未加载' }}）：校验/对比按钮会明确报错，不会用 JS 假替代。</p>
      <p v-if="saveError" class="code-block err">{{ saveError }}</p>
      <p v-if="analysisError" class="code-block err">{{ analysisError }}</p>

      <div v-if="conflict" class="banner err-banner">
        <strong>检测到版本冲突</strong>
        <p>已保留为新版本，旧版本没有被覆盖；下方是实际保存回执。</p>
        <pre class="code-block">{{ prettyJson(redactForDisplay(conflict.receipt)) }}</pre>
        <div class="actions">
          <button class="btn btn-ghost" type="button" @click="conflict = null">知道了</button>
        </div>
      </div>

      <div v-if="analysisText" class="section">
        <div class="section-head">
          <h3 class="section-title">Rust 校验结果</h3>
          <span class="section-sub">{{ analysis && analysis.valid ? '通过' : '有问题' }}</span>
        </div>
        <pre class="code-block">{{ analysisText }}</pre>
      </div>
      <div v-if="diffText" class="section">
        <div class="section-head">
          <h3 class="section-title">与原文差异</h3>
          <span class="section-sub">{{ readbackDiff && readbackDiff.equal ? '无变化' : '已修改' }}</span>
        </div>
        <pre class="code-block">{{ diffText }}</pre>
      </div>
      <div v-if="readback" class="section">
        <div class="section-head">
          <h3 class="section-title">回读内容</h3>
          <span class="section-sub">get_source</span>
        </div>
        <pre class="code-block tall">{{ readback }}</pre>
      </div>

      <div class="actions">
        <button class="btn btn-ghost danger" type="button" @click="askArchive({ bookSourceName: editing.name, bookSourceUrl: editing.url })">
          <AppIcon name="trash" :size="16" /><span>清理旧版本</span>
        </button>
      </div>
    </div>
    </fieldset>
    <ReaderImportDialog v-if="readerVisible" :state="readerState" @pick="readerImport.launch" @cancel="readerImport.cancel" @retry="readerImport.retry" />
  </section>
</template>

<style scoped>
/* Group presentation only; reuse existing action paint/hit-area styles. */
.source-group { min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.source-group-header { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; padding: 6px 0; }
.source-group-domain { flex-basis: 100%; overflow-wrap: anywhere; }
.source-group-header > span { overflow-wrap: anywhere; min-width: 0; }
.source-project-id { flex-basis: 100%; white-space: normal; overflow-wrap: anywhere; }
/* Page-local overrides intentionally outrank shell's old two-column entry layout. */
.sources-page .source-entry {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 0;
  min-width: 0;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  overflow: hidden;
}
.sources-page .source-entry.selecting { grid-template-columns: 44px minmax(0, 1fr); }
.sources-page .source-entry > .source-row { grid-column: 1 / -1; border: 0; border-radius: 0; background: transparent; }
.sources-page .source-entry.selecting > .source-row { grid-column: 2; }
.source-pick { grid-column: 1; grid-row: 1; min-height: 44px; display: flex; justify-content: center; align-items: center; align-self: stretch; }
.source-pick input { width: 22px; height: 22px; accent-color: var(--c1); }
.source-row-actions { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 4px; border-top: 1px solid var(--line); padding: 0 4px; }
.source-row-more { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: 4px; padding: 4px 8px; border-top: 1px solid var(--line); }
.sources-page .btn { min-height: 44px; height: auto; min-width: 44px; white-space: normal; padding: 6px 8px; }
.source-row-actions .btn { border: 0; border-radius: 8px; background: transparent; font-size: 12px; }
.source-search { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 4px; align-items: center; }
.source-search .search { min-width: 0; margin: 0; }
.source-search .input { min-height: 44px; }
.source-toolbar { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.source-toolbar .btn { flex: 1 1 auto; padding-inline: 6px; font-size: 12px; white-space: nowrap; }
.source-advanced { margin: 2px 0 6px; }
.source-advanced > .btn { font-size: 12px; color: var(--muted); }
.source-selection { margin: 8px 0; padding: 8px; border: 1px solid var(--line); border-radius: 10px; }
.source-selection label { min-height: 44px; }
.selection-scope { flex-basis: 100%; margin: 0; }
.source-stage { color: var(--muted); }
.source-meta { color: var(--muted); font-size: 11px; }
/* Keep Back in document flow; sticky rows occlude short IME viewports. */
.detail-navigation { padding: 8px 0; background: var(--surface); }
.source-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.source-row {
  display: grid;
  /* Metadata must never reserve a column at the expense of the source name. */
  grid-template-columns: minmax(0, 1fr);
  grid-template-areas: 'name' 'url' 'meta';
  min-width: 0;
  gap: 4px;
  padding: 10px 12px;
  text-align: left;
  border-radius: 14px;
  border: 1px solid var(--line);
  background: var(--surface);
}

.source-row.on {
  border-color: color-mix(in srgb, var(--c1) 45%, var(--line));
  background: color-mix(in srgb, var(--c1) 9%, transparent);
}

.source-name {
  grid-area: name;
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 13.5px;
  font-weight: 700;
}

.source-url {
  grid-area: url;
  font-size: 11.5px;
  color: var(--muted);
  overflow-wrap: anywhere;
}

.source-meta {
  grid-area: meta;
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
}

.source-meta > * {
  min-width: 0;
  max-width: 100%;
  overflow-wrap: anywhere;
}

.source-meta .chip {
  height: auto;
  min-height: 20px;
  white-space: normal;
}

.pager {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
}

.code-area {
  font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  resize: vertical;
  min-height: 200px;
}

.err-banner {
  border-color: color-mix(in srgb, var(--bad) 40%, var(--line));
  background: color-mix(in srgb, var(--bad) 8%, transparent);
}
</style>
