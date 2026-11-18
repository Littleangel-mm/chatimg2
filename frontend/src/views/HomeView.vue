<script setup>
import { ref, onMounted } from 'vue'
import { defineOptions } from 'vue'
import { useUserStore } from '../stores/user'
import { useConversationsStore } from '../stores/conversations'
import ChatSidebar from '../components/chat/ChatSidebar.vue'
import ChatMain from '../components/chat/ChatMain.vue'
import KeyModal from '../components/KeyModal.vue'

defineOptions({ name: 'home' })

const userStore = useUserStore()
const convStore = useConversationsStore()
const mobileSidebar = ref(false)

onMounted(() => {
  if (userStore.keyCode) {
    convStore.bindKeyCode(userStore.keyCode)
  }
  convStore.activateGenerationArea('image')
})
</script>

<template>
  <div class="home-page">
  <div class="chat-layout">
    <div
      v-if="mobileSidebar"
      class="sidebar-overlay"
      @click="mobileSidebar = false"
    />

    <div class="sidebar-wrap" :class="{ open: mobileSidebar || convStore.sidebarOpen }">
      <ChatSidebar @close-mobile="mobileSidebar = false" />
    </div>

    <ChatMain @open-sidebar="mobileSidebar = true" />
    <KeyModal />
  </div>
  <footer
    class="site-filing"
    :class="{ 'site-filing--sidebar-open': convStore.sidebarOpen }"
    aria-label="网站备案信息"
  >
    <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">
      蜀ICP备2023012180号-3
    </a>
    <a
      href="https://beian.mps.gov.cn/#/query/webSearch?code=51152802000149"
      target="_blank"
      rel="noopener noreferrer"
    >
      川公网安备51152802000149号
    </a>
  </footer>
  </div>
</template>

<style scoped>
.home-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: var(--bg);
}

.chat-layout {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.site-filing {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 4px 24px;
  flex-shrink: 0;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--border);
  background: var(--bg-input);
  color: var(--text-strong);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.6;
}

.site-filing a {
  white-space: nowrap;
}

.site-filing a:hover {
  color: var(--accent-hover);
  text-decoration: underline;
}

.sidebar-wrap {
  flex-shrink: 0;
  height: 100%;
}

.sidebar-overlay {
  display: none;
}

@media (min-width: 769px) {
  .site-filing--sidebar-open {
    padding-left: calc(var(--sidebar-width) + 16px);
  }
}

@media (max-width: 768px) {
  .site-filing {
    column-gap: 16px;
    padding-top: 8px;
    padding-bottom: calc(8px + env(safe-area-inset-bottom, 0px));
    font-size: 11px;
  }

  .sidebar-wrap {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 200;
    transform: translateX(-100%);
    transition: transform 0.2s;
    box-shadow: 4px 0 30px rgba(176, 120, 120, 0.25);
  }

  .sidebar-wrap.open {
    transform: translateX(0);
  }

  .sidebar-overlay {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(70, 45, 50, 0.32);
    z-index: 199;
  }
}
</style>
