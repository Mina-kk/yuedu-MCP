<script setup>
import AppIcon from './Icon.vue'
import ThemeSwatch from './ThemeSwatch.vue'

// 主题选择：10 套 SVG 双色球 + 三模式（亮色/暗色/跟随系统）。
import { PALETTES, MODES, modeLabel } from '../lib/palettes.js'

defineProps({
  themeIdx: { type: Number, default: 0 },
  mode: { type: String, default: 'auto' }
})
const emit = defineEmits(['pick-theme', 'pick-mode'])

function modeIcon(mode) {
  return mode === 'light' ? 'sun' : mode === 'dark' ? 'moon' : 'auto'
}
</script>

<template>
  <div class="section">
    <div class="section-head">
      <h3 class="section-title">外观</h3>
      <span class="section-sub">{{ modeLabel(mode) }}</span>
    </div>
    <div class="segmented mode">
      <button
        v-for="m in MODES"
        :key="m"
        type="button"
        class="seg"
        :class="{ on: mode === m }"
        :aria-pressed="String(mode === m)"
        @click="emit('pick-mode', m)"
      >
        <AppIcon :name="modeIcon(m)" :size="16" />
        <span>{{ modeLabel(m) }}</span>
      </button>
    </div>
  </div>

  <div class="section">
    <div class="section-head">
      <h3 class="section-title">主题色</h3>
      <span class="section-sub">10 套配色</span>
    </div>
    <div class="palette-grid">
      <button
        v-for="(p, index) in PALETTES"
        :key="p.key"
        type="button"
        class="palette"
        :class="{ on: themeIdx === index }"
        :aria-pressed="String(themeIdx === index)"
        :aria-label="`主题色：${p.name}`"
        @click="emit('pick-theme', index)"
      >
        <ThemeSwatch :palette="p" :size="30" />
        <span class="palette-name">{{ p.name }}</span>
        <span class="palette-check">
          <AppIcon v-if="themeIdx === index" name="check" :size="15" />
        </span>
      </button>
    </div>
  </div>
</template>
