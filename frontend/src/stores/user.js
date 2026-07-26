import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api, { generateApi } from '../api'
import { useConversationsStore } from './conversations'
import { useAuthStore } from './auth'
import { resolveGenerationStatus } from '../utils/generation'

const CACHE_KEY = 'userCache'
let historyFetchPromise = null
let historyFetchOwner = null

function readCache() {
  try {
    return JSON.parse(localStorage.getItem(CACHE_KEY) || 'null')
  } catch {
    return null
  }
}

function writeCache(data) {
  localStorage.setItem(CACHE_KEY, JSON.stringify({ ...data, updatedAt: Date.now() }))
}

function isInvalidKeyError(e) {
  const status = e.response?.status
  const msg = e.response?.data?.message || e.message || ''
  return status === 401 || msg.includes('无效') || msg.includes('禁用') || msg.includes('不存在')
}

export const useUserStore = defineStore('user', () => {
  const cached = readCache()
  const keyCode = ref(localStorage.getItem('keyCode') || '')
  const keyId = ref(cached?.keyId ?? null)
  const totalCredits = ref(cached?.totalCredits ?? 0)
  const usedCredits = ref(cached?.usedCredits ?? 0)
  const history = ref(cached?.history ?? [])
  const activating = ref(false)
  const generating = ref(false)
  const refreshing = ref(false)
  const syncingCredits = ref(false)

  const remainingCredits = computed(() => {
    const authStore = useAuthStore()
    if (authStore.isUser && authStore.user?.remainingCredits != null) {
      return authStore.user.remainingCredits
    }
    const remaining = totalCredits.value - usedCredits.value
    return Number.isFinite(remaining) ? Math.max(0, remaining) : 0
  })
  const isActivated = computed(() => {
    const authStore = useAuthStore()
    return !!keyCode.value || authStore.isUser
  })
  const isBusy = computed(() => activating.value || generating.value)

  function persist() {
    if (!keyCode.value) return
    writeCache({
      keyId: keyId.value,
      totalCredits: totalCredits.value,
      usedCredits: usedCredits.value,
      history: history.value
    })
  }

  function hydrateFromCache() {
    const cache = readCache()
    if (!cache || !keyCode.value) return false
    keyId.value = cache.keyId
    totalCredits.value = cache.totalCredits ?? 0
    usedCredits.value = cache.usedCredits ?? 0
    history.value = cache.history ?? []
    return true
  }

  function applyActivationData(data) {
    if (!data) return
    keyId.value = data.keyId ?? keyId.value
    if (data.totalCredits != null) totalCredits.value = data.totalCredits
    if (data.usedCredits != null) usedCredits.value = data.usedCredits
    if (data.remainingCredits != null && data.totalCredits == null) {
      totalCredits.value = usedCredits.value + data.remainingCredits
    }
    persist()
  }

  function upsertHistoryRecord(record) {
    const idx = history.value.findIndex(item => item.id === record.id)
    if (idx >= 0) {
      history.value[idx] = record
    } else {
      history.value.unshift(record)
    }
    persist()
    try {
      useConversationsStore().syncRecord(record)
    } catch {
      /* store may not be ready during init */
    }
  }

  async function activate(code) {
    const normalized = code.trim().toUpperCase()
    activating.value = true
    try {
      const { data } = await api.post('/user/activate', { keyCode: normalized })
      if (data.code !== 200) throw new Error(data.message)
      keyCode.value = normalized
      localStorage.setItem('keyCode', normalized)
      applyActivationData(data.data)
      useConversationsStore().bindKeyCode(normalized)
      return data.data
    } finally {
      activating.value = false
    }
  }

  async function fetchHistory() {
    const owner = historyOwner()
    if (!owner) return
    if (historyFetchPromise && historyFetchOwner === owner) return historyFetchPromise
    const request = loadHistory(owner)
    historyFetchPromise = request
    historyFetchOwner = owner
    try {
      return await request
    } finally {
      if (historyFetchPromise === request) {
        historyFetchPromise = null
        historyFetchOwner = null
      }
    }
  }

  function historyOwner() {
    const authStore = useAuthStore()
    if (authStore.isUser) return `user:${authStore.user?.id || authStore.user?.email}`
    return keyCode.value ? `key:${keyCode.value}` : ''
  }

  async function loadHistory(expectedOwner) {
    const authStore = useAuthStore()
    const params = keyCode.value ? { keyCode: keyCode.value } : undefined
    const { data } = await api.get('/user/history', { params })
    if (data.code === 200 && historyOwner() === expectedOwner) {
      history.value = data.data || []
      persist()
      try {
        const convStore = useConversationsStore()
        const ownerKey = authStore.user?.email || keyCode.value
        convStore.bindKeyCode(ownerKey)
        await convStore.loadRemote().catch(() => false)
        convStore.importFromHistory(history.value, ownerKey)
        convStore.syncFromHistoryRecords(history.value)
      } catch {
        /* ignore */
      }
    }
  }

  async function syncCreditsFromServer() {
    const authStore = useAuthStore()
    if (authStore.isUser) {
      if (keyCode.value) {
        await activate(keyCode.value).catch(() => {})
      }
      await authStore.fetchMe().catch(() => {})
      return true
    }
    if (!keyCode.value) return false
    syncingCredits.value = true
    try {
      const { data } = await api.post('/user/activate', { keyCode: keyCode.value.trim().toUpperCase() })
      if (data.code !== 200) throw new Error(data.message)
      applyActivationData(data.data)
      return true
    } catch (e) {
      if (isInvalidKeyError(e)) {
        logout()
      }
      return false
    } finally {
      syncingCredits.value = false
    }
  }

  async function refreshSession() {
    const authStore = useAuthStore()
    if (authStore.isUser) {
      if (keyCode.value) {
        await activate(keyCode.value)
      }
      await authStore.fetchMe()
      await fetchHistory()
      return
    }
    if (!keyCode.value) return
    refreshing.value = true
    try {
      await activate(keyCode.value)
      await fetchHistory()
    } catch (e) {
      if (isInvalidKeyError(e)) {
        logout()
        throw new Error('密钥无效或已禁用，请重新激活')
      }
      throw e
    } finally {
      refreshing.value = false
    }
  }

  function initSession() {
    const authStore = useAuthStore()
    if (authStore.isUser) {
      syncCreditsFromServer()
        .then(() => fetchHistory())
        .catch(() => fetchHistory().catch(() => {}))
      return
    }
    if (!keyCode.value) return
    hydrateFromCache()
    try {
      useConversationsStore().bindKeyCode(authStore.user?.email || keyCode.value)
    } catch {
      /* ignore */
    }
    syncCreditsFromServer()
      .then(ok => {
        if (ok) {
          fetchHistory().catch(() => {})
        }
      })
      .catch(() => {})
  }

  async function generate({ prompt, type, sourceImage, clientMessageId }) {
    generating.value = true
    try {
      const { data } = await generateApi.post('/user/generate', {
        keyCode: keyCode.value,
        prompt,
        type,
        sourceImage,
        clientMessageId
      })
      if (data.code !== 200) throw new Error(data.message)

      upsertHistoryRecord(data.data)
      applyLocalGenerationCharge(data.data)
      generating.value = false
      await syncCreditsFromServer()

      return data.data
    } catch (e) {
      await Promise.all([
        syncCreditsFromServer().catch(() => {}),
        fetchHistory().catch(() => {})
      ])
      throw e
    } finally {
      generating.value = false
    }
  }

  function applyLocalGenerationCharge(record) {
    const cost = Number(record?.creditsCost || 20)
    if (!Number.isFinite(cost) || cost <= 0 || resolveGenerationStatus(record) !== 'processing') {
      return
    }
    const authStore = useAuthStore()
    if (authStore.isUser && authStore.user) {
      const used = Number(authStore.user.usedCredits || 0) + cost
      const total = Number(authStore.user.totalCredits || 0)
      authStore.user = {
        ...authStore.user,
        usedCredits: used,
        remainingCredits: Math.max(0, total - used)
      }
      localStorage.setItem('authUser', JSON.stringify(authStore.user))
      return
    }
    usedCredits.value += cost
    persist()
  }

  function logout() {
    keyCode.value = ''
    keyId.value = null
    totalCredits.value = 0
    usedCredits.value = 0
    history.value = []
    localStorage.removeItem('keyCode')
    localStorage.removeItem(CACHE_KEY)
    try {
      useConversationsStore().resetSession()
    } catch {
      /* ignore */
    }
  }

  return {
    keyCode, keyId, totalCredits, usedCredits, history,
    activating, generating, refreshing, syncingCredits,
    remainingCredits, isActivated, isBusy,
    activate, fetchHistory, refreshSession, initSession,
    syncCreditsFromServer, generate, logout, hydrateFromCache
  }
})
