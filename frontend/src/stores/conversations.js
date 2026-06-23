import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getImageUrl } from '../api'

const STORAGE_PREFIX = 'conversations_'

function storageKey(keyCode) {
  return `${STORAGE_PREFIX}${keyCode || 'guest'}`
}

function readStored(keyCode) {
  try {
    return JSON.parse(localStorage.getItem(storageKey(keyCode)) || 'null')
  } catch {
    return null
  }
}

function writeStored(keyCode, data) {
  localStorage.setItem(storageKey(keyCode), JSON.stringify(data))
}

function recordIdEquals(a, b) {
  return Number(a) === Number(b)
}

function resolveRecordStatus(record) {
  const status = (record?.status || '').toLowerCase()
  if (status === 'completed' || status === 'failed') return status
  if (record?.imagePath && status === 'processing') return 'completed'
  return status || 'processing'
}

function uid() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

function defaultTitle(prompt, type) {
  const text = (prompt || '').trim()
  if (text) return text.length > 28 ? `${text.slice(0, 28)}…` : text
  return type === 'img2img' ? '图生图对话' : '文生图对话'
}

export const useConversationsStore = defineStore('conversations', () => {
  const conversations = ref([])
  const activeId = ref(null)
  const sidebarOpen = ref(true)
  const boundKeyCode = ref('')
  const pendingPrompt = ref('')

  const activeConversation = computed(() =>
    conversations.value.find(c => c.id === activeId.value) ?? null
  )

  const sortedConversations = computed(() =>
    [...conversations.value].sort((a, b) => b.updatedAt - a.updatedAt)
  )

  function persist() {
    if (!boundKeyCode.value) return
    writeStored(boundKeyCode.value, {
      conversations: conversations.value,
      activeId: activeId.value
    })
  }

  function bindKeyCode(keyCode) {
    const normalized = (keyCode || '').trim().toUpperCase()
    if (boundKeyCode.value === normalized) return
    boundKeyCode.value = normalized
    const stored = readStored(normalized)
    conversations.value = stored?.conversations ?? []
    activeId.value = stored?.activeId ?? conversations.value[0]?.id ?? null
  }

  function clearAll() {
    conversations.value = []
    activeId.value = null
    if (boundKeyCode.value) {
      localStorage.removeItem(storageKey(boundKeyCode.value))
    }
  }

  function createConversation(type = 'text2img') {
    const conv = {
      id: uid(),
      title: type === 'img2img' ? '新图生图' : '新对话',
      type,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      messages: []
    }
    conversations.value.unshift(conv)
    activeId.value = conv.id
    persist()
    return conv
  }

  function selectConversation(id) {
    activeId.value = id
    persist()
  }

  function renameConversation(id, title) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return
    const next = (title || '').trim()
    if (!next) return
    conv.title = next
    conv.updatedAt = Date.now()
    persist()
  }

  function deleteConversation(id) {
    conversations.value = conversations.value.filter(c => c.id !== id)
    if (activeId.value === id) {
      activeId.value = conversations.value[0]?.id ?? null
    }
    persist()
  }

  function ensureActive(type = 'text2img') {
    if (!activeId.value || !conversations.value.some(c => c.id === activeId.value)) {
      return createConversation(type)
    }
    return activeConversation.value
  }

  function addExchange(conversationId, { prompt, sourceImage, type, model }) {
    const conv = conversations.value.find(c => c.id === conversationId)
    if (!conv) return null

    const userMsg = {
      id: uid(),
      role: 'user',
      content: prompt,
      sourceImage: sourceImage || null,
      createdAt: Date.now()
    }
    const assistantMsg = {
      id: uid(),
      role: 'assistant',
      recordId: null,
      record: null,
      status: 'processing',
      errorMessage: null,
      createdAt: Date.now()
    }

    conv.messages.push(userMsg, assistantMsg)
    if (conv.messages.length === 2) {
      conv.title = defaultTitle(prompt, type || conv.type)
    }
    conv.updatedAt = Date.now()
    persist()
    return { userMsg, assistantMsg }
  }

  function syncRecord(record) {
    if (!record?.id) return
    let changed = false

    for (const conv of conversations.value) {
      let matched = false
      for (const msg of conv.messages) {
        if (msg.role !== 'assistant') continue
        if (recordIdEquals(msg.recordId, record.id) || recordIdEquals(msg.record?.id, record.id)) {
          msg.recordId = record.id
          msg.record = { ...record }
          msg.status = resolveRecordStatus(record)
          msg.errorMessage = record.errorMessage || null
          conv.updatedAt = Date.now()
          changed = true
          matched = true
        }
      }

      if (!matched && conv.id === activeId.value) {
        const pending = [...conv.messages].reverse().find(
          m => m.role === 'assistant' && m.status === 'processing' && !m.recordId
        )
        if (pending) {
          pending.recordId = record.id
          pending.record = { ...record }
          pending.status = resolveRecordStatus(record)
          pending.errorMessage = record.errorMessage || null
          conv.updatedAt = Date.now()
          changed = true
        }
      }
    }

    if (changed) persist()
  }

  function failAssistantForRecord(recordId, errorMessage) {
    let changed = false
    for (const conv of conversations.value) {
      for (const msg of conv.messages) {
        if (msg.role !== 'assistant') continue
        if (!recordIdEquals(msg.recordId, recordId) && !recordIdEquals(msg.record?.id, recordId)) continue
        msg.status = 'failed'
        msg.errorMessage = errorMessage || '生成失败'
        conv.updatedAt = Date.now()
        changed = true
      }
    }
    if (changed) persist()
  }

  function syncFromHistoryRecords(records) {
    if (!records?.length) return
    const byId = new Map(records.map(record => [Number(record.id), record]))
    let changed = false

    for (const conv of conversations.value) {
      for (const msg of conv.messages) {
        if (msg.role !== 'assistant') continue
        const id = msg.recordId || msg.record?.id
        if (id == null) continue
        const latest = byId.get(Number(id))
        if (!latest) continue

        const nextStatus = resolveRecordStatus(latest)
        const prevStatus = resolveRecordStatus(msg.record || { status: msg.status })
        const imageChanged = latest.imagePath !== msg.record?.imagePath
        if (nextStatus !== prevStatus || imageChanged) {
          msg.recordId = latest.id
          msg.record = { ...latest }
          msg.status = nextStatus
          msg.errorMessage = latest.errorMessage || null
          conv.updatedAt = Date.now()
          changed = true
        }
      }
    }

    if (changed) persist()
  }

  function failLatestAssistant(errorMessage) {
    const conv = activeConversation.value
    if (!conv) return
    const pending = [...conv.messages].reverse().find(
      m => m.role === 'assistant' && m.status === 'processing'
    )
    if (!pending) return
    pending.status = 'failed'
    pending.errorMessage = errorMessage || '生成失败'
    conv.updatedAt = Date.now()
    persist()
  }

  function importFromHistory(history, keyCode) {
    bindKeyCode(keyCode)
    if (conversations.value.length > 0 || !history?.length) return

    const imported = history.map(record => {
      const type = record.generationType === 'img2img' ? 'img2img' : 'text2img'
      const userMsg = {
        id: uid(),
        role: 'user',
        content: record.prompt,
        sourceImage: null,
        createdAt: new Date(record.createdAt || Date.now()).getTime()
      }
      const assistantMsg = {
        id: uid(),
        role: 'assistant',
        recordId: record.id,
        record: { ...record },
        status: resolveRecordStatus(record),
        errorMessage: record.errorMessage || null,
        createdAt: new Date(record.createdAt || Date.now()).getTime() + 1
      }
      return {
        id: uid(),
        title: defaultTitle(record.prompt, type),
        type,
        createdAt: userMsg.createdAt,
        updatedAt: assistantMsg.createdAt,
        messages: [userMsg, assistantMsg]
      }
    })

    conversations.value = imported.sort((a, b) => b.updatedAt - a.updatedAt)
    activeId.value = conversations.value[0]?.id ?? null
    persist()
  }

  function imageUrlForMessage(msg) {
    if (!msg?.record) return ''
    return getImageUrl(msg.record)
  }

  function setPendingPrompt(text) {
    pendingPrompt.value = text || ''
  }

  function consumePendingPrompt() {
    const text = pendingPrompt.value
    pendingPrompt.value = ''
    return text
  }

  return {
    conversations,
    activeId,
    sidebarOpen,
    pendingPrompt,
    activeConversation,
    sortedConversations,
    bindKeyCode,
    clearAll,
    createConversation,
    selectConversation,
    renameConversation,
    deleteConversation,
    ensureActive,
    addExchange,
    syncRecord,
    failLatestAssistant,
    failAssistantForRecord,
    syncFromHistoryRecords,
    importFromHistory,
    imageUrlForMessage,
    setPendingPrompt,
    consumePendingPrompt
  }
})
