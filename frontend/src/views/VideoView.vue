<script setup>
import { onMounted, ref } from 'vue'
import { defineOptions } from 'vue'
import { useConversationsStore } from '../stores/conversations'
import ChatSidebar from '../components/chat/ChatSidebar.vue'
import VideoMain from '../components/video/VideoMain.vue'
import KeyModal from '../components/KeyModal.vue'

defineOptions({ name: 'video' })

const convStore = useConversationsStore()
const mobileSidebar = ref(false)

onMounted(() => {
  if (window.matchMedia('(max-width: 768px)').matches) {
    convStore.sidebarOpen = false
  }
})
</script>

<template>
  <div class="video-layout">
    <div v-if="mobileSidebar" class="sidebar-overlay" @click="mobileSidebar = false" />
    <div class="sidebar-wrap" :class="{ open: mobileSidebar || convStore.sidebarOpen }">
      <ChatSidebar @close-mobile="mobileSidebar = false" />
    </div>
    <VideoMain @open-sidebar="mobileSidebar = true" />
    <KeyModal />
  </div>
</template>

<style scoped>
.video-layout {
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
    inset: 0 auto 0 0;
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
    z-index: 199;
    background: rgba(70, 45, 50, 0.32);
  }
}
</style>
