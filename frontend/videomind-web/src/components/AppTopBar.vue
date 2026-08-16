<script setup>
import BrandMark from './BrandMark.vue'
defineProps({
  user: { type: Object, required: true },
  knowledgeLoading: { type: Boolean, default: false }
})
defineEmits(['create-knowledge', 'logout'])
</script>

<template>
  <header class="app-topbar">
    <BrandMark compact />
    <span class="topbar-divider"></span>
    <div class="runtime-status"><span></span>本地模式（已连接）</div>
    <button
      type="button"
      class="topbar-knowledge-button"
      :disabled="knowledgeLoading"
      @click="$emit('create-knowledge')"
    >
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11v16H6.5A2.5 2.5 0 0 0 4 21.5v-16ZM20 5.5A2.5 2.5 0 0 0 17.5 3H13v16h4.5a2.5 2.5 0 0 1 2.5 2.5v-16ZM17 8v5m-2.5-2.5h5"/></svg>
      新建文档知识库
    </button>
    <div class="topbar-account">
      <span class="user-avatar">{{ user.username?.slice(0, 1)?.toUpperCase() }}</span>
      <strong>{{ user.username }}</strong>
      <button type="button" class="logout-button" @click="$emit('logout')" aria-label="退出登录">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 5H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4m5-4 4-3-4-3m4 3H9" /></svg>退出
      </button>
    </div>
  </header>
</template>
