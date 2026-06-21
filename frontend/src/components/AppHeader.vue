<script setup>
import { useUserStore } from '../stores/user'
import { prefetchRoute } from '../utils/prefetch'

const userStore = useUserStore()

function openKeyModal() {
  window.dispatchEvent(new CustomEvent('open-key-modal'))
}

function scrollTo(hash) {
  const el = document.querySelector(hash)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function onAdminEnter() {
  prefetchRoute('admin-login')
  prefetchRoute('admin-keys')
}
</script>

<template>
  <header class="header">
    <div class="container header-inner">
      <router-link to="/" class="logo tap">
        <span class="logo-icon">✦</span>
        <span class="logo-text">img2.ai</span>
      </router-link>

      <nav class="nav">
        <router-link to="/" class="nav-link tap" active-class="active" exact-active-class="active">
          Home
        </router-link>
        <a href="#generator" class="nav-link tap" @click.prevent="scrollTo('#generator')">Generator</a>
        <a href="#gallery" class="nav-link tap" @click.prevent="scrollTo('#gallery')">Gallery</a>
        <router-link
          to="/admin"
          class="nav-link tap admin-link"
          active-class="active"
          @mouseenter="onAdminEnter"
          @focus="onAdminEnter"
        >
          Admin
        </router-link>
      </nav>

      <div class="header-actions">
        <template v-if="userStore.isActivated">
          <button
            class="credits-badge tap"
            :class="{ syncing: userStore.syncingCredits || userStore.refreshing }"
            title="点击刷新积分"
            @click="userStore.syncCreditsFromServer()"
          >
            <span class="credits-dot"></span>
            {{ userStore.remainingCredits }} 积分
          </button>
        </template>
        <button class="btn-ghost tap" @click="openKeyModal">
          {{ userStore.isActivated ? '切换密钥' : '激活密钥' }}
        </button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.header {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(8, 8, 12, 0.85);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--border);
}

.header-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  font-size: 20px;
}

.logo-icon {
  background: var(--gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  font-size: 22px;
}

.nav {
  display: flex;
  gap: 4px;
}

.nav-link {
  padding: 8px 14px;
  border-radius: 8px;
  font-size: 14px;
  color: var(--text-muted);
  transition: color 0.12s, background 0.12s, transform 0.1s;
}

.nav-link:hover,
.nav-link.active {
  color: var(--text);
  background: rgba(255,255,255,0.05);
}

.admin-link { color: #b49cff; }

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.credits-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  background: rgba(124, 92, 255, 0.15);
  border: 1px solid rgba(124, 92, 255, 0.3);
  border-radius: 999px;
  font-size: 13px;
  font-weight: 500;
  color: #b49cff;
  cursor: pointer;
  border-width: 1px;
  font-family: inherit;
}

.credits-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #7c5cff;
}

.credits-badge.syncing { opacity: 0.7; }

@media (max-width: 768px) {
  .nav { display: none; }
}
</style>
