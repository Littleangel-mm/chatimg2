<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()
const visible = ref(false)
const inputKey = ref('')
const error = ref('')

onMounted(() => {
  window.addEventListener('open-key-modal', open)
  if (!userStore.isActivated) {
    open()
  }
})

onUnmounted(() => {
  window.removeEventListener('open-key-modal', open)
})

function open() {
  visible.value = true
  inputKey.value = userStore.keyCode || ''
  error.value = ''
}

function close() {
  if (!userStore.isActivated) return
  visible.value = false
}

async function submit() {
  if (!inputKey.value.trim()) {
    error.value = '请输入密钥'
    return
  }
  error.value = ''
  try {
    await userStore.activate(inputKey.value.trim())
    await userStore.fetchHistory()
    visible.value = false
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '激活失败'
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="visible" class="overlay" @click.self="close">
        <div class="modal card">
          <div class="modal-header">
            <h2>激活密钥</h2>
            <p>输入管理员发放的密钥以开始使用</p>
          </div>
          <input
            v-model="inputKey"
            type="text"
            placeholder="请输入密钥编号"
            class="key-input"
            @keyup.enter="submit"
          />
          <p v-if="error" class="error">{{ error }}</p>
          <button class="btn-primary full" :disabled="userStore.activating" @click="submit">
            {{ userStore.activating ? '验证中...' : '激活' }}
          </button>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 24px;
}

.modal {
  width: 100%;
  max-width: 420px;
  padding: 32px;
}

.modal-header h2 {
  font-size: 22px;
  margin-bottom: 8px;
}

.modal-header p {
  color: var(--text-muted);
  font-size: 14px;
  margin-bottom: 24px;
}

.key-input {
  width: 100%;
  padding: 14px 16px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  color: var(--text);
  font-size: 15px;
  outline: none;
  margin-bottom: 12px;
}

.key-input:focus {
  border-color: var(--accent);
}

.error {
  color: #f87171;
  font-size: 13px;
  margin-bottom: 12px;
}

.full {
  width: 100%;
  padding: 14px;
}
</style>
