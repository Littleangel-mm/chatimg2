<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from './stores/user'
import AppHeader from './components/AppHeader.vue'
import { prefetchOnIdle, prefetchRoute } from './utils/prefetch'

const router = useRouter()
const userStore = useUserStore()
const navigating = ref(false)

let removeBefore = null
let removeAfter = null

function onWindowFocus() {
  if (userStore.isActivated) {
    userStore.syncCreditsFromServer()
  }
}

onMounted(() => {
  userStore.initSession()
  prefetchOnIdle()
  window.addEventListener('focus', onWindowFocus)

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
  removeBefore?.()
  removeAfter?.()
})
</script>

<template>
  <div class="app-root">
    <div class="nav-bar" :class="{ active: navigating }" />
    <AppHeader />
    <router-view v-slot="{ Component, route }">
      <keep-alive :include="['home', 'admin-keys']">
        <component v-if="Component" :is="Component" :key="route.name" class="page-view" />
      </keep-alive>
      <div v-if="!Component" class="page-loading">加载中...</div>
    </router-view>
  </div>
</template>

<style scoped>
.app-root {
  min-height: 100vh;
}

.page-view {
  animation: page-in 0.15s ease;
}

@keyframes page-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.nav-bar {
  position: fixed;
  top: 0;
  left: 0;
  height: 2px;
  width: 0;
  background: var(--gradient);
  z-index: 9999;
  transition: width 0.15s ease;
  pointer-events: none;
}

.nav-bar.active {
  width: 70%;
  transition: width 0.4s ease;
}

.page-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - 64px);
  color: var(--text-muted);
  font-size: 14px;
}
</style>
