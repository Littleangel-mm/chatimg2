<script setup>
import { computed, onActivated, onMounted, ref, defineOptions } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminStore } from '../../stores/admin'

defineOptions({ name: 'admin-users' })

const router = useRouter()
const adminStore = useAdminStore()
const error = ref('')
const message = ref('')
const initialLoad = ref(true)
const resettingId = ref(null)

const users = computed(() => adminStore.users)

function logout() {
  adminStore.logout()
  router.push('/admin')
}

async function loadUsers() {
  error.value = ''
  try {
    await adminStore.fetchUsers()
  } catch (e) {
    error.value = e.message
  } finally {
    initialLoad.value = false
  }
}

async function resetPassword(user) {
  if (!confirm(`确定重置 ${user.email} 的密码？`)) return
  resettingId.value = user.id
  try {
    const data = await adminStore.resetPassword(user.id)
    message.value = `已重置 ${data.email} 的密码，临时密码：${data.tempPassword}`
    setTimeout(() => (message.value = ''), 5000)
  } catch (e) {
    error.value = e.message
  } finally {
    resettingId.value = null
  }
}

function maskKeys(keys) {
  if (!keys?.length) return '暂无'
  return keys.join(' · ')
}

onMounted(loadUsers)
onActivated(loadUsers)
</script>

<template>
  <div class="admin-page">
    <div class="container">
      <div class="page-header">
        <div>
          <h1>用户管理</h1>
          <p>共 {{ users.length }} 个账号</p>
        </div>
        <div class="header-actions">
          <router-link to="/admin/keys" class="btn-ghost tap">密钥管理</router-link>
          <router-link to="/" class="btn-ghost tap">返回首页</router-link>
          <button class="btn-ghost tap" @click="logout">退出登录</button>
        </div>
      </div>

      <p v-if="error" class="error">{{ error }}</p>
      <p v-if="message" class="success">{{ message }}</p>

      <div class="table-wrap card" :class="{ loading: adminStore.userLoading }">
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>注册邮箱</th>
              <th>密码</th>
              <th>绑定密钥</th>
              <th>积分</th>
              <th>状态</th>
              <th>注册时间</th>
              <th>最后登录</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users" :key="user.id">
              <td>{{ user.id }}</td>
              <td>
                <div class="email-cell">
                  <strong>{{ user.email }}</strong>
                  <small>{{ user.username || '未设置昵称' }}</small>
                </div>
              </td>
              <td>
                <span class="mask">{{ user.passwordMask }}</span>
              </td>
              <td>
                <div class="keys">
                  <span v-for="key in user.boundKeys" :key="key" class="key-tag">{{ key }}</span>
                  <span v-if="!user.boundKeys?.length" class="empty-text">暂无</span>
                </div>
                <small class="muted">共 {{ user.boundKeyCount }} 把</small>
              </td>
              <td>{{ user.remainingCredits }}/{{ user.totalCredits }}</td>
              <td>
                <span :class="['status', user.status === 'ACTIVE' ? 'active' : 'disabled']">
                  {{ user.status === 'ACTIVE' ? '正常' : '禁用' }}
                </span>
              </td>
              <td>{{ user.createdAt || '-' }}</td>
              <td>{{ user.lastLoginAt || '-' }}</td>
              <td>
                <button class="btn-sm danger" :disabled="resettingId === user.id" @click="resetPassword(user)">
                  {{ resettingId === user.id ? '重置中...' : '重置密码' }}
                </button>
              </td>
            </tr>
            <tr v-if="!adminStore.userLoading && users.length === 0">
              <td colspan="9" class="empty">暂无用户</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-page { padding: 40px 0 80px; }
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 32px;
  gap: 16px;
  flex-wrap: wrap;
}
.page-header h1 { font-size: 28px; margin-bottom: 4px; }
.page-header p { color: var(--text-muted); font-size: 14px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.error { color: var(--danger); margin-bottom: 12px; font-size: 14px; }
.success { color: var(--success); margin-bottom: 12px; font-size: 14px; }
.table-wrap { overflow-x: auto; }
.table-wrap.loading { opacity: 0.65; pointer-events: none; }
table { width: 100%; border-collapse: collapse; font-size: 14px; }
th, td { padding: 14px 16px; text-align: left; border-bottom: 1px solid var(--border); vertical-align: top; }
th { color: var(--text-muted); font-size: 12px; text-transform: uppercase; }
.email-cell { display: flex; flex-direction: column; gap: 4px; }
.email-cell small, .muted { color: var(--text-muted); font-size: 12px; }
.mask { font-family: monospace; letter-spacing: 0.08em; color: var(--text-muted); }
.keys { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 4px; }
.key-tag {
  display: inline-flex;
  align-items: center;
  padding: 3px 8px;
  border-radius: 999px;
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  color: var(--accent-hover);
  font-size: 12px;
}
.empty-text { color: var(--text-muted); font-size: 13px; }
.status { padding: 2px 10px; border-radius: 999px; font-size: 12px; }
.status.active { background: rgba(138, 169, 127, 0.18); color: var(--success); }
.status.disabled { background: rgba(210, 115, 138, 0.16); color: var(--danger); }
.btn-sm {
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  color: var(--text);
}
.btn-sm.danger { color: var(--danger); }
.btn-sm:disabled { opacity: 0.5; cursor: not-allowed; }
.empty { text-align: center; color: var(--text-muted); padding: 40px !important; }

@media (max-width: 768px) {
  .admin-page {
    padding: 24px 0 48px;
  }

  .page-header {
    margin-bottom: 20px;
  }

  .page-header h1 {
    font-size: 24px;
  }

  .header-actions {
    width: 100%;
  }

  .header-actions > * {
    flex: 1 1 96px;
    text-align: center;
  }

  .table-wrap {
    margin: 0 -10px;
    border-radius: 0;
  }

  table {
    min-width: 920px;
  }

  th,
  td {
    padding: 12px 10px;
  }
}
</style>
