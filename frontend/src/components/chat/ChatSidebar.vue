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

const items = computed(() => convStore.sortedConversations)

function newChat(type) {
  convStore.createConversation(type)
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
  await authStore.logout()
  userStore.logout()
  router.push('/login')
}
</script>

<template>
  <aside class="sidebar" :class="{ collapsed: !convStore.sidebarOpen }">
    <div class="sidebar-top">
      <button class="sidebar-toggle" title="&#x6536;&#x8D77;&#x4FA7;&#x8FB9;&#x680F;" @click="convStore.sidebarOpen = !convStore.sidebarOpen">
        &#9776;
      </button>
      <span class="brand">img2.ai</span>
    </div>

    <div class="new-actions">
      <button class="new-btn" @click="newChat('text2img')">&#x65B0;&#x5BF9;&#x8BDD;</button>
      <button class="new-btn secondary" @click="newChat('img2img')">&#x65B0;&#x56FE;&#x751F;&#x56FE;</button>
      <button class="new-btn gallery" @click="openInspiration">&#x7075;&#x611F;&#x753B;&#x5ECA;</button>
    </div>

    <div class="conv-list">
      <p v-if="items.length === 0" class="empty-hint">&#x6682;&#x65E0;&#x5BF9;&#x8BDD;&#xFF0C;&#x70B9;&#x51FB;&#x4E0A;&#x65B9;&#x5F00;&#x59CB;</p>
      <div v-for="conv in items" :key="conv.id" class="conv-item" :class="{ active: conv.id === convStore.activeId }">
        <button type="button" class="conv-body" @click="select(conv.id)">
          <span class="conv-title">{{ conv.title }}</span>
        </button>
        <ConversationMenu :conversation="conv" @rename="rename" @delete="remove" />
      </div>
    </div>

    <div class="sidebar-footer">
      <div v-if="userStore.isActivated" class="credits" @click="userStore.syncCreditsFromServer()">
        <span class="dot" />
        {{ userStore.remainingCredits }} &#x79EF;&#x5206;
      </div>
      <button v-if="authStore.isLoggedIn || userStore.isActivated" class="key-link" @click="openKeyModal">&#x5207;&#x6362;&#x5BC6;&#x94A5;</button>
      <button v-if="authStore.isLoggedIn || userStore.isActivated" class="logout-link" @click="logout">&#x9000;&#x51FA;&#x767B;&#x5F55;</button>
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

.new-btn.secondary {
  background: transparent;
  box-shadow: none;
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
  align-items: center;
  gap: 2px;
  width: 100%;
  padding: 2px 4px 2px 2px;
  margin-bottom: 2px;
  border-radius: 10px;
  transition: background 0.12s;
}

.conv-item:hover {
  background: var(--hover);
}

.conv-item.active {
  background: var(--accent-soft);
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