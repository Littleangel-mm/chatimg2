import { createRouter, createWebHistory } from 'vue-router'
import { useAdminStore } from '../stores/admin'
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
    {
      path: '/',
      name: 'home',
      component: routeChunks.home,
      meta: { keepAlive: true }
    },
    {
      path: '/admin',
      name: 'admin-login',
      component: routeChunks['admin-login']
    },
    {
      path: '/admin/keys',
      name: 'admin-keys',
      component: routeChunks['admin-keys'],
      meta: { requiresAdmin: true, keepAlive: true }
    }
  ]
})

router.beforeEach(async (to) => {
  if (to.meta.requiresAdmin) {
    const adminStore = useAdminStore()
    if (!adminStore.isLoggedIn()) {
      return '/admin'
    }
    adminStore.prefetchKeys(to.query.page ? Number(to.query.page) : 0)
  }
})

export default router
