<script setup>
import {
  computed,
  nextTick,
  onMounted,
  onUnmounted,
  reactive,
  ref,
  watch
} from 'vue'
import { useAuthStore } from '../../stores/auth'
import { useConversationsStore } from '../../stores/conversations'
import { useUserStore } from '../../stores/user'
import { useVideosStore } from '../../stores/videos'
import VideoReferenceImage from './VideoReferenceImage.vue'

defineEmits(['open-sidebar'])

const AI_AVATAR_URL = 'http://xtstuc.dyfl.top/xtsimage/6a6f6a3beb424eaed75effde133c9b85.png'

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
const referenceAssets = ref([])
const referenceUrls = ref([createUrlReference(0)])
const error = ref('')
const promptEl = ref(null)
const feedEl = ref(null)
const avatarFailed = ref(false)
const preparingSubmission = ref(false)

const durations = [5, 10, 15]
const ratios = ['16:9', '9:16', '1:1', '4:3', '3:4', '2.39:1']
const MAX_REFERENCE_IMAGES = 9

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

const busy = computed(() => {
  return preparingSubmission.value || videosStore.submitting || referenceAssets.value.some(item => item.uploading)
})
const normalizedReferenceUrls = computed(() => {
  return referenceUrls.value
    .map((item, index) => ({ ...item, url: normalizeHttpUrl(item.value), sortOrder: index }))
    .filter(item => item.url)
})
const hasInvalidReferenceUrl = computed(() => {
  return referenceUrls.value.some(item => item.value.trim() && !normalizeHttpUrl(item.value))
})
const referenceReady = computed(() => {
  if (mode.value === 'image') {
    return referenceAssets.value.length > 0 && referenceAssets.value.every(item => !!item.asset && !item.uploading)
  }
  if (mode.value === 'url') return normalizedReferenceUrls.value.length > 0 && !hasInvalidReferenceUrl.value
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

watch(
  () => conversation.value?.id,
  () => {
    const type = conversation.value?.type
    mode.value = type === 'image2video' ? 'image' : 'text'
    prompt.value = ''
    error.value = ''
    clearReference()
    clearReferenceUrls()
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
  const conv = ensureVideoConversation()
  mode.value = nextMode
  convStore.setConversationType(conv.id, nextMode === 'text' ? 'text2video' : 'image2video')
  error.value = ''
  if (nextMode !== 'image') clearReference()
  if (nextMode !== 'url') clearReferenceUrls()
  nextTick(() => promptEl.value?.focus())
}

function ensureVideoConversation() {
  const current = conversation.value
  if (current?.type === 'text2video' || current?.type === 'image2video') return current
  return convStore.activateGenerationArea('video')
}

function clearReference() {
  referenceAssets.value.forEach(item => URL.revokeObjectURL(item.preview))
  referenceAssets.value = []
}

function createUrlReference(index) {
  return {
    key: `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
    value: '',
    role: index === 0 ? 'BACKGROUND' : 'SUBJECT',
    previewFailed: false
  }
}

function clearReferenceUrls() {
  referenceUrls.value = [createUrlReference(0)]
}

function addReferenceUrl() {
  if (referenceUrls.value.length >= MAX_REFERENCE_IMAGES) return
  referenceUrls.value.push(createUrlReference(referenceUrls.value.length))
  error.value = ''
}

function removeReferenceUrl(key) {
  const index = referenceUrls.value.findIndex(item => item.key === key)
  if (index < 0) return
  referenceUrls.value.splice(index, 1)
  if (!referenceUrls.value.length) clearReferenceUrls()
  error.value = ''
}

function onReferenceUrlInput(reference) {
  reference.previewFailed = false
  if (error.value.includes('公网图片 URL')) error.value = ''
}

async function onFileChange(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  if (!files.length) return
  await applyReferenceFiles(files)
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

function validateReferenceUrl(reference, index) {
  if (reference.value.trim() && !normalizeHttpUrl(reference.value)) {
    error.value = `第 ${index + 1} 个公网图片 URL 格式无效`
  }
}

function removeReference(key) {
  const index = referenceAssets.value.findIndex(item => item.key === key)
  if (index < 0 || referenceAssets.value[index].uploading) return
  URL.revokeObjectURL(referenceAssets.value[index].preview)
  referenceAssets.value.splice(index, 1)
}

async function applyReferenceFiles(files) {
  const available = MAX_REFERENCE_IMAGES - referenceAssets.value.length
  if (available <= 0) {
    error.value = `一次最多上传 ${MAX_REFERENCE_IMAGES} 张参考图`
    return
  }
  if (files.length > available) {
    error.value = `一次最多上传 ${MAX_REFERENCE_IMAGES} 张参考图，已保留前 ${available} 张`
  } else {
    error.value = ''
  }

  for (const file of files.slice(0, available)) {
    if (referenceAssets.value.length >= MAX_REFERENCE_IMAGES) break
    const normalized = normalizeClipboardImage(file, file.type || 'image/png')
    if (!normalized || !normalized.type.startsWith('image/')) {
      error.value = '请选择图片文件'
      continue
    }
    if (normalized.size > 20 * 1024 * 1024) {
      error.value = '每张参考图片不能超过 20MB'
      continue
    }

    const item = reactive({
      key: `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
      preview: URL.createObjectURL(normalized),
      role: referenceAssets.value.length === 0 ? 'BACKGROUND' : 'SUBJECT',
      asset: null,
      uploading: true
    })
    referenceAssets.value.push(item)
    try {
      item.asset = await videosStore.uploadImage(normalized)
    } catch (uploadError) {
      error.value = uploadError.message
      item.uploading = false
      removeReference(item.key)
      continue
    }
    item.uploading = false
  }
}

async function onPaste(event) {
  const items = Array.from(event.clipboardData?.items || [])
  const filesFromList = Array.from(event.clipboardData?.files || []).filter(file => {
    return file.type?.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp)$/i.test(file.name || '')
  })
  const files = filesFromList.length
    ? filesFromList
    : items.filter(item => item.type?.startsWith('image/')).map(item => item.getAsFile?.()).filter(Boolean)
  if (!files.length) return

  event.preventDefault()
  setMode('image')
  await applyReferenceFiles(files)
  nextTick(() => {
    resizePrompt()
    promptEl.value?.focus()
  })
}

async function submit() {
  if (preparingSubmission.value || videosStore.submitting) return
  if (referenceAssets.value.some(item => item.uploading)) {
    error.value = '参考图片仍在上传，请稍等'
    return
  }
  const text = prompt.value.trim()
  if (!text) {
    error.value = '请先描述你想生成的视频'
    return
  }
  if (mode.value === 'image' && !referenceReady.value) {
    error.value = '请先完成参考图片上传'
    return
  }
  if (mode.value === 'url' && !referenceReady.value) {
    error.value = hasInvalidReferenceUrl.value ? '请修正格式无效的公网图片 URL' : '请至少输入一个公网图片 URL'
    return
  }

  const assets = mode.value === 'image'
    ? referenceAssets.value.map((item, index) => ({
        mediaType: 'IMAGE',
        role: item.role,
        localPath: item.asset.localPath,
        sortOrder: index
      }))
    : mode.value === 'url'
      ? normalizedReferenceUrls.value.map(item => ({
          mediaType: 'IMAGE',
          role: item.role,
          sourceUrl: item.url,
          sortOrder: item.sortOrder
        }))
      : []

  const conversationType = mode.value === 'text' ? 'text2video' : 'image2video'
  const conv = ensureVideoConversation()
  convStore.setConversationType(conv.id, conversationType)
  const exchange = convStore.addExchange(conv.id, {
    prompt: text,
    sourceImage: mode.value === 'image'
      ? referenceAssets.value[0]?.asset?.sourceUrl
      : mode.value === 'url'
        ? normalizedReferenceUrls.value[0]?.url
        : null,
    sourceImages: mode.value === 'image'
      ? referenceAssets.value.map(item => item.asset.sourceUrl)
      : mode.value === 'url'
        ? normalizedReferenceUrls.value.map(item => item.url)
        : [],
    type: conversationType
  })

  error.value = ''
  preparingSubmission.value = true
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
    clearReferenceUrls()
    await userStore.syncCreditsFromServer().catch(() => {})
    scrollToLatest()
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
      return
    }
    error.value = submitError.message
    convStore.failAssistantMessage(exchange.assistantMsg.id, submitError.message)
  } finally {
    preparingSubmission.value = false
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
  return exchangeReferenceImages(exchange).length ? '图生视频' : '文生视频'
}

function exchangeReferenceImages(exchange) {
  const taskAssets = Array.isArray(exchange.assistant?.videoTask?.inputAssets)
    ? exchange.assistant.videoTask.inputAssets
        .filter(asset => asset?.mediaType === 'IMAGE' && (asset?.sourceUrl || asset?.previewPath))
        .sort((left, right) => Number(left.sortOrder || 0) - Number(right.sortOrder || 0))
        .map(asset => ({ sourceUrl: asset.sourceUrl || '', previewPath: asset.previewPath || '' }))
    : []
  if (taskAssets.length) return taskAssets
  const images = Array.isArray(exchange.user?.sourceImages)
    ? exchange.user.sourceImages.filter(Boolean)
    : []
  if (images.length) return images.map(sourceUrl => ({ sourceUrl, previewPath: '' }))
  return exchange.user?.sourceImage
    ? [{ sourceUrl: exchange.user.sourceImage, previewPath: '' }]
    : []
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
}

onMounted(initialize)
onUnmounted(() => {
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
            <div
              v-if="exchangeReferenceImages(exchange).length"
              class="message-references"
              :class="{ single: exchangeReferenceImages(exchange).length === 1 }"
            >
              <VideoReferenceImage
                v-for="(image, imageIndex) in exchangeReferenceImages(exchange)"
                :key="`${exchange.id}-${image.previewPath || image.sourceUrl}-${imageIndex}`"
                class="message-reference"
                :source-url="image.sourceUrl"
                :preview-path="image.previewPath"
                :alt="`视频参考图 ${imageIndex + 1}`"
              />
            </div>
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
          <div class="avatar assistant">
            <img
              v-if="!avatarFailed"
              :src="AI_AVATAR_URL"
              alt="AI"
              referrerpolicy="no-referrer"
              @error="avatarFailed = true"
            />
            <span v-else>AI</span>
          </div>
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
          <div v-for="(reference, index) in referenceAssets" :key="reference.key" class="upload-item">
            <div class="upload-preview">
              <img :src="reference.preview" :alt="`参考图 ${index + 1}`" />
              <span v-if="reference.uploading" class="upload-mask">上传中...</span>
              <button
                type="button"
                class="clear-upload"
                :disabled="reference.uploading"
                :title="`移除参考图 ${index + 1}`"
                :aria-label="`移除参考图 ${index + 1}`"
                @click="removeReference(reference.key)"
              >×</button>
            </div>
            <select v-model="reference.role" class="reference-role" :aria-label="`参考图 ${index + 1} 类型`">
              <option value="BACKGROUND">场景</option>
              <option value="SUBJECT">角色</option>
              <option value="REFERENCE">参考</option>
            </select>
          </div>
          <label
            v-if="referenceAssets.length < MAX_REFERENCE_IMAGES"
            class="upload-add"
            :class="{ disabled: busy }"
            title="添加参考图"
          >
            <input type="file" accept="image/*" multiple hidden :disabled="busy" @change="onFileChange" />
            <span>＋</span>
            <strong>{{ referenceAssets.length }}/{{ MAX_REFERENCE_IMAGES }}</strong>
          </label>
        </div>

        <div v-if="mode === 'url'" class="url-upload-list">
          <div v-for="(reference, index) in referenceUrls" :key="reference.key" class="url-upload-row">
            <div class="url-field" :class="{ invalid: reference.value && !normalizeHttpUrl(reference.value) }">
              <span aria-hidden="true">URL {{ index + 1 }}</span>
              <input
                v-model="reference.value"
                type="url"
                maxlength="4000"
                inputmode="url"
                autocomplete="off"
                placeholder="粘贴可公网访问的图片直链"
                :aria-label="`公网图片 URL ${index + 1}`"
                @input="onReferenceUrlInput(reference)"
                @blur="validateReferenceUrl(reference, index)"
              />
            </div>
            <select v-model="reference.role" class="url-reference-role" :aria-label="`URL 参考图 ${index + 1} 类型`">
              <option value="BACKGROUND">场景</option>
              <option value="SUBJECT">角色</option>
              <option value="REFERENCE">参考</option>
            </select>
            <div v-if="normalizeHttpUrl(reference.value) && !reference.previewFailed" class="url-preview">
              <img
                :src="normalizeHttpUrl(reference.value)"
                :alt="`URL 参考图 ${index + 1}`"
                @error="reference.previewFailed = true"
              />
            </div>
            <span
              v-else-if="normalizeHttpUrl(reference.value)"
              class="url-preview-failed"
              title="直链预览失败，仍可提交"
              aria-label="直链预览失败，仍可提交"
            >!</span>
            <button
              type="button"
              class="remove-url"
              :title="`移除 URL ${index + 1}`"
              :aria-label="`移除 URL ${index + 1}`"
              @click="removeReferenceUrl(reference.key)"
            >×</button>
          </div>
          <button
            v-if="referenceUrls.length < MAX_REFERENCE_IMAGES"
            type="button"
            class="add-url"
            title="添加图片 URL"
            aria-label="添加图片 URL"
            @click="addReferenceUrl"
          >＋ <span>{{ referenceUrls.length }}/{{ MAX_REFERENCE_IMAGES }}</span></button>
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
              {{ preparingSubmission || videosStore.submitting ? '提交中...' : '生成视频' }}
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

.credit-count {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11px;
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

.avatar img {
  width: 100%;
  height: 100%;
  display: block;
  border-radius: inherit;
  object-fit: cover;
}

.avatar.user {
  color: var(--accent-hover);
  background: var(--accent-soft);
}

.avatar.assistant {
  overflow: hidden;
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

.message-references {
  width: min(276px, 72vw);
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
}

.message-reference {
  display: block;
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
}

.message-references.single {
  display: block;
  max-width: min(260px, 72vw);
}

.message-references.single .message-reference {
  width: min(260px, 72vw);
  height: auto;
  max-height: 320px;
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
  max-width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 3px;
  margin: 2px 0 8px;
}

.upload-item {
  width: 68px;
  flex: 0 0 68px;
}

.url-upload-list {
  max-height: 196px;
  display: grid;
  gap: 6px;
  overflow-y: auto;
  padding-right: 2px;
  margin: 2px 0 8px;
}

.url-upload-row {
  display: flex;
  align-items: center;
  gap: 6px;
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
  white-space: nowrap;
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

.url-reference-role {
  width: 68px;
  height: 40px;
  flex: 0 0 68px;
  padding: 0 5px;
  border-radius: 8px;
  color: var(--text-muted);
  background: var(--bg-input);
  border: 1px solid var(--border);
  font-size: 11px;
}

.url-preview-failed {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  flex: 0 0 40px;
  border-radius: 8px;
  color: var(--danger);
  background: var(--bg-input);
  border: 1px solid var(--border);
  font-size: 14px;
  font-weight: 700;
}

.remove-url {
  width: 28px;
  height: 40px;
  flex: 0 0 28px;
  border-radius: 8px;
  color: var(--text-muted);
  background: var(--bg-input);
  border: 1px solid var(--border);
  font-size: 18px;
}

.remove-url:hover {
  color: var(--danger);
  border-color: var(--danger);
}

.add-url {
  width: fit-content;
  height: 28px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0 9px;
  border-radius: 7px;
  color: var(--text-muted);
  background: var(--bg-input);
  border: 1px dashed var(--border-strong);
  font-size: 15px;
}

.add-url:hover {
  color: var(--accent-hover);
  border-color: var(--accent);
}

.add-url span {
  font-size: 11px;
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

.upload-add.disabled {
  cursor: default;
  opacity: 0.55;
  pointer-events: none;
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

.clear-upload:disabled {
  cursor: default;
  opacity: 0.45;
}

.reference-role {
  width: 68px;
  height: 24px;
  margin-top: 4px;
  padding: 0 4px;
  border-radius: 6px;
  color: var(--text-muted);
  background: var(--bg-input);
  border: 1px solid var(--border);
  font-size: 11px;
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
  .options select:first-child {
    max-width: 128px;
  }

  .options select:last-child {
    display: none;
  }

}
</style>
