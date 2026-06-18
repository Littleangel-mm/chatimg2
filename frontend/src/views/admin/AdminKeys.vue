<script setup>
import { ref, computed, onMounted, onActivated, defineOptions } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminStore } from '../../stores/admin'

defineOptions({ name: 'admin-keys' })

const router = useRouter()
const adminStore = useAdminStore()

const newCredits = ref(100)
const editId = ref(null)
const editCredits = ref(100)
const error = ref('')
const message = ref('')
const creating = ref(false)
const initialLoad = ref(true)

const pageDisplay = computed(() => adminStore.page + 1)
const showSkeleton = computed(() => initialLoad.value && adminStore.keys.length === 0)

async function loadKeys(p = adminStore.page, background = false) {
  try {
    await adminStore.fetchKeys(p, { background })
  } catch (e) {
    error.value = e.message
  } finally {
    initialLoad.value = false
  }
}

onMounted(() => loadKeys(0, !!adminStore.keys.length))
onActivated(() => loadKeys(adminStore.page, true))

function logout() {
  adminStore.logout()
  router.push('/admin')
}

async function addKey() {
  error.value = ''
  creating.value = true
  try {
    const key = await adminStore.createKey(newCredits.value)
    message.value = `密钥已生成：${key.keyCode}`
    newCredits.value = 100
    setTimeout(() => message.value = '', 4000)
  } catch (e) {
    error.value = e.response?.data?.message || e.message
  } finally {
    creating.value = false
  }
}

function copyKey(code) {
  navigator.clipboard.writeText(code)
  message.value = '已复制密钥'
  setTimeout(() => message.value = '', 1500)
}

function startEdit(key) {
  editId.value = key.id
  editCredits.value = key.totalCredits
}

async function saveEdit() {
  try {
    await adminStore.updateKey(editId.value, editCredits.value)
    editId.value = null
    message.value = '更新成功'
    setTimeout(() => message.value = '', 2000)
  } catch (e) {
    error.value = e.response?.data?.message || e.message
  }
}

async function removeKey(id) {
  if (!confirm('确定删除此密钥？相关生成记录也会删除。')) return
  try {
    await adminStore.deleteKey(id)
    message.value = '删除成功'
    setTimeout(() => message.value = '', 2000)
  } catch (e) {
    error.value = e.response?.data?.message || e.message
  }
}

function goPage(p) {
  if (p < 0 || p >= adminStore.totalPages || adminStore.tableLoading) return
  loadKeys(p, false).catch(e => { error.value = e.message })
}
</script>

<template>
  <div class="admin-page">
    <div class="container">
      <div class="page-header">
        <div>
          <h1>密钥管理</h1>
          <p>管理员：{{ adminStore.admin?.username }} · 共 {{ adminStore.total }} 条</p>
        </div>
        <div class="header-actions">
          <router-link to="/" class="btn-ghost tap">返回首页</router-link>
          <button class="btn-ghost tap" @click="logout">退出登录</button>
        </div>
      </div>

      <div class="add-card card">
        <div class="add-row">
          <div>
            <h3>添加密钥</h3>
            <p class="add-desc">系统自动生成密钥编号，默认 100 积分</p>
          </div>
          <div class="add-form">
            <input v-model.number="newCredits" type="number" min="1" placeholder="积分" class="input input-sm" />
            <button class="btn-primary tap" :disabled="creating" @click="addKey">
              {{ creating ? '生成中...' : '+ 添加密钥' }}
            </button>
          </div>
        </div>
      </div>

      <p v-if="error" class="error">{{ error }}</p>
      <p v-if="message" class="success">{{ message }}</p>

      <div class="table-wrap card" :class="{ loading: adminStore.tableLoading }">
        <div v-if="showSkeleton" class="skeleton-wrap">
          <div v-for="i in 5" :key="i" class="skeleton-row" />
        </div>
        <table v-else>
          <thead>
            <tr>
              <th>ID</th>
              <th>密钥编号</th>
              <th>总积分</th>
              <th>已用</th>
              <th>剩余</th>
              <th>状态</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="key in adminStore.keys" :key="key.id">
              <td>{{ key.id }}</td>
              <td>
                <code>{{ key.keyCode }}</code>
                <button class="btn-copy" title="复制" @click="copyKey(key.keyCode)">📋</button>
              </td>
              <td>
                <template v-if="editId === key.id">
                  <input v-model.number="editCredits" type="number" class="input input-xs" />
                </template>
                <template v-else>{{ key.totalCredits }}</template>
              </td>
              <td>{{ key.usedCredits }}</td>
              <td>{{ key.totalCredits - key.usedCredits }}</td>
              <td>
                <span :class="['status', key.status === 1 ? 'active' : 'disabled']">
                  {{ key.status === 1 ? '启用' : '禁用' }}
                </span>
              </td>
              <td class="time">{{ key.createdAt?.replace('T', ' ').slice(0, 19) }}</td>
              <td class="actions">
                <template v-if="editId === key.id">
                  <button class="btn-sm save" @click="saveEdit">保存</button>
                  <button class="btn-sm" @click="editId = null">取消</button>
                </template>
                <template v-else>
                  <button class="btn-sm" @click="startEdit(key)">改积分</button>
                  <button class="btn-sm danger" @click="removeKey(key.id)">删除</button>
                </template>
              </td>
            </tr>
            <tr v-if="adminStore.keys.length === 0">
              <td colspan="8" class="empty">暂无密钥，点击上方添加</td>
            </tr>
          </tbody>
        </table>

        <div v-if="!showSkeleton && adminStore.totalPages > 0" class="pagination">
          <button class="page-btn tap" :disabled="adminStore.page === 0 || adminStore.tableLoading" @click="goPage(adminStore.page - 1)">
            上一页
          </button>
          <span class="page-info">
            第 {{ pageDisplay }} / {{ adminStore.totalPages }} 页
          </span>
          <button
            class="page-btn tap"
            :disabled="adminStore.page >= adminStore.totalPages - 1 || adminStore.tableLoading"
            @click="goPage(adminStore.page + 1)"
          >
            下一页
          </button>
        </div>
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
  flex-wrap: wrap;
  gap: 16px;
}

.page-header h1 { font-size: 28px; margin-bottom: 4px; }
.page-header p { color: var(--text-muted); font-size: 14px; }
.header-actions { display: flex; gap: 8px; }

.add-card { padding: 24px; margin-bottom: 24px; }

.add-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
}

.add-card h3 { font-size: 16px; margin-bottom: 4px; }
.add-desc { color: var(--text-muted); font-size: 13px; }

.add-form { display: flex; gap: 12px; align-items: center; }

.input {
  padding: 10px 14px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text);
  font-size: 14px;
  outline: none;
}

.input-sm { width: 100px; }
.input-xs { width: 80px; padding: 4px 8px; }
.input:focus { border-color: var(--accent); }

.error { color: #f87171; margin-bottom: 12px; font-size: 14px; }
.success { color: #4ade80; margin-bottom: 12px; font-size: 14px; }

.table-wrap { overflow-x: auto; transition: opacity 0.15s; }
.table-wrap.loading { opacity: 0.65; pointer-events: none; }

.skeleton-wrap { padding: 16px; }

.skeleton-row {
  height: 48px;
  margin-bottom: 8px;
  border-radius: 8px;
  background: linear-gradient(90deg, var(--bg-input) 25%, #252530 50%, var(--bg-input) 75%);
  background-size: 200% 100%;
  animation: shimmer 1s infinite;
}

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

table { width: 100%; border-collapse: collapse; font-size: 14px; }

th, td {
  padding: 14px 16px;
  text-align: left;
  border-bottom: 1px solid var(--border);
}

th {
  color: var(--text-muted);
  font-weight: 500;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

code {
  background: var(--bg-input);
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
}

.btn-copy {
  margin-left: 6px;
  background: none;
  font-size: 14px;
  opacity: 0.5;
  transition: opacity 0.2s;
}

.btn-copy:hover { opacity: 1; }

.status {
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
}

.status.active { background: rgba(74, 222, 128, 0.15); color: #4ade80; }
.status.disabled { background: rgba(248, 113, 113, 0.15); color: #f87171; }

.time { color: var(--text-muted); font-size: 13px; white-space: nowrap; }
.actions { display: flex; gap: 6px; white-space: nowrap; }

.btn-sm {
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  background: rgba(255,255,255,0.06);
  color: var(--text);
}

.btn-sm:hover { background: rgba(255,255,255,0.1); }
.btn-sm.save { background: rgba(124, 92, 255, 0.2); color: #b49cff; }
.btn-sm.danger { color: #f87171; }
.btn-sm.danger:hover { background: rgba(248, 113, 113, 0.15); }

.empty { text-align: center; color: var(--text-muted); padding: 40px !important; }

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 20px;
  border-top: 1px solid var(--border);
}

.page-btn {
  padding: 8px 16px;
  border-radius: 8px;
  background: rgba(255,255,255,0.06);
  color: var(--text);
  font-size: 14px;
  transition: background 0.12s, transform 0.1s;
}

.page-btn:hover:not(:disabled) { background: rgba(255,255,255,0.1); }
.page-btn:disabled { opacity: 0.3; cursor: not-allowed; }

.page-info { color: var(--text-muted); font-size: 14px; }
</style>
