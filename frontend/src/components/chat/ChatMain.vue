<script setup>
import { ref, watch, nextTick } from 'vue'
import { useConversationsStore } from '../../stores/conversations'
import { useUserStore } from '../../stores/user'
import { compressImage } from '../../utils/image'
import ChatMessage from './ChatMessage.vue'

const convStore = useConversationsStore()
const userStore = useUserStore()

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
    error.value = '请上传图片文件'
    return
  }
  uploading.value = true
  error.value = ''
  try {
    const compressed = await compressImage(file)
    sourcePreview.value = compressed
    sourceBase64.value = compressed
  } catch {
    error.value = '图片处理失败'
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

async function send() {
  if (!userStore.isActivated) {
    openKeyModal()
    return
  }

  const text = prompt.value.trim()
  if (!text) {
    error.value = '请输入描述'
    return
  }

  const conv = convStore.ensureActive(conversation()?.type || 'text2img')
  const isImg2img = conv.type === 'img2img'

  if (isImg2img && !sourceBase64.value) {
    error.value = '请先上传参考图片'
    return
  }

  error.value = ''
  convStore.addExchange(conv.id, {
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
    if (isImg2img) clearSource()
  } catch (e) {
    const msg = e.response?.data?.message || e.message || '生成失败'
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
</script>

<template>
  <div class="chat-main">
    <header class="chat-header">
      <button
        v-if="!convStore.sidebarOpen"
        class="menu-btn"
        @click="convStore.sidebarOpen = true"
      >
        ☰
      </button>
      <div class="header-title">
        <h1>{{ conversation()?.title || 'img2.ai' }}</h1>
        <span v-if="conversation()" class="type-badge">
          {{ conversation().type === 'img2img' ? '图生图' : '文生图' }}
        </span>
      </div>
      <button class="key-btn" @click="openKeyModal">
        {{ userStore.isActivated ? '切换密钥' : '激活密钥' }}
      </button>
    </header>

    <div v-if="!conversation()" class="welcome">
      <div class="welcome-inner">
        <h2>今天想创作什么？</h2>
        <p>从左侧新建对话，或开始文生图 / 图生图</p>
        <div class="welcome-actions">
          <button class="welcome-btn" @click="convStore.createConversation('text2img')">✨ 新对话</button>
          <button class="welcome-btn" @click="convStore.createConversation('img2img')">🖼 新图生图</button>
        </div>
      </div>
    </div>

    <div v-else ref="messagesEl" class="messages">
      <div v-if="conversation().messages.length === 0" class="empty-chat">
        <p>{{ conversation().type === 'img2img' ? '上传参考图并描述想要的效果' : '描述你想生成的画面' }}</p>
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
          <div v-if="uploading" class="upload-chip loading">压缩中…</div>
          <div v-else-if="sourcePreview" class="upload-chip">
            <img :src="sourcePreview" alt="参考图" />
            <button class="clear" @click="clearSource">✕</button>
          </div>
          <label v-else class="upload-chip add">
            <input type="file" accept="image/*" hidden @change="onFileChange" />
            + 参考图
          </label>
        </div>

        <div class="input-row">
          <textarea
            ref="promptEl"
            v-model="prompt"
            class="prompt"
            :placeholder="conversation().type === 'img2img'
              ? '描述如何变换这张图片…'
              : '描述你想生成的画面…'"
            rows="1"
            @keydown="onKeydown"
            @input="resizePrompt"
          />
          <select v-model="model" class="model-select" title="模型">
            <option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }}</option>
          </select>
          <button
            class="send-btn"
            :disabled="userStore.generating || uploading || !prompt.trim()"
            @click="send"
          >
            <span v-if="userStore.generating" class="spin">⟳</span>
            <span v-else>↑</span>
          </button>
        </div>

        <p v-if="error" class="error">{{ error }}</p>
        <p class="hint">Enter 发送 · Shift+Enter 换行 · 每次 20 积分</p>
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
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.type-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(124, 92, 255, 0.15);
  color: #b49cff;
  flex-shrink: 0;
}

.key-btn {
  padding: 8px 14px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-muted);
  font-size: 13px;
}

.key-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: var(--text);
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
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 12px;
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
  padding: 12px 20px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid var(--border);
  color: var(--text);
  font-size: 14px;
}

.welcome-btn:hover {
  background: rgba(124, 92, 255, 0.12);
  border-color: rgba(124, 92, 255, 0.3);
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
  background: linear-gradient(transparent, var(--bg) 20%);
}

.input-card {
  max-width: var(--chat-max-width);
  margin: 0 auto;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 12px;
  box-shadow: 0 -4px 24px rgba(0, 0, 0, 0.2);
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
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text-muted);
  font-size: 12px;
  outline: none;
  max-width: 120px;
}

.send-btn {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--gradient);
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  flex-shrink: 0;
}

.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.error {
  color: #f87171;
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
