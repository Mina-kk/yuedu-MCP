<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useDetailNavigation } from '../lib/detail-navigation.js'
import AppIcon from '../components/Icon.vue'
import EmptyState from '../components/EmptyState.vue'
import { callToolParsed, itemsOf, prettyJson } from '../lib/tools.js'

// 技能页：list_skills（{id,name,builtIn,enabled}）→ get_skill(id) 读 Markdown；
// 自定义技能 save_skill(id, markdown) / delete_skill(id) 都要明确确认；内置技能只读。
// 知识库：search_knowledge(query)、read_knowledge(domain, mode) 折叠展示。
const props = defineProps({ store: { type: Object, required: true } })
const s = props.store.state
const { pageRef, detailOpen, enterDetail, leaveDetail } = useDetailNavigation()

const tab = ref('builtin')
const skills = ref([])
const loading = ref(false)
let openRevision = 0
const listError = ref('')

const current = ref(null) // {id,name,builtIn}
const markdown = ref('')
const original = ref('')
const loadingSkill = ref(false)
const saveBusy = ref(false)
const skillError = ref('')

const creating = ref(false)
const newId = ref('')
const newName = ref('')

const knowledgeQuery = ref('')
const knowledgeBusy = ref(false)
const knowledgeResult = ref('')
const knowledgeError = ref('')

const domain = ref('')
const mode = ref('')
const refBusy = ref(false)
const refResult = ref('')
const refError = ref('')

const dirty = computed(() => current.value !== null && markdown.value !== original.value)

watch(dirty, (v) => props.store.markPageDirty(v && !(current.value && current.value.builtIn)))
onBeforeUnmount(() => { openRevision += 1; props.store.markPageDirty(false) })
watch(current, (value, previous) => { if (!value && previous) leaveDetail() })

const filtered = computed(() => {
  const want = tab.value === 'builtin'
  return skills.value.filter((k) => Boolean(k.builtIn) === want)
})

onMounted(() => load())

async function load() {
  loading.value = true
  listError.value = ''
  try {
    const parsed = await callToolParsed(props.store, 'list_skills', {})
    if (parsed.threw) throw new Error(parsed.threw)
    if (parsed.isError) throw new Error(parsed.text || 'list_skills 返回错误')
    skills.value = itemsOf(parsed, 'skills')
  } catch (error) {
    skills.value = []
    listError.value = (error && error.message) || '技能列表读取失败'
  } finally {
    loading.value = false
  }
}

async function open(skill) {
  if (dirty.value) {
    props.store.confirmDialog({
      kind: 'discard',
      title: '放弃未保存的修改？',
      message: '当前技能还没保存。',
      confirmLabel: '放弃并切换',
      danger: true,
      onAccept: () => { original.value = markdown.value; return open(skill) }
    })
    return
  }
  const revision = ++openRevision
  enterDetail()
  loadingSkill.value = true
  skillError.value = ''
  try {
    const result = await props.store.bridge.call('skills.read', { id: skill.id })
    if (revision !== openRevision) return
    if (result?.id !== skill.id || typeof result.markdown !== 'string' || typeof result.enabled !== 'boolean') throw new Error('技能管理回执无效')
    current.value = { ...result, builtIn: Boolean(result.builtIn) }
    markdown.value = result.markdown
    original.value = result.markdown
    props.store.markPageDirty(false)
  } catch (error) {
    if (revision !== openRevision) return
    skillError.value = (error && error.message) || '读取技能失败'
  } finally {
    if (revision === openRevision) loadingSkill.value = false
  }
}

async function importSkill() {
  if (saveBusy.value) return
  saveBusy.value = true
  listError.value = ''
  try {
    const result = await props.store.bridge.call('skills.import', {})
    if (result?.cancelled === true) return
    if (result?.imported !== true || !result.skill?.id) throw new Error('技能导入回执无效')
    tab.value = 'custom'
    await load()
    props.store.toast('技能包已导入')
  } catch (error) { listError.value = error.message || '导入失败' }
  finally { saveBusy.value = false }
}

async function toggleEnabled() {
  if (saveBusy.value || !current.value) return
  saveBusy.value = true
  skillError.value = ''
  const { id, enabled } = current.value
  try {
    const result = await props.store.bridge.call('skills.setEnabled', { id, enabled: !enabled })
    if (result?.id !== id || result.enabled !== !enabled) throw new Error('启用状态回执无效，请刷新详情')
    current.value.enabled = result.enabled
    await load()
  } catch (error) { skillError.value = error.message || '状态更新失败' }
  finally { saveBusy.value = false }
}

async function exportSkill() {
  if (saveBusy.value || !current.value || dirty.value) return
  saveBusy.value = true
  skillError.value = ''
  try {
    const result = await props.store.bridge.call('skills.export', { id: current.value.id })
    if (result?.cancelled === true) return
    if (result?.saved !== true) throw new Error('技能包未保存')
    props.store.toast('技能包已导出')
  } catch (error) { skillError.value = error.message || '导出失败' }
  finally { saveBusy.value = false }
}

function handleBack() {
  if (!detailOpen.value) return false
  closeEditor()
  return true
}
defineExpose({ handleBack })

function closeEditor() {
  if (saveBusy.value) return
  openRevision += 1
  loadingSkill.value = false
  if (dirty.value) {
    props.store.confirmDialog({
      kind: 'discard',
      title: '放弃未保存的修改？',
      message: '关闭后编辑内容不会保留。',
      confirmLabel: '放弃修改',
      danger: true,
      onAccept: () => {
        current.value = null
        markdown.value = ''
        original.value = ''
        props.store.markPageDirty(false)
      }
    })
    return
  }
  current.value = null
  leaveDetail()
  props.store.markPageDirty(false)
}

function startCreate() {
  creating.value = true
  newId.value = ''
  newName.value = ''
  skillError.value = ''
}

async function createSkill() {
  const id = newId.value.trim()
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(id)) {
    skillError.value = 'ID 只能用字母、数字、点、下划线、连字符，1–64 字符'
    return
  }
  saveBusy.value = true
  skillError.value = ''
  const body = `---\nname: ${newName.value.trim() || id}\ndescription: \n---\n\n# ${newName.value.trim() || id}\n\n`
  try {
    const parsed = await callToolParsed(props.store, 'save_skill', { id, markdown: body })
    if (parsed.threw) throw new Error(parsed.threw)
    if (parsed.isError) throw new Error(parsed.text || 'save_skill 返回错误')
    creating.value = false
    tab.value = 'custom'
    await load()
    const found = skills.value.find((k) => k.id === id)
    if (found) await open(found)
    props.store.toast('自定义技能已创建')
  } catch (error) {
    skillError.value = (error && error.message) || '创建失败'
  } finally {
    saveBusy.value = false
  }
}

async function save() {
  if (!current.value || current.value.builtIn) return
  saveBusy.value = true
  skillError.value = ''
  try {
    const parsed = await callToolParsed(props.store, 'save_skill', {
      id: current.value.id,
      markdown: markdown.value
    })
    if (parsed.threw) throw new Error(parsed.threw)
    if (parsed.isError) throw new Error(parsed.text || 'save_skill 返回错误')
    const back = await props.store.bridge.call('skills.read', { id: current.value.id })
    if (back?.id !== current.value.id || back.markdown !== markdown.value) throw new Error('保存后回读不一致')
    original.value = back.markdown
    props.store.markPageDirty(false)
    props.store.toast('技能已保存')
    load()
  } catch (error) {
    skillError.value = (error && error.message) || '保存失败'
  } finally {
    saveBusy.value = false
  }
}

function askDelete(skill) {
  props.store.confirmDialog({
    kind: 'delete',
    title: '删除自定义技能？',
    name: skill.name || skill.id,
    message: '删除后无法恢复；内置技能不能删除。',
    confirmLabel: '删除',
    danger: true,
    onAccept: () => remove(skill)
  })
}

async function remove(skill) {
  try {
    const parsed = await callToolParsed(props.store, 'delete_skill', { id: skill.id })
    if (parsed.threw) throw new Error(parsed.threw)
    if (parsed.isError) throw new Error(parsed.text || 'delete_skill 返回错误')
    if (current.value && current.value.id === skill.id) {
      current.value = null
      markdown.value = ''
      original.value = ''
      props.store.markPageDirty(false)
    }
    props.store.toast('技能已删除')
    load()
  } catch (error) {
    props.store.toast((error && error.message) || '删除失败', 'err')
  }
}

async function searchKnowledge() {
  const q = knowledgeQuery.value.trim()
  if (!q) return
  knowledgeBusy.value = true
  knowledgeError.value = ''
  knowledgeResult.value = ''
  try {
    const parsed = await callToolParsed(props.store, 'search_knowledge', { query: q })
    if (parsed.threw) throw new Error(parsed.threw)
    if (parsed.isError) throw new Error(parsed.text || 'search_knowledge 返回错误')
    knowledgeResult.value = parsed.data ? prettyJson(parsed.data) : parsed.text
  } catch (error) {
    knowledgeError.value = (error && error.message) || '搜索失败'
  } finally {
    knowledgeBusy.value = false
  }
}

async function readKnowledge() {
  const d = domain.value.trim()
  const m = mode.value.trim()
  if (!d || !m) {
    refError.value = 'read_knowledge 需要 domain 与 mode 两个参数'
    return
  }
  refBusy.value = true
  refError.value = ''
  refResult.value = ''
  try {
    const parsed = await callToolParsed(props.store, 'read_knowledge', { domain: d, mode: m })
    if (parsed.threw) throw new Error(parsed.threw)
    if (parsed.isError) throw new Error(parsed.text || 'read_knowledge 返回错误')
    refResult.value = parsed.data ? prettyJson(parsed.data) : parsed.text
  } catch (error) {
    refError.value = (error && error.message) || '读取失败'
  } finally {
    refBusy.value = false
  }
}
</script>

<template>
  <section ref="pageRef" class="page">
    <div v-if="detailOpen" class="detail-navigation">
      <button data-detail-back class="btn btn-ghost" type="button" @click="handleBack"><AppIcon name="chevron-left" :size="16" /><span>返回列表</span></button>
    </div>
    <div v-show="!detailOpen" class="section">
      <div class="section-head">
        <h3 class="section-title">技能</h3>
        <span class="section-sub">{{ skills.length }} 个</span>
      </div>
      <div class="segmented">
        <button class="seg" type="button" :class="{ on: tab === 'builtin' }" @click="tab = 'builtin'">内置</button>
        <button class="seg" type="button" :class="{ on: tab === 'custom' }" @click="tab = 'custom'">自定义</button>
      </div>
      <div class="actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /><span>刷新</span>
        </button>
        <button data-skill-import class="btn btn-ghost btn-sm" type="button" :disabled="saveBusy" @click="importSkill">导入 MD / ZIP</button>
        <button class="btn btn-primary btn-sm" type="button" :disabled="saveBusy" @click="startCreate">
          <AppIcon name="plus" :size="15" /><span>新建技能</span>
        </button>
      </div>
      <p v-if="listError" class="code-block err">{{ listError }}</p>
      <EmptyState v-if="loading" title="正在读取技能" text="list_skills" :busy="true" />
      <EmptyState
        v-else-if="!filtered.length && !listError"
        :title="tab === 'builtin' ? '没有内置技能' : '还没有自定义技能'"
        :text="tab === 'builtin' ? '宿主没有返回内置技能' : '点「新建技能」创建第一个'"
        icon="sparkles"
      />
      <div v-else class="skill-list">
        <button
          v-for="k in filtered"
          :key="k.id"
          type="button"
          class="skill-row"
          :class="{ on: current && current.id === k.id }"
          @click="open(k)"
        >
          <span class="skill-name">{{ k.name || k.id }}</span>
          <span class="skill-id mono">{{ k.id }}</span>
          <span class="skill-flags">
            <span v-if="!k.enabled" class="chip bad">已禁用</span>
            <button
              v-if="!k.builtIn"
              class="icon-btn"
              type="button"
              title="删除技能"
              aria-label="删除技能"
              @click.stop="askDelete(k)"
            >
              <AppIcon name="trash" :size="15" />
            </button>
          </span>
        </button>
      </div>
    </div>

    <div v-if="creating" class="section">
      <div class="section-head">
        <h3 class="section-title">新建自定义技能</h3>
        <span class="section-sub">save_skill</span>
      </div>
      <label class="field">
        <span class="flabel">技能 ID</span>
        <input v-model="newId" class="input" placeholder="my-skill" autocapitalize="off" spellcheck="false" />
      </label>
      <label class="field">
        <span class="flabel">显示名称</span>
        <input v-model="newName" class="input" placeholder="我的技能" />
      </label>
      <p v-if="skillError" class="code-block err">{{ skillError }}</p>
      <div class="actions">
        <button class="btn btn-ghost" type="button" @click="creating = false">取消</button>
        <button class="btn btn-primary" type="button" :disabled="saveBusy || !newId.trim()" @click="createSkill">
          <span v-if="saveBusy" class="spin"></span>
          <AppIcon v-else name="check" :size="16" /><span>创建</span>
        </button>
      </div>
    </div>

    <p v-if="detailOpen && !current && skillError" class="code-block err" role="alert">{{ skillError }}</p>
    <div v-if="loadingSkill" class="section">
      <EmptyState title="正在读取技能内容" text="get_skill" :busy="true" />
    </div>

    <div v-if="current" class="section">
      <div class="section-head">
        <h3 class="section-title">{{ current.name || current.id }}</h3>
        <span class="section-sub">{{ current.builtIn ? '内置（只读）' : dirty ? '有未保存修改' : '已同步' }}</span>
      </div>
      <label class="field">
        <span class="flabel">SKILL.md</span>
        <textarea
          v-model="markdown"
          class="textarea code-area"
          rows="18"
          spellcheck="false"
          :readonly="current.builtIn"
          aria-label="技能 Markdown"
          @input="props.store.markPageDirty(!current.builtIn)"
        ></textarea>
      </label>
      <p v-if="skillError" class="code-block err">{{ skillError }}</p>
      <div class="actions">
        <button data-skill-toggle class="btn btn-ghost" type="button" :disabled="saveBusy" @click="toggleEnabled">{{ current.enabled ? '禁用技能' : '启用技能' }}</button>
        <button data-skill-export class="btn btn-ghost" type="button" :disabled="saveBusy || dirty" @click="exportSkill">导出 ZIP</button>
        <button
          v-if="!current.builtIn"
          class="btn btn-primary"
          type="button"
          :disabled="saveBusy || !dirty"
          @click="save"
        >
          <span v-if="saveBusy" class="spin"></span>
          <AppIcon v-else name="check" :size="16" /><span>保存</span>
        </button>
        <button v-else class="btn btn-ghost" type="button" @click="props.store.toast('内置技能不可覆盖')">
          <AppIcon name="alert" :size="16" /><span>内置只读</span>
        </button>
        <button v-if="!current.builtIn" class="btn btn-ghost danger" type="button" :disabled="saveBusy" @click="askDelete(current)">删除技能</button>
        <button class="btn btn-ghost" type="button" @click="closeEditor">
          <AppIcon name="x" :size="16" /><span>关闭</span>
        </button>
      </div>
    </div>

    <details v-show="!detailOpen" class="section collapsible">
      <summary class="section-head">
        <h3 class="section-title">知识库搜索</h3>
        <span class="section-sub">search_knowledge</span>
      </summary>
      <div class="search">
        <AppIcon name="search" :size="16" class="search-ico" />
        <input v-model="knowledgeQuery" type="search" class="input" placeholder="搜索 Legado 规则知识" aria-label="搜索知识库" @keyup.enter="searchKnowledge" />
      </div>
      <div class="actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="knowledgeBusy || !knowledgeQuery.trim()" @click="searchKnowledge">
          <span v-if="knowledgeBusy" class="spin"></span><span>搜索</span>
        </button>
      </div>
      <p v-if="knowledgeError" class="code-block err">{{ knowledgeError }}</p>
      <pre v-if="knowledgeResult" class="code-block tall">{{ knowledgeResult }}</pre>
    </details>

    <details v-show="!detailOpen" class="section collapsible">
      <summary class="section-head">
        <h3 class="section-title">规则参考</h3>
        <span class="section-sub">read_knowledge(domain, mode)</span>
      </summary>
      <label class="field">
        <span class="flabel">domain</span>
        <input v-model="domain" class="input" placeholder="如 search / toc / content" autocapitalize="off" spellcheck="false" />
      </label>
      <label class="field">
        <span class="flabel">mode</span>
        <input v-model="mode" class="input" placeholder="如 json / js" autocapitalize="off" spellcheck="false" />
      </label>
      <div class="actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="refBusy" @click="readKnowledge">
          <span v-if="refBusy" class="spin"></span><span>读取</span>
        </button>
      </div>
      <p v-if="refError" class="code-block err">{{ refError }}</p>
      <pre v-if="refResult" class="code-block tall">{{ refResult }}</pre>
    </details>
  </section>
</template>

<style scoped>
/* Keep Back in document flow: a sticky row occludes edits/actions in a short IME viewport. */
.detail-navigation { padding: 8px 0; background: var(--surface); }
.skill-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.skill-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-areas: 'name flags' 'id id';
  gap: 2px 8px;
  padding: 10px 12px;
  text-align: left;
  border-radius: 14px;
  border: 1px solid var(--line);
  background: var(--surface);
}

.skill-row.on {
  border-color: color-mix(in srgb, var(--c1) 45%, var(--line));
  background: color-mix(in srgb, var(--c1) 9%, transparent);
}

.skill-name {
  grid-area: name;
  font-size: 13.5px;
  font-weight: 700;
}

.skill-id {
  grid-area: id;
  font-size: 11.5px;
  color: var(--muted);
  overflow-wrap: anywhere;
}

.skill-flags {
  grid-area: flags;
  display: flex;
  align-items: center;
  gap: 6px;
}

.code-area {
  font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  resize: vertical;
  min-height: 220px;
}

.collapsible summary {
  cursor: pointer;
  list-style: none;
}

.collapsible summary::-webkit-details-marker {
  display: none;
}
</style>
