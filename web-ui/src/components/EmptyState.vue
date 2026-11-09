<script setup>
import AppIcon from './Icon.vue'

defineProps({
  title: { type: String, default: '' },
  text: { type: String, default: '' },
  icon: { type: String, default: 'sparkles' },
  busy: { type: Boolean, default: false }
})
</script>

<template>
  <div class="empty">
    <div class="empty-art">
      <span class="empty-ring"></span>
      <span class="empty-ico">
        <span v-if="busy" class="spin"></span>
        <AppIcon v-else :name="icon" :size="21" />
      </span>
    </div>
    <h3 class="empty-title">{{ title }}</h3>
    <p class="empty-sub">{{ text }}</p>
    <slot></slot>
  </div>
</template>
