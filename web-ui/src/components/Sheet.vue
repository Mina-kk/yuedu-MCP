<script setup>
import { ref } from 'vue'
import { useSheetFocus } from '../lib/focus.js'
import AppIcon from './Icon.vue'

defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  eyebrow: { type: String, default: '' },
  wide: { type: Boolean, default: false }
})
const emit = defineEmits(['close'])
const panelRef = ref(null)
useSheetFocus(panelRef, '[data-sheet-focus]')
</script>

<template>
  <div class="sheet-layer">
    <div class="scrim" @click="emit('close')"></div>
    <section
      ref="panelRef"
      class="sheet"
      :class="{ wide }"
      tabindex="-1"
      role="dialog"
      aria-modal="true"
      :aria-label="title"
      @click.stop
    >
      <div class="sheet-handle"></div>
      <div class="sheet-head">
        <div class="head-text">
          <span v-if="eyebrow" class="sheet-eyebrow">{{ eyebrow }}</span>
          <h2 class="sheet-title">{{ title }}</h2>
          <p v-if="subtitle" class="sheet-sub">{{ subtitle }}</p>
        </div>
        <button class="icon-btn" type="button" title="关闭" aria-label="关闭" @click="emit('close')">
          <AppIcon name="x" :size="19" />
        </button>
      </div>
      <div class="sheet-body">
        <slot></slot>
      </div>
      <div v-if="$slots.foot" class="sheet-foot">
        <slot name="foot"></slot>
      </div>
    </section>
  </div>
</template>

<style scoped>
.sheet.wide {
  width: min(720px, 100%);
}
</style>
