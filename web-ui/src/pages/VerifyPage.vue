<script setup>
import { computed, onMounted, ref } from 'vue'
import AppIcon from '../components/Icon.vue'
import EmptyState from '../components/EmptyState.vue'
import { formatTime } from '../lib/tools.js'

// 验证中心：宿主真实会话（verification.list）+ 人工 open/complete/close。
// 外站页面由宿主在「无 privileged bridge 的独立 WebView」打开（native 顶部 96dp 以下），
// 本页绝不创建 iframe；App 顶部只留 96 CSSpx 的 toolbar 显示返回与完成。
const props = defineProps({ store: { type: Object, required: true } })
const s = props.store.state

const sessions = ref([])
const loading = ref(false)
const listError = ref('')
const busyId = ref('')
const actionError = ref('')
const answers = ref({})
const repairUrls = ref({})
const selectedIds = ref([])
const locked = computed(() => loading.value || Boolean(busyId.value))

const browserOpen = computed(() => Boolean(s.verificationBrowser && s.verificationBrowser.open))
const activeSession = computed(() => {
  const id = s.verificationBrowser && s.verificationBrowser.sessionId
  return sessions.value.find((x) => x.id === id) || null
})

onMounted(() => load())

async function load() {
  if (s.bridgeKind !== 'android') {
    listError.value = '验证中心需要 Android 宿主：浏览器模式没有隔离 WebView，无法打开外站验证页。'
    sessions.value = []
    return
  }
  loading.value = true
  listError.value = ''
  try {
    const result = await props.store.bridge.call('verification.list', {})
    sessions.value = Array.isArray(result && result.sessions) ? result.sessions : []
    selectedIds.value = selectedIds.value.filter(id => sessions.value.some(x => x.id === id))
  } catch (error) {
    sessions.value = []
    listError.value = (error && error.message) || '验证会话读取失败'
  } finally {
    loading.value = false
  }
}

async function act(method, params, label) {
  if (locked.value) return null
  actionError.value = ''
  const id = params && params.id ? params.id : ''
  busyId.value = id || label
  try {
    const result = await props.store.bridge.call(method, params || {})
    await load()
    return result
  } catch (error) {
    actionError.value = (error && error.message) || `${label} 失败`
    return null
  } finally {
    busyId.value = ''
  }
}

// 新宿主决定 URL 是否可打开；旧宿主仅兜底空地址，不在 Vue 重写安全策略。
function canOpen(session) {
  return typeof session.canOpen === 'boolean' ? session.canOpen : Boolean(session.url?.trim())
}

function canComplete(session) {
  return String(session.status).toUpperCase() !== 'COMPLETED' && (session.kind === 'image_code' || canOpen(session))
}

function addressMessage(session) {
  if (session.addressIssue === 'missing_url' || !session.url?.trim()) return '缺少验证地址'
  if (session.addressIssue === 'unsupported_url' || !canOpen(session)) return '验证地址不受支持'
  return ''
}

function canRepair(session) {
  return ['WAITING', 'COMPLETED'].includes(String(session.status).toUpperCase()) && session.kind !== 'image_code' && !canOpen(session)
}

function askRepair(session) {
  const url = repairUrls.value[session.id] || ''
  if (locked.value || !canRepair(session) || !url.trim()) return
  let accepted = false
  props.store.confirmDialog({
    kind: 'repair',
    title: '补全验证地址？',
    name: session.domain || session.id,
    message: `${String(session.status).toUpperCase() === 'COMPLETED' ? '补全会将已完成记录重置为待验证。' : ''}仅补全当前历史记录，已保存的 Cookie 不受影响。保存后不会自动打开，请手动打开验证页。`,
    confirmLabel: '确认补全',
    danger: false,
    onAccept: () => {
      if (accepted || locked.value) return
      accepted = true
      return repairAddress(session.id, url)
    }
  })
}

function isRepairedSession(session, id, url) {
  return session?.id === id && session.url === url && session.canOpen === true &&
    session.status === 'WAITING' && session.addressIssue === '' && session.finalUrl === '' && session.answer === ''
}

async function repairAddress(id, url) {
  const session = sessions.value.find(x => x.id === id)
  if (locked.value || !session || !canRepair(session)) return
  busyId.value = id
  actionError.value = ''
  try {
    // 原样提交用户输入，URL/私网校验由宿主负责；不能用 domain/finalUrl 猜地址。
    const receipt = await props.store.bridge.call('verification.repairAddress', { id, url })
    const result = await props.store.bridge.call('verification.list', {})
    if (receipt?.repaired !== true || !isRepairedSession(receipt.session, id, url)) {
      throw new Error('地址补全回执无效')
    }
    if (!Array.isArray(result?.sessions)) throw new Error('会话列表格式无效')
    const matches = result.sessions.filter(x => x.id === id)
    if (matches.length !== 1 || !isRepairedSession(matches[0], id, url)) {
      throw new Error('回读未确认同一记录的验证地址')
    }
    sessions.value = result.sessions
    selectedIds.value = selectedIds.value.filter(key => sessions.value.some(x => x.id === key))
    delete repairUrls.value[id]
    props.store.toast('验证地址已补全，请手动打开验证页')
  } catch (error) {
    actionError.value = `无法确认地址补全结果：${error?.message || '补全失败'}`
  } finally {
    busyId.value = ''
  }
}

async function open(session) {
  if (!canOpen(session)) return
  const result = await act('verification.open', { id: session.id }, '打开验证页')
  if (result) props.store.toast('已在隔离 WebView 打开验证页')
}

async function complete(session) {
  if (!canComplete(session)) return
  const answer = (answers.value[session.id] || '').trim()
  const params = { id: session.id }
  if (answer) params.answer = answer
  const result = await act('verification.complete', params, '完成验证')
  if (result) {
    answers.value = { ...answers.value, [session.id]: '' }
    props.store.toast('已标记完成')
  }
}

function askDelete(session) {
  if (locked.value) return
  const targets = session ? [session] : sessions.value.filter(x => selectedIds.value.includes(x.id))
  if (!targets.length) return
  let accepted = false
  props.store.confirmDialog({
    kind: 'delete',
    title: session ? '删除验证记录？' : '批量删除验证记录？',
    name: session ? session.domain || session.id : `已选 ${targets.length} 条`,
    message: `将删除 ${targets.length} 条验证记录，已保存的 Cookie 不受影响。`,
    confirmLabel: '删除记录',
    danger: true,
    onAccept: () => {
      if (accepted || locked.value) return
      accepted = true
      return deleteRecords(targets)
    }
  })
}

async function deleteRecords(targets) {
  if (locked.value) return
  busyId.value = 'delete'
  actionError.value = ''
  let deleted = 0
  const failures = []
  try {
    for (const session of targets) {
      let reason = ''
      if (browserOpen.value && s.verificationBrowser.sessionId === session.id) {
        try {
          const result = await props.store.bridge.call('verification.browser.close', {})
          if (!result?.closed) throw new Error('关闭验证页未获确认')
        } catch (error) {
          failures.push(`${session.domain || session.id}：${error?.message || '关闭验证页失败'}`)
          continue
        }
      }
      try {
        await props.store.bridge.call('verification.close', { id: session.id })
      } catch (error) {
        reason = error?.message || '删除失败'
      }
      try {
        const result = await props.store.bridge.call('verification.list', {})
        if (!Array.isArray(result?.sessions)) throw new Error('会话列表格式无效')
        sessions.value = result.sessions
        if (!result.sessions.some(x => x.id === session.id)) {
          deleted++
          selectedIds.value = selectedIds.value.filter(id => id !== session.id)
          delete answers.value[session.id]
          continue
        }
        reason ||= '回读后记录仍存在'
      } catch (error) {
        reason = [reason, `无法确认删除结果：${error?.message || '读取失败'}`].filter(Boolean).join('；')
      }
      failures.push(`${session.domain || session.id}：${reason}`)
    }
    actionError.value = failures.join('\n')
    if (deleted) props.store.toast(`已删除 ${deleted} 条验证记录`)
  } finally {
    busyId.value = ''
  }
}

async function closeBrowser() {
  const result = await act('verification.browser.close', {}, '关闭验证页')
  if (result) props.store.toast('已返回控制台')
}

const statusChip = (status) => {
  const v = String(status || '').toUpperCase()
  if (v === 'COMPLETED') return { cls: 'ok', text: '已完成' }
  if (v === 'WAITING' || v === 'PENDING') return { cls: 'bad', text: '待处理' }
  return { cls: '', text: status || '未知' }
}
</script>

<template>
  <section class="page">
    <div v-if="browserOpen" class="banner verify-banner">
      <strong>隔离验证页已打开</strong>
      <p>
        外站页面在宿主独立 WebView 中显示（位于顶部工具栏下方）。主 WebView 不加载该页面，
        也不会向它暴露 privileged 桥。
        <template v-if="activeSession">当前会话：{{ activeSession.domain || activeSession.id }}</template>
      </p>
      <div class="actions">
        <button class="btn btn-ghost" type="button" :disabled="locked" @click="closeBrowser">
          <AppIcon name="x" :size="16" /><span>关闭验证页</span>
        </button>
        <button
          v-if="activeSession && String(activeSession.status).toUpperCase() !== 'COMPLETED'"
          class="btn btn-primary"
          type="button"
          :disabled="locked || !canComplete(activeSession)"
          @click="complete(activeSession)"
        >
          <AppIcon name="check" :size="16" /><span>标记完成</span>
        </button>
      </div>
    </div>

    <div class="section">
      <div class="section-head">
        <h3 class="section-title">验证会话</h3>
        <span class="section-sub">{{ sessions.length }} 个</span>
      </div>
      <div class="actions selection-actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="locked" @click="load">
          <AppIcon name="refresh" :size="15" /><span>刷新</span>
        </button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="locked || !sessions.length" @click="selectedIds = sessions.map(x => x.id)">全选当前记录</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="locked || !selectedIds.length" @click="selectedIds = []">取消选择</button>
        <span aria-live="polite">已选 {{ selectedIds.length }} 条</span>
        <button class="btn btn-ghost btn-sm danger" type="button" :disabled="locked || !selectedIds.length" @click="askDelete()">批量删除</button>
      </div>
      <p v-if="actionError" class="code-block err">{{ actionError }}</p>
      <p v-if="listError" class="code-block err">{{ listError }}</p>
      <EmptyState v-if="loading" title="正在读取验证会话" text="verification.list" :busy="true" />
      <EmptyState
        v-else-if="!sessions.length && !listError"
        title="没有待处理的验证"
        text="遇到验证码/登录/WAF 时，工具会在这里创建会话"
        icon="shield"
      />
      <div v-else class="session-list">
        <article v-for="session in sessions" :key="session.id" class="session">
          <header class="session-head">
            <label class="session-select">
              <input v-model="selectedIds" type="checkbox" :disabled="locked" :value="session.id" :aria-label="`选择 ${session.domain || session.id}`" />
              <span>选择</span>
            </label>
            <span class="session-domain">{{ session.domain || session.id }}</span>
            <span class="chip" :class="statusChip(session.status).cls">{{ statusChip(session.status).text }}</span>
            <span v-if="session.kind" class="chip">{{ session.kind }}</span>
          </header>
          <p v-if="session.purpose" class="session-purpose">{{ session.purpose }}</p>
          <p class="session-url mono">{{ session.finalUrl || session.url }}</p>
          <p v-if="addressMessage(session)" class="hint">{{ addressMessage(session) }}</p>
          <p class="muted small">
            <span v-if="session.createdAt">{{ formatTime(session.createdAt) }}</span>
            <span v-if="session.answer"> · 已输入答案</span>
          </p>
          <img
            v-if="session.imageData"
            class="captcha"
            :src="session.imageData"
            alt="验证码图片"
          />
          <label v-if="session.kind === 'image_code'" class="field">
            <span class="flabel">验证码</span>
            <input
              v-model="answers[session.id]"
              class="input"
              placeholder="输入图片里的验证码"
              autocapitalize="off"
              spellcheck="false"
            />
          </label>
          <div v-if="canRepair(session)" class="repair-address">
            <label class="field">
              <span class="flabel">验证地址</span>
              <input
                v-model="repairUrls[session.id]"
                class="input"
                type="text"
                inputmode="url"
                aria-label="验证地址"
                placeholder="输入完整的 http(s) 验证地址"
                autocapitalize="off"
                spellcheck="false"
                :disabled="locked"
              />
              <span class="fhint">请提供真实验证地址，由宿主校验。补全不会自动打开网页。</span>
            </label>
            <div class="actions">
              <button class="btn btn-ghost btn-sm" type="button" :disabled="locked || !(repairUrls[session.id] || '').trim()" @click="askRepair(session)">补全地址</button>
            </div>
          </div>
          <div class="actions">
            <button class="btn btn-ghost btn-sm" type="button" :disabled="locked || !canOpen(session)" @click="open(session)">
              <AppIcon name="link" :size="15" /><span>打开验证页</span>
            </button>
            <button v-if="String(session.status).toUpperCase() !== 'COMPLETED'" class="btn btn-primary btn-sm" type="button" :disabled="locked || !canComplete(session)" @click="complete(session)">
              <AppIcon name="check" :size="15" /><span>标记完成</span>
            </button>
            <button class="btn btn-ghost btn-sm danger" type="button" :disabled="locked" @click="askDelete(session)">
              <AppIcon name="trash" :size="15" /><span>删除记录</span>
            </button>
          </div>
        </article>
      </div>
    </div>
  </section>
</template>

<style scoped>
.verify-banner {
  border-color: color-mix(in srgb, var(--c1) 45%, var(--line));
  background: color-mix(in srgb, var(--c1) 10%, transparent);
}

.session-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.session {
  padding: 12px 13px;
  border-radius: 16px;
  border: 1px solid var(--line);
  background: var(--surface);
}

.session-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.session-domain {
  font-size: 13.5px;
  font-weight: 700;
  overflow-wrap: anywhere;
}

.session-purpose {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--muted);
}

.session-url {
  margin: 4px 0 0;
  font-size: 11.5px;
  color: var(--muted);
  overflow-wrap: anywhere;
}

.captcha {
  display: block;
  max-width: 100%;
  margin: 8px 0;
  border-radius: 12px;
  border: 1px solid var(--line);
  background: #fff;
}
</style>
