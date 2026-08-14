<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import App from './App.vue'
import { api } from './api'

const user = ref(null)
const checked = ref(false)
const registerMode = ref(false)
const username = ref('')
const password = ref('')

onMounted(async () => {
  try {
    user.value = await api.me()
  } catch {
    user.value = null
  } finally {
    checked.value = true
  }
})

async function submit() {
  try {
    const result = registerMode.value
      ? await api.register(username.value, password.value)
      : await api.login(username.value, password.value)
    user.value = { username: result.username, subject: result.subject }
  } catch (error) {
    ElMessage.error(error.message || '认证失败')
  }
}

async function logout() {
  await api.logout()
  user.value = null
}
</script>

<template>
  <div v-if="!checked" class="auth-loading">正在加载…</div>
  <main v-else-if="!user" class="auth-shell">
    <section class="auth-card">
      <div class="auth-mark">VM</div>
      <h1>VideoMind</h1>
      <p>登录后继续管理你的视频与知识。</p>
      <el-input v-model="username" placeholder="用户名" />
      <el-input v-model="password" type="password" show-password placeholder="密码（至少 8 位）" @keyup.enter="submit" />
      <el-button type="primary" @click="submit">{{ registerMode ? '创建账号' : '登录' }}</el-button>
      <button class="auth-switch" @click="registerMode = !registerMode">
        {{ registerMode ? '已有账号？登录' : '没有账号？注册' }}
      </button>
    </section>
  </main>
  <template v-else>
    <div class="account-toolbar">
      <span>{{ user.username }}</span>
      <span class="local-mode">本地知识库 · PEC</span>
      <button @click="logout">退出</button>
    </div>
    <App />
  </template>
</template>

<style scoped>
.auth-loading,.auth-shell{min-height:100vh;display:grid;place-items:center;background:radial-gradient(circle at 50% 20%,#282116,#090909 58%);color:#eee}.auth-card{width:min(410px,90vw);display:grid;gap:14px;padding:42px;border:1px solid rgba(231,185,111,.22);border-radius:24px;background:rgba(17,17,18,.94);box-shadow:0 30px 90px #000}.auth-card h1{margin:0}.auth-card p{margin:0 0 15px;color:#999}.auth-mark{width:52px;height:52px;display:grid;place-items:center;border-radius:16px;color:#171008;background:#e7b96f;font-weight:900}.auth-switch{border:0;color:#d7b57c;background:transparent;cursor:pointer}.account-toolbar{position:fixed;z-index:1000;top:18px;right:24px;display:flex;align-items:center;gap:8px}.account-toolbar span{color:#bbb;font-size:12px}.account-toolbar button{padding:7px 11px;border:1px solid rgba(231,185,111,.25);border-radius:999px;color:#d8c29e;background:#16130f;cursor:pointer}.account-toolbar button.bound{color:#111;background:#e7b96f}
.account-toolbar .local-mode{padding:7px 11px;border:1px solid rgba(101,215,79,.25);border-radius:999px;color:#8adf7b;background:rgba(101,215,79,.08)}
</style>
