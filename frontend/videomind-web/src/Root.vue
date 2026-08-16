<script setup>
import { defineAsyncComponent, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AuthScreen from './components/AuthScreen.vue'
import { api } from './api'

const WorkspaceApp = defineAsyncComponent(() => import('./App.vue'))
const user = ref(null)
const checked = ref(false)
const authenticating = ref(false)

onMounted(async () => {
  try {
    user.value = await api.me()
  } catch {
    user.value = null
  } finally {
    checked.value = true
  }
})

async function authenticate({ username, password, registerMode }) {
  authenticating.value = true
  try {
    const result = registerMode
      ? await api.register(username, password)
      : await api.login(username, password)
    user.value = { username: result.username, subject: result.subject }
  } catch (error) {
    ElMessage.error(error.message || '认证失败')
  } finally {
    authenticating.value = false
  }
}

async function logout() {
  try {
    await api.logout()
  } finally {
    user.value = null
  }
}
</script>

<template>
  <div v-if="!checked" class="boot-screen" aria-live="polite">
    <span class="boot-mark"></span>
    <p>正在打开 VideoMind…</p>
  </div>
  <AuthScreen v-else-if="!user" :loading="authenticating" @submit="authenticate" />
  <WorkspaceApp v-else :user="user" @logout="logout" />
</template>
