const prefetched = new Set()

export const routeChunks = {
  home: () => import('../views/HomeView.vue'),
  inspiration: () => import('../views/InspirationView.vue'),
  'admin-login': () => import('../views/admin/AdminLogin.vue'),
  'admin-keys': () => import('../views/admin/AdminKeys.vue')
}

export function prefetchRoute(name) {
  if (prefetched.has(name)) return
  const loader = routeChunks[name]
  if (loader) {
    prefetched.add(name)
    loader()
  }
}

export function prefetchAllRoutes() {
  Object.keys(routeChunks).forEach(prefetchRoute)
}

export function prefetchOnIdle() {
  const run = () => prefetchAllRoutes()
  if (typeof requestIdleCallback === 'function') {
    requestIdleCallback(run, { timeout: 2000 })
  } else {
    setTimeout(run, 300)
  }
}
