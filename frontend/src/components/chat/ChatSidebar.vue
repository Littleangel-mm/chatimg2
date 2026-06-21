<script setup>
import { computed } from 'vue'
import { useConversationsStore } from '../../stores/conversations'
import { useUserStore } from '../../stores/user'
import ConversationMenu from './ConversationMenu.vue'

const convStore = useConversationsStore()
const userStore = useUserStore()

const emit = defineEmits(['close-mobile'])

const items = computed(() => convStore.sortedConversations)

function newChat(type) {
  convStore.createConversation(type)
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
</script>

<template>
  <aside class="sidebar" :class="{ collapsed: !convStore.sidebarOpen }">
    <div class="sidebar-top">
      <button class="sidebar-toggle" title="收起侧边栏" @click="convStore.sidebarOpen = !convStore.sidebarOpen">
        ☰
      </button>
      <span class="brand">img2.ai</span>
    </div>

    <div class="new-actions">
      <button class="new-btn" @click="newChat('text2img')">
        <span class="icon">✨</span>
        新对话
      </button>
      <button class="new-btn secondary" @click="newChat('img2img')">
        <span class="icon">🖼</span>
        新图生图
      </button>
    </div>

    <div class="conv-list">
      <p v-if="items.length === 0" class="empty-hint">暂无对话，点击上方开始</p>
      <div
        v-for="conv in items"
        :key="conv.id"
        class="conv-item"
        :class="{ active: conv.id === convStore.activeId }"
      >
        <button type="button" class="conv-body" @click="select(conv.id)">
          <span class="conv-icon">{{ conv.type === 'img2img' ? '🖼' : '💬' }}</span>
          <span class="conv-title">{{ conv.title }}</span>
        </button>
        <ConversationMenu
          :conversation="conv"
          @rename="rename"
          @delete="remove"
        />
      </div>
    </div>

    <div class="sidebar-footer">
      <div v-if="userStore.isActivated" class="credits" @click="userStore.syncCreditsFromServer()">
        <span class="dot" />
        {{ userStore.remainingCredits }} 积分
      </div>
      <router-link to="/admin" class="admin-link">管理后台</router-link>
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
  background: rgba(255, 255, 255, 0.06);
  color: var(--text);
}

.brand {
  font-weight: 700;
  font-size: 16px;
  background: var(--gradient);
  -webkit-background-clip: text;
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
  padding: 10px 14px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid var(--border);
  color: var(--text);
  font-size: 14px;
  text-align: left;
  transition: background 0.15s;
}

.new-btn:hover {
  background: rgba(255, 255, 255, 0.1);
}

.new-btn.secondary {
  background: transparent;
}

.new-btn .icon {
  font-size: 16px;
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
  align-items: center;
  gap: 2px;
  width: 100%;
  padding: 2px 4px 2px 2px;
  margin-bottom: 2px;
  border-radius: 10px;
  transition: background 0.12s;
}

.conv-item:hover,
.conv-item.active {
  background: rgba(255, 255, 255, 0.06);
}

.conv-item.active {
  background: rgba(124, 92, 255, 0.12);
}

.conv-item:hover :deep(.menu-trigger),
.conv-item.active :deep(.menu-trigger) {
  opacity: 1;
}

.conv-body {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 4px 8px 10px;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  font-size: 14px;
  text-align: left;
}

.conv-item:hover .conv-body,
.conv-item.active .conv-body {
  color: var(--text);
}

.conv-icon {
  flex-shrink: 0;
  font-size: 14px;
}

.conv-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  color: #b49cff;
  cursor: pointer;
  background: rgba(124, 92, 255, 0.1);
}

.credits .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #7c5cff;
}

.admin-link {
  font-size: 13px;
  color: var(--text-muted);
  padding: 6px 12px;
}

.admin-link:hover {
  color: var(--text);
}
</style>
