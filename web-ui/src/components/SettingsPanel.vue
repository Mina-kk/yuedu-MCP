<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import AppIcon from './Icon.vue'
import { TOKEN_DISABLE_CONFIRM } from '../store.js'

// 设置：主题（本地立即生效）+ 运行时/阅读端字段（settings.save 整体校验后回读）。
// token 写后不回显；失败如实显示宿主错误。
const props = defineProps({
  bootstrap: { type: Object, default: null },
  bootstrapOk: { type: Boolean, default: false },
  runtimeConnected: { type: Boolean, default: false },
  bootstrapError: { type: String, default: '' },
  coreStatus: { type: String, default: 'idle' },
  coreVersion: { type: String, default: '' },
  themeIdx: { type: Number, default: 0 },
  mode: { type: String, default: 'auto' },
  saveSettings: { type: Function, default: null },
  hostAction: { type: Function, default: null },
  readerStatus: { type: Function, default: null },
  bridgeKind: { type: String, default: '' },
  settingsSaving: { type: Boolean, default: false },
  confirmDialog: { type: Function, default: null }
})
const emit = defineEmits(['close', 'pick-theme', 'pick-mode', 'dirty', 'retry', 'connect-web', 'exit'])
const coreStatusLabel = computed(() => ({ idle: '尚未加载', loading: '加载中', loaded: '已加载', failed: '加载失败' }[props.coreStatus] || '状态未知'))

function versionValue(value, fallback = '未知') {
  return value == null || !String(value).trim() ? fallback : String(value)
}

const SOURCE_TYPES = [
  [-1, '自动'],
  [0, '文本'],
  [1, '音频'],
  [2, '图片'],
  [3, '文件'],
  [4, '视频']
]

const boot = props.bootstrap || {}
const form = reactive({
  port: boot.service ? String(boot.service.port ?? '') : '',
  compact: Boolean(boot.service && boot.service.compact),
  userAgent: boot.runtime ? boot.runtime.userAgent || '' : '',
  bookSourceType: boot.runtime && Number.isFinite(Number(boot.runtime.bookSourceType)) ? Number(boot.runtime.bookSourceType) : -1,
  readerBaseUrl: boot.reader ? boot.reader.baseUrl || '' : '',
  readerToken: ''
})

const saving = ref(false)
const busy = computed(() => saving.value || props.settingsSaving)
const authUnknown = computed(() => !props.bootstrapOk)
const tokenEditable = computed(() => props.bridgeKind === 'android' && props.runtimeConnected &&
  !authUnknown.value && props.bootstrap?.capabilities?.tokenRequiredEditable === true && props.bootstrap?.capabilities?.tokenRequiredLocked !== true)
const tokenDraft = ref(boot.service?.tokenRequired !== false)
const tokenBaseline = ref(tokenDraft.value)
// Write-only secret: never include in the persisted/original form snapshot.
const mcpToken = ref('')
const mcpTokenEditable = computed(() => props.bridgeKind === 'android' && props.runtimeConnected &&
  props.bootstrapOk && props.bootstrap?.capabilities?.mcpTokenEditable === true)
onBeforeUnmount(() => { mcpToken.value = '' })
const originalForm = ref(JSON.stringify(form))
const dirty = computed(() => Boolean(mcpToken.value) || JSON.stringify(form) !== originalForm.value || tokenDraft.value !== tokenBaseline.value)
const error = ref('')
const webEndpoint = ref('')
const webToken = ref('')
watch(dirty, value => emit('dirty', value), { flush: 'sync' })
watch([() => props.bootstrapOk, () => props.bootstrap?.service?.tokenRequired], ([ok, value]) => {
  // Re-read establishes the actual baseline even after a partial save. Preserve
  // edited drafts, but compare the next toggle with the host, not the old value.
  if (ok && !saving.value && typeof value === 'boolean') {
    const untouched = tokenDraft.value === tokenBaseline.value
    tokenBaseline.value = value
    if (untouched) tokenDraft.value = value
  }
})

function payload() {
  const out = {}
  const port = Number(form.port)
  if (Number.isInteger(port) && port > 0) out.port = port
  out.compact = Boolean(form.compact)
  if (tokenEditable.value && tokenDraft.value !== tokenBaseline.value) out.tokenRequired = tokenDraft.value
  if (mcpToken.value) out.mcpToken = mcpToken.value
  if (form.userAgent.trim()) out.userAgent = form.userAgent.trim()
  out.bookSourceType = Number(form.bookSourceType)
  out.readerBaseUrl = form.readerBaseUrl.trim()
  if (form.readerToken.trim()) out.readerToken = form.readerToken.trim()
  return out
}

function submit() {
  if (busy.value) return
  if (authUnknown.value && tokenDraft.value !== tokenBaseline.value) { error.value = '当前鉴权状态未知，请重新读取状态后保存'; return }
  const next = payload()
  if (next.tokenRequired === false) {
    if (!props.confirmDialog) { error.value = '确认接口不可用，未保存'; return }
    props.confirmDialog({ ...TOKEN_DISABLE_CONFIRM, onAccept: () => save(next) })
    return
  }
  return save(next)
}

async function save(next) {
  if (busy.value) return
  if (next.mcpToken && !mcpTokenEditable.value) { error.value = '当前状态未知或宿主不支持修改访问令牌，请重新读取状态'; return }
  if (typeof next.tokenRequired === 'boolean' && !tokenEditable.value) { error.value = '当前鉴权状态未知或不可更改，请重新读取状态'; return }
  error.value = ''
  saving.value = true
  try {
    if (!props.saveSettings) throw new Error('保存接口不可用')
    const snapshot = await props.saveSettings(next)
    if (next.mcpToken && (snapshot?.partial || snapshot?.failed?.length || snapshot?.settingsResult?.mcpTokenApplied !== true)) {
      throw new Error('访问令牌更新结果未知')
    }
    if (typeof next.tokenRequired === 'boolean') {
      if (snapshot?.partial || snapshot?.failed?.length || snapshot?.service?.tokenRequired !== next.tokenRequired) {
        throw new Error('访问令牌校验保存回读不一致或缺失，未确认生效，请重新读取状态')
      }
    }
    if (typeof snapshot?.service?.tokenRequired === 'boolean') {
      tokenBaseline.value = snapshot.service.tokenRequired
      tokenDraft.value = snapshot.service.tokenRequired
    }
    form.readerToken = ''
    mcpToken.value = ''
    originalForm.value = JSON.stringify(form)
    emit('dirty', false)
  } catch (err) {
    error.value = next.mcpToken ? '访问令牌更新结果未知或设置未全部保存；请重新读取状态后重试，或复制当前配置核验' : (err && err.message) || '保存失败'
  } finally {
    saving.value = false
  }
}

const deviceReady = computed(() => props.bridgeKind === 'android' && props.runtimeConnected && props.bootstrapOk && props.bootstrap?.capabilities?.deviceSettings === true)
const deviceState = computed(() => props.bootstrapOk ? (props.bootstrap?.capabilities || {}) : {})
const statusLabel = value => value === true ? '已开启' : value === false ? '未开启' : '未知'
const notice = ref('')
async function deviceAction(method, params = {}) {
  if (busy.value || !deviceReady.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    if (!props.hostAction) throw new Error('宿主接口不可用')
    const result = await props.hostAction(method, params)
    if (result?.opened === false) throw new Error('无法打开系统设置，请从系统应用信息手动进入')
    notice.value = '操作已返回；权限以重新读取的系统状态为准，打开设置不表示已授权。'
  } catch (err) { error.value = err?.message || '操作失败' }
  finally { saving.value = false }
}
const readerReady = computed(() => props.bridgeKind === 'android' && props.runtimeConnected && props.bootstrapOk && props.bootstrap?.capabilities?.readerConfigActions === true)
const readerResult = ref('')
function acceptReaderSnapshot(snapshot) {
  if (snapshot?.partial || snapshot?.failed?.length || !snapshot?.reader || typeof snapshot.reader.baseUrl !== 'string' || typeof snapshot.reader.tokenSaved !== 'boolean') throw new Error('阅读配置回读无效')
  form.readerBaseUrl = snapshot.reader.baseUrl
  form.readerToken = ''
  // Update only these baselines; unrelated edits remain dirty.
  const original = JSON.parse(originalForm.value)
  original.readerBaseUrl = form.readerBaseUrl
  original.readerToken = ''
  originalForm.value = JSON.stringify(original)
}
async function testReader() {
  if (busy.value || !readerReady.value) return
  saving.value = true
  error.value = ''
  readerResult.value = ''
  try {
    const address = form.readerBaseUrl.trim()
    const url = new URL(address)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || !['', '/'].includes(url.pathname)) throw new Error('请输入有效的阅读 HTTP(S) origin 地址')
    if (!props.saveSettings || !props.readerStatus) throw new Error('阅读测试接口不可用')
    const next = { readerBaseUrl: address }
    if (form.readerToken.trim()) next.readerToken = form.readerToken.trim()
    acceptReaderSnapshot(await props.saveSettings(next))
    const result = await props.readerStatus()
    readerResult.value = JSON.stringify(result, null, 2)
  } catch (_) { error.value = '阅读连接测试未完成：请检查地址、保存状态及阅读服务；不会使用旧地址代测。' }
  finally { saving.value = false }
}
function requestClearReader() {
  if (busy.value || !readerReady.value || !props.confirmDialog) return
  props.confirmDialog({ title: '清除阅读配置？', message: '将解除阅读连接并清除已保存的地址与令牌，不删除阅读端书源。', confirmLabel: '清除配置', danger: true, onAccept: async () => {
    if (busy.value) return
    saving.value = true
    error.value = ''
    try {
      const snapshot = await props.hostAction('reader.clear', {})
      if (snapshot?.reader?.baseUrl !== '' || snapshot?.reader?.tokenSaved !== false) throw new Error('清除未确认')
      acceptReaderSnapshot(snapshot)
      readerResult.value = ''
      notice.value = '阅读地址与令牌已清除并回读确认'
    } catch (_) { error.value = '阅读配置清除未确认，请重新读取状态后重试' }
    finally { saving.value = false }
  } })
}
function handleBack() { return busy.value }
defineExpose({ handleBack })

function connectWeb() {
  error.value = ''
  try {
    emit('connect-web', { endpoint: webEndpoint.value.trim(), token: webToken.value.trim() })
  } catch (err) {
    error.value = (err && err.message) || '连接失败'
  }
}
</script>

<template>
  <div class="section">
    <div class="section-head">
      <h3 class="section-title">应用版本（APK）</h3>
    </div>
    <dl class="version-details">
      <div><dt>版本名称（versionName）</dt><dd>{{ versionValue(bootstrap?.versionName, '版本未知') }}</dd></div>
      <div><dt>版本代码（versionCode）</dt><dd>{{ versionValue(bootstrap?.versionCode) }}</dd></div>
      <div><dt>构建修订（buildRevision）</dt><dd class="mono">{{ versionValue(bootstrap?.buildRevision) }}</dd></div>
    </dl>
  </div>

  <div class="section">
    <div class="section-head">
      <h3 class="section-title">运行时</h3>
      <span class="section-sub">{{ runtimeConnected ? '已连接宿主' : '未连接' }}</span>
    </div>
    <p v-if="!runtimeConnected" class="hint">
      当前页面没有 Android 宿主桥。可以在下面显式填写本机 MCP 地址（标准 Streamable HTTP，需自行确认端口），
      或回到 App 内使用。
    </p>
    <p v-else-if="bootstrapError" class="hint err-text">bootstrap 失败：{{ bootstrapError }}</p>
    <div v-if="!runtimeConnected" class="field">
      <label class="flabel" for="set-endpoint">运行时地址</label>
      <input id="set-endpoint" v-model="webEndpoint" class="input" placeholder="http://127.0.0.1:8787/mcp" autocapitalize="off" spellcheck="false" />
      <label class="flabel" for="set-webtoken" style="margin-top: 10px">访问令牌（仅本次会话内存）</label>
      <input id="set-webtoken" v-model="webToken" class="input" type="password" placeholder="Bearer token" autocomplete="off" />
      <div class="actions">
        <button class="btn btn-primary" type="button" :disabled="!webEndpoint" @click="connectWeb">
          <AppIcon name="link" :size="17" /><span>连接 MCP</span>
        </button>
      </div>
    </div>
    <div v-else class="actions">
      <button class="btn btn-ghost" type="button" @click="emit('retry')">
        <AppIcon name="refresh" :size="17" /><span>重新读取状态</span>
      </button>
    </div>
  </div>

  <div class="section">
    <div class="section-head">
      <h3 class="section-title">Rust 前端核</h3>
      <span class="section-sub">{{ coreStatusLabel }}</span>
    </div>
    <dl class="version-details">
      <div><dt>Rust 核心版本</dt><dd class="mono">{{ versionValue(coreVersion, '版本未知') }}</dd></div>
    </dl>
    <p class="hint">
      <template v-if="coreStatus === 'loaded'">已加载真实 wasm。书源分析/对比/筛选/证据分级由 Rust 计算，与 APK 应用版本分别记录。</template>
      <template v-else-if="coreStatus === 'failed'">Rust 核不可用：wasm 未构建或加载失败。依赖它的分析功能会明确报错，不会用 JS 假替代。</template>
      <template v-else-if="coreStatus === 'loading'">Rust 核加载中…</template>
      <template v-else>Rust 核尚未加载。</template>
    </p>
  </div>

  <div class="section">
    <div class="section-head">
      <h3 class="section-title">MCP 服务</h3>
      <span class="section-sub">端口 1024–65530</span>
    </div>
    <label class="field">
      <span class="flabel">端口</span>
      <input v-model="form.port" class="input" inputmode="numeric" placeholder="8787" :disabled="busy" />
    </label>
    <div class="row">
      <div class="row-text">
        <span class="row-title">访问令牌校验</span>
        <span class="row-sub">{{ tokenDraft ? '开启：连接需要令牌' : '关闭：免令牌访问' }} · 保存后生效</span>
      </div>
      <button type="button" class="switch" role="switch" aria-label="访问令牌校验"
        :class="{ on: tokenDraft }" :aria-checked="String(tokenDraft)" :disabled="busy || !tokenEditable"
        @click="tokenDraft = !tokenDraft"><i></i></button>
    </div>
    <p v-if="authUnknown" class="hint">当前鉴权状态未知，请重新读取状态；上方仅保留未确认的设置草稿。</p>
    <p v-else-if="!tokenEditable" class="hint">当前宿主不支持更改访问令牌校验，请在支持此功能的 Android App 内操作。</p>
    <label class="field" for="set-mcp-token">
      <span class="flabel">新访问令牌</span>
      <input id="set-mcp-token" v-model="mcpToken" class="input" type="password"
        autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="留空保持当前令牌"
        :disabled="busy || !mcpTokenEditable" aria-describedby="mcp-token-hint" />
      <span id="mcp-token-hint" class="fhint">留空保持当前令牌；推荐随机高强度令牌，保存后需更新客户端配置。已存令牌不会回显。</span>
    </label>
    <p v-if="!tokenDraft" class="hint">当前不校验；修改令牌不会开启校验。</p>
    <p v-if="!mcpTokenEditable" class="hint">当前状态未知或宿主不支持修改访问令牌，请重新读取状态或使用支持此功能的 Android App。</p>
    <div class="row">
      <div class="row-text">
        <span class="row-title">精简模式</span>
        <span class="row-sub">tools/list 只返回截断描述，客户端按需补全</span>
      </div>
      <button
        type="button"
        class="switch"
        role="switch"
        :class="{ on: form.compact }"
        :aria-checked="String(form.compact)"
        :disabled="busy"
        @click="form.compact = !form.compact"
      >
        <i></i>
      </button>
    </div>
  </div>

  <div class="section">
    <div class="section-head">
      <h3 class="section-title">抓取与书源</h3>
      <span class="section-sub">写入运行时配置</span>
    </div>
    <label class="field">
      <span class="flabel">User-Agent</span>
      <input v-model="form.userAgent" class="input" placeholder="留空保持原值" autocapitalize="off" spellcheck="false" :disabled="busy" />
    </label>
    <span class="flabel">书源类型（缺省注入 bookSourceType）</span>
    <div class="segmented wrap">
      <button
        v-for="[value, label] in SOURCE_TYPES"
        :key="value"
        type="button"
        class="seg"
        :class="{ on: Number(form.bookSourceType) === value }"
        :disabled="busy"
        @click="form.bookSourceType = value"
      >
        {{ label }}
      </button>
    </div>
  </div>

  <div class="section">
    <div class="section-head">
      <h3 class="section-title">阅读同步</h3>
      <span class="section-sub">{{ bootstrap?.reader?.tokenSaved ? '已保存令牌' : '未保存令牌' }}</span>
    </div>
    <label class="field">
      <span class="flabel">阅读 Web 地址</span>
      <input v-model="form.readerBaseUrl" class="input" placeholder="https://reader.example" autocapitalize="off" spellcheck="false" :disabled="busy" />
    </label>
    <label class="field">
      <span class="flabel">阅读令牌</span>
      <input v-model="form.readerToken" class="input" type="password" placeholder="留空则保留同 origin 令牌" autocomplete="off" :disabled="busy" />
      <span class="fhint">令牌写入安全存储后不回显；改 origin 时旧令牌会被清除。</span>
    </label>
    <p class="hint">先在阅读 App 开启 Web 服务并核对端口。同机可用 http://127.0.0.1:实际端口；其他设备使用同一局域网的实际地址。测试先保存本次阅读输入，不保存其他草稿。401 表示鉴权要求，不等于离线；能力 unknown 不等于不支持，连接可达不代表单源通过。</p>
    <div class="actions">
      <button class="btn btn-ghost" type="button" :disabled="busy || !readerReady" @click="testReader">保存并测试连接</button>
      <button class="btn btn-ghost danger" type="button" :disabled="busy || !readerReady" @click="requestClearReader">清除阅读配置</button>
    </div>
    <pre v-if="readerResult" class="code-block">{{ readerResult }}</pre>
  </div>

  <div class="section">
    <div class="section-head">
      <h3 class="section-title">外观</h3>
      <span class="section-sub">10 套主题 · 浅深自动</span>
    </div>
    <slot name="theme"></slot>
  </div>

  <div v-if="bridgeKind === 'android'" class="section">
    <h3 class="section-title">权限与后台运行</h3>
    <p class="hint">通知权限：{{ statusLabel(deviceState.notificationPermission) }} · 应用通知：{{ statusLabel(deviceState.notificationsEnabled) }} · MCP 渠道：{{ deviceState.channelCreated === false ? '尚未创建' : statusLabel(deviceState.mcpChannelEnabled) }}</p>
    <p class="hint">悬浮窗权限：{{ statusLabel(deviceState.overlayPermission) }} · 悬浮球偏好：{{ statusLabel(deviceState.overlayEnabled) }} · 电池不受限：{{ statusLabel(deviceState.batteryUnrestricted) }}</p>
    <p class="hint">悬浮球需要系统权限、启用偏好及服务运行。自启动由厂商管理，无法自动确认；打开应用信息后请手动查找自启动／后台运行。</p>
    <div class="actions">
      <button v-for="[method, label] in [['notification.request','申请通知权限'], ['notification.openSettings','应用通知设置'], ['notification.openChannel','MCP 通知渠道'], ['overlay.openSettings','悬浮窗权限设置'], ['device.openBatterySettings','电池优化设置'], ['device.openAutoStartSettings','自启动设置说明']]"
        :key="method" class="btn btn-ghost" type="button" :disabled="busy || !deviceReady" @click="deviceAction(method)">{{ label }}</button>
      <button class="btn btn-ghost" type="button" :disabled="busy || !deviceReady || typeof deviceState.overlayEnabled !== 'boolean'" @click="deviceAction('overlay.setEnabled', { enabled: !deviceState.overlayEnabled })">{{ deviceState.overlayEnabled ? '停用悬浮球' : '启用悬浮球' }}</button>
    </div>
  </div>
  <p v-if="notice" class="hint" role="status">{{ notice }}</p>
  <p v-if="error" class="code-block err">{{ error }}</p>

  <div class="section">
    <div class="export-row">
      <button class="btn btn-ghost danger" type="button" :disabled="busy" @click="emit('exit')">
        <AppIcon name="x" :size="17" /><span>退出应用</span>
      </button>
    </div>
  </div>

  <div class="sheet-foot" style="margin: 8px -20px -18px">
    <button class="btn btn-ghost" type="button" :disabled="busy" @click="emit('close')">关闭</button>
    <button class="btn btn-primary" type="button" :disabled="busy || !runtimeConnected || (authUnknown && tokenDraft !== tokenBaseline)" :aria-busy="String(busy)" @click="submit">
      <span v-if="saving" class="spin"></span>
      <AppIcon v-else name="check" :size="17" />
      <span>保存设置</span>
    </button>
  </div>
</template>


<style scoped>
.version-details {
  display: grid;
  gap: 10px;
  margin: 0;
}
.version-details dt {
  color: var(--muted);
  font-size: 12px;
}
.version-details dd {
  margin: 3px 0 0;
  overflow-wrap: anywhere;
  white-space: normal;
}
.err-text {
  color: var(--bad);
}
</style>
