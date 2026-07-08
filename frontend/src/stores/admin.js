import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '../api'
import { useAuthStore } from './auth'

const CACHE_TTL = 30_000

export const useAdminStore = defineStore('admin', () => {
  const admin = ref(JSON.parse(localStorage.getItem('admin') || 'null'))
  const keys = ref([])
  const users = ref([])
  const loading = ref(false)
  const tableLoading = ref(false)
  const userLoading = ref(false)
  const page = ref(0)
  const size = ref(10)
  const total = ref(0)
  const totalPages = ref(0)
  const pageCache = ref({})

  const isLoggedIn = () => {
    const authStore = useAuthStore()
    return authStore.isAdmin || !!admin.value
  }

  function applyPage(data, p) {
    keys.value = data.list
    total.value = data.total
    page.value = data.page ?? p
    totalPages.value = data.totalPages
  }

  function getCached(p) {
    const cached = pageCache.value[p]
    if (!cached) return null
    if (Date.now() - cached.fetchedAt > CACHE_TTL) return null
    return cached
  }

  async function fetchKeys(p = page.value, { background = false } = {}) {
    const cached = pageCache.value[p]
    if (cached) applyPage(cached, p)

    if (background && cached && Date.now() - cached.fetchedAt < CACHE_TTL) {
      return cached
    }

    tableLoading.value = true
    try {
      const { data } = await api.get('/admin/keys', { params: { page: p, size: size.value } })
      if (data.code !== 200) throw new Error(data.message)
      const payload = { ...data.data, fetchedAt: Date.now() }
      pageCache.value[p] = payload
      applyPage(payload, p)
      return payload
    } finally {
      tableLoading.value = false
    }
  }

  async function fetchUsers() {
    userLoading.value = true
    try {
      const { data } = await api.get('/admin/users')
      if (data.code !== 200) throw new Error(data.message)
      users.value = data.data || []
      return users.value
    } finally {
      userLoading.value = false
    }
  }

  function prefetchKeys(p = 0) {
    if (getCached(p)) return Promise.resolve(getCached(p))
    return fetchKeys(p, { background: true }).catch(() => {})
  }

  function invalidateCache() {
    pageCache.value = {}
  }

  async function login(username, password) {
    loading.value = true
    try {
      const authStore = useAuthStore()
      const session = await authStore.login({ account: username, password })
      if (session.role !== 'ADMIN') throw new Error('无管理员权限')
      admin.value = session
      localStorage.setItem('admin', JSON.stringify(session))
      invalidateCache()
      prefetchKeys(0)
      return session
    } finally {
      loading.value = false
    }
  }

  function logout() {
    const authStore = useAuthStore()
    authStore.logout()
    admin.value = null
    localStorage.removeItem('admin')
    keys.value = []
    users.value = []
    invalidateCache()
  }

  async function createKey(totalCredits = 100) {
    const { data } = await api.post('/admin/keys', { totalCredits })
    if (data.code !== 200) throw new Error(data.message)
    invalidateCache()
    await fetchKeys(0)
    return data.data
  }

  async function updateKey(id, totalCredits) {
    if (!Number.isFinite(totalCredits) || totalCredits < 0) {
      throw new Error('请填写有效积分')
    }
    const { data } = await api.put(`/admin/keys/${id}`, { totalCredits })
    if (data.code !== 200) throw new Error(data.message)
    invalidateCache()
    await fetchKeys(page.value)
    return data.data
  }

  async function resetUsedCredits(id) {
    const { data } = await api.post(`/admin/keys/${id}/reset-used`)
    if (data.code !== 200) throw new Error(data.message)
    invalidateCache()
    await fetchKeys(page.value)
    return data.data
  }

  async function deleteKey(id) {
    const { data } = await api.delete(`/admin/keys/${id}`)
    if (data.code !== 200) throw new Error(data.message)
    invalidateCache()
    const nextPage = keys.value.length === 1 && page.value > 0 ? page.value - 1 : page.value
    await fetchKeys(nextPage)
  }

  async function resetPassword(userId) {
    const { data } = await api.post(`/admin/users/${userId}/reset-password`)
    if (data.code !== 200) throw new Error(data.message)
    await fetchUsers()
    return data.data
  }

  async function crawlInspiration(mediaType) {
    const { data } = await api.post('/admin/inspiration/crawl', null, {
      params: mediaType ? { mediaType } : {}
    })
    if (data.code !== 200) throw new Error(data.message)
    return data.data
  }

  async function inspirationStatus() {
    const { data } = await api.get('/admin/inspiration/status')
    if (data.code !== 200) throw new Error(data.message)
    return data.data
  }

  return {
    admin, keys, users, loading, tableLoading, userLoading, page, size, total, totalPages,
    isLoggedIn, login, logout, fetchKeys, fetchUsers, prefetchKeys, createKey, updateKey, deleteKey, resetUsedCredits,
    resetPassword, crawlInspiration, inspirationStatus
  }
})
