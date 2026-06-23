<script setup>
import { ref, onMounted } from 'vue'
import { defineOptions } from 'vue'
import { useUserStore } from '../stores/user'
import { useConversationsStore } from '../stores/conversations'
import ChatSidebar from '../components/chat/ChatSidebar.vue'
import ChatMain from '../components/chat/ChatMain.vue'
import KeyModal from '../components/KeyModal.vue'
import InspirationModal from '../components/InspirationModal.vue'

defineOptions({ name: 'home' })

const userStore = useUserStore()
const convStore = useConversationsStore()
const mobileSidebar = ref(false)

onMounted(() => {
  if (userStore.keyCode) {
    convStore.bindKeyCode(userStore.keyCode)
  }
})
</script>

<template>
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
    <InspirationModal />
  </div>
</template>

<style scoped>
.chat-layout {
  display: flex;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: var(--bg);
}

.sidebar-wrap {
  flex-shrink: 0;
  height: 100%;
}

.sidebar-overlay {
  display: none;
}

@media (max-width: 768px) {
  .sidebar-wrap {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 200;
    transform: translateX(-100%);
    transition: transform 0.2s;
    box-shadow: 4px 0 24px rgba(0, 0, 0, 0.4);
  }

  .sidebar-wrap.open {
    transform: translateX(0);
  }

  .sidebar-overlay {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    z-index: 199;
  }
}
</style>
