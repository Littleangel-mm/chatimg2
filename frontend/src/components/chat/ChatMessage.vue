<script setup>
import { computed, ref } from 'vue'
import { useConversationsStore } from '../../stores/conversations'
import { normalizeImageUrl } from '../../api'
import ImageLightbox from '../ImageLightbox.vue'

const props = defineProps({
  message: { type: Object, required: true }
})

const convStore = useConversationsStore()
const POLICY_ADVICE_PREFIX = 'CONTENT_POLICY_ADVICE:'
const LEGACY_POLICY_ADVICE_PREFIX = '内容安全提示：'

const imageUrl = computed(() => convStore.imageUrlForMessage(props.message))
const sourceImageUrl = computed(() => normalizeImageUrl(props.message.sourceImage))
const messageStatus = computed(() => {
  if (props.message.record?.imagePath) return 'completed'
  return (props.message.record?.status || props.message.status || '').toLowerCase()
})
const isProcessing = computed(() => {
  const status = messageStatus.value
  if (status === 'completed' || status === 'failed') return false
  return status === 'processing' || !status
})
const isFailed = computed(() => {
  return messageStatus.value === 'failed'
})
const policyAdviceText = computed(() => extractPolicyAdvice(props.message.errorMessage || ''))
const isPolicyAdvice = computed(() => !!policyAdviceText.value)
const displayError = computed(() => policyAdviceText.value || (props.message.errorMessage || ''))
const previewImage = ref('')

function openPreview(url) {
  if (url) previewImage.value = url
}

function extractPolicyAdvice(text) {
  if (!text) return ''
  const markerIndex = text.indexOf(POLICY_ADVICE_PREFIX)
  if (markerIndex >= 0) {
    return text.slice(markerIndex + POLICY_ADVICE_PREFIX.length).trim()
  }
  const legacyIndex = text.indexOf(LEGACY_POLICY_ADVICE_PREFIX)
  if (legacyIndex >= 0) {
    return text.slice(legacyIndex + LEGACY_POLICY_ADVICE_PREFIX.length).trim()
  }
  return ''
}
</script>

<template>
  <div class="message" :class="message.role">
    <div class="avatar">
      {{ message.role === 'user' ? '你' : 'AI' }}
    </div>
    <div class="bubble">
      <template v-if="message.role === 'user'">
        <img
          v-if="sourceImageUrl"
          :src="sourceImageUrl"
          alt="参考图"
          class="source-thumb"
          @click="openPreview(sourceImageUrl)"
        />
        <p class="text">{{ message.content }}</p>
      </template>

      <template v-else>
        <div v-if="isProcessing" class="assistant-loading">
          <span class="spin">⟳</span>
          <span>我知道你很急，但是你先别急</span>
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

.message.user .avatar {
  background: var(--accent-soft);
  color: var(--accent-hover);
}

.message.assistant .avatar {
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

.source-thumb {
  max-width: 200px;
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
