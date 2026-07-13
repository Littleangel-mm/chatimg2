<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const email = ref('')
const password = ref('')
const confirmPassword = ref('')
const code = ref('')
const error = ref('')
const sent = ref(false)

async function sendCode() {
  error.value = ''
  try {
    await authStore.sendCode(email.value.trim(), 'REGISTER')
    sent.value = true
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '验证码发送失败'
  }
}

async function submit() {
  error.value = ''
  if (password.value.length < 6) {
    error.value = '密码至少 6 位'
    return
  }
  if (password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  try {
    await authStore.register(email.value.trim(), password.value, code.value.trim())
    router.push('/')
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '注册失败'
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card card">
      <h1>注册账号</h1>
      <p class="subtitle">使用邮箱接收验证码</p>

      <form @submit.prevent="submit">
        <div class="field">
          <label>QQ 邮箱</label>
          <input v-model="email" type="email" autocomplete="email" placeholder="请输入 QQ 邮箱" />
        </div>

        <div class="field">
          <label>密码</label>
          <input v-model="password" type="password" autocomplete="new-password" />
        </div>

        <div class="field">
          <label>确认密码</label>
          <input v-model="confirmPassword" type="password" autocomplete="new-password" />
        </div>

        <div class="field">
          <label>验证码</label>
          <div class="code-row">
            <input v-model="code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" />
            <button type="button" class="btn-ghost code-btn" :disabled="authStore.sendingCode || !email" @click="sendCode">
              {{ sent ? '重新发送' : '发送验证码' }}
            </button>
          </div>
        </div>

        <p v-if="error" class="error">{{ error }}</p>
        <button type="submit" class="btn-primary full tap" :disabled="authStore.loading || !email.trim() || !password || !confirmPassword || !code.trim()">
          {{ authStore.loading ? '注册中...' : '注册并登录' }}
        </button>
      </form>

      <router-link to="/login" class="switch-link">已有账号，去登录</router-link>
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

.code-row {
  display: grid;
  grid-template-columns: 1fr 112px;
  gap: 8px;
}

.code-btn {
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text);
  font-size: 13px;
}

.full { width: 100%; padding: 14px; }

.error { color: var(--danger); font-size: 13px; margin-bottom: 12px; }

.switch-link {
  display: block;
  margin-top: 18px;
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
}

@media (max-width: 520px) {
  .auth-page {
    align-items: flex-start;
    padding: 18px 12px;
  }

  .auth-card {
    padding: 28px 18px;
  }

  .auth-card h1 {
    font-size: 24px;
  }

  .code-row {
    grid-template-columns: 1fr;
  }

  .code-btn {
    width: 100%;
  }
}
</style>
