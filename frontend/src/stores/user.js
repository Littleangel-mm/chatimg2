import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '../api'

const CACHE_KEY = 'userCache'
const POLL_INTERVAL_MS = 4000
const POLL_MAX_ATTEMPTS = 180

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

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
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
    const remaining = totalCredits.value - usedCredits.value
    return Number.isFinite(remaining) ? Math.max(0, remaining) : 0
  })
  const isActivated = computed(() => !!keyCode.value)
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
      return data.data
    } finally {
      activating.value = false
    }
  }

  async function fetchHistory() {
    if (!keyCode.value) return
    const { data } = await api.get('/user/history', { params: { keyCode: keyCode.value } })
    if (data.code === 200) {
      history.value = data.data || []
      persist()
    }
  }

  async function syncCreditsFromServer() {
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
    if (!keyCode.value) return
    hydrateFromCache()
    syncCreditsFromServer()
      .then(ok => {
        if (ok) fetchHistory().catch(() => {})
      })
      .catch(() => {})
  }

  async function pollRecordStatus(recordId) {
    for (let i = 0; i < POLL_MAX_ATTEMPTS; i++) {
      await sleep(POLL_INTERVAL_MS)
      const { data } = await api.get(`/user/generate/${recordId}`, {
        params: { keyCode: keyCode.value }
      })
      if (data.code !== 200) throw new Error(data.message)

      const record = data.data
      upsertHistoryRecord(record)

      if (record.status === 'completed') {
        await syncCreditsFromServer()
        return record
      }
      if (record.status === 'failed') {
        await syncCreditsFromServer()
        throw new Error(record.errorMessage || '生成失败')
      }
    }
    throw new Error('生图超时，请刷新页面查看历史')
  }

  async function generate({ prompt, type, sourceImage, model }) {
    generating.value = true
    try {
      const { data } = await api.post('/user/generate', {
        keyCode: keyCode.value,
        prompt,
        type,
        model,
        sourceImage
      })
      if (data.code !== 200) throw new Error(data.message)

      upsertHistoryRecord(data.data)
      await syncCreditsFromServer()

      if (data.data.status === 'completed') {
        return data.data
      }

      return await pollRecordStatus(data.data.id)
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

  function logout() {
    keyCode.value = ''
    keyId.value = null
    totalCredits.value = 0
    usedCredits.value = 0
    history.value = []
    localStorage.removeItem('keyCode')
    localStorage.removeItem(CACHE_KEY)
  }

  return {
    keyCode, keyId, totalCredits, usedCredits, history,
    activating, generating, refreshing, syncingCredits,
    remainingCredits, isActivated, isBusy,
    activate, fetchHistory, refreshSession, initSession,
    syncCreditsFromServer, generate, logout, hydrateFromCache
  }
})
