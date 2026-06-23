<script setup>
import { ref, onMounted, onActivated, onDeactivated, onUnmounted, nextTick, defineOptions } from 'vue'
import { useRouter } from 'vue-router'
import { useInspirationStore } from '../stores/inspiration'
import { useConversationsStore } from '../stores/conversations'
import { getInspirationImageUrl } from '../api'

defineOptions({ name: 'inspiration' })

const router = useRouter()
const store = useInspirationStore()
const convStore = useConversationsStore()

const selected = ref(null)
const copied = ref(false)
const scrollEl = ref(null)
const refreshed = ref(false)

const POLL_INTERVAL_MS = 5 * 60 * 1000
let pollTimer = null

const tabs = [
  { id: 'image', name: '图片' },
  { id: 'video', name: '视频' }
]

async function refreshIfUpdated() {
  const changed = await store.checkForUpdates()
  if (changed) {
    nextTick(() => {
      if (scrollEl.value) scrollEl.value.scrollTop = 0
    })
    refreshed.value = true
    setTimeout(() => (refreshed.value = false), 2500)
  }
}

function startPoll() {
  if (pollTimer) return
  pollTimer = setInterval(refreshIfUpdated, POLL_INTERVAL_MS)
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onMounted(() => {
  store.ensureLoaded()
  startPoll()
})

onActivated(() => {
  refreshIfUpdated()
  startPoll()
})

onDeactivated(stopPoll)
onUnmounted(stopPoll)

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
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 500) {
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
  convStore.setPendingPrompt(prompt)
  router.push('/')
}

function goHome() {
  router.push('/')
}
</script>

<template>
  <div class="insp-page">
    <header class="insp-topbar">
      <button class="back-btn" @click="goHome">← 返回创作</button>
      <div class="insp-heading">
        <h1>灵感画廊</h1>
        <span v-if="store.total" class="insp-count">{{ store.total }} 个灵感</span>
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
    </header>

    <Transition name="fade">
      <div v-if="refreshed" class="insp-toast">灵感画廊已更新</div>
    </Transition>

    <div ref="scrollEl" class="insp-body" @scroll="onScroll">
      <div class="insp-inner">
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
              <p class="insp-card-prompt">{{ item.prompt }}</p>
              <button class="insp-card-btn" @click.stop="usePrompt(item.prompt)">
                用此提示词生成
              </button>
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
        <p v-else-if="store.items.length" class="insp-end">已经到底啦</p>
      </div>
    </div>

    <!-- 详情面板 -->
    <Transition name="fade">
      <div v-if="selected" class="insp-detail-overlay" @click.self="selected = null">
        <div class="insp-detail">
          <button class="detail-close" @click="selected = null">✕</button>
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
</template>

<style scoped>
.insp-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  background: var(--bg);
}

.insp-topbar {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 16px 24px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.back-btn {
  padding: 8px 14px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-muted);
  font-size: 13px;
}

.back-btn:hover {
  background: rgba(255, 255, 255, 0.12);
  color: var(--text);
}

.insp-heading {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.insp-heading h1 {
  font-size: 20px;
  font-weight: 700;
  background: var(--gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
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
  padding: 6px 18px;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  font-size: 13px;
}

.insp-tab.active {
  background: var(--bg-input);
  color: var(--text);
}

.insp-toast {
  position: fixed;
  top: 76px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 1020;
  padding: 10px 20px;
  border-radius: 999px;
  background: rgba(124, 92, 255, 0.95);
  color: #fff;
  font-size: 13px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.3);
}

.insp-body {
  flex: 1;
  overflow-y: auto;
}

.insp-inner {
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px;
}

.insp-grid {
  columns: 5 220px;
  column-gap: 16px;
}

.insp-card {
  position: relative;
  break-inside: avoid;
  margin-bottom: 16px;
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid var(--border);
  background: var(--bg-input);
}

.insp-card img {
  width: 100%;
  display: block;
  transition: transform 0.25s;
}

.insp-card:hover img {
  transform: scale(1.03);
}

.insp-card-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px;
  background: linear-gradient(transparent 40%, rgba(0, 0, 0, 0.85));
  opacity: 0;
  transition: opacity 0.18s;
}

.insp-card:hover .insp-card-overlay {
  opacity: 1;
}

.insp-card-prompt {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.85);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.insp-card-btn {
  align-self: flex-start;
  padding: 7px 14px;
  border-radius: 999px;
  background: var(--gradient);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
}

.insp-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 50vh;
  color: var(--text-muted);
  text-align: center;
}

.insp-state .muted {
  font-size: 13px;
  opacity: 0.8;
}

.insp-loadmore {
  text-align: center;
  padding: 20px;
  color: var(--text-muted);
  font-size: 13px;
}

.insp-loadmore-btn {
  display: block;
  margin: 12px auto 0;
  padding: 10px 28px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--text);
  font-size: 13px;
}

.insp-loadmore-btn:hover {
  background: rgba(255, 255, 255, 0.12);
}

.insp-end {
  text-align: center;
  padding: 20px;
  color: var(--text-muted);
  font-size: 13px;
}

/* 详情 */
.insp-detail-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.72);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1010;
  padding: 24px;
}

.insp-detail {
  position: relative;
  width: 100%;
  max-width: 880px;
  max-height: 88vh;
  display: flex;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.insp-detail-img {
  width: 48%;
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
  padding: 28px;
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

.detail-close {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 2;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  font-size: 15px;
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

@media (max-width: 768px) {
  .insp-topbar {
    flex-wrap: wrap;
    gap: 12px;
    padding: 12px 16px;
  }
  .insp-tabs {
    margin-left: 0;
  }
  .insp-inner {
    padding: 16px;
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
