import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from './stores/user'
import AppHeader from './components/AppHeader.vue'
import { prefetchOnIdle, prefetchRoute } from './utils/prefetch'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const navigating = ref(false)

const showHeader = computed(() => route.path.startsWith('/admin'))

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
  <div class="app-root" :class="{ 'chat-mode': !showHeader }">
    <div class="nav-bar" :class="{ active: navigating }" />
    <AppHeader v-if="showHeader" />
    <router-view v-slot="{ Component, route: r }">
      <keep-alive :include="['home', 'inspiration', 'admin-users', 'admin-keys']">
        <component v-if="Component" :is="Component" :key="r.name" class="page-view" />
      </keep-alive>
      <div v-if="!Component" class="page-loading">加载中...</div>
    </router-view>
  </div>
</template>
