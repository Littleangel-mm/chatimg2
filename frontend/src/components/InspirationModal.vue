<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useInspirationStore } from '../stores/inspiration'
import { getInspirationImageUrl } from '../api'

const store = useInspirationStore()

const visible = ref(false)
const selected = ref(null)
const copied = ref(false)
const scrollEl = ref(null)

const tabs = [
  { id: 'image', name: '图片' },
  { id: 'video', name: '视频' }
]

function open() {
  visible.value = true
  selected.value = null
  store.ensureLoaded()
}

function close() {
  visible.value = false
  selected.value = null
}

function switchTab(type) {
  if (store.mediaType === type) return
  store.setMediaType(type)
  selected.value = null
  store.fetchPage(0).then(() => {
    nextTick(() => {
      if (scrollEl.value) scrollEl.value.scrollTop = 0
    })
  })
}

function imageUrl(item) {
  return getInspirationImageUrl(item)
}

function onScroll() {
  const el = scrollEl.value
  if (!el) return
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 400) {
    store.loadMore()
  }
}

async function copyPrompt(prompt) {
  try {
    await navigator.clipboard.writeText(prompt)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    /* ignore */
  }
}

function usePrompt(prompt) {
  window.dispatchEvent(new CustomEvent('use-inspiration-prompt', { detail: { prompt } }))
  close()
}

function onKeydown(e) {
  if (e.key === 'Escape' && visible.value) {
    if (selected.value) selected.value = null
    else close()
  }
}

onMounted(() => {
  window.addEventListener('open-inspiration', open)
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  window.removeEventListener('open-inspiration', open)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="visible" class="insp-overlay" @click.self="close">
        <div class="insp-modal">
          <header class="insp-header">
            <div class="insp-title">
              <h2>灵感画廊</h2>
              <span class="insp-count" v-if="store.total">{{ store.total }} 个灵感</span>
            </div>
            <div class="insp-tabs">
              <button
                v-for="t in tabs"
                :key="t.id"
                class="insp-tab"
                :class="{ active: store.mediaType === t.id }"
                @click="switchTab(t.id)"
              >
                {{ t.name }}
              </button>
            </div>
            <button class="insp-close" @click="close">✕</button>
          </header>

          <div ref="scrollEl" class="insp-body" @scroll="onScroll">
            <div v-if="store.error && !store.items.length" class="insp-state">
              <p>{{ store.error }}</p>
            </div>
            <div v-else-if="!store.loaded && store.loading" class="insp-state">
              <span class="spin">⟳</span>
              <p>正在加载灵感…</p>
            </div>
            <div v-else-if="store.loaded && !store.items.length" class="insp-state">
              <p>暂无灵感数据</p>
              <p class="muted">请管理员在后台「灵感画廊」点击「爬取更新」后再来查看</p>
            </div>

            <div v-else class="insp-grid">
              <figure
                v-for="item in store.items"
                :key="item.id"
                class="insp-card"
                @click="selected = item"
              >
                <img :src="imageUrl(item)" :alt="item.prompt" loading="lazy" />
                <figcaption class="insp-card-overlay">
                  <span class="insp-card-hint">查看 / 使用提示词</span>
                </figcaption>
              </figure>
            </div>

            <div v-if="store.loading && store.items.length" class="insp-loadmore">
              <span class="spin">⟳</span> 加载中…
            </div>
            <button
              v-else-if="store.hasMore"
              class="insp-loadmore-btn"
              @click="store.loadMore()"
            >
              加载更多
            </button>
          </div>
        </div>

        <!-- 详情面板 -->
        <Transition name="fade">
          <div v-if="selected" class="insp-detail-overlay" @click.self="selected = null">
            <div class="insp-detail">
              <button class="insp-close detail" @click="selected = null">✕</button>
              <div class="insp-detail-img">
                <img :src="imageUrl(selected)" :alt="selected.prompt" />
              </div>
              <div class="insp-detail-info">
                <h3>提示词 Prompt</h3>
                <div class="insp-prompt-box">{{ selected.prompt }}</div>
                <div class="insp-detail-actions">
                  <button class="btn-ghost-line" @click="copyPrompt(selected.prompt)">
                    {{ copied ? '已复制 ✓' : '复制提示词' }}
                  </button>
                  <button class="btn-grad" @click="usePrompt(selected.prompt)">
                    用此提示词生成
                  </button>
                </div>
              </div>
            </div>
          </div>
        </Transition>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.insp-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.72);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 24px;
}

.insp-modal {
  width: 100%;
  max-width: 1100px;
  height: 90vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.insp-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.insp-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.insp-title h2 {
  font-size: 18px;
  font-weight: 700;
}

.insp-count {
  font-size: 12px;
  color: var(--text-muted);
}

.insp-tabs {
  display: flex;
  gap: 4px;
  margin-left: auto;
  background: rgba(255, 255, 255, 0.04);
  padding: 4px;
  border-radius: 10px;
}

.insp-tab {
  padding: 6px 16px;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  font-size: 13px;
}

.insp-tab.active {
  background: var(--bg-input);
  color: var(--text);
}

.insp-close {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-muted);
  font-size: 15px;
}

.insp-close:hover {
  background: rgba(255, 255, 255, 0.12);
  color: var(--text);
}

.insp-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.insp-grid {
  columns: 4 220px;
  column-gap: 14px;
}

.insp-card {
  position: relative;
  break-inside: avoid;
  margin-bottom: 14px;
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid var(--border);
  background: var(--bg-input);
}

.insp-card img {
  width: 100%;
  display: block;
}

.insp-card-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: flex-end;
  padding: 12px;
  background: linear-gradient(transparent 55%, rgba(0, 0, 0, 0.7));
  opacity: 0;
  transition: opacity 0.15s;
}

.insp-card:hover .insp-card-overlay {
  opacity: 1;
}

.insp-card-hint {
  font-size: 12px;
  color: #fff;
}

.insp-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 320px;
  color: var(--text-muted);
  text-align: center;
}

.insp-state .muted {
  font-size: 13px;
  opacity: 0.8;
}

.insp-loadmore {
  text-align: center;
  padding: 16px;
  color: var(--text-muted);
  font-size: 13px;
}

.insp-loadmore-btn {
  display: block;
  margin: 8px auto 0;
  padding: 10px 24px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--text);
  font-size: 13px;
}

.insp-loadmore-btn:hover {
  background: rgba(255, 255, 255, 0.12);
}

/* 详情 */
.insp-detail-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1010;
  padding: 24px;
}

.insp-detail {
  position: relative;
  width: 100%;
  max-width: 820px;
  max-height: 88vh;
  display: flex;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.insp-detail-img {
  width: 45%;
  background: #000;
  display: flex;
  align-items: center;
  justify-content: center;
}

.insp-detail-img img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  max-height: 88vh;
}

.insp-detail-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 24px;
  min-width: 0;
}

.insp-detail-info h3 {
  font-size: 14px;
  color: var(--text-muted);
  margin-bottom: 12px;
}

.insp-prompt-box {
  flex: 1;
  overflow-y: auto;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text);
}

.insp-detail-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}

.btn-ghost-line {
  flex: 1;
  padding: 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--text);
  font-size: 14px;
}

.btn-ghost-line:hover {
  background: rgba(255, 255, 255, 0.12);
}

.btn-grad {
  flex: 1.4;
  padding: 12px;
  border-radius: 10px;
  background: var(--gradient);
  color: #fff;
  font-weight: 600;
  font-size: 14px;
}

.insp-close.detail {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 2;
  background: rgba(0, 0, 0, 0.5);
}

.spin {
  display: inline-block;
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 768px) {
  .insp-overlay {
    padding: 0;
  }
  .insp-modal {
    height: 100vh;
    max-width: 100%;
    border-radius: 0;
  }
  .insp-grid {
    columns: 2 140px;
    column-gap: 10px;
  }
  .insp-detail {
    flex-direction: column;
    max-height: 92vh;
  }
  .insp-detail-img {
    width: 100%;
    max-height: 38vh;
  }
}
</style>
