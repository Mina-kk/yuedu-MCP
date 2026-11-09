<script setup>
import { ref } from 'vue'
import { useSheetFocus } from '../lib/focus.js'
import AppIcon from './Icon.vue'

const props = defineProps({
  dialog: { type: Object, required: true }
})
const emit = defineEmits(['close', 'accept'])
const panelRef = ref(null)
useSheetFocus(panelRef, '[data-sheet-focus]')

function iconFor(kind) {
  if (kind === 'delete') return 'trash'
  if (kind === 'discard') return 'alert'
  if (kind === 'exit') return 'x'
  return 'alert'
}
</script>

<template>
  <div class="sheet-layer dialog-layer">
    <div class="scrim" @click="emit('close')"></div>
    <div class="dialog-wrap">
      <div
        ref="panelRef"
        class="dialog"
        tabindex="-1"
        role="alertdialog"
        aria-modal="true"
        :aria-label="dialog.title"
        @click.stop
      >
        <div class="dialog-ico" :class="{ neutral: !dialog.danger }">
          <AppIcon :name="iconFor(dialog.kind)" :size="22" />
        </div>
        <h3 class="dialog-title">{{ dialog.title }}</h3>
        <p class="dialog-text">
          <span v-if="dialog.name" class="dialog-name">{{ dialog.name }}</span>
          <span>{{ dialog.message }}</span>
        </p>
        <div class="dialog-actions">
          <button
            v-if="!dialog.hideCancel"
            class="btn btn-ghost"
            type="button"
            data-sheet-focus
            @click="emit('close')"
          >
            {{ dialog.cancelLabel || '取消' }}
          </button>
          <button
            class="btn"
            :class="dialog.danger ? 'btn-danger' : 'btn-primary'"
            type="button"
            :data-sheet-focus="dialog.hideCancel ? '' : undefined"
            @click="emit('accept')"
          >
            <AppIcon :name="dialog.danger ? 'trash' : 'check'" :size="17" />
            <span>{{ dialog.confirmLabel || '确定' }}</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog-ico.neutral {
  background: var(--chip);
  color: var(--text-soft);
}
</style>
