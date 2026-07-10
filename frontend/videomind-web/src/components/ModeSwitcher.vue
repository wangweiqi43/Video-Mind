<script setup>
defineProps({
  modelValue: {
    type: String,
    default: 'normal'
  }
})

const emit = defineEmits(['update:modelValue'])
</script>

<template>
  <div class="app-mode-switcher" aria-label="运行模式" data-testid="mode-switcher">
    <button
      type="button"
      :class="{ active: modelValue === 'normal' }"
      data-testid="mode-normal"
      @click="emit('update:modelValue', 'normal')"
    >
      普通模式
    </button>
    <button
      type="button"
      :class="{ active: modelValue === 'advanced' }"
      data-testid="mode-advanced"
      @click="emit('update:modelValue', 'advanced')"
    >
      高级模式 <span>✨</span>
    </button>
  </div>
</template>

<style scoped>
.app-mode-switcher {
  display: inline-flex;
  padding: 4px;
  border: 1px solid rgba(231, 185, 111, 0.22);
  border-radius: 999px;
  background: rgba(10, 10, 12, 0.74);
  box-shadow: 0 14px 36px rgba(0, 0, 0, 0.24);
  backdrop-filter: blur(16px);
}

button {
  min-width: 104px;
  padding: 8px 14px;
  border: 0;
  border-radius: 999px;
  color: var(--muted);
  background: transparent;
  cursor: pointer;
  font-family: "Aptos", "Microsoft YaHei", sans-serif;
  font-size: 13px;
  font-weight: 800;
  transition: 0.18s ease;
}

button:hover {
  color: var(--text);
}

button.active {
  color: #171008;
  background: linear-gradient(135deg, #ffd993, var(--gold) 58%, #a16d32);
  box-shadow: 0 8px 20px rgba(231, 185, 111, 0.2);
}

button span {
  margin-left: 3px;
}
</style>
