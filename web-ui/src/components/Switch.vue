<script setup>
defineProps({
  modelValue: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  label: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])
</script>

<template>
  <button
    type="button"
    class="switch"
    :class="{ on: modelValue }"
    role="switch"
    :aria-checked="String(modelValue)"
    :aria-label="label || undefined"
    :disabled="disabled"
    @click="emit('update:modelValue', !modelValue)"
  >
    <i></i>
  </button>
</template>
