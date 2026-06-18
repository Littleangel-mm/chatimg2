import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api, { generateApi } from '../api'

const CACHE_KEY = 'userCache'

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

  const remainingCredits = computed(() => totalCredits.value - usedCredits.value)
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

  async function activate(code) {
    activating.value = true
    try {
      const { data } = await api.post('/user/activate', { keyCode: code })
      if (data.code !== 200) throw new Error(data.message)
      keyCode.value = code
      keyId.value = data.data.keyId
      totalCredits.value = data.data.totalCredits
      usedCredits.value = data.data.usedCredits
      localStorage.setItem('keyCode', code)
      persist()
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

  async function refreshSession() {
    if (!keyCode.value) return
    refreshing.value = true
    try {
      await Promise.all([activate(keyCode.value), fetchHistory()])
    } catch {
      logout()
      throw new Error('会话已失效')
    } finally {
      refreshing.value = false
    }
  }

  function initSession() {
    if (!keyCode.value) return
    hydrateFromCache()
    const cache = readCache()
    const stale = !cache || Date.now() - (cache.updatedAt || 0) > 30_000
    if (stale) {
      refreshSession().catch(() => {})
    }
  }

  async function generate({ prompt, type, sourceImage, model }) {
    generating.value = true
    try {
      const { data } = await generateApi.post('/user/generate', {
        keyCode: keyCode.value,
        prompt,
        type,
        model,
        sourceImage
      })
      if (data.code !== 200) throw new Error(data.message)
      history.value.unshift(data.data)
      usedCredits.value += data.data.creditsCost || 20
      persist()
      return data.data
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
    activating, generating, refreshing,
    remainingCredits, isActivated, isBusy,
    activate, fetchHistory, refreshSession, initSession, generate, logout, hydrateFromCache
  }
})
