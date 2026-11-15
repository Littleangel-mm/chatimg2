<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useUserStore } from '../stores/user'

const router = useRouter()
const authStore = useAuthStore()
const userStore = useUserStore()

const account = ref('')
const password = ref('')
const error = ref('')

async function submit() {
  error.value = ''
  try {
    const user = await authStore.login({
      account: account.value.trim(),
      password: password.value
    })
    userStore.logout()
    router.push(user.role === 'ADMIN' ? '/admin/keys' : '/')
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '登录失败'
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card card">
      <h1>登录</h1>
      <p class="subtitle">使用 QQ 邮箱和密码登录</p>

      <form @submit.prevent="submit">
        <div class="field">
          <label>QQ 邮箱</label>
          <input v-model="account" type="email" autocomplete="email" placeholder="请输入 QQ 邮箱" />
        </div>

        <div class="field">
          <label>密码</label>
          <input v-model="password" type="password" autocomplete="current-password" />
        </div>

        <p v-if="error" class="error">{{ error }}</p>
        <button type="submit" class="btn-primary full tap" :disabled="authStore.loading || !account.trim() || !password">
          {{ authStore.loading ? '登录中...' : '登录' }}
        </button>
      </form>

      <router-link to="/register" class="switch-link">注册账号</router-link>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: calc(100vh - 64px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.auth-card {
  width: 100%;
  max-width: 420px;
  padding: 40px;
}

.auth-card h1 { font-size: 26px; margin-bottom: 6px; }

.subtitle {
  color: var(--text-muted);
  font-size: 14px;
  margin-bottom: 30px;
}

.field { margin-bottom: 16px; }

.field label {
  display: block;
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 6px;
}

.field input {
  width: 100%;
  padding: 12px 14px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text);
  font-size: 15px;
  outline: none;
}

.field input:focus { border-color: var(--accent); }

.full { width: 100%; padding: 14px; }

.error { color: var(--danger); font-size: 13px; margin-bottom: 12px; }

.switch-link {
  display: block;
  margin-top: 18px;
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
}
</style>
