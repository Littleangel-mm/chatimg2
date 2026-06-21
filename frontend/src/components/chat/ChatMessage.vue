<script setup>
import { computed } from 'vue'
import { useConversationsStore } from '../../stores/conversations'

const props = defineProps({
  message: { type: Object, required: true }
})

const convStore = useConversationsStore()

const imageUrl = computed(() => convStore.imageUrlForMessage(props.message))
const isProcessing = computed(() => props.message.status === 'processing')
const isFailed = computed(() => props.message.status === 'failed')
</script>

<template>
  <div class="message" :class="message.role">
    <div class="avatar">
      {{ message.role === 'user' ? '你' : 'AI' }}
    </div>
    <div class="bubble">
      <template v-if="message.role === 'user'">
        <img
          v-if="message.sourceImage"
          :src="message.sourceImage"
          alt="参考图"
          class="source-thumb"
        />
        <p class="text">{{ message.content }}</p>
      </template>

      <template v-else>
        <div v-if="isProcessing" class="assistant-loading">
          <span class="spin">⟳</span>
          <span>正在生成图片…</span>
        </div>
        <div v-else-if="isFailed" class="assistant-error">
          <span>生成失败</span>
          <p v-if="message.errorMessage">{{ message.errorMessage }}</p>
        </div>
        <div v-else-if="imageUrl" class="assistant-image">
          <img :src="imageUrl" :alt="message.record?.prompt || '生成结果'" loading="lazy" />
          <p v-if="message.record?.taskCode" class="task-code">{{ message.record.taskCode }}</p>
        </div>
        <div v-else class="assistant-loading">
          <span class="text-muted">等待结果…</span>
        </div>
      </template>
    </div>
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
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.08);
  color: var(--text-muted);
}

.message.user .avatar {
  background: rgba(124, 92, 255, 0.2);
  color: #b49cff;
}

.message.assistant .avatar {
  background: rgba(74, 222, 128, 0.15);
  color: #4ade80;
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
  background: rgba(124, 92, 255, 0.12);
  border: 1px solid rgba(124, 92, 255, 0.2);
  padding: 12px 16px;
  border-radius: 16px 16px 4px 16px;
  max-width: 85%;
}

.source-thumb {
  max-width: 200px;
  max-height: 160px;
  border-radius: 12px;
  margin-bottom: 8px;
  object-fit: cover;
  border: 1px solid var(--border);
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
  background: rgba(248, 113, 113, 0.1);
  border: 1px solid rgba(248, 113, 113, 0.25);
  border-radius: 12px;
  color: #f87171;
  font-size: 14px;
}

.assistant-error p {
  margin-top: 6px;
  font-size: 13px;
  opacity: 0.85;
}

.assistant-image img {
  max-width: 100%;
  max-height: 480px;
  border-radius: 12px;
  border: 1px solid var(--border);
  display: block;
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
