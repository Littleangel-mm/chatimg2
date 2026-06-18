<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminStore } from '../../stores/admin'
import { prefetchRoute } from '../../utils/prefetch'

const router = useRouter()
const adminStore = useAdminStore()

const username = ref('admin')
const password = ref('')
const error = ref('')

async function submit() {
  error.value = ''
  try {
    await adminStore.login(username.value, password.value)
    prefetchRoute('admin-keys')
    router.push('/admin/keys')
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '登录失败'
  }
}
</script>

<template>
  <div class="admin-page">
    <div class="login-card card">
      <h1>管理后台</h1>
      <p class="subtitle">ChatImg2 Admin</p>

      <form @submit.prevent="submit">
        <div class="field">
          <label>用户名</label>
          <input v-model="username" type="text" autocomplete="username" />
        </div>
        <div class="field">
          <label>密码</label>
          <input v-model="password" type="password" autocomplete="current-password" />
        </div>
        <p v-if="error" class="error">{{ error }}</p>
        <button type="submit" class="btn-primary full tap" :disabled="adminStore.loading">
          {{ adminStore.loading ? '登录中...' : '登录' }}
        </button>
      </form>

      <router-link to="/" class="back-link tap">← 返回首页</router-link>
    </div>
  </div>
</template>

<style scoped>
.admin-page {
  min-height: calc(100vh - 64px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 40px;
}

.login-card h1 { font-size: 24px; margin-bottom: 4px; }

.subtitle {
  color: var(--text-muted);
  font-size: 14px;
  margin-bottom: 32px;
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

.error { color: #f87171; font-size: 13px; margin-bottom: 12px; }

.full { width: 100%; padding: 14px; }

.back-link {
  display: block;
  text-align: center;
  margin-top: 24px;
  color: var(--text-muted);
  font-size: 14px;
}

.back-link:hover { color: var(--text); }
</style>
