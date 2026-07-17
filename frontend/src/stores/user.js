import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api, { generateApi } from '../api'
import { useConversationsStore } from './conversations'
import { useAuthStore } from './auth'

const CACHE_KEY = 'userCache'
const POLL_INTERVAL_MS = 4000
const POLL_MAX_ATTEMPTS = 900
const RECOVERY_INTERVAL_MS = 5000
const RECOVERY_MAX_ATTEMPTS = 36
const POLICY_ADVICE_PREFIX = 'CONTENT_POLICY_ADVICE:'
const LEGACY_POLICY_ADVICE_PREFIX = '内容安全提示：'
const RECOVERY_INTERRUPTED_MARKERS = ['生成任务中断', '已退回积分']
const activePolls = new Set()

function normalizeStatus(status) {
  return (status || '').toLowerCase()
}

function resolveRecordStatus(record) {
  const status = normalizeStatus(record?.status)
  if (record?.imagePath) return 'completed'
  if (status === 'failed' && isRecoveryInterruptedRecord(record)) return 'processing'
  if (status === 'completed' || status === 'failed') return status
  return status || 'processing'
}

function isRecoveryInterruptedRecord(record) {
  const message = record?.errorMessage || ''
  return RECOVERY_INTERRUPTED_MARKERS.every(marker => message.includes(marker))
}

function friendlyGenerationError(message, record = null) {
  const text = message || ''
  const policyAdvice = extractPolicyAdvice(text)
  if (policyAdvice) {
    return `${POLICY_ADVICE_PREFIX}${policyAdvice}`
  }
  const rawApiError = /API|503|model_not_found|No available channel|request id|distributor|GPT-Pro/i.test(text)
  if (record?.generationType === 'img2img' || rawApiError) {
    return '生成失败，请更换图片重试'
  }
  return text || '生成失败'
}

function extractPolicyAdvice(text) {
  if (!text) return ''
  const markerIndex = text.indexOf(POLICY_ADVICE_PREFIX)
  if (markerIndex >= 0) {
    return text.slice(markerIndex + POLICY_ADVICE_PREFIX.length).trim()
  }
  const legacyIndex = text.indexOf(LEGACY_POLICY_ADVICE_PREFIX)
  if (legacyIndex >= 0) {
    return text.slice(legacyIndex + LEGACY_POLICY_ADVICE_PREFIX.length).trim()
  }
  return ''
}

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

function isTransientPollError(error) {
  const status = error?.response?.status
  if (!status) return true
  return status === 408 || status === 429 || status >= 500
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
    const authStore = useAuthStore()
    if (!keyCode.value && !authStore.isUser) return
    const params = keyCode.value ? { keyCode: keyCode.value } : undefined
    const { data } = await api.get('/user/history', { params })
    if (data.code === 200) {
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
      resumePendingGenerations().catch(() => {})
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
          fetchHistory()
            .then(() => resumePendingGenerations())
            .catch(() => {})
        }
      })
      .catch(() => {})
  }

  function collectPendingRecordIds() {
    const ids = new Set()
    try {
      const convStore = useConversationsStore()
      for (const conv of convStore.conversations) {
        for (const msg of conv.messages) {
          if (msg.role !== 'assistant') continue
          const status = resolveRecordStatus(msg.record || { status: msg.status })
          if (status !== 'processing') continue
          const id = msg.recordId || msg.record?.id
          if (id != null) ids.add(Number(id))
        }
      }
    } catch {
      /* store may not be ready */
    }
    return [...ids]
  }

  async function pollRecordStatus(recordId) {
    const normalizedId = Number(recordId)
    if (activePolls.has(normalizedId)) {
      for (let i = 0; i < POLL_MAX_ATTEMPTS; i++) {
        await sleep(POLL_INTERVAL_MS)
        const cached = history.value.find(item => Number(item.id) === normalizedId)
        const status = resolveRecordStatus(cached)
        if (status === 'completed') return cached
        if (status === 'failed') throw new Error(cached?.errorMessage || '生成失败')
      }
      throw new Error('生图超时，请刷新页面查看历史')
    }

    activePolls.add(normalizedId)
    try {
      let lastTransientError = null
      for (let i = 0; i < POLL_MAX_ATTEMPTS; i++) {
        await sleep(POLL_INTERVAL_MS)
        let data
        try {
          const params = keyCode.value ? { keyCode: keyCode.value } : undefined
          const response = await api.get(`/user/generate/${normalizedId}`, { params })
          data = response.data
          if (data.code !== 200) throw new Error(data.message)
          lastTransientError = null
        } catch (error) {
          if (isTransientPollError(error)) {
            lastTransientError = error
            continue
          }
          throw error
        }

        const record = data.data
        upsertHistoryRecord(record)

        const status = resolveRecordStatus(record)
        if (status === 'completed') {
          await syncCreditsFromServer()
          return record
        }
        if (status === 'failed') {
          await syncCreditsFromServer()
          try {
            useConversationsStore().failAssistantForRecord(normalizedId, record.errorMessage || '生成失败')
          } catch {
            /* ignore */
          }
          throw new Error(record.errorMessage || '生成失败')
        }
      }
      throw new Error('生图超时，请刷新页面查看历史')
    } finally {
      activePolls.delete(normalizedId)
    }
  }

  async function resumePendingGenerations() {
    const authStore = useAuthStore()
    if (!keyCode.value && !authStore.isUser) return
    const pendingIds = collectPendingRecordIds()
    if (!pendingIds.length) return
    await Promise.allSettled(
      pendingIds.map(id =>
        pollRecordStatus(id).catch(err => {
          recoverRecordAfterPollError(id, err.message).catch(() => {})
        })
      )
    )
  }

  async function recoverRecordAfterPollError(recordId, errorMessage) {
    const normalizedId = Number(recordId)
    for (let i = 0; i < RECOVERY_MAX_ATTEMPTS; i++) {
      await sleep(RECOVERY_INTERVAL_MS)
      await fetchHistory().catch(() => {})
      const latest = history.value.find(item => Number(item.id) === normalizedId)
      if (resolveRecordStatus(latest) === 'completed') {
        try {
          useConversationsStore().syncRecord(latest)
          useConversationsStore().syncFromHistoryRecords(history.value)
        } catch {
          /* ignore */
        }
        return latest
      }
      if (resolveRecordStatus(latest) === 'processing') {
        continue
      }
      if (resolveRecordStatus(latest) === 'failed') {
        try {
          useConversationsStore().failAssistantForRecord(recordId, latest.errorMessage || errorMessage)
        } catch {
          /* ignore */
        }
        return latest
      }
    }
    return null
  }

  async function generate({ prompt, type, sourceImage, model, clientMessageId }) {
    generating.value = true
    try {
      const { data } = await generateApi.post('/user/generate', {
        keyCode: keyCode.value,
        prompt,
        type,
        model,
        sourceImage,
        clientMessageId
      })
      if (data.code !== 200) throw new Error(data.message)

      upsertHistoryRecord(data.data)
      applyLocalGenerationCharge(data.data)
      generating.value = false
      await syncCreditsFromServer()

      if (resolveRecordStatus(data.data) === 'processing') {
        pollRecordStatus(data.data.id).catch(err => {
          recoverRecordAfterPollError(data.data.id, err.message).catch(() => {})
        })
      }

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
    if (!Number.isFinite(cost) || cost <= 0 || resolveRecordStatus(record) !== 'processing') {
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
      useConversationsStore().clearAll()
    } catch {
      /* ignore */
    }
  }

  return {
    keyCode, keyId, totalCredits, usedCredits, history,
    activating, generating, refreshing, syncingCredits,
    remainingCredits, isActivated, isBusy,
    activate, fetchHistory, refreshSession, initSession,
    syncCreditsFromServer, generate, resumePendingGenerations, logout, hydrateFromCache
  }
})
