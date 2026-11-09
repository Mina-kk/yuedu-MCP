<script setup>
import { computed } from 'vue'

const props = defineProps({
  palette: { type: Object, required: true },
  size: { type: [Number, String], default: 30 }
})

// 每套色板一个固定 id：不会在多个地方生成随机 / 全局重复的渐变 id。
const gradientId = computed(() => `studio-swatch-${props.palette.key}`)
const side = computed(() => String(Number(props.size) || 30))
</script>

<template>
  <svg
    class="swatch"
    :width="side"
    :height="side"
    viewBox="0 0 32 32"
    role="img"
    :aria-label="palette.name"
    focusable="false"
    shape-rendering="geometricPrecision"
  >
    <defs>
      <linearGradient :id="gradientId" x1="0" y1="0" x2="1" y2="1">
        <stop offset="50%" :stop-color="palette.c1" />
        <stop offset="50%" :stop-color="palette.c2" />
      </linearGradient>
    </defs>
    <circle cx="16" cy="16" r="15" :fill="`url(#${gradientId})`" stroke-width="1" />
  </svg>
</template>

<style scoped>
.swatch {
  display: block;
  flex: none;
  width: 30px;
  height: 30px;
  aspect-ratio: 1 / 1;
}
</style>
