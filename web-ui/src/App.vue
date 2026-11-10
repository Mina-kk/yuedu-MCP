<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createStore, PAGES } from './store.js'
import { applyTheme, watchSystem } from './theme.js'
import { createBackHandler } from './lib/back.js'
import { observeViewport } from './lib/viewport.js'
import { core } from './lib/core.js'
import AppIcon from './components/Icon.vue'
import Sheet from './components/Sheet.vue'
import ConfirmDialog from './components/ConfirmDialog.vue'
import ToastHost from './components/ToastHost.vue'
import ThemePicker from './components/ThemePicker.vue'
import SettingsPanel from './components/SettingsPanel.vue'
import McpPage from './pages/McpPage.vue'
import SourcesPage from './pages/SourcesPage.vue'
import SkillsPage from './pages/SkillsPage.vue'
import VerifyPage from './pages/VerifyPage.vue'
import LogsPage from './pages/LogsPage.vue'
import { createWebRuntimeBridge } from './lib/bridge.js'

// 应用外壳：页头 + 5 页 + 底部导航 + 设置 sheet + 确认框 + toast。
// 宿主事件统一 {event, payload}（window CustomEvent 读 detail）：
//   studio:back / studio:insets / studio:service / studio:verification-browser / studio:route
// Insets 由宿主「已原生避让后」以 0/0/0 传入，避免双算；浏览器里回落到 safe-area。
const store = createStore()
const s = store.state

const TABS = [
  { key: 'mcp', label: 'MCP', icon: 'server' },
  { key: 'sources', label: '书源', icon: 'book' },
  { key: 'skills', label: '技能', icon: 'sparkles' },
  { key: 'verify', label: '验证', icon: 'shield' },
  { key: 'logs', label: '日志', icon: 'list' }
]

const PAGES_MAP = {
  mcp: McpPage,
  sources: SourcesPage,
  skills: SkillsPage,
  verify: VerifyPage,
  logs: LogsPage
}

const activePage = computed(() => PAGES_MAP[s.page] || McpPage)
const verifyBrowserOpen = computed(() => Boolean(s.verificationBrowser && s.verificationBrowser.open))
const appVersion = computed(() => {
  const name = s.bootstrap?.versionName
  return typeof name === 'string' && name.trim() ? `APK ${name}` : '版本未知'
})
const rootRef = ref(null)
const unsubscribers = []

function applyThemeNow() {
  applyTheme({
    idx: s.themeIdx,
    mode: s.mode,
    onDark: (dark) => {
      // 宿主只同步系统栏图标/背景；浏览器模式没有该桥，失败静默。
      if (s.bridgeKind === 'android') {
        Promise.resolve(store.bridge.call('app.theme', { dark })).catch(() => {})
      }
    }
  })
}

watch(() => [s.themeIdx, s.mode], applyThemeNow)

// rawIme is a visibility signal only: Android already removed its pixels from
// the WebView. Both viewports resize together, so their difference is zero.
let hostKeyboardOpen = false
let hasHostInsets = false
let keyboardFrame = 0
function revealKeyboardFocus() {
  cancelAnimationFrame(keyboardFrame)
  keyboardFrame = requestAnimationFrame(() => {
    if (document.documentElement.dataset.keyboard !== '1') return
    const main = rootRef.value?.querySelector('main')
    const field = document.activeElement
    if (!main?.contains(field) || !field.matches('input, textarea, [contenteditable="true"]')) return
    const area = main.getBoundingClientRect()
    const bounds = field.getBoundingClientRect()
    const backHeight = main.querySelector('.detail-navigation')?.getBoundingClientRect().height || 0
    const top = area.top + backHeight + 4
    const bottom = area.bottom - 4
    // Oversized textareas align below the sticky Vue Back row. Scroll only the
    // main pane, never the document (which would pan the shell header away).
    if (bounds.top >= bottom || bounds.bottom <= top || bounds.top < top) {
      main.scrollTop += bounds.top - top
    }
  })
}
function syncKeyboardFlag() {
  const el = document.documentElement
  const ime = Number(s.insets && s.insets.ime) || 0
  let open = hostKeyboardOpen || ime > 0
  if (!open && !hasHostInsets && window.visualViewport) {
    const viewport = window.visualViewport
    const field = document.activeElement
    const editing = field?.matches('input:not([readonly]), textarea:not([readonly]), [contenteditable="true"]')
    // Browser chrome and pinch zoom also shrink visualViewport. Neither means
    // an IME; native inset events, when present, are the authoritative signal.
    open = Boolean(editing && Math.abs((viewport.scale || 1) - 1) < 0.01 &&
      window.innerHeight - viewport.height > 120)
  }
  el.setAttribute('data-keyboard', open ? '1' : '0')
  if (open) revealKeyboardFocus()
}

watch(() => s.insets, syncKeyboardFlag, { deep: true })

const pageRef = ref(null)
const rootBack = createBackHandler({
  state: s,
  actions: {
    closeDialog: () => store.closeDialog(),
    requestDiscard: (sheet) => store.requestDiscard(sheet),
    closeSheet: () => store.closeSheet(),
    goRoot: () => store.goRoot(),
    requestExit: () => store.requestExit()
  }
})

function handleBack() {
  if (s.dialog || s.sheet) return rootBack()
  if (verifyBrowserOpen.value) return closeVerifyBrowser()
  if (pageRef.value?.handleBack?.()) return
  return rootBack()
}

function onKeydown(event) {
  if (event.key !== 'Escape') return
  event.preventDefault()
  handleBack()
}

function routePage(payload) {
  const target = payload && (payload.page || payload.route)
  if (typeof target === 'string' && PAGES.includes(target)) store.setPage(target)
}

function onHostEvent(event, payload) {
  switch (event) {
    case 'studio:back':
      handleBack()
      break
    case 'studio:insets': {
      store.applyInsets(payload)
      const p = payload && typeof payload === 'object' ? payload : {}
      const rawIme = Number(p.rawIme)
      hostKeyboardOpen = Number.isFinite(rawIme) && rawIme > 0
      hasHostInsets = true
      syncKeyboardFlag()
      const el = document.documentElement
      // 宿主已原生避让：它下发的值（通常 0/0/0）就是最终值，UI 不再叠加。
      const set = (name, value) => {
        const n = Number(value)
        el.style.setProperty(name, `${Number.isFinite(n) && n > 0 ? n : 0}px`)
      }
      set('--native-inset-top', p.top)
      set('--native-inset-bottom', p.bottom)
      set('--native-inset-left', p.left)
      set('--native-inset-right', p.right)
      el.setAttribute('data-native-insets', '1')
      break
    }
    case 'studio:service':
      if (payload && typeof payload === 'object') {
        store.state.service = { ...store.state.service, ...payload }
      }
      break
    case 'studio:verification-browser':
      store.applyVerificationBrowser(payload)
      break
    case 'studio:route':
      routePage(payload)
      break
    default:
      break
  }
}

async function loadCore() {
  s.coreStatus = 'loading'
  const api = await core.load()
  s.coreStatus = core.status()
  s.coreError = core.error()
  s.coreVersion = api ? core.version() : ''
}

async function connectWeb({ endpoint, token }) {
  await store.connectWebRuntime(() =>
    createWebRuntimeBridge({ endpoint, token, timeoutMs: 45_000 })
  )
  if (s.bootstrapOk) await store.loadTools()
}

async function saveSettings(payload) {
  try {
    await store.saveSettings(payload)
    store.toast('设置已保存')
  } catch (error) {
    store.toast((error && error.message) || '保存失败', 'err')
    throw error
  }
}

function closeVerifyBrowser() {
  Promise.resolve(store.bridge.call('verification.browser.close', {}))
    .then(() => store.applyVerificationBrowser({ action: 'closed' }))
    .catch((error) => store.toast((error && error.message) || '关闭验证页失败', 'err'))
}

function completeVerifyBrowser() {
  const id = s.verificationBrowser && s.verificationBrowser.sessionId
  if (!id) {
    store.toast('没有正在进行的验证会话', 'err')
    return
  }
  Promise.resolve(store.bridge.call('verification.complete', { id }))
    .then(() => {
      store.toast('已标记完成')
      store.applyVerificationBrowser({ action: 'closed', id })
    })
    .catch((error) => store.toast((error && error.message) || '标记完成失败', 'err'))
}

function acceptDialog() {
  const dialog = s.dialog
  const fn = dialog && dialog.onAccept
  store.closeDialog()
  if (typeof fn === 'function') {
    Promise.resolve()
      .then(fn)
      .catch((error) => store.toast((error && error.message) || '操作失败', 'err'))
  } else if (dialog && dialog.action === 'app.exit') {
    Promise.resolve(store.bridge.call('app.exit', {})).catch((error) =>
      store.toast((error && error.message) || '退出失败', 'err')
    )
  }
}

function closeSheet() {
  if (s.sheetDirty) {
    store.requestDiscard(s.sheet)
    return
  }
  store.closeSheet()
}

onMounted(() => {
  applyThemeNow()
  unsubscribers.push(observeViewport(window, document.documentElement, syncKeyboardFlag))
  unsubscribers.push(watchSystem(() => applyThemeNow()))
  unsubscribers.push(store.bridge.onEvent(onHostEvent))
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('focusin', syncKeyboardFlag)
  loadCore()
  store.bootstrap().then(() => {
    if (s.bootstrapOk) store.loadTools()
  })
})

onBeforeUnmount(() => {
  unsubscribers.forEach((fn) => {
    try {
      fn()
    } catch {
      // 退订失败不影响卸载
    }
  })
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('focusin', syncKeyboardFlag)
  cancelAnimationFrame(keyboardFrame)
  store.bridge.dispose && store.bridge.dispose('unmount')
})
</script>

<template>
  <div ref="rootRef" class="app-shell" :class="{ 'verify-open': verifyBrowserOpen }">
    <header class="app-head shell-head">
      <div class="brand-line">
        <div class="head-text">
          <h1 class="app-title">阅读书源 MCP</h1>
          <p class="app-sub">以阅读规则为准</p>
        </div>
      </div>
      <div class="head-actions">
        <!-- 验证隔离页打开时，主 Web 顶部只留返回/完成（外站在 native 96dp 以下的独立 WebView） -->
        <template v-if="verifyBrowserOpen">
          <button class="btn btn-ghost btn-sm" type="button" @click="closeVerifyBrowser">
            <AppIcon name="chevron-left" :size="15" /><span>返回</span>
          </button>
          <button class="btn btn-primary btn-sm" type="button" @click="completeVerifyBrowser">
            <AppIcon name="check" :size="15" /><span>完成</span>
          </button>
        </template>
        <template v-else>
          <span class="chip" :title="`应用版本：${appVersion}`">
            {{ appVersion }}
          </span>
          <button class="icon-btn" type="button" title="设置" aria-label="设置" @click="store.openSheet('settings')">
            <AppIcon name="gear" :size="19" />
          </button>
        </template>
      </div>
    </header>

    <main class="shell with-tabs page-body">
      <component ref="pageRef" :is="activePage" :store="store" :key="s.page" />
    </main>

    <nav class="tabbar" aria-label="主导航">
      <button
        v-for="t in TABS"
        :key="t.key"
        type="button"
        class="tab"
        :class="{ on: s.page === t.key }"
        :aria-current="s.page === t.key ? 'page' : undefined"
        @click="store.setPage(t.key)"
      >
        <AppIcon :name="t.icon" :size="19" />
        <span>{{ t.label }}</span>
      </button>
    </nav>

    <Sheet
      v-if="s.sheet === 'settings'"
      title="设置"
      eyebrow="运行时 · 外观"
      subtitle="主题本地立即生效；运行时字段保存后由宿主回读"
      :wide="true"
      @close="closeSheet"
    >
      <SettingsPanel
        :bootstrap="s.bootstrap"
        :runtime-connected="s.runtimeConnected"
        :bootstrap-error="s.bootstrapError"
        :core-status="s.coreStatus"
        :core-version="s.coreVersion"
        :theme-idx="s.themeIdx"
        :mode="s.mode"
        @close="closeSheet"
        @retry="store.bootstrap"
        :save-settings="saveSettings"
        @dirty="store.markDirty"
        @connect-web="connectWeb"
        @exit="store.requestExit"
      >
        <template #theme>
          <ThemePicker
            :theme-idx="s.themeIdx"
            :mode="s.mode"
            @pick-theme="store.setTheme"
            @pick-mode="store.setMode"
          />
        </template>
      </SettingsPanel>
    </Sheet>

    <ConfirmDialog v-if="s.dialog" :dialog="s.dialog" @close="store.closeDialog" @accept="acceptDialog" />

    <ToastHost :toast="s.toast" />
  </div>
</template>
