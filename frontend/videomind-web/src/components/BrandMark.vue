<script setup>
defineProps({ compact: { type: Boolean, default: false } })
</script>

<template>
  <span class="brand-lockup" :class="{ compact }" aria-label="VideoMind">
    <svg class="brand-symbol" viewBox="0 0 40 40" aria-hidden="true">
      <defs>
        <linearGradient id="vm-brand-gradient" x1="4" y1="4" x2="36" y2="36" gradientUnits="userSpaceOnUse">
          <stop stop-color="#6366f1" />
          <stop offset="1" stop-color="#4338ca" />
        </linearGradient>
      </defs>
      <path fill="url(#vm-brand-gradient)" d="M8 4.8c1.3-.8 2.9-.8 4.2 0l20.1 12.1a3.7 3.7 0 0 1 0 6.3L12.2 35.3A4 4 0 0 1 6 31.9V8.2a4 4 0 0 1 2-3.4Z" />
      <path fill="#fff" d="M14.2 12.8c0-1.1 1.2-1.8 2.2-1.2l12.2 7.3a1.4 1.4 0 0 1 0 2.4l-12.2 7.3a1.4 1.4 0 0 1-2.2-1.2v-4.3l5.5-3-5.5-3.1v-4.2Z" />
    </svg>
    <strong>VideoMind</strong>
  </span>
</template>
