<script setup>
import { ref, watch, nextTick, onMounted, onActivated, computed } from 'vue'
import { useConversationsStore } from '../../stores/conversations'
import { useUserStore } from '../../stores/user'
import { useAuthStore } from '../../stores/auth'
import { compressImage } from '../../utils/image'
import ChatMessage from './ChatMessage.vue'

const convStore = useConversationsStore()
const userStore = useUserStore()
const authStore = useAuthStore()

const prompt = ref('')
const model = ref('gpt-image-2')
const sourcePreview = ref('')
const sourceBase64 = ref('')
const error = ref('')
const uploading = ref(false)
const messagesEl = ref(null)
const promptEl = ref(null)

const models = [
  { id: 'gpt-image-2', name: 'GPT Image 2' },
  { id: 'gpt-image-1', name: 'GPT Image' },
  { id: 'dall-e-2', name: 'DALL-E 2' },
  { id: 'dall-e-3', name: 'DALL-E 3' }
]

const conversation = () => convStore.activeConversation

const avatarText = computed(() => {
  const source = authStore.user?.email || authStore.user?.username || userStore.keyCode || 'U'
  return source.trim().charAt(0).toUpperCase() || 'U'
})

const showAccountMenu = ref(false)

const accountInfo = computed(() => ({
  email: authStore.user?.email || '未登录',
  name: authStore.user?.username || authStore.user?.email?.split('@')[0] || '未设置',
  role: authStore.user?.role === 'ADMIN' ? '管理员' : authStore.user?.role === 'USER' ? '用户' : '未登录',
  credits: authStore.user?.remainingCredits ?? userStore.remainingCredits,
  keyCode: authStore.isAdmin ? '管理员不绑定密钥' : userStore.keyCode || '未激活'
}))

watch(
  () => convStore.activeConversation?.messages?.length,
  () => scrollToBottom()
)

watch(
  () => convStore.activeId,
  () => {
    prompt.value = ''
    error.value = ''
    if (conversation()?.type !== 'img2img') {
      clearSource()
    }
    nextTick(() => {
      scrollToBottom()
      resizePrompt()
    })
  }
)

watch(prompt, () => nextTick(resizePrompt))

function resizePrompt() {
  const el = promptEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 160)}px`
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesEl.value) {
      messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    }
  })
}

async function onFileChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (!file.type.startsWith('image/')) {
    error.value = '\u8bf7\u4e0a\u4f20\u56fe\u7247\u6587\u4ef6'
    return
  }
  uploading.value = true
  error.value = ''
  try {
    const compressed = await compressImage(file)
    sourcePreview.value = compressed
    sourceBase64.value = compressed
  } catch {
    error.value = '\u56fe\u7247\u5904\u7406\u5931\u8d25'
  } finally {
    uploading.value = false
    e.target.value = ''
  }
}

function clearSource() {
  sourcePreview.value = ''
  sourceBase64.value = ''
}

function openKeyModal() {
  window.dispatchEvent(new CustomEvent('open-key-modal'))
}

function toggleAccountMenu() {
  showAccountMenu.value = !showAccountMenu.value
}

async function send() {
  if (!userStore.isActivated) {
    openKeyModal()
    return
  }

  const text = prompt.value.trim()
  if (!text) {
    error.value = '\u8bf7\u8f93\u5165\u63cf\u8ff0'
    return
  }

  const conv = convStore.ensureActive(conversation()?.type || 'text2img')
  const isImg2img = conv.type === 'img2img'

  if (isImg2img && !sourceBase64.value) {
    error.value = '\u8bf7\u5148\u4e0a\u4f20\u53c2\u8003\u56fe\u7247'
    return
  }

  error.value = ''
  const exchange = convStore.addExchange(conv.id, {
    prompt: text,
    sourceImage: isImg2img ? sourceBase64.value : null,
    type: conv.type,
    model: model.value
  })

  prompt.value = ''
  scrollToBottom()

  try {
    const record = await userStore.generate({
      prompt: text,
      type: conv.type,
      model: model.value,
      sourceImage: isImg2img ? sourceBase64.value : undefined
    })
    convStore.syncRecord(record)
    convStore.attachRecordToMessage(exchange?.assistantMsg?.id, record)
    if (isImg2img) clearSource()
  } catch (e) {
    const msg = e.response?.data?.message || e.message || '\u751f\u6210\u5931\u8d25'
    convStore.failLatestAssistant(msg)
    error.value = msg
  }

  scrollToBottom()
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    if (!userStore.generating) send()
  }
}

function applyPendingPrompt() {
  const text = convStore.consumePendingPrompt()
  if (!text) return
  let conv = conversation()
  if (!conv || conv.type !== 'text2img') {
    conv = convStore.createConversation('text2img')
  }
  error.value = ''
  prompt.value = text
  nextTick(() => {
    resizePrompt()
    promptEl.value?.focus()
    scrollToBottom()
  })
}

onMounted(() => {
  applyPendingPrompt()
  if (userStore.isActivated) {
    userStore.fetchHistory().catch(() => {
      userStore.resumePendingGenerations().catch(() => {})
    })
  }
})
onActivated(applyPendingPrompt)
</script>

<template>
  <div class="chat-main">
    <header class="chat-header">
      <button
        v-if="!convStore.sidebarOpen"
        class="menu-btn"
        @click="convStore.sidebarOpen = true"
      >
        &#9776;
      </button>
      <div class="header-title">
        <h1>{{ conversation()?.title || 'img2.ai' }}</h1>
        <span v-if="conversation()" class="type-badge">
          {{ conversation().type === 'img2img' ? '\u56fe\u751f\u56fe' : '\u6587\u751f\u56fe' }}
        </span>
      </div>
      <div class="avatar-wrap">
        <button class="user-avatar" :title="authStore.user?.email || '账号信息'" aria-label="账号信息" @click.stop="toggleAccountMenu">
          {{ avatarText }}
        </button>
        <Transition name="fade">
          <div v-if="showAccountMenu" class="account-popover" @click.stop>
            <div class="account-top">
              <div class="account-avatar">{{ avatarText }}</div>
              <div class="account-meta">
                <strong>{{ accountInfo.name }}</strong>
                <span>{{ accountInfo.role }}</span>
              </div>
            </div>
            <dl class="account-list">
              <div>
                <dt>邮箱</dt>
                <dd>{{ accountInfo.email }}</dd>
              </div>
              <div>
                <dt>身份</dt>
                <dd>{{ accountInfo.role }}</dd>
              </div>
              <div>
                <dt>积分</dt>
                <dd>{{ accountInfo.credits }}</dd>
              </div>
              <div>
                <dt>密钥</dt>
                <dd>{{ accountInfo.keyCode }}</dd>
              </div>
            </dl>
          </div>
        </Transition>
      </div>
    </header>

    <div v-if="!conversation()" class="welcome">
      <div class="welcome-inner">
        <h2>&#x4ECA;&#x5929;&#x60F3;&#x521B;&#x4F5C;&#x4EC0;&#x4E48;&#xFF1F;</h2>
        <p>&#x4ECE;&#x5DE6;&#x4FA7;&#x65B0;&#x5EFA;&#x5BF9;&#x8BDD;&#xFF0C;&#x6216;&#x5F00;&#x59CB;&#x6587;&#x751F;&#x56FE; / &#x56FE;&#x751F;&#x56FE;</p>
        <div class="welcome-actions">
          <button class="welcome-btn" @click="convStore.createConversation('text2img')">&#x65B0;&#x5BF9;&#x8BDD;</button>
          <button class="welcome-btn" @click="convStore.createConversation('img2img')">&#x65B0;&#x56FE;&#x751F;&#x56FE;</button>
        </div>
      </div>
    </div>

    <div v-else ref="messagesEl" class="messages">
      <div v-if="conversation().messages.length === 0" class="empty-chat">
        <p>{{ conversation().type === 'img2img' ? '\u4e0a\u4f20\u53c2\u8003\u56fe\u5e76\u63cf\u8ff0\u60f3\u8981\u7684\u6548\u679c' : '\u63cf\u8ff0\u4f60\u60f3\u751f\u6210\u7684\u753b\u9762' }}</p>
      </div>
      <ChatMessage
        v-for="msg in conversation().messages"
        :key="msg.id"
        :message="msg"
      />
    </div>

    <div v-if="conversation()" class="input-area">
      <div class="input-card">
        <div v-if="conversation().type === 'img2img'" class="upload-row">
          <div v-if="uploading" class="upload-chip loading">&#x538B;&#x7F29;&#x4E2D;...</div>
          <div v-else-if="sourcePreview" class="upload-chip">
            <img :src="sourcePreview" alt="&#x53C2;&#x8003;&#x56FE;" />
            <button class="clear" @click="clearSource">&times;</button>
          </div>
          <label v-else class="upload-chip add">
            <input type="file" accept="image/*" hidden @change="onFileChange" />
            + &#x53C2;&#x8003;&#x56FE;
          </label>
        </div>

        <div class="input-row">
          <textarea
            ref="promptEl"
            v-model="prompt"
            class="prompt"
            :placeholder="conversation().type === 'img2img'
              ? '\u63cf\u8ff0\u5982\u4f55\u53d8\u6362\u8fd9\u5f20\u56fe\u7247...'
              : '\u63cf\u8ff0\u4f60\u60f3\u751f\u6210\u7684\u753b\u9762...'"
            rows="1"
            @keydown="onKeydown"
            @input="resizePrompt"
          />
          <select v-model="model" class="model-select" title="&#x6A21;&#x578B;">
            <option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }}</option>
          </select>
          <button
            class="send-btn"
            :disabled="userStore.generating || uploading || !prompt.trim()"
            @click="send"
          >
            <span v-if="userStore.generating">发送中...</span>
            <span v-else>发送</span>
          </button>
        </div>

        <p v-if="error" class="error">{{ error }}</p>
        <p class="hint">Enter &#x53D1;&#x9001; · Shift+Enter &#x6362;&#x884C; · &#x6BCF;&#x6B21; 20 &#x79EF;&#x5206;</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
  background: var(--bg);
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.menu-btn {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  font-size: 18px;
}

.header-title {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-title h1 {
  font-family: var(--font-serif);
  font-size: 20px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.type-badge {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--gold-soft);
  color: var(--gold);
  flex-shrink: 0;
}

.user-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  color: var(--accent-hover);
  font-size: 14px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: var(--shadow-sm);
}

.user-avatar:hover {
  background: var(--accent);
  color: #fff;
}


.avatar-wrap {
  position: relative;
}

.account-popover {
  position: absolute;
  top: calc(100% + 10px);
  right: 0;
  z-index: 20;
  min-width: 260px;
  padding: 16px;
  border-radius: 16px;
  background: rgba(255, 249, 247, 0.98);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-lg);
}

.account-top {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.account-avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent-hover);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  flex-shrink: 0;
}

.account-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.account-meta strong {
  font-size: 15px;
  color: var(--text-strong);
}

.account-meta span {
  font-size: 12px;
  color: var(--text-muted);
}

.account-list {
  display: grid;
  gap: 10px;
}

.account-list div {
  display: grid;
  gap: 2px;
}

.account-list dt {
  font-size: 11px;
  color: var(--text-muted);
}

.account-list dd {
  font-size: 13px;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.welcome {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}

.welcome-inner {
  text-align: center;
  max-width: 420px;
}

.welcome-inner h2 {
  font-family: var(--font-serif);
  font-size: 38px;
  font-weight: 600;
  margin-bottom: 12px;
  color: var(--text-strong);
}

.welcome-inner p {
  color: var(--text-muted);
  margin-bottom: 28px;
}

.welcome-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}

.welcome-btn {
  padding: 12px 22px;
  border-radius: 999px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  color: var(--text);
  font-size: 14px;
  box-shadow: var(--shadow-sm);
  transition: transform 0.1s, background 0.15s, border-color 0.15s;
}

.welcome-btn:hover {
  background: var(--accent-soft);
  border-color: var(--accent-border);
  transform: translateY(-1px);
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 8px 24px 24px;
}

.empty-chat {
  text-align: center;
  padding: 60px 20px;
  color: var(--text-muted);
  font-size: 15px;
}

.input-area {
  flex-shrink: 0;
  padding: 16px 24px 24px;
  background: linear-gradient(transparent, var(--bg) 28%);
}

.input-card {
  max-width: var(--chat-max-width);
  margin: 0 auto;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 12px;
  box-shadow: var(--shadow);
}

.upload-row {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.upload-chip {
  position: relative;
  width: 64px;
  height: 64px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--text-muted);
}

.upload-chip.add {
  cursor: pointer;
  border-style: dashed;
}

.upload-chip.add:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.upload-chip img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.upload-chip .clear {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.7);
  color: #fff;
  font-size: 10px;
}

.input-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.prompt {
  flex: 1;
  min-height: 44px;
  max-height: 160px;
  padding: 10px 12px;
  background: transparent;
  border: none;
  color: var(--text);
  font-size: 15px;
  resize: none;
  outline: none;
  line-height: 1.5;
  overflow: hidden;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.prompt::-webkit-scrollbar {
  display: none;
}

.model-select {
  padding: 8px 10px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 10px;
  color: var(--text-muted);
  font-size: 12px;
  outline: none;
  max-width: 120px;
}

.send-btn {
  min-width: 84px;
  height: 42px;
  padding: 0 18px;
  border-radius: 12px;
  background: var(--gradient);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  flex-shrink: 0;
  box-shadow: 0 6px 16px rgba(224, 155, 176, 0.4);
}
.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.error {
  color: var(--danger);
  font-size: 13px;
  margin-top: 8px;
}

.hint {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 8px;
  text-align: center;
}

.spin {
  display: inline-block;
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
