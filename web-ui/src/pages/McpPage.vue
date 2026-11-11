<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useDetailNavigation } from '../lib/detail-navigation.js'
import AppIcon from '../components/Icon.vue'
import EmptyState from '../components/EmptyState.vue'
import ToolResult from '../components/ToolResult.vue'
import FeatureGuide from '../components/FeatureGuide.vue'
import { callToolParsed, itemsOf, prettyJson } from '../lib/tools.js'
import { TOKEN_DISABLE_CONFIRM } from '../store.js'

// MCP 连接页：真实服务状态（app.bootstrap）、启停（service.set）、连接复制（connection.copy）、
// 工具目录（tools.list，真实注册表 73 项）与参数 JSON 调用器（tools.call）。
// 不猜端口、不造目录；已存令牌不下发，只通过宿主剪贴板接口复制。
const props = defineProps({ store: { type: Object, required: true } })
const s = props.store.state
const { pageRef, detailOpen, enterDetail, leaveDetail } = useDetailNavigation()

const search = ref('')
const guideOpen = ref(false)
const connectionLan = ref(false)
const copying = ref(false)
const headerCopySupported = computed(() => s.bridgeKind === 'android' && s.capabilities?.connectionHeaderCopy === true)
const headerCopyDisabled = computed(() => copyDisabled.value || !headerCopySupported.value || !tokenKnown.value || !service.value.tokenRequired)
const copyDisabled = computed(() => !s.runtimeConnected || s.settingsSaving || savingToken.value || copying.value)
function openGuide() { enterDetail(); guideOpen.value = true }
const selected = ref('')
const argsText = ref('{}')
const originalArgs = ref('{}')
const dirty = computed(() => detailOpen.value && argsText.value !== originalArgs.value)
watch(dirty, value => props.store.markPageDirty(value))
onBeforeUnmount(() => props.store.markPageDirty(false))
const calling = ref(false)
const result = ref(null)
const resultError = ref('')
const pendingConfirm = ref(null)
const busyService = ref(false)
const savingToken = ref(false)
const tokenError = ref('')
const tokenKnown = computed(() => s.runtimeConnected && s.bootstrapOk && typeof s.service?.tokenRequired === 'boolean')
const tokenEditable = computed(() => s.bridgeKind === 'android' && s.runtimeConnected &&
  tokenKnown.value && s.capabilities?.tokenRequiredEditable === true && s.capabilities?.tokenRequiredLocked !== true)

const service = computed(() => s.service || {})
const statusLabel = computed(() => {
  if (!s.runtimeConnected || !s.bootstrapOk) return '状态未知'
  if (service.value.starting) return '启动中'
  return service.value.running ? '运行中' : '已停止'
})

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  const all = Array.isArray(s.tools) ? s.tools : []
  if (!q) return all
  return all.filter(
    (t) =>
      String(t.name || '').toLowerCase().includes(q) ||
      String(t.description || '').toLowerCase().includes(q)
  )
})

const selectedTool = computed(() => (s.tools || []).find((t) => t.name === selected.value) || null)

const schemaProps = computed(() => {
  const schema = selectedTool.value && selectedTool.value.inputSchema
  if (!schema || typeof schema !== 'object') return []
  const props2 = schema.properties && typeof schema.properties === 'object' ? schema.properties : {}
  const required = Array.isArray(schema.required) ? schema.required : []
  return Object.entries(props2).map(([name, def]) => ({
    name,
    type: (def && def.type) || 'any',
    required: required.includes(name),
    description: (def && def.description) || ''
  }))
})

const isDangerous = computed(() => {
  const a = selectedTool.value && selectedTool.value.annotations
  return Boolean(a && (a.destructiveHint || a.readOnlyHint === false && a.destructiveHint))
})

const toolCount = computed(() => {
  const cap = s.capabilities && s.capabilities.toolCount
  return Number.isFinite(Number(cap)) ? Number(cap) : null
})

onMounted(() => {
  if (!s.tools.length && !s.toolsLoading) props.store.loadTools()
})

async function retryBootstrap() {
  await props.store.bootstrap()
  if (s.bootstrapOk) props.store.loadTools()
}

async function toggleService() {
  if (!s.runtimeConnected || busyService.value) return
  busyService.value = true
  try {
    await props.store.setService(service.value.running ? 'stop' : 'start')
    await props.store.bootstrap()
  } catch {
    // store 已 toast 真实错误
  } finally {
    busyService.value = false
  }
}

async function restartService() {
  if (!s.runtimeConnected || busyService.value) return
  busyService.value = true
  try {
    await props.store.setService('restart')
    await props.store.bootstrap()
  } catch {
    // 同上
  } finally {
    busyService.value = false
  }
}

async function copy(kind, lan = false) {
  if (copyDisabled.value) return
  const header = ['header-name', 'header-value', 'header-line'].includes(kind)
  if (header && headerCopyDisabled.value) return
  copying.value = true
  try {
    if (header) {
      // Do not use the legacy store helper: it toasts before validating kind.
      const receipt = await props.store.bridge.call('connection.copy', { kind, lan: false })
      if (receipt?.copied !== true || receipt?.kind !== kind) throw new Error('unconfirmed')
      props.store.toast('已复制到剪贴板')
    } else await props.store.copyConnection(kind, lan)
  } catch (error) {
    props.store.toast('复制失败：宿主未确认写入剪贴板，请检查连接与宿主支持。', 'err')
  } finally { copying.value = false }
}

function toggleTokenRequired() {
  if (!tokenEditable.value || savingToken.value || s.settingsSaving) return
  const next = service.value.tokenRequired === false
  const save = async () => {
    if (!tokenEditable.value || savingToken.value || s.settingsSaving) return
    savingToken.value = true
    tokenError.value = ''
    try {
      await props.store.saveSettings({ tokenRequired: next })
      props.store.toast(next ? '访问令牌校验已开启' : '访问令牌校验已关闭')
    } catch (error) {
      tokenError.value = error?.message || '访问令牌校验保存失败'
      props.store.toast(tokenError.value, 'err')
    } finally { savingToken.value = false }
  }
  if (next) return save()
  props.store.confirmDialog({ ...TOKEN_DISABLE_CONFIRM, onAccept: save })
}

function handleBack() {
  if (guideOpen.value) { guideOpen.value = false; leaveDetail(); return true }
  if (!detailOpen.value) return false
  if (calling.value) return true
  const close = () => {
    selected.value = ''
    pendingConfirm.value = null
    argsText.value = originalArgs.value
    props.store.markPageDirty(false)
    leaveDetail()
  }
  if (dirty.value) props.store.confirmDialog({ kind: 'discard', title: '放弃未保存的参数？', message: '返回后参数草稿不会保留。', confirmLabel: '放弃修改', danger: true, onAccept: close })
  else close()
  return true
}
defineExpose({ handleBack })

function pick(tool) {
  enterDetail()
  selected.value = tool.name
  result.value = null
  resultError.value = ''
  pendingConfirm.value = null
  const schema = tool.inputSchema
  const props2 = (schema && schema.properties) || {}
  const skeleton = {}
  for (const [name, def] of Object.entries(props2)) {
    const t = def && def.type
    if (t === 'boolean') skeleton[name] = false
    else if (t === 'integer' || t === 'number') skeleton[name] = 0
    else if (t === 'array') skeleton[name] = []
    else if (t === 'object') skeleton[name] = {}
    else skeleton[name] = ''
  }
  argsText.value = JSON.stringify(skeleton, null, 2)
  originalArgs.value = argsText.value
}

function parseArgs() {
  const raw = argsText.value.trim()
  if (!raw) return {}
  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch (error) {
    throw new Error(`参数不是合法 JSON：${(error && error.message) || '解析失败'}`)
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('参数必须是 JSON 对象，例如 {"limit": 20}')
  }
  return parsed
}

async function run() {
  if (!selected.value) return
  let args
  try {
    args = parseArgs()
  } catch (error) {
    resultError.value = (error && error.message) || '参数错误'
    return
  }
  if (isDangerous.value) {
    pendingConfirm.value = { name: selected.value, args }
    return
  }
  await execute(selected.value, args)
}

async function confirmRun() {
  const p = pendingConfirm.value
  pendingConfirm.value = null
  if (p) await execute(p.name, p.args)
}

async function execute(name, args) {
  calling.value = true
  result.value = null
  resultError.value = ''
  try {
    const parsed = await callToolParsed(props.store, name, args)
    if (parsed.threw) resultError.value = parsed.threw
    else result.value = { content: [{ type: 'text', text: parsed.text || '（空结果）' }], isError: parsed.isError }
  } finally {
    calling.value = false
  }
}

function copyResult() {
  const text = result.value && result.value.content && result.value.content[0] ? result.value.content[0].text : ''
  if (!text) return
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(
      () => props.store.toast('结果已复制'),
      () => props.store.toast('浏览器拒绝写入剪贴板', 'err')
    )
  }
}

const prettyResult = computed(() => {
  const text = result.value && result.value.content && result.value.content[0] ? result.value.content[0].text : ''
  if (!text) return ''
  try {
    return prettyJson(JSON.parse(text))
  } catch {
    return text
  }
})
</script>

<template>
  <section ref="pageRef" class="page">
    <div v-if="detailOpen" class="detail-navigation">
      <button data-detail-back class="btn btn-ghost" type="button" @click="handleBack"><AppIcon name="chevron-left" :size="16" /><span>返回列表</span></button>
    </div>
    <div v-show="!detailOpen">
    <div class="actions"><button class="btn btn-ghost" type="button" @click="openGuide">功能指南</button></div>
    <div class="stat-grid">
      <div class="stat">
        <span class="stat-label">服务</span>
        <span class="stat-value" :class="{ ok: service.running }">{{ statusLabel }}</span>
        <span class="stat-sub">{{ s.runtimeConnected ? (s.bridgeKind === 'web' ? '浏览器直连 MCP' : 'Android 宿主桥') : '未连接运行时' }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">端口</span>
        <span class="stat-value">{{ service.port || '—' }}</span>
        <span class="stat-sub">{{ !tokenKnown ? '鉴权状态未知' : service.tokenRequired ? '需要令牌' : '无令牌' }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">客户端</span>
        <span class="stat-value">{{ Number.isFinite(Number(service.clientCount)) ? service.clientCount : '—' }}</span>
        <span class="stat-sub">{{ service.compact ? '精简模式' : '完整描述' }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">工具</span>
        <span class="stat-value">{{ toolCount ?? (s.tools.length || '—') }}</span>
        <span class="stat-sub">真实注册表</span>
      </div>
    </div>

    <div v-if="!s.runtimeConnected" class="banner err-banner">
      <strong>未连接运行时</strong>
      <p>{{ s.bootstrapError || '当前页面没有 Android 宿主桥。可在设置里显式填写 MCP 地址，或回到 App 内使用。' }}</p>
      <div class="actions">
        <button class="btn btn-ghost" type="button" @click="props.store.openSheet('settings')">
          <AppIcon name="gear" :size="16" /><span>打开设置</span>
        </button>
      </div>
    </div>

    <div v-else-if="s.bootstrapError" class="banner err-banner">
      <strong>bootstrap 失败</strong>
      <p>{{ s.bootstrapError }}</p>
      <div class="actions">
        <button class="btn btn-ghost" type="button" @click="retryBootstrap">
          <AppIcon name="refresh" :size="16" /><span>重试</span>
        </button>
      </div>
    </div>

    <div class="section">
      <div class="section-head">
        <h3 class="section-title">服务控制</h3>
        <span class="section-sub">{{ service.starting ? '正在切换…' : statusLabel }}</span>
      </div>
      <div class="actions">
        <button class="btn btn-primary" type="button" :disabled="!s.runtimeConnected || busyService" @click="toggleService">
          <AppIcon :name="service.running ? 'x' : 'zap'" :size="16" />
          <span>{{ service.running ? '停止服务' : '启动服务' }}</span>
        </button>
        <button class="btn btn-ghost" type="button" :disabled="!s.runtimeConnected || busyService" @click="restartService">
          <AppIcon name="refresh" :size="16" /><span>重启</span>
        </button>
      </div>
      <div class="row">
        <div class="row-text">
          <span class="row-title">访问令牌校验</span>
          <span class="row-sub">{{ !tokenKnown ? '访问令牌校验状态未知，请重新读取状态' : service.tokenRequired ? '已开启 · 连接需要令牌' : '已关闭 · 免令牌访问' }}</span>
        </div>
        <button type="button" class="switch" :role="tokenKnown ? 'switch' : 'button'" aria-label="访问令牌校验"
          :class="{ on: tokenKnown && service.tokenRequired }" :aria-checked="tokenKnown ? String(service.tokenRequired) : undefined"
          :aria-busy="String(savingToken || s.settingsSaving)" :disabled="!tokenEditable || savingToken || s.settingsSaving" @click="toggleTokenRequired"><i></i></button>
      </div>
      <p v-if="tokenKnown && !tokenEditable" class="hint">当前宿主不支持更改访问令牌校验，请在支持此功能的 Android App 内操作。</p>
      <p v-if="tokenError" class="code-block err" role="alert">{{ tokenError }}</p>
      <div class="endpoint-list">
        <div v-for="ep in service.endpoints || []" :key="ep" class="endpoint-row">
          <code class="mono">{{ ep }}</code>
        </div>
        <p v-if="!(service.endpoints || []).length" class="muted small">宿主没有返回可用端点。</p>
      </div>
      <div class="actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="s.settingsSaving || copying" @click="props.store.openSheet('settings')">
          <AppIcon name="gear" :size="15" /><span>修改访问令牌</span>
        </button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="copyDisabled" @click="copy('token')">
          <AppIcon name="copy" :size="15" /><span>复制令牌</span>
        </button>
      </div>
      <div class="actions" role="group" aria-label="客户端连接位置">
        <button data-connection-local class="btn btn-ghost btn-sm" type="button" :aria-pressed="!connectionLan" :disabled="copying" @click="connectionLan = false">同机客户端</button>
        <button data-connection-lan class="btn btn-ghost btn-sm" type="button" :aria-pressed="connectionLan" :disabled="copying" @click="connectionLan = true">局域网客户端</button>
      </div>
      <p class="hint">{{ connectionLan ? '局域网配置：宿主复制时获取当前首个可用局域网地址；无地址会报错，不回退为同机。客户端需在可信同一网络。' : '同机配置：仅用于本设备的客户端，其他设备请选择局域网。' }}</p>
      <div class="actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="copyDisabled" @click="copy('url', connectionLan)">复制所选连接地址</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="copyDisabled" @click="copy('json', connectionLan)">
          <AppIcon name="copy" :size="15" /><span>复制客户端配置</span>
        </button>
      </div>
      <p v-if="tokenKnown && service.tokenRequired" class="hint">
        点击“复制令牌”，将纯令牌粘贴到客户端的 Token / Bearer Token 字段（不要再加 Bearer 前缀）；
        若使用自定义请求头，名称填写 <code>Authorization</code>，值填写 <code>Bearer &lt;令牌&gt;</code>；整行格式为 <code>Authorization: Bearer &lt;令牌&gt;</code>。无需 OAuth 授权，本服务不支持 OAuth。
      </p>
      <div class="actions">
        <button data-copy-kind="header-name" class="btn btn-ghost btn-sm" type="button" :disabled="headerCopyDisabled" @click="copy('header-name')">复制请求头名</button>
        <button data-copy-kind="header-value" class="btn btn-ghost btn-sm" type="button" :disabled="headerCopyDisabled" @click="copy('header-value')">复制请求头值</button>
        <button data-copy-kind="header-line" class="btn btn-ghost btn-sm" type="button" :disabled="headerCopyDisabled" @click="copy('header-line')">复制整行请求头</button>
      </div>
      <p v-if="!headerCopySupported" class="hint">宿主尚未支持请求头复制；请求头名可手动填写 Authorization，令牌请使用上方“复制令牌”。</p>
      <p v-if="tokenKnown && !service.tokenRequired" class="hint">当前免令牌访问：客户端 Token 和 Authorization 请求头留空，无需 OAuth，本服务不支持 OAuth。现有令牌仍保留，可通过“复制令牌”获取。</p>
      <p class="hint">“复制令牌”只含纯令牌；“复制请求头值”含 Bearer 前缀；“复制整行请求头”还含 Authorization:，仅用于接受完整请求头行的客户端，不能粘贴到 Token 字段。</p>
      <p class="hint">已存令牌仅由宿主复制到本机剪贴板，不会回显；修改入口位于设置的 MCP 服务区。</p>
    </div>

    <div class="section">
      <div class="section-head">
        <h3 class="section-title">工具目录</h3>
        <span class="section-sub">{{ s.bootstrapOk ? `${filtered.length} / ${toolCount ?? s.tools.length}` : '未加载' }}</span>
      </div>
      <div class="search">
        <AppIcon name="search" :size="16" class="search-ico" />
        <input v-model="search" type="search" class="input" placeholder="按名称或描述搜索工具" aria-label="搜索工具" />
      </div>
      <p v-if="s.toolsError" class="code-block err">{{ s.toolsError }}</p>
      <EmptyState
        v-else-if="s.toolsLoading"
        title="正在读取工具目录"
        text="来自宿主真实 MCP 注册表"
        :busy="true"
      />
      <EmptyState
        v-else-if="!filtered.length"
        :title="s.tools.length ? '没有匹配的工具' : '工具目录为空'"
        :text="s.tools.length ? '换个关键词试试' : '宿主没有返回任何工具，或运行时未连接'"
        icon="search"
      />
      <div v-else class="tool-list">
        <button
          v-for="tool in filtered"
          :key="tool.name"
          type="button"
          class="tool-row"
          :class="{ on: selected === tool.name }"
          @click="pick(tool)"
        >
          <span class="tool-name mono">{{ tool.name }}</span>
          <span class="tool-desc">{{ tool.description }}</span>
          <span class="tool-flags">
            <span v-if="tool.annotations && tool.annotations.readOnlyHint" class="chip ok">只读</span>
            <span v-if="tool.annotations && tool.annotations.destructiveHint" class="chip bad">破坏性</span>
          </span>
        </button>
      </div>
    </div>

    </div>
    <FeatureGuide v-if="guideOpen" :bootstrap="s.bootstrap" />
    <div v-if="selectedTool" class="section">
      <div class="section-head">
        <h3 class="section-title mono">{{ selectedTool.name }}</h3>
        <span class="section-sub">{{ schemaProps.length }} 个参数</span>
      </div>
      <p class="hint">{{ selectedTool.description }}</p>
      <div v-if="schemaProps.length" class="schema-list">
        <div v-for="p in schemaProps" :key="p.name" class="schema-row">
          <code class="mono">{{ p.name }}</code>
          <span class="chip">{{ p.type }}</span>
          <span v-if="p.required" class="chip bad">必填</span>
          <span class="schema-desc">{{ p.description }}</span>
        </div>
      </div>
      <label class="field">
        <span class="flabel">参数 JSON</span>
        <textarea v-model="argsText" class="textarea code-area" rows="8" spellcheck="false" aria-label="工具参数 JSON"></textarea>
      </label>
      <p v-if="resultError" class="code-block err">{{ resultError }}</p>
      <div v-if="pendingConfirm" class="banner err-banner">
        <strong>确认调用破坏性工具</strong>
        <p>{{ pendingConfirm.name }} 可能删除或覆盖数据，参数：</p>
        <pre class="code-block">{{ prettyJson(pendingConfirm.args) }}</pre>
        <div class="actions">
          <button class="btn btn-ghost" type="button" @click="pendingConfirm = null">取消</button>
          <button class="btn btn-danger" type="button" @click="confirmRun">确认调用</button>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-primary" type="button" :disabled="calling || !s.runtimeConnected" @click="run">
          <span v-if="calling" class="spin"></span>
          <AppIcon v-else name="zap" :size="16" />
          <span>调用</span>
        </button>
        <button v-if="result" class="btn btn-ghost" type="button" @click="copyResult">
          <AppIcon name="copy" :size="16" /><span>复制结果</span>
        </button>
      </div>
      <ToolResult v-if="calling || result || resultError" :result="null" :error="resultError" :busy="calling" />
      <pre v-if="result && prettyResult" class="code-block tall">{{ prettyResult }}</pre>
    </div>
  </section>
</template>

<style scoped>
/* Keep Back in document flow: a sticky row occludes edits/actions in a short IME viewport. */
.detail-navigation { padding: 8px 0; background: var(--surface); }
.stat-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 13px 14px;
  border-radius: 18px;
  border: 1px solid var(--line);
  background: var(--surface);
}

.stat-label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--muted);
}

.stat-value {
  font-size: 21px;
  font-weight: 780;
  letter-spacing: -0.02em;
}

.stat-value.ok {
  color: var(--good, #1f9d55);
}

.stat-sub {
  font-size: 11.5px;
  color: var(--muted);
}

.err-banner {
  border-color: color-mix(in srgb, var(--bad) 40%, var(--line));
  background: color-mix(in srgb, var(--bad) 8%, transparent);
}

.endpoint-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 10px 0;
}

.endpoint-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 12px;
  border: 1px solid var(--line);
  background: var(--field);
}

.endpoint-row code {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 12px;
}

.tool-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 46dvh;
  overflow-y: auto;
  padding-right: 2px;
}

.tool-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-areas: 'name flags' 'desc desc';
  gap: 2px 8px;
  padding: 10px 12px;
  text-align: left;
  border-radius: 14px;
  border: 1px solid var(--line);
  background: var(--surface);
  transition: border-color 0.16s, background 0.16s;
}

.tool-row.on {
  border-color: color-mix(in srgb, var(--c1) 45%, var(--line));
  background: color-mix(in srgb, var(--c1) 9%, transparent);
}

.tool-name {
  grid-area: name;
  font-size: 13px;
  font-weight: 700;
  overflow-wrap: anywhere;
}

.tool-desc {
  grid-area: desc;
  font-size: 11.5px;
  color: var(--muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.tool-flags {
  grid-area: flags;
  display: flex;
  gap: 4px;
}

.schema-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 8px 0 12px;
}

.schema-row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  font-size: 12px;
}

.schema-desc {
  flex: 1 1 100%;
  color: var(--muted);
  font-size: 11.5px;
}

.code-area {
  font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12.5px;
  resize: vertical;
  min-height: 120px;
}

@media (min-width: 700px) {
  .stat-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}
</style>
