<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { useConversationsStore } from '../../stores/conversations'
import { normalizeImageUrl } from '../../api'
import {
  extractPolicyAdvice,
  isRecoverableGenerationFailure,
  resolveGenerationStatus
} from '../../utils/generation'
import ImageLightbox from '../ImageLightbox.vue'

const RECOVERY_GRACE_MS = 3 * 60 * 1000
const AI_AVATAR_URL = 'http://xtstuc.dyfl.top/xtsimage/6a6f6a3beb424eaed75effde133c9b85.png'

const props = defineProps({
  message: { type: Object, required: true },
  assistantMode: { type: Boolean, default: false }
})

const emit = defineEmits(['apply-prompt'])

const convStore = useConversationsStore()
const imageUrl = computed(() => convStore.imageUrlForMessage(props.message))
const sourceImageUrls = computed(() => {
  const images = props.message.sourceImages?.length
    ? props.message.sourceImages : (props.message.sourceImage ? [props.message.sourceImage] : [])
  return images.map(path => normalizeImageUrl(path)).filter(Boolean)
})
const generationRecord = computed(() => props.message.record || {
  status: props.message.status,
  recoverable: props.message.recoverable,
  retryable: props.message.retryable
})
const messageStatus = computed(() => {
  return resolveGenerationStatus(generationRecord.value)
})
const backendError = computed(() => {
  return props.message.errorMessage || props.message.record?.errorMessage || ''
})
const recoveryStartedAt = ref(0)
const recoveryNow = ref(Date.now())
let recoveryTimer = null

const isRecoverableFailure = computed(() => {
  return isRecoverableGenerationFailure(generationRecord.value, backendError.value)
})
const isRecoveryGrace = computed(() => {
  return isRecoverableFailure.value && recoveryStartedAt.value > 0 &&
    recoveryNow.value - recoveryStartedAt.value < RECOVERY_GRACE_MS
})
const isProcessing = computed(() => {
  if (isRecoveryGrace.value) return true
  const status = messageStatus.value
  if (status === 'completed' || status === 'failed') return false
  return status === 'processing' || !status
})
const isFailed = computed(() => {
  return messageStatus.value === 'failed' && !isRecoveryGrace.value
})
const policyAdviceText = computed(() => extractPolicyAdvice(props.message.errorMessage || ''))
const isPolicyAdvice = computed(() => !!policyAdviceText.value)
const displayError = computed(() => policyAdviceText.value || (props.message.errorMessage || ''))
const loadingText = computed(() => {
  if (isRecoveryGrace.value) {
    return '稍等一下，我马上就好，就快好了喵～'
  }
  return '正在认真画啦，再给我一点点时间喵～'
})
const previewImage = ref('')
const avatarFailed = ref(false)

function stopRecoveryTimer() {
  if (!recoveryTimer) return
  window.clearInterval(recoveryTimer)
  recoveryTimer = null
}

watch(isRecoverableFailure, recoverable => {
  if (!recoverable) {
    recoveryStartedAt.value = 0
    stopRecoveryTimer()
    return
  }
  if (!recoveryStartedAt.value) {
    recoveryStartedAt.value = Date.now()
  }
  recoveryNow.value = Date.now()
  if (!recoveryTimer) {
    recoveryTimer = window.setInterval(() => {
      recoveryNow.value = Date.now()
      if (!isRecoveryGrace.value) stopRecoveryTimer()
    }, 1000)
  }
}, { immediate: true })

onUnmounted(stopRecoveryTimer)

function openPreview(url) {
  if (url) previewImage.value = url
}

</script>

<template>
  <div class="message" :class="message.role">
    <div class="avatar">
      <template v-if="message.role === 'user'">你</template>
      <img
        v-else-if="!avatarFailed"
        :src="AI_AVATAR_URL"
        alt="AI"
        referrerpolicy="no-referrer"
        @error="avatarFailed = true"
      />
      <template v-else>AI</template>
    </div>
    <div class="bubble">
      <template v-if="message.role === 'user'">
        <div v-if="sourceImageUrls.length" class="source-gallery">
          <img
          v-for="(sourceImageUrl, index) in sourceImageUrls"
          :key="index"
          :src="sourceImageUrl"
          alt="参考图"
          class="source-thumb"
          @click="openPreview(sourceImageUrl)"
          />
        </div>
        <p class="text">{{ message.content }}</p>
      </template>

      <template v-else>
        <template v-if="message.assistant && message.status === 'completed'">
          <div class="assistant-advice"><p>{{ message.content }}</p></div>
          <div v-if="message.assistant?.prompt" class="assistant-prompt-card">
            <strong>最终提示词</strong>
            <pre>{{ message.assistant.prompt }}</pre>
            <button type="button" @click="emit('apply-prompt', message.assistant)">应用到生图</button>
          </div>
          <div v-if="message.assistant?.negativePrompt" class="assistant-negative">
            反向提示词：{{ message.assistant.negativePrompt }}
          </div>
        </template>
        <div v-if="isProcessing" class="assistant-loading">
          <span class="spin">⟳</span>
          <span>{{ loadingText }}</span>
        </div>
        <div v-else-if="isFailed && isPolicyAdvice" class="assistant-advice">
          <p>{{ displayError }}</p>
        </div>
        <div v-else-if="isFailed" class="assistant-error">
          <span>生成失败</span>
          <p v-if="displayError">{{ displayError }}</p>
        </div>
        <div v-else-if="imageUrl" class="assistant-image">
          <img
            :src="imageUrl"
            :alt="message.record?.prompt || '生成结果'"
            loading="lazy"
            @click="openPreview(imageUrl)"
          />
          <p v-if="message.record?.taskCode" class="task-code">{{ message.record.taskCode }}</p>
        </div>
        <div v-else class="assistant-loading">
          <span class="text-muted">等待结果…</span>
        </div>
      </template>
    </div>
    <ImageLightbox
      :src="previewImage"
      :alt="message.record?.prompt || '图片预览'"
      @close="previewImage = ''"
    />
  </div>
</template>

<style scoped>
.message {
  display: flex;
  gap: 14px;
  padding: 20px 0;
  max-width: var(--chat-max-width);
  margin: 0 auto;
  width: 100%;
}

.message.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
  background: var(--bg-blush);
  color: var(--text-muted);
}

.avatar img {
  width: 100%;
  height: 100%;
  display: block;
  border-radius: inherit;
  object-fit: cover;
}

.message.user .avatar {
  background: var(--accent-soft);
  color: var(--accent-hover);
}

.message.assistant .avatar {
  overflow: hidden;
  background: var(--gold-soft);
  color: var(--gold);
}

.bubble {
  flex: 1;
  min-width: 0;
}

.message.user .bubble {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.text {
  font-size: 15px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.message.user .text {
  background: var(--gradient);
  color: #fff;
  padding: 12px 16px;
  border-radius: 18px 18px 4px 18px;
  max-width: 85%;
  box-shadow: 0 6px 16px rgba(224, 155, 176, 0.32);
}

.source-gallery {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  max-width: 100%;
}

.source-thumb {
  max-width: min(200px, 100%);
  max-height: 160px;
  border-radius: 12px;
  margin-bottom: 8px;
  object-fit: cover;
  border: 1px solid var(--border);
  cursor: zoom-in;
}

.assistant-loading {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-muted);
  font-size: 14px;
  padding: 8px 0;
}

.spin {
  display: inline-block;
  animation: spin 0.9s linear infinite;
  font-size: 20px;
  color: var(--accent);
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.assistant-error {
  padding: 12px 16px;
  background: rgba(210, 115, 138, 0.1);
  border: 1px solid rgba(210, 115, 138, 0.28);
  border-radius: 12px;
  color: var(--danger);
  font-size: 14px;
}

.assistant-error p {
  margin-top: 6px;
  font-size: 13px;
  opacity: 0.85;
}

.assistant-advice {
  color: var(--text);
  font-size: 15px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  padding: 8px 0;
}

.assistant-advice p {
  margin: 0;
}

.assistant-prompt-card {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-input);
}

.assistant-prompt-card pre {
  margin: 8px 0;
  white-space: pre-wrap;
  word-break: break-word;
  font: inherit;
  line-height: 1.55;
}

.assistant-prompt-card button {
  padding: 7px 14px;
  border-radius: 999px;
  background: var(--gradient);
  color: #fff;
  font-size: 12px;
}

.assistant-negative {
  margin-top: 8px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.assistant-image img {
  max-width: 100%;
  max-height: 480px;
  border-radius: 12px;
  border: 1px solid var(--border);
  display: block;
  cursor: zoom-in;
}

.task-code {
  margin-top: 8px;
  font-size: 11px;
  font-family: monospace;
  color: var(--text-muted);
}

.text-muted {
  color: var(--text-muted);
}
</style>
