<script setup>
import { computed } from 'vue'
import AppIcon from './Icon.vue'

// MCP CallToolResult 原形渲染：content[] 里的 text 一律 <pre>，绝不 v-html。
const props = defineProps({
  result: { type: Object, default: null },
  error: { type: String, default: '' },
  busy: { type: Boolean, default: false }
})

const blocks = computed(() => {
  const r = props.result
  if (!r || !Array.isArray(r.content)) return []
  return r.content.map((item, index) => {
    if (item && item.type === 'text') return { kind: 'text', text: String(item.text ?? ''), index }
    if (item && item.type === 'image') return { kind: 'image', text: `[图片 ${item.mimeType || ''} ${String(item.data || '').length} 字节]`, index }
    if (item && item.type === 'resource') return { kind: 'text', text: JSON.stringify(item.resource || item, null, 2), index }
    return { kind: 'text', text: JSON.stringify(item, null, 2), index }
  })
})

const isError = computed(() => Boolean(props.result && props.result.isError))
</script>

<template>
  <div class="tool-result">
    <div v-if="busy" class="result-wait">
      <span class="spin"></span>
      <span>正在调用真实工具…</span>
    </div>
    <template v-else>
      <p v-if="error" class="code-block err">{{ error }}</p>
      <template v-else-if="result">
        <div class="result-head">
          <span class="chip" :class="isError ? 'bad' : 'ok'">
            <AppIcon :name="isError ? 'alert' : 'check'" :size="12" />
            {{ isError ? '工具返回错误' : '工具返回成功' }}
          </span>
          <span class="muted small">{{ blocks.length }} 段内容</span>
        </div>
        <pre v-for="block in blocks" :key="block.index" class="code-block tall">{{ block.text }}</pre>
        <p v-if="!blocks.length" class="muted small">工具没有返回内容。</p>
      </template>
    </template>
  </div>
</template>

<style scoped>
.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.code-block + .code-block {
  margin-top: 8px;
}

.result-wait {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 14px;
  border-radius: 13px;
  border: 1px solid var(--line);
  background: var(--field);
  color: var(--muted);
  font-size: 12.5px;
}
</style>
