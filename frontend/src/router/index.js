import { createRouter, createWebHistory } from 'vue-router'
import { useAdminStore } from '../stores/admin'
import { useAuthStore } from '../stores/auth'
import { routeChunks } from '../utils/prefetch'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to) {
    if (to.hash) {
      return { el: to.hash, behavior: 'smooth', top: 80 }
    }
    return { top: 0 }
  },
  routes: [
    { path: '/', name: 'home', component: routeChunks.home, meta: { requiresAuth: true, keepAlive: true } },
    { path: '/login', name: 'login', component: routeChunks.login },
    { path: '/register', name: 'register', component: routeChunks.register },
    { path: '/inspiration', name: 'inspiration', component: routeChunks.inspiration, meta: { requiresAuth: true, keepAlive: true } },
    { path: '/video', name: 'video', component: routeChunks.video, meta: { requiresAuth: true, keepAlive: true } },
    { path: '/admin', name: 'admin-login', component: routeChunks['admin-login'] },
    { path: '/admin/users', name: 'admin-users', component: routeChunks['admin-users'], meta: { requiresAdmin: true, keepAlive: true } },
    { path: '/admin/keys', name: 'admin-keys', component: routeChunks['admin-keys'], meta: { requiresAdmin: true, keepAlive: true } }
  ]
})

router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  if (authStore.token && !authStore.user) {
    await authStore.fetchMe().catch(() => {})
  }

  if ((to.name === 'login' || to.name === 'register') && authStore.isLoggedIn) {
    return authStore.isAdmin ? '/admin/users' : '/'
  }

  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    return '/login'
  }

  if (to.meta.requiresAdmin) {
    if (!authStore.isLoggedIn) {
      return '/login'
    }
    if (!authStore.isAdmin) {
      return '/'
    }
    const adminStore = useAdminStore()
    adminStore.prefetchKeys(to.query.page ? Number(to.query.page) : 0)
  }
})

export default router
