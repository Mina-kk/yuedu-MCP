<script setup>
import AppIcon from './Icon.vue'

defineProps({ toast: { type: Object, default: null } })
</script>

<template>
  <div class="toast-host" aria-live="polite" aria-atomic="true">
    <div v-if="toast" :key="toast.seq" class="toast" :class="{ err: toast.kind === 'err' }">
      <span class="toast-ico">
        <AppIcon :name="toast.kind === 'err' ? 'x' : 'check'" :size="15" />
      </span>
      <span>{{ toast.text }}</span>
    </div>
  </div>
</template>
