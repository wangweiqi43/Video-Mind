<script setup>
import { computed, ref } from 'vue'
import BrandMark from './BrandMark.vue'
import AuthIllustration from './AuthIllustration.vue'

const props = defineProps({ loading: { type: Boolean, default: false } })
const emit = defineEmits(['submit'])
const registerMode = ref(false)
const username = ref('')
const password = ref('')
const heading = computed(() => registerMode.value ? '创建账号' : '欢迎回来')

function submit() {
  if (!username.value.trim() || password.value.length < 8 || props.loading) return
  emit('submit', { username: username.value.trim(), password: password.value, registerMode: registerMode.value })
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-story">
      <div>
        <BrandMark />
        <p class="auth-positioning">本地运行的视频理解与知识问答工作台</p>
        <span class="auth-rule"></span>
        <p class="auth-description">
          <svg viewBox="0 0 24 28" aria-hidden="true"><path d="M12 2 21 5v7c0 6-3.7 10.8-9 14-5.3-3.2-9-8-9-14V5l9-3Z"/><path d="m8.5 13 2.2 2.2 4.8-5"/></svg>
          视频解析、知识库与对话，集中在一个工作区。
        </p>
      </div>
      <AuthIllustration />
      <div class="local-status"><span></span>本地模式</div>
    </section>
    <section class="auth-form-zone">
      <form class="auth-form" @submit.prevent="submit">
        <h1>{{ heading }}</h1>
        <label>
          <span>用户名</span>
          <el-input v-model="username" size="large" autocomplete="username" placeholder="请输入用户名" autofocus>
            <template #prefix><svg class="auth-field-icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3.5"/><path d="M5.5 20v-2.1c0-3.1 2.9-5.4 6.5-5.4s6.5 2.3 6.5 5.4V20"/></svg></template>
          </el-input>
        </label>
        <label>
          <span>密码（至少 8 位）</span>
          <el-input v-model="password" size="large" type="password" autocomplete="current-password" show-password placeholder="请输入密码" @keyup.enter="submit">
            <template #prefix><svg class="auth-field-icon" viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="10" width="14" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v2"/></svg></template>
          </el-input>
        </label>
        <el-button class="primary-action auth-submit" native-type="submit" size="large" :loading="loading" :disabled="!username.trim() || password.length < 8">
          {{ registerMode ? '创建账号' : '登录' }}
        </el-button>
        <div class="auth-switch-row">
          <span>{{ registerMode ? '已有账号？' : '没有账号？' }}</span>
          <button type="button" @click="registerMode = !registerMode">{{ registerMode ? '登录' : '注册' }}</button>
        </div>
      </form>
    </section>
  </main>
</template>
