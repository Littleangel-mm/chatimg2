<script setup>
import {
  computed,
  nextTick,
  onActivated,
  onDeactivated,
  onMounted,
  onUnmounted,
  ref,
  watch
} from 'vue'
import { useAuthStore } from '../../stores/auth'
import { useConversationsStore } from '../../stores/conversations'
import { useUserStore } from '../../stores/user'
import { useVideosStore } from '../../stores/videos'

defineEmits(['open-sidebar'])

const authStore = useAuthStore()
const convStore = useConversationsStore()
const userStore = useUserStore()
const videosStore = useVideosStore()

const mode = ref('text')
const prompt = ref('')
const model = ref('')
const durationSeconds = ref(5)
const aspectRatio = ref('16:9')
const resolution = ref('720p')
const referenceAsset = ref(null)
const referencePreview = ref('')
const referenceUrl = ref('')
const urlPreviewFailed = ref(false)
const error = ref('')
const promptEl = ref(null)
const feedEl = ref(null)
let pollTimer = null

const durations = [5, 10, 15]
const ratios = ['16:9', '9:16', '1:1', '4:3', '3:4', '2.39:1']

const availableModels = computed(() => videosStore.models)

const selectedModel = computed(() => {
  return availableModels.value.find(item => item.id === model.value) || availableModels.value[0]
})

const estimatedCredits = computed(() => {
  const unit = Number(selectedModel.value?.costPerSecond)
  if (!Number.isFinite(unit) || unit <= 0) return null
  return Math.ceil(unit * durationSeconds.value)
})

const avatarText = computed(() => {
  const source = authStore.user?.email || authStore.user?.username || 'U'
  return source.trim().charAt(0).toUpperCase() || 'U'
})

const busy = computed(() => videosStore.submitting || videosStore.uploading)
const normalizedReferenceUrl = computed(() => normalizeHttpUrl(referenceUrl.value))
const referenceReady = computed(() => {
  if (mode.value === 'image') return !!referenceAsset.value
  if (mode.value === 'url') return !!normalizedReferenceUrl.value
  return true
})
const conversation = computed(() => convStore.activeConversation)
const exchanges = computed(() => {
  const rows = []
  const messages = [...(conversation.value?.messages || [])].sort((a, b) => {
    return (a.createdAt || 0) - (b.createdAt || 0)
  })
  for (const message of messages) {
    if (message.role === 'user') {
      rows.push({ id: message.id, user: message, assistant: null })
      continue
    }
    const pending = [...rows].reverse().find(row => !row.assistant)
    if (pending) pending.assistant = message
  }
  return rows
})

watch(
  availableModels,
  items => {
    if (!items.length) return
    if (!items.some(item => item.id === model.value)) model.value = items[0].id
  },
  { deep: true, immediate: true }
)

watch(prompt, () => nextTick(resizePrompt))
watch(referenceUrl, () => {
  urlPreviewFailed.value = false
  if (error.value === '请输入有效的公网图片 URL') error.value = ''
})

watch(
  () => conversation.value?.id,
  () => {
    const type = conversation.value?.type
    mode.value = type === 'image2video' ? 'image' : 'text'
    prompt.value = ''
    error.value = ''
    clearReference()
    referenceUrl.value = ''
  }
)

function resizePrompt() {
  const element = promptEl.value
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 150)}px`
}

function scrollToLatest() {
  nextTick(() => {
    if (feedEl.value) feedEl.value.scrollTop = 0
  })
}

function setMode(nextMode) {
  const conv = ensureVideoConversation(nextMode === 'text' ? 'text2video' : 'image2video')
  mode.value = nextMode
  convStore.setConversationType(conv.id, nextMode === 'text' ? 'text2video' : 'image2video')
  error.value = ''
  if (nextMode !== 'image') clearReference()
  if (nextMode !== 'url') referenceUrl.value = ''
  nextTick(() => promptEl.value?.focus())
}

function ensureVideoConversation(type = 'text2video') {
  const current = conversation.value
  if (current?.type === 'text2video' || current?.type === 'image2video') return current
  return convStore.createConversation(type)
}

function clearReference() {
  if (referencePreview.value) URL.revokeObjectURL(referencePreview.value)
  referencePreview.value = ''
  referenceAsset.value = null
}

async function onFileChange(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  await applyReferenceFile(file, file.type)
}

function normalizeClipboardImage(file, fallbackType = 'image/png') {
  if (!file) return null
  if (file.type?.startsWith('image/')) return file
  return new File([file], file.name || `pasted.${fallbackType.split('/')[1] || 'png'}`, {
    type: fallbackType
  })
}

function normalizeHttpUrl(value) {
  const candidate = (value || '').trim()
  if (!candidate || candidate.length > 4000) return ''
  try {
    const parsed = new URL(candidate)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.href : ''
  } catch {
    return ''
  }
}

function validateReferenceUrl() {
  if (referenceUrl.value.trim() && !normalizedReferenceUrl.value) {
    error.value = '请输入有效的公网图片 URL'
  }
}

async function applyReferenceFile(file, fallbackType = '') {
  const normalized = normalizeClipboardImage(file, fallbackType || 'image/png')
  if (!normalized || !normalized.type.startsWith('image/')) {
    error.value = '请选择图片文件'
    return
  }
  if (normalized.size > 20 * 1024 * 1024) {
    error.value = '参考图片不能超过 20MB'
    return
  }

  clearReference()
  referencePreview.value = URL.createObjectURL(normalized)
  error.value = ''
  try {
    referenceAsset.value = await videosStore.uploadImage(normalized)
  } catch (uploadError) {
    error.value = uploadError.message
    clearReference()
  }
}

async function onPaste(event) {
  const items = Array.from(event.clipboardData?.items || [])
  const imageItem = items.find(item => item.type?.startsWith('image/'))
  const fileFromItem = imageItem?.getAsFile?.()
  const fileFromList = Array.from(event.clipboardData?.files || []).find(file => {
    return file.type?.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp)$/i.test(file.name || '')
  })
  const file = fileFromItem || fileFromList
  if (!file) return

  event.preventDefault()
  mode.value = 'image'
  await applyReferenceFile(file, imageItem?.type || file.type || 'image/png')
  nextTick(() => {
    resizePrompt()
    promptEl.value?.focus()
  })
}

async function submit() {
  if (busy.value) return
  const text = prompt.value.trim()
  if (!text) {
    error.value = '请先描述你想生成的视频'
    return
  }
  if (mode.value === 'image' && !referenceAsset.value) {
    error.value = '请先上传一张参考图片'
    return
  }
  if (mode.value === 'url' && !normalizedReferenceUrl.value) {
    error.value = '请输入有效的公网图片 URL'
    return
  }

  const assets = mode.value === 'image'
    ? [{
        mediaType: 'IMAGE',
        role: 'REFERENCE',
        localPath: referenceAsset.value.localPath,
        sortOrder: 0
      }]
    : mode.value === 'url'
      ? [{
          mediaType: 'IMAGE',
          role: 'REFERENCE',
          sourceUrl: normalizedReferenceUrl.value,
          sortOrder: 0
        }]
      : []

  const conversationType = mode.value === 'text' ? 'text2video' : 'image2video'
  const conv = ensureVideoConversation(conversationType)
  convStore.setConversationType(conv.id, conversationType)
  const exchange = convStore.addExchange(conv.id, {
    prompt: text,
    sourceImage: mode.value === 'image'
      ? referenceAsset.value.sourceUrl
      : mode.value === 'url'
        ? normalizedReferenceUrl.value
        : null,
    type: conversationType
  })

  error.value = ''
  try {
    await convStore.syncRemote().catch(() => false)
    await videosStore.submit({
      prompt: text,
      model: model.value,
      durationSeconds: durationSeconds.value,
      aspectRatio: aspectRatio.value,
      resolution: resolution.value,
      clientMessageId: exchange.assistantMsg.id,
      assets
    }).then(task => convStore.attachVideoTaskToMessage(exchange.assistantMsg.id, task))
    prompt.value = ''
    clearReference()
    referenceUrl.value = ''
    await userStore.syncCreditsFromServer().catch(() => {})
    scrollToLatest()
    startPolling()
  } catch (submitError) {
    await Promise.allSettled([
      videosStore.loadHistory(),
      convStore.loadRemote()
    ])
    convStore.syncVideoTasks(videosStore.tasks)
    const recovered = convStore.conversations
      .flatMap(item => item.messages || [])
      .find(message => message.id === exchange.assistantMsg.id)
    if (recovered?.videoTask) {
      error.value = ''
      startPolling()
      return
    }
    if (submitError.recoverable) {
      error.value = ''
      startPolling()
      return
    }
    error.value = submitError.message
    convStore.failAssistantMessage(exchange.assistantMsg.id, submitError.message)
  }
}

function onKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}

function processingText(task) {
  if (!task) {
    return '任务已经交给后端啦，我正在确认进度，稍等一下下喵～'
  }
  if (task.detailStatus === 'created' || task.detailStatus === 'submitting') {
    return '正在把灵感交给 Seedance，马上就开拍喵～'
  }
  if (task.detailStatus === 'submission_unknown') {
    return '回信稍微慢了一点，我还在认真确认，马上就好喵～'
  }
  return '画面还在认真生成，再等我一下下，就快好啦喵～'
}

function statusText(task) {
  if (task.status === 'completed') return '已完成'
  if (task.status === 'failed') return '生成失败'
  return '生成中'
}

function typeText(task) {
  return task.generationType === 'image_to_video' ? '图生视频' : '文生视频'
}

function exchangeTypeText(exchange) {
  return exchange.user?.sourceImage ? '图生视频' : '文生视频'
}

function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

function resultUrl(path) {
  if (!path) return ''
  if (/^https?:\/\//i.test(path)) return path
  return path.startsWith('/') ? path : `/${path}`
}

async function pollProcessing() {
  if (document.hidden || !videosStore.processingTasks.length) return
  const before = new Map(videosStore.processingTasks.map(task => [task.taskCode, task.status]))
  await videosStore.refreshProcessing()
  convStore.syncVideoTasks(videosStore.tasks)
  const completed = videosStore.tasks.some(task => before.has(task.taskCode) && task.status !== 'processing')
  if (completed) userStore.syncCreditsFromServer().catch(() => {})
}

function startPolling() {
  if (pollTimer) return
  pollTimer = window.setInterval(pollProcessing, 5000)
}

function stopPolling() {
  if (!pollTimer) return
  window.clearInterval(pollTimer)
  pollTimer = null
}

async function initialize() {
  const ownerKey = authStore.user?.email || userStore.keyCode
  if (ownerKey) convStore.bindKeyCode(ownerKey)
  ensureVideoConversation()
  const results = await Promise.allSettled([
    videosStore.loadModels(),
    videosStore.loadHistory(),
    convStore.loadRemote()
  ])
  convStore.syncVideoTasks(videosStore.tasks)
  const failure = results.find(result => result.status === 'rejected')
  if (failure && !videosStore.tasks.length) {
    error.value = failure.reason?.message || '视频功能加载失败，请稍后重试'
  }
  startPolling()
}

onMounted(initialize)
onActivated(startPolling)
onDeactivated(stopPolling)
onUnmounted(() => {
  stopPolling()
  clearReference()
})
</script>

<template>
  <main class="video-main">
    <header class="video-header">
      <button
        v-if="!convStore.sidebarOpen"
        class="menu-btn"
        title="打开侧边栏"
        aria-label="打开侧边栏"
        @click="$emit('open-sidebar')"
      >
        &#9776;
      </button>
      <div class="header-title">
        <h1>视频生成</h1>
        <span class="type-badge">Seedance 2.0</span>
      </div>
      <div class="account-summary">
        <span class="credit-count">{{ userStore.remainingCredits }} 积分</span>
        <span class="user-avatar" :title="authStore.user?.email || '账号'">{{ avatarText }}</span>
      </div>
    </header>

    <section ref="feedEl" class="video-feed">
      <div v-if="videosStore.loading" class="feed-empty">
        <span class="spinner">⟳</span>
        <p>正在载入作品...</p>
      </div>

      <div v-else-if="!exchanges.length" class="video-welcome">
        <div class="welcome-mark">▶</div>
        <h2>今天想让什么动起来？</h2>
        <p>Seedance 已就位，等你的第一幕。</p>
      </div>

      <article v-for="exchange in exchanges" :key="exchange.id" class="task-thread">
        <div class="message user-message">
          <div class="avatar user">你</div>
          <div class="user-content">
            <img
              v-if="exchange.user.sourceImage"
              class="message-reference"
              :src="resultUrl(exchange.user.sourceImage)"
              alt="视频参考图"
            />
            <p>{{ exchange.user.content }}</p>
            <span>
              {{ exchange.assistant?.videoTask ? typeText(exchange.assistant.videoTask) : exchangeTypeText(exchange) }}
              <template v-if="exchange.assistant?.videoTask?.durationSeconds">
                · {{ exchange.assistant.videoTask.durationSeconds }} 秒
              </template>
              <template v-if="exchange.assistant?.videoTask?.aspectRatio">
                · {{ exchange.assistant.videoTask.aspectRatio }}
              </template>
            </span>
          </div>
        </div>

        <div v-if="exchange.assistant" class="message assistant-message">
          <div class="avatar assistant">AI</div>
          <div class="assistant-content">
            <div
              v-if="!exchange.assistant.videoTask && exchange.assistant.status !== 'failed'"
              class="task-processing"
            >
              <span class="spinner">⟳</span>
              <div>
                <strong>{{ processingText(null) }}</strong>
                <p>正在从后端恢复任务状态</p>
              </div>
            </div>

            <div
              v-else-if="exchange.assistant.status === 'failed' || exchange.assistant.videoTask?.status === 'failed'"
              class="task-error"
            >
              <strong>这次没有生成成功</strong>
              <p>{{ exchange.assistant.videoTask?.errorMessage || exchange.assistant.errorMessage || '视频生成失败，请稍后再试' }}</p>
              <span v-if="exchange.assistant.videoTask?.errorSuggestion">
                {{ exchange.assistant.videoTask.errorSuggestion }}
              </span>
            </div>

            <div
              v-else-if="exchange.assistant.videoTask?.status === 'processing'"
              class="task-processing"
            >
              <span class="spinner">◌</span>
              <div>
                <strong>{{ processingText(exchange.assistant.videoTask) }}</strong>
                <p>{{ exchange.assistant.videoTask.taskCode }}</p>
              </div>
            </div>

            <div v-else class="task-result">
              <video
                v-if="exchange.assistant.videoTask?.results?.[0]"
                :src="resultUrl(exchange.assistant.videoTask.results[0])"
                controls
                playsinline
                preload="metadata"
              />
              <div class="result-meta">
                <div>
                  <strong>{{ statusText(exchange.assistant.videoTask) }}</strong>
                  <span>{{ formatTime(exchange.assistant.videoTask?.completedAt || exchange.assistant.videoTask?.createdAt) }}</span>
                </div>
                <a
                  v-if="exchange.assistant.videoTask?.results?.[0]"
                  class="download-link"
                  :href="resultUrl(exchange.assistant.videoTask.results[0])"
                  target="_blank"
                  rel="noopener"
                >下载视频</a>
              </div>
            </div>
          </div>
        </div>
      </article>
    </section>

    <section class="composer-area">
      <div class="composer">
        <div class="mode-switch" role="tablist" aria-label="视频生成模式">
          <button :class="{ active: mode === 'text' }" type="button" @click="setMode('text')">文生视频</button>
          <button :class="{ active: mode === 'image' }" type="button" @click="setMode('image')">图生视频</button>
          <button :class="{ active: mode === 'url' }" type="button" @click="setMode('url')">URL 上传</button>
        </div>

        <div v-if="mode === 'image'" class="upload-row">
          <div v-if="referencePreview" class="upload-preview">
            <img :src="referencePreview" alt="参考图" />
            <span v-if="videosStore.uploading" class="upload-mask">上传中...</span>
            <button type="button" class="clear-upload" title="移除参考图" aria-label="移除参考图" @click="clearReference">×</button>
          </div>
          <label v-else class="upload-add">
            <input type="file" accept="image/*" hidden @change="onFileChange" />
            <span>＋</span>
            <strong>参考图</strong>
          </label>
        </div>

        <div v-if="mode === 'url'" class="url-upload-row">
          <div class="url-field" :class="{ invalid: referenceUrl && !normalizedReferenceUrl }">
            <span aria-hidden="true">URL</span>
            <input
              v-model="referenceUrl"
              type="url"
              maxlength="4000"
              inputmode="url"
              autocomplete="off"
              placeholder="粘贴可公网访问的图片直链"
              aria-label="公网图片 URL"
              @blur="validateReferenceUrl"
            />
          </div>
          <div v-if="normalizedReferenceUrl && !urlPreviewFailed" class="url-preview">
            <img :src="normalizedReferenceUrl" alt="URL 参考图" @error="urlPreviewFailed = true" />
          </div>
          <span v-else-if="normalizedReferenceUrl" class="url-preview-hint">直链预览失败，仍可尝试提交</span>
        </div>

        <textarea
          ref="promptEl"
          v-model="prompt"
          class="prompt"
          :placeholder="mode === 'text' ? '描述你想生成的视频画面...' : '描述参考图要如何动起来...'"
          rows="1"
          maxlength="10000"
          @keydown="onKeydown"
          @paste="onPaste"
        />

        <div class="composer-toolbar">
          <div class="options">
            <select v-model="model" title="视频模型" aria-label="视频模型">
              <option v-for="item in availableModels" :key="item.id" :value="item.id">{{ item.name }}</option>
            </select>
            <select v-model.number="durationSeconds" title="视频时长" aria-label="视频时长">
              <option v-for="seconds in durations" :key="seconds" :value="seconds">{{ seconds }} 秒</option>
            </select>
            <select v-model="aspectRatio" title="画面比例" aria-label="画面比例">
              <option v-for="ratio in ratios" :key="ratio" :value="ratio">{{ ratio }}</option>
            </select>
            <select v-model="resolution" title="分辨率" aria-label="分辨率">
              <option value="720p">720p</option>
            </select>
          </div>

          <div class="submit-group">
            <span v-if="estimatedCredits !== null" class="cost">预计 {{ estimatedCredits }} 积分</span>
            <button
              type="button"
              class="submit-btn"
              :disabled="busy || !prompt.trim() || !referenceReady"
              @click="submit"
            >
              {{ videosStore.submitting ? '提交中...' : '生成视频' }}
            </button>
          </div>
        </div>

        <p v-if="error" class="error-text">{{ error }}</p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.video-main {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg);
}

.video-header {
  min-height: 65px;
  padding: 12px 20px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--border);
  background: rgba(251, 244, 241, 0.92);
  backdrop-filter: blur(14px);
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
  font-size: 20px;
  font-weight: 600;
}

.type-badge,
.credit-count {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11px;
}

.type-badge {
  color: var(--gold);
  background: var(--gold-soft);
}

.account-summary {
  display: flex;
  align-items: center;
  gap: 10px;
}

.credit-count {
  color: var(--accent-hover);
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
}

.user-avatar,
.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-radius: 50%;
  font-weight: 700;
}

.user-avatar {
  width: 40px;
  height: 40px;
  color: var(--accent-hover);
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  font-size: 14px;
}

.video-feed {
  flex: 1;
  overflow-y: auto;
  padding: 12px 24px 32px;
}

.feed-empty,
.video-welcome {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: var(--text-muted);
}

.feed-empty {
  gap: 10px;
  font-size: 14px;
}

.video-welcome h2 {
  margin-top: 14px;
  font-size: 36px;
}

.video-welcome p {
  margin-top: 8px;
  font-size: 14px;
}

.welcome-mark {
  width: 58px;
  height: 58px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  background: var(--gradient);
  box-shadow: 0 12px 30px rgba(224, 155, 176, 0.3);
}

.task-thread {
  max-width: var(--chat-max-width);
  margin: 0 auto;
  padding: 14px 0 24px;
  border-bottom: 1px solid rgba(239, 221, 216, 0.74);
}

.task-thread:last-child {
  border-bottom: 0;
}

.message {
  display: flex;
  gap: 14px;
  width: 100%;
}

.user-message {
  flex-direction: row-reverse;
}

.assistant-message {
  margin-top: 18px;
}

.avatar {
  width: 34px;
  height: 34px;
  font-size: 11px;
}

.avatar.user {
  color: var(--accent-hover);
  background: var(--accent-soft);
}

.avatar.assistant {
  color: var(--gold);
  background: var(--gold-soft);
}

.user-content {
  max-width: 82%;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 5px;
}

.user-content p {
  padding: 12px 16px;
  border-radius: 18px 18px 4px 18px;
  color: #fff;
  background: var(--gradient);
  box-shadow: 0 6px 16px rgba(224, 155, 176, 0.28);
  white-space: pre-wrap;
  word-break: break-word;
  text-align: left;
  font-size: 15px;
}

.message-reference {
  display: block;
  width: min(260px, 72vw);
  max-height: 320px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
}

.user-content span {
  color: var(--text-muted);
  font-size: 11px;
}

.assistant-content {
  min-width: 0;
  flex: 1;
}

.task-processing {
  min-height: 50px;
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text-muted);
}

.task-processing strong {
  display: block;
  color: var(--text);
  font-size: 14px;
  font-weight: 500;
}

.task-processing p {
  margin-top: 3px;
  font-size: 11px;
}

.spinner {
  display: inline-block;
  color: var(--accent);
  font-size: 20px;
  animation: spin 0.9s linear infinite;
}

.task-error {
  max-width: 580px;
  padding: 13px 15px;
  border-radius: 8px;
  color: var(--danger);
  background: rgba(210, 115, 138, 0.1);
  border: 1px solid rgba(210, 115, 138, 0.28);
}

.task-error strong,
.task-error p,
.task-error span {
  display: block;
}

.task-error p {
  margin-top: 5px;
  font-size: 13px;
}

.task-error span {
  margin-top: 7px;
  color: var(--text-muted);
  font-size: 12px;
}

.task-result {
  width: min(100%, 640px);
}

.task-result video {
  display: block;
  width: 100%;
  max-height: 540px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: #211d1f;
  box-shadow: var(--shadow-sm);
}

.result-meta {
  min-height: 42px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding-top: 9px;
}

.result-meta div {
  display: flex;
  align-items: baseline;
  gap: 9px;
}

.result-meta strong {
  color: var(--success);
  font-size: 13px;
}

.result-meta span {
  color: var(--text-muted);
  font-size: 11px;
}

.download-link {
  color: var(--accent-hover);
  font-size: 12px;
}

.download-link:hover {
  text-decoration: underline;
}

.composer-area {
  flex-shrink: 0;
  padding: 16px 24px 24px;
  background: linear-gradient(transparent, var(--bg) 25%);
}

.composer {
  max-width: var(--chat-max-width);
  margin: 0 auto;
  padding: 12px;
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  box-shadow: var(--shadow);
}

.mode-switch {
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  margin-bottom: 8px;
  border-radius: 999px;
  background: var(--bg-input);
  border: 1px solid var(--border);
}

.mode-switch button {
  height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  color: var(--text-muted);
  background: transparent;
  font-size: 12px;
}

.mode-switch button.active {
  color: var(--accent-hover);
  background: var(--bg-elevated);
  box-shadow: var(--shadow-sm);
}

.upload-row {
  margin: 2px 0 8px;
}

.url-upload-row {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 2px 0 8px;
}

.url-field {
  min-width: 0;
  flex: 1;
  height: 40px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 11px;
  border-radius: 8px;
  color: var(--text-muted);
  background: var(--bg-input);
  border: 1px solid var(--border);
}

.url-field:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.url-field.invalid {
  border-color: var(--danger);
}

.url-field span {
  flex-shrink: 0;
  color: var(--accent-hover);
  font-size: 10px;
  font-weight: 700;
}

.url-field input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: 0;
  color: var(--text);
  background: transparent;
  font-size: 13px;
}

.url-preview {
  width: 40px;
  height: 40px;
  overflow: hidden;
  flex-shrink: 0;
  border-radius: 8px;
  border: 1px solid var(--border);
}

.url-preview img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.url-preview-hint {
  min-width: 0;
  flex-shrink: 1;
  color: var(--text-muted);
  font-size: 11px;
  text-align: right;
}

.upload-preview,
.upload-add {
  position: relative;
  width: 68px;
  height: 68px;
  overflow: hidden;
  border-radius: 8px;
  border: 1px dashed var(--border-strong);
}

.upload-preview img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.upload-mask {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: #fff;
  background: rgba(60, 45, 42, 0.58);
  font-size: 11px;
}

.clear-upload {
  position: absolute;
  top: 3px;
  right: 3px;
  width: 21px;
  height: 21px;
  border-radius: 50%;
  color: #fff;
  background: rgba(45, 32, 35, 0.72);
}

.upload-add {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  cursor: pointer;
}

.upload-add:hover {
  color: var(--accent-hover);
  border-color: var(--accent);
}

.upload-add span {
  font-size: 20px;
  line-height: 1;
}

.upload-add strong {
  margin-top: 4px;
  font-size: 11px;
  font-weight: 500;
}

.prompt {
  width: 100%;
  min-height: 46px;
  max-height: 150px;
  padding: 9px 10px;
  resize: none;
  overflow-y: auto;
  border: 0;
  outline: 0;
  color: var(--text);
  background: transparent;
  font-size: 15px;
  line-height: 1.5;
}

.composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.options,
.submit-group {
  display: flex;
  align-items: center;
  gap: 7px;
}

.options {
  min-width: 0;
  flex-wrap: wrap;
}

.options select {
  height: 34px;
  padding: 0 8px;
  max-width: 145px;
  border-radius: 8px;
  outline: 0;
  color: var(--text-muted);
  background: var(--bg-input);
  border: 1px solid var(--border);
  font-size: 11px;
}

.submit-group {
  flex-shrink: 0;
}

.cost {
  color: var(--text-muted);
  font-size: 11px;
  white-space: nowrap;
}

.submit-btn {
  min-width: 96px;
  height: 40px;
  padding: 0 16px;
  border-radius: 8px;
  color: #fff;
  background: var(--gradient);
  box-shadow: 0 6px 16px rgba(224, 155, 176, 0.35);
  font-size: 13px;
  font-weight: 700;
}

.submit-btn:disabled {
  opacity: 0.42;
  cursor: not-allowed;
}

.error-text {
  margin: 8px 3px 0;
  color: var(--danger);
  font-size: 12px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 760px) {
  .video-header {
    padding: 10px 14px;
  }

  .credit-count {
    display: none;
  }

  .video-feed {
    padding: 8px 14px 22px;
  }

  .video-welcome h2 {
    font-size: 29px;
  }

  .composer-area {
    padding: 10px 10px max(12px, env(safe-area-inset-bottom));
  }

  .composer {
    border-radius: 14px;
  }

  .composer-toolbar {
    align-items: flex-end;
  }

  .options {
    gap: 5px;
  }

  .options select {
    max-width: 112px;
  }

  .cost {
    display: none;
  }

  .submit-btn {
    min-width: 84px;
    padding: 0 12px;
  }
}

@media (max-width: 480px) {
  .type-badge {
    display: none;
  }

  .options select:first-child {
    max-width: 128px;
  }

  .options select:last-child {
    display: none;
  }

  .url-preview-hint {
    display: none;
  }
}
</style>
