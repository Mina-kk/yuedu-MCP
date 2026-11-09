<script setup>
import { reactive, ref, watch } from 'vue'
import AppIcon from './Icon.vue'

// 设置：主题（本地立即生效）+ 运行时/阅读端字段（settings.save 整体校验后回读）。
// token 写后不回显；失败如实显示宿主错误。
const props = defineProps({
  bootstrap: { type: Object, default: null },
  runtimeConnected: { type: Boolean, default: false },
  bootstrapError: { type: String, default: '' },
  coreStatus: { type: String, default: 'idle' },
  coreVersion: { type: String, default: '' },
  themeIdx: { type: Number, default: 0 },
  mode: { type: String, default: 'auto' },
  saveSettings: { type: Function, default: null }
})
const emit = defineEmits(['close', 'pick-theme', 'pick-mode', 'dirty', 'retry', 'connect-web', 'exit'])

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
const error = ref('')
const webEndpoint = ref('')
const webToken = ref('')
watch(form, () => emit('dirty', true), { deep: true, flush: 'sync' })

function payload() {
  const out = {}
  const port = Number(form.port)
  if (Number.isInteger(port) && port > 0) out.port = port
  out.compact = Boolean(form.compact)
  if (form.userAgent.trim()) out.userAgent = form.userAgent.trim()
  out.bookSourceType = Number(form.bookSourceType)
  out.readerBaseUrl = form.readerBaseUrl.trim()
  if (form.readerToken.trim()) out.readerToken = form.readerToken.trim()
  return out
}

async function submit() {
  error.value = ''
  saving.value = true
  try {
    if (!props.saveSettings) throw new Error('保存接口不可用')
    await props.saveSettings(payload())
    form.readerToken = ''
    emit('dirty', false)
  } catch (err) {
    error.value = (err && err.message) || '保存失败'
  } finally {
    saving.value = false
  }
}

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
      <span class="section-sub">{{ coreVersion || coreStatus }}</span>
    </div>
    <p class="hint">
      <template v-if="coreStatus === 'loaded'">已加载真实 wasm（{{ coreVersion }}）。书源分析/对比/筛选/证据分级由 Rust 计算。</template>
      <template v-else-if="coreStatus === 'failed'">Rust 核不可用：wasm 未构建或加载失败。依赖它的分析功能会明确报错，不会用 JS 假替代。</template>
      <template v-else>Rust 核加载中…</template>
    </p>
  </div>

  <div class="section">
    <div class="section-head">
      <h3 class="section-title">MCP 服务</h3>
      <span class="section-sub">端口 1024–65530</span>
    </div>
    <label class="field">
      <span class="flabel">端口</span>
      <input v-model="form.port" class="input" inputmode="numeric" placeholder="8787" />
    </label>
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
      <input v-model="form.userAgent" class="input" placeholder="留空保持原值" autocapitalize="off" spellcheck="false" />
    </label>
    <span class="flabel">书源类型（缺省注入 bookSourceType）</span>
    <div class="segmented wrap">
      <button
        v-for="[value, label] in SOURCE_TYPES"
        :key="value"
        type="button"
        class="seg"
        :class="{ on: Number(form.bookSourceType) === value }"
        @click="form.bookSourceType = value"
      >
        {{ label }}
      </button>
    </div>
  </div>

  <div class="section">
    <div class="section-head">
      <h3 class="section-title">阅读同步</h3>
      <span class="section-sub">{{ boot.reader && boot.reader.tokenSaved ? '已保存令牌' : '未保存令牌' }}</span>
    </div>
    <label class="field">
      <span class="flabel">阅读 Web 地址</span>
      <input v-model="form.readerBaseUrl" class="input" placeholder="https://reader.example" autocapitalize="off" spellcheck="false" />
    </label>
    <label class="field">
      <span class="flabel">阅读令牌</span>
      <input v-model="form.readerToken" class="input" type="password" placeholder="留空则保留同 origin 令牌" autocomplete="off" />
      <span class="fhint">令牌写入安全存储后不回显；改 origin 时旧令牌会被清除。</span>
    </label>
  </div>

  <div class="section">
    <div class="section-head">
      <h3 class="section-title">外观</h3>
      <span class="section-sub">10 套主题 · 浅深自动</span>
    </div>
    <slot name="theme"></slot>
  </div>

  <p v-if="error" class="code-block err">{{ error }}</p>

  <div class="section">
    <div class="export-row">
      <button class="btn btn-ghost danger" type="button" @click="emit('exit')">
        <AppIcon name="x" :size="17" /><span>退出应用</span>
      </button>
    </div>
  </div>

  <div class="sheet-foot" style="margin: 8px -20px -18px">
    <button class="btn btn-ghost" type="button" @click="emit('close')">关闭</button>
    <button class="btn btn-primary" type="button" :disabled="saving || !runtimeConnected" :aria-busy="String(saving)" @click="submit">
      <span v-if="saving" class="spin"></span>
      <AppIcon v-else name="check" :size="17" />
      <span>保存设置</span>
    </button>
  </div>
</template>


<style scoped>
.err-text {
  color: var(--bad);
}
</style>
