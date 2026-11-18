<script setup>
import { ref, watch, nextTick, onMounted, onActivated, computed } from 'vue'
import { useConversationsStore } from '../../stores/conversations'
import { useUserStore } from '../../stores/user'
import { useAuthStore } from '../../stores/auth'
import api, { getImageUrl } from '../../api'
import { compressImage } from '../../utils/image'
import ChatMessage from './ChatMessage.vue'

const convStore = useConversationsStore()
const userStore = useUserStore()
const authStore = useAuthStore()

const prompt = ref('')
const model = ref('gpt-image-2')
const sourcePreviews = ref([])
const sourceBase64s = ref([])
const error = ref('')
const uploading = ref(false)
const assistantSending = ref(false)
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
const adminContactImage = '/img/admin/wechat--admin.jpg'

const accountInfo = computed(() => ({
  email: authStore.user?.email || '未登录',
  name: authStore.user?.username || authStore.user?.email?.split('@')[0] || '未设置',
  role: authStore.user?.role === 'ADMIN' ? '管理员' : authStore.user?.role === 'USER' ? '用户' : '未登录',
  credits: authStore.user?.remainingCredits ?? userStore.remainingCredits,
  keyCode: authStore.isAdmin ? '管理员不绑定密钥' : userStore.keyCode || '未激活'
}))

const currentType = computed(() => conversation()?.type || 'text2img')
const isPromptAssistant = computed(() => currentType.value === 'prompt_assistant')
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
  const files = Array.from(e.target.files || [])
  if (!files.length) return
  await applySourceFiles(files)
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
  if (sourceBase64s.value.length >= 4) {
    error.value = '最多支持 4 张参考图'
    return
  }
  if (file.size > 20 * 1024 * 1024) {
    error.value = '单张参考图不能超过 20MB'
    return
  }
  const normalized = normalizeClipboardImage(file, fallbackType || 'image/png')
  if (!normalized || !normalized.type.startsWith('image/')) {
    error.value = '\u8bf7\u4e0a\u4f20\u56fe\u7247\u6587\u4ef6'
    return
  }
  error.value = ''
  try {
    const owner = convStore.activeId
    const compressed = await compressImage(normalized)
    if (owner !== convStore.activeId || currentType.value !== 'img2img') return
    if (sourceBase64s.value.length >= 4) return
    sourcePreviews.value.push(compressed)
    sourceBase64s.value.push(compressed)
  } catch (err) {
    error.value = err?.message || '\u56fe\u7247\u5904\u7406\u5931\u8d25'
  }
}

async function applySourceFiles(files) {
  if (uploading.value) return
  uploading.value = true
  const owner = convStore.activeId
  try {
    for (const file of files) {
      if (convStore.activeId !== owner) break
      await applySourceFile(file, file.type)
    }
  } finally {
    uploading.value = false
  }
}

function removeSource(index) {
  sourcePreviews.value.splice(index, 1)
  sourceBase64s.value.splice(index, 1)
}

async function onPaste(e) {
  const draft = prompt.value
  const items = Array.from(e.clipboardData?.items || [])
  const imageItems = items.filter(item => item.type?.startsWith('image/'))
  const filesFromItems = imageItems.map(item => item.getAsFile?.()).filter(Boolean)
  const filesFromList = Array.from(e.clipboardData?.files || []).filter(file => {
    return file.type?.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp)$/i.test(file.name || '')
  })
  const files = filesFromItems.length ? filesFromItems : filesFromList
  if (!files.length) return

  e.preventDefault()
  if (!conversation()) {
    convStore.createConversation('img2img')
  } else if (conversation()?.type !== 'img2img') {
    convStore.setConversationType(conversation().id, 'img2img')
  }
  await applySourceFiles(files)
  nextTick(() => {
    if (draft && !prompt.value) {
      prompt.value = draft
    }
    resizePrompt()
    promptEl.value?.focus()
  })
}

function clearSource() {
  sourcePreviews.value = []
  sourceBase64s.value = []
}

function switchChatType(type) {
  const draft = prompt.value
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

function blobToDataUrl(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = reject
    reader.readAsDataURL(blob)
  })
}

async function applyAssistantPrompt(result) {
  let nextType = sourcePreviews.value.length ? 'img2img' : 'text2img'
  if (!sourcePreviews.value.length) {
    const previous = [...(conversation()?.messages || [])]
      .reverse()
      .find(message => message.role === 'assistant' && message.record &&
        (message.record.imagePath || message.record.imageUrl || message.record.remoteImageAvailable || message.record.taskCode))
    const previousUrl = previous ? getImageUrl(previous.record) : ''
    if (previousUrl) {
      try {
        const response = await fetch(previousUrl, { credentials: 'include' })
        if (response.ok) {
          const dataUrl = await blobToDataUrl(await response.blob())
          sourcePreviews.value = [dataUrl]
          sourceBase64s.value = [dataUrl]
          nextType = 'img2img'
        }
      } catch {
        // 旧图不可读取时仍允许把提示词应用到文生图
      }
    }
  }
  switchChatType(nextType)
  prompt.value = result?.prompt || ''
  nextTick(() => {
    resizePrompt()
    promptEl.value?.focus()
    scrollToBottom()
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

async function requestAssistantReply(conv, userMessage, assistantMessage) {
  assistantSending.value = true
  error.value = ''
  scrollToBottom()
  try {
    await convStore.syncRemote().catch(() => false)
    const { data } = await api.post('/chat/assistant/messages', {
      conversationId: conv.id,
      clientMessageId: userMessage.id,
      assistantMessageId: assistantMessage.id,
      message: userMessage.content
    }, { timeout: 150000 })
    if (data.code !== 200) throw new Error(data.message || '提示词助手请求失败')
    if (!data.data?.reply?.trim()) throw new Error('助手没有返回有效回复，请重试')
    convStore.updateAssistantMessage(assistantMessage.id, data.data)
    convStore.syncRemote().catch(() => false)
  } catch (e) {
    await convStore.loadRemote().catch(() => false)
    const latest = convStore.conversations.find(item => item.id === conv.id)
      ?.messages.find(item => item.id === assistantMessage.id)
    if (latest?.status !== 'completed' || !latest?.content?.trim()) {
      const msg = e.response?.data?.message || e.message || '提示词助手请求失败'
      error.value = msg
      convStore.failAssistantMessage(assistantMessage.id, msg)
      convStore.syncRemote().catch(() => false)
    }
  } finally {
    assistantSending.value = false
    scrollToBottom()
  }
}

async function retryAssistantReply(message) {
  if (assistantSending.value) return
  const conv = conversation()
  const index = conv?.messages.findIndex(item => item.id === message.id) ?? -1
  const userMessage = conv?.messages[index - 1]
  if (!conv || userMessage?.role !== 'user') return
  message.assistant ||= { kind: 'prompt_assistant' }
  message.status = 'processing'
  message.errorMessage = null
  await requestAssistantReply(conv, userMessage, message)
}

async function send() {
  if (userStore.generating || uploading.value || assistantSending.value) {
    return
  }

  if (!isPromptAssistant.value && !userStore.isActivated) {
    openKeyModal()
    return
  }

  const text = prompt.value.trim()
  if (!text) {
    error.value = '\u8bf7\u8f93\u5165\u63cf\u8ff0'
    return
  }

  const conv = convStore.ensureActive(currentType.value || 'text2img')
  if (conv.type === 'prompt_assistant') {
    error.value = ''
    const exchange = convStore.addExchange(conv.id, { prompt: text, type: 'prompt_assistant' })
    prompt.value = ''
    await requestAssistantReply(conv, exchange.userMsg, exchange.assistantMsg)
    return
  }
  const isImg2img = conv.type === 'img2img'
  const submittedSourceImages = isImg2img ? [...sourceBase64s.value] : []

  if (isImg2img && !submittedSourceImages.length) {
    error.value = '\u8bf7\u5148\u4e0a\u4f20\u53c2\u8003\u56fe\u7247'
    return
  }

  error.value = ''
  const exchange = convStore.addExchange(conv.id, {
    prompt: text,
    sourceImage: submittedSourceImages[0] || null,
    sourceImages: submittedSourceImages,
    type: isImg2img ? 'img2img' : 'text2img'
  })

  prompt.value = ''
  scrollToBottom()

  try {
    await convStore.syncRemote().catch(() => false)
    const record = await userStore.generate({
      prompt: text,
      type: isImg2img ? 'img2img' : 'text2img',
       sourceImages: submittedSourceImages,
      clientMessageId: exchange?.assistantMsg?.id
    })
    convStore.syncRecord(record)
    convStore.attachRecordToMessage(exchange?.assistantMsg?.id, record)
    convStore.syncRemote().catch(() => false)
    if (isImg2img) clearSource()
  } catch (e) {
    const msg = e.response?.data?.message || e.message || '\u751f\u6210\u5931\u8d25'
    error.value = msg
    convStore.failAssistantMessage(exchange?.assistantMsg?.id, msg)
    userStore.fetchHistory().catch(() => {})
    convStore.syncRemote().catch(() => false)
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
    userStore.fetchHistory().catch(() => {})
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
          {{ conversation().type === 'img2img' ? '\u56fe\u751f\u56fe' : conversation().type === 'prompt_assistant' ? 'AI提示词助手' : '\u6587\u751f\u56fe' }}
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
        <p>{{ conversation().type === 'img2img' ? '\u4e0a\u4f20\u53c2\u8003\u56fe\u5e76\u63cf\u8ff0\u60f3\u8981\u7684\u6548\u679c' : conversation().type === 'prompt_assistant' ? '告诉我你想创作或修改什么，我会连续记住上下文' : '\u63cf\u8ff0\u4f60\u60f3\u751f\u6210\u7684\u753b\u9762' }}</p>
      </div>
      <ChatMessage
        v-for="msg in activeMessages"
        :key="msg.id"
        :message="msg"
        :assistant-mode="currentType === 'prompt_assistant'"
        :assistant-busy="assistantSending"
        @apply-prompt="applyAssistantPrompt"
        @retry-assistant="retryAssistantReply"
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
          <button
            type="button"
            :class="{ active: currentType === 'prompt_assistant' }"
            @click="switchChatType('prompt_assistant')"
          >
            提示词助手
          </button>
        </div>
        <div v-if="conversation().type === 'img2img'" class="upload-row">
          <div v-if="uploading" class="upload-chip loading">&#x538B;&#x7F29;&#x4E2D;...</div>
          <div v-for="(preview, index) in sourcePreviews" :key="index" class="upload-chip">
            <img :src="preview" :alt="'参考图 ' + (index + 1)" />
            <button class="clear" :disabled="uploading" :title="'移除参考图 ' + (index + 1)"
              :aria-label="'移除参考图 ' + (index + 1)" @click="removeSource(index)">&times;</button>
          </div>
          <label v-if="sourcePreviews.length < 4 && !uploading" class="upload-chip add">
            <input type="file" accept="image/png,image/jpeg,image/webp" multiple hidden @change="onFileChange" />
            + &#x53C2;&#x8003;&#x56FE;
          </label>
        </div>

        <div class="input-row">
          <textarea
            ref="promptEl"
            v-model="prompt"
            class="prompt"
            :placeholder="conversation().type === 'img2img'
              ? '描述如何使用这些参考图...'
              : conversation().type === 'prompt_assistant'
                ? '继续描述你想优化的内容...'
                : '\u63cf\u8ff0\u4f60\u60f3\u751f\u6210\u7684\u753b\u9762...'"
            rows="1"
            @keydown="onKeydown"
            @paste="onPaste"
            @input="resizePrompt"
          />
          <select v-if="!isPromptAssistant" v-model="model" class="model-select" title="&#x6A21;&#x578B;">
            <option v-for="m in availableModels" :key="m.id" :value="m.id">{{ m.name }}</option>
          </select>
          <button
            class="send-btn"
            :disabled="userStore.generating || uploading || assistantSending || !prompt.trim()"
            @click="send"
          >
            <span v-if="userStore.generating || assistantSending">发送中...</span>
            <span v-else>发送</span>
          </button>
        </div>

        <p v-if="error" class="error">{{ error }}</p>
        <p class="hint">Enter 发送 · Shift+Enter 换行 · {{ isPromptAssistant ? 'AI 提示词对话' : '每次 20 积分' }}</p>
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
  flex-wrap: wrap;
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

@media (max-width: 480px) {
  .input-row { flex-wrap: wrap; }
  .input-row .prompt { flex: 1 0 100%; min-width: 0; }
  .input-row .model-select { margin-left: auto; }
}
</style>
