<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from './stores/user'
import { useConversationsStore } from './stores/conversations'
import { useVideosStore } from './stores/videos'
import AppHeader from './components/AppHeader.vue'
import { prefetchOnIdle, prefetchRoute } from './utils/prefetch'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const conversationsStore = useConversationsStore()
const videosStore = useVideosStore()
const navigating = ref(false)

const showHeader = computed(() => route.path.startsWith('/admin'))

let removeBefore = null
let removeAfter = null
let creditTimer = null
let sharedStateTimer = null
let sharedStateRefreshing = false

async function refreshSharedState() {
  const isGenerationRoute = route.name === 'home' || route.name === 'video'
  if (sharedStateRefreshing || document.hidden || !isGenerationRoute || !userStore.isActivated) return
  sharedStateRefreshing = true
  try {
    if (route.name === 'video') {
      await videosStore.refreshProcessing()
      conversationsStore.syncVideoTasks(videosStore.tasks)
    }
    await conversationsStore.loadRemote()
  } catch {
    /* The next interval or focus event retries transient failures. */
  } finally {
    sharedStateRefreshing = false
  }
}

function onWindowFocus() {
  if (userStore.isActivated) {
    userStore.syncCreditsFromServer()
    refreshSharedState()
  }
}

function onVisibilityChange() {
  if (!document.hidden) refreshSharedState()
}

onMounted(() => {
  userStore.initSession()
  prefetchOnIdle()
  window.addEventListener('focus', onWindowFocus)
  window.addEventListener('online', refreshSharedState)
  document.addEventListener('visibilitychange', onVisibilityChange)
  creditTimer = window.setInterval(() => {
    if (userStore.isActivated) {
      userStore.syncCreditsFromServer()
    }
  }, 30000)
  sharedStateTimer = window.setInterval(refreshSharedState, 4000)

  removeBefore = router.beforeEach((to, from, next) => {
    if (to.path !== from.path) navigating.value = true
    prefetchRoute(to.name)
    next()
  })
  removeAfter = router.afterEach(() => {
    navigating.value = false
  })
})

onUnmounted(() => {
  window.removeEventListener('focus', onWindowFocus)
  window.removeEventListener('online', refreshSharedState)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (creditTimer) window.clearInterval(creditTimer)
  if (sharedStateTimer) window.clearInterval(sharedStateTimer)
  removeBefore?.()
  removeAfter?.()
})
</script>

<template>
  <div class="app-root" :class="{ 'chat-mode': !showHeader }">
    <div class="nav-bar" :class="{ active: navigating }" />
    <AppHeader v-if="showHeader" />
    <router-view v-slot="{ Component, route: r }">
      <keep-alive :include="['home', 'inspiration', 'video', 'admin-users', 'admin-keys']">
        <component v-if="Component" :is="Component" :key="r.name" class="page-view" />
      </keep-alive>
      <div v-if="!Component" class="page-loading">加载中...</div>
    </router-view>
  </div>
</template>
