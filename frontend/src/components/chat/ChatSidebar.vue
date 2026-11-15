<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useConversationsStore } from '../../stores/conversations'
import { useUserStore } from '../../stores/user'
import { useAuthStore } from '../../stores/auth'
import ConversationMenu from './ConversationMenu.vue'

const router = useRouter()
const convStore = useConversationsStore()
const userStore = useUserStore()
const authStore = useAuthStore()

const emit = defineEmits(['close-mobile'])

function previewText(conv) {
  const messages = conv?.messages || []
  const latest = [...messages].reverse().find(msg => {
    return (msg?.content || '').trim() || msg?.sourceImage || msg?.record?.imagePath
  })
  if (!latest) {
    return conv?.type === 'img2img' ? '上传参考图，继续改图细节' : '描述你想生成的画面'
  }
  if ((latest.content || '').trim()) {
    return latest.content.trim()
  }
  if (latest.sourceImage) {
    return '已上传参考图'
  }
  if (latest.record?.imagePath) {
    return '生成完成'
  }
  return conv?.type === 'img2img' ? '图生图对话' : '文生图对话'
}

const items = computed(() =>
  convStore.sortedConversations.map(conv => ({
    ...conv,
    preview: previewText(conv)
  }))
)

function newChat() {
  convStore.createConversation('text2img')
  emit('close-mobile')
}

function openInspiration() {
  router.push('/inspiration')
  emit('close-mobile')
}

function select(id) {
  convStore.selectConversation(id)
  emit('close-mobile')
}

function rename(id, title) {
  convStore.renameConversation(id, title)
}

function remove(id) {
  convStore.deleteConversation(id)
}

function openKeyModal() {
  window.dispatchEvent(new CustomEvent('open-key-modal'))
  emit('close-mobile')
}

async function logout() {
  await convStore.flushPendingDeletes().catch(() => false)
  await authStore.logout()
  userStore.logout()
  router.push('/login')
}
</script>

<template>
  <aside class="sidebar" :class="{ collapsed: !convStore.sidebarOpen }">
    <div class="sidebar-top">
      <button class="sidebar-toggle" title="收起侧边栏" @click="convStore.sidebarOpen = !convStore.sidebarOpen">
        &#9776;
      </button>
      <span class="brand">img2.ai</span>
    </div>

    <div class="new-actions">
      <button class="new-btn" @click="newChat">新对话</button>
      <button class="new-btn gallery" @click="openInspiration">灵感画廊</button>
    </div>

    <div class="conv-list">
      <p v-if="items.length === 0" class="empty-hint">暂无对话，点击上方开始</p>
      <div v-for="conv in items" :key="conv.id" class="conv-item" :class="{ active: conv.id === convStore.activeId }">
        <button type="button" class="conv-body" @click="select(conv.id)">
          <span class="conv-title">{{ conv.title }}</span>
          <span class="conv-preview">{{ conv.preview }}</span>
        </button>
        <ConversationMenu :conversation="conv" @rename="rename" @delete="remove" />
      </div>
    </div>

    <div class="sidebar-footer">
      <div v-if="userStore.isActivated" class="credits" @click="userStore.syncCreditsFromServer()">
        <span class="dot" />
        {{ userStore.remainingCredits }} 积分
      </div>
      <button v-if="authStore.isLoggedIn || userStore.isActivated" class="key-link" @click="openKeyModal">切换密钥</button>
      <button v-if="authStore.isLoggedIn || userStore.isActivated" class="logout-link" @click="logout">退出登录</button>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: var(--sidebar-width);
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border);
  flex-shrink: 0;
  transition: width 0.2s, transform 0.2s;
}

.sidebar.collapsed {
  width: 0;
  overflow: hidden;
  border: none;
}

.sidebar-top {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
}

.sidebar-toggle {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  font-size: 18px;
}

.sidebar-toggle:hover {
  background: var(--hover);
  color: var(--text);
}

.brand {
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 22px;
  letter-spacing: 0.5px;
  background: var(--gradient);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.new-actions {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.new-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  border-radius: 12px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  color: var(--text);
  font-size: 14px;
  text-align: left;
  box-shadow: var(--shadow-sm);
  transition: background 0.15s, transform 0.1s, box-shadow 0.15s;
}

.new-btn:hover {
  background: var(--bg-blush);
  transform: translateY(-1px);
}

.new-btn.gallery {
  background: var(--gradient);
  border-color: transparent;
  color: #fff;
  font-weight: 500;
  box-shadow: 0 6px 18px rgba(224, 155, 176, 0.35);
}

.new-btn.gallery:hover {
  background: var(--gradient);
  opacity: 0.94;
}

.conv-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 12px;
}

.empty-hint {
  padding: 16px 12px;
  font-size: 13px;
  color: var(--text-muted);
  text-align: center;
}

.conv-item {
  display: flex;
  align-items: stretch;
  gap: 6px;
  width: 100%;
  padding: 4px;
  margin-bottom: 4px;
  border-radius: 14px;
  border: 1px solid transparent;
  transition: background 0.15s, border-color 0.15s, box-shadow 0.15s;
}

.conv-item:hover {
  background: rgba(255, 255, 255, 0.48);
  border-color: rgba(224, 155, 176, 0.2);
}

.conv-item.active {
  background: rgba(255, 244, 242, 0.96);
  border-color: rgba(224, 155, 176, 0.28);
  box-shadow: 0 8px 20px rgba(224, 155, 176, 0.1);
}

.conv-item:hover :deep(.menu-trigger),
.conv-item.active :deep(.menu-trigger) {
  opacity: 1;
}

.conv-item :deep(.conv-menu) {
  display: flex;
  align-items: center;
}

.conv-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  padding: 10px 8px 10px 10px;
  border-radius: 10px;
  background: transparent;
  color: var(--text-muted);
  font-size: 14px;
  text-align: left;
}

.conv-item:hover .conv-body,
.conv-item.active .conv-body {
  color: var(--text);
}

.conv-title {
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text);
  font-size: 14px;
  line-height: 1.35;
}

.conv-preview {
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  line-height: 1.4;
  color: var(--text-muted);
  opacity: 0.9;
}

.sidebar-footer {
  padding: 12px;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.credits {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 500;
  color: var(--accent-hover);
  cursor: pointer;
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
}

.credits .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
}

.key-link,
.logout-link {
  font-size: 13px;
  color: var(--text-muted);
  padding: 6px 12px;
  text-align: left;
  background: transparent;
}

.key-link:hover,
.logout-link:hover {
  color: var(--text);
}
</style>
