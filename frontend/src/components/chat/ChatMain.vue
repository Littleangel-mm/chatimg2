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

const availableModels = computed(() => {
  return models.filter(item => item.id === 'gpt-image-2')
})

const conversation = () => convStore.activeConversation

const avatarText = computed(() => {
  const source = authStore.user?.email || authStore.user?.username || userStore.keyCode || 'U'
  return source.trim().charAt(0).toUpperCase() || 'U'
})

const showAccountMenu = ref(false)
const showContactMenu = ref(false)
const adminContactImage = 'img/admin/wechat--admin.jpg'

const accountInfo = computed(() => ({
  email: authStore.user?.email || '未登录',
  name: authStore.user?.username || authStore.user?.email?.split('@')[0] || '未设置',
  role: authStore.user?.role === 'ADMIN' ? '管理员' : authStore.user?.role === 'USER' ? '用户' : '未登录',
  credits: authStore.user?.remainingCredits ?? userStore.remainingCredits,
  keyCode: authStore.isAdmin ? '管理员不绑定密钥' : userStore.keyCode || '未激活'
}))

const currentType = computed(() => conversation()?.type || 'text2img')
const activeMessages = computed(() => {
  const messages = conversation()?.messages || []
  return [...messages].sort((a, b) => {
    const timeDiff = (a.createdAt || 0) - (b.createdAt || 0)
    if (timeDiff !== 0) return timeDiff
    if (a.role === b.role) return 0
    return a.role === 'user' ? -1 : 1
  })
})

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

watch(
  () => conversation()?.type,
  () => {
    if (!availableModels.value.some(item => item.id === model.value)) {
      model.value = availableModels.value[0]?.id || 'gpt-image-2'
    }
  }
)

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
  await applySourceFile(file, file.type)
  e.target.value = ''
}

function normalizeClipboardImage(file, fallbackType = 'image/png') {
  if (!file) return null
  if (file.type?.startsWith('image/')) return file
  return new File([file], file.name || `pasted.${fallbackType.split('/')[1] || 'png'}`, {
    type: fallbackType
  })
}

async function applySourceFile(file, fallbackType = '') {
  const normalized = normalizeClipboardImage(file, fallbackType || 'image/png')
  if (!normalized || !normalized.type.startsWith('image/')) {
    error.value = '\u8bf7\u4e0a\u4f20\u56fe\u7247\u6587\u4ef6'
    return
  }
  uploading.value = true
  error.value = ''
  try {
    const compressed = await compressImage(normalized)
    sourcePreview.value = compressed
    sourceBase64.value = compressed
  } catch (err) {
    error.value = err?.message || '\u56fe\u7247\u5904\u7406\u5931\u8d25'
  } finally {
    uploading.value = false
  }
}

async function onPaste(e) {
  const draft = prompt.value
  const items = Array.from(e.clipboardData?.items || [])
  const imageItem = items.find(item => item.type?.startsWith('image/'))
  const fileFromItem = imageItem?.getAsFile?.()
  const fileFromList = Array.from(e.clipboardData?.files || []).find(file => {
    return file.type?.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp)$/i.test(file.name || '')
  })
  const file = fileFromItem || fileFromList
  if (!file) return

  e.preventDefault()
  if (!conversation()) {
    convStore.createConversation('img2img')
  } else if (conversation()?.type !== 'img2img') {
    convStore.setConversationType(conversation().id, 'img2img')
  }
  await applySourceFile(file, imageItem?.type || file.type || 'image/png')
  nextTick(() => {
    if (draft && !prompt.value) {
      prompt.value = draft
    }
    resizePrompt()
    promptEl.value?.focus()
  })
}

function clearSource() {
  sourcePreview.value = ''
  sourceBase64.value = ''
}

function switchChatType(type) {
  let conv = conversation()
  if (!conv) {
    conv = convStore.createConversation(type)
  } else {
    if (conv.type === type) return
    conv = convStore.setConversationType(conv.id, type) || conv
  }
  error.value = ''
  if (type === 'text2img') {
    clearSource()
  }
  nextTick(() => {
    if (draft && !prompt.value) {
      prompt.value = draft
    }
    resizePrompt()
    promptEl.value?.focus()
  })
}

function openKeyModal() {
  window.dispatchEvent(new CustomEvent('open-key-modal'))
}

function toggleAccountMenu() {
  showAccountMenu.value = !showAccountMenu.value
  if (showAccountMenu.value) showContactMenu.value = false
}

function toggleContactMenu() {
  showContactMenu.value = !showContactMenu.value
  if (showContactMenu.value) showAccountMenu.value = false
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

  const conv = convStore.ensureActive(currentType.value || 'text2img')
  const isImg2img = conv.type === 'img2img'

  if (isImg2img && !sourceBase64.value) {
    error.value = '\u8bf7\u5148\u4e0a\u4f20\u53c2\u8003\u56fe\u7247'
    return
  }

  error.value = ''
  const exchange = convStore.addExchange(conv.id, {
    prompt: text,
    sourceImage: isImg2img ? sourceBase64.value : null,
    type: isImg2img ? 'img2img' : 'text2img',
    model: model.value
  })

  prompt.value = ''
  scrollToBottom()

  try {
    const record = await userStore.generate({
      prompt: text,
      type: isImg2img ? 'img2img' : 'text2img',
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
  if (!conv) {
    conv = convStore.createConversation('text2img')
  } else if (conv.type !== 'text2img') {
    conv = convStore.setConversationType(conv.id, 'text2img') || conv
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
        <button class="notice-btn" title="联系客服" aria-label="联系客服" @click.stop="toggleContactMenu">
          联系客服
        </button>
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
    <Transition name="fade">
      <div v-if="showContactMenu" class="contact-overlay" @click="showContactMenu = false">
        <div class="contact-popover">
          <div class="contact-title">
            <strong>联系客服</strong>
            <span>扫码添加微信</span>
          </div>
          <img :src="adminContactImage" alt="客服微信" class="contact-image" />
        </div>
      </div>
    </Transition>

    <div v-if="!conversation()" class="welcome">
      <div class="welcome-inner">
        <h2>&#x4ECA;&#x5929;&#x60F3;&#x521B;&#x4F5C;&#x4EC0;&#x4E48;&#xFF1F;</h2>
        <p>&#x5148;&#x65B0;&#x5EFA;&#x5BF9;&#x8BDD;&#xFF0C;&#x518D;&#x5728;&#x8F93;&#x5165;&#x6846;&#x4E0A;&#x65B9;&#x5207;&#x6362;&#x6587;&#x751F;&#x56FE;&#x6216;&#x56FE;&#x751F;&#x56FE;</p>
        <div class="welcome-actions">
          <button class="welcome-btn" @click="convStore.createConversation('text2img')">&#x65B0;&#x5BF9;&#x8BDD;</button>
        </div>
      </div>
    </div>

    <div v-else ref="messagesEl" class="messages">
      <div v-if="conversation().messages.length === 0" class="empty-chat">
        <p>{{ conversation().type === 'img2img' ? '\u4e0a\u4f20\u53c2\u8003\u56fe\u5e76\u63cf\u8ff0\u60f3\u8981\u7684\u6548\u679c' : '\u63cf\u8ff0\u4f60\u60f3\u751f\u6210\u7684\u753b\u9762' }}</p>
      </div>
      <ChatMessage
        v-for="msg in activeMessages"
        :key="msg.id"
        :message="msg"
      />
    </div>

    <div v-if="conversation()" class="input-area">
      <div class="input-card">
        <div class="mode-switch" role="tablist" aria-label="生成模式">
          <button
            type="button"
            :class="{ active: currentType === 'text2img' }"
            @click="switchChatType('text2img')"
          >
            文生图
          </button>
          <button
            type="button"
            :class="{ active: currentType === 'img2img' }"
            @click="switchChatType('img2img')"
          >
            图生图
          </button>
        </div>
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
            @paste="onPaste"
            @input="resizePrompt"
          />
          <select v-model="model" class="model-select" title="&#x6A21;&#x578B;">
            <option v-for="m in availableModels" :key="m.id" :value="m.id">{{ m.name }}</option>
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
  display: flex;
  align-items: center;
  gap: 10px;
}

.notice-btn {
  height: 34px;
  padding: 0 14px;
  border-radius: 999px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  color: var(--text-muted);
  font-size: 13px;
  box-shadow: var(--shadow-sm);
  transition: background 0.15s, border-color 0.15s, color 0.15s, transform 0.1s;
}

.notice-btn:hover {
  background: var(--accent-soft);
  border-color: var(--accent-border);
  color: var(--accent-hover);
  transform: translateY(-1px);
}

.contact-overlay {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(54, 31, 37, 0.14);
}

.contact-popover {
  width: min(300px, 100%);
  padding: 16px;
  border-radius: 16px;
  background: rgba(255, 249, 247, 0.98);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-lg);
}

.contact-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 10px;
}

.contact-title strong {
  font-size: 14px;
  color: var(--text-strong);
}

.contact-title span {
  font-size: 12px;
  color: var(--text-muted);
}

.contact-image {
  width: 100%;
  display: block;
  border-radius: 12px;
  border: 1px solid var(--border);
  background: #fff;
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

.mode-switch {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px;
  margin-bottom: 10px;
  border-radius: 999px;
  background: var(--bg-input);
  border: 1px solid var(--border);
}

.mode-switch button {
  height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  background: transparent;
  color: var(--text-muted);
  font-size: 12px;
  transition: background 0.15s, color 0.15s, box-shadow 0.15s;
}

.mode-switch button.active {
  background: var(--bg-elevated);
  color: var(--accent-hover);
  box-shadow: var(--shadow-sm);
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
