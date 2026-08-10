import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '../api'
import { getImageUrl } from '../api'
import { friendlyGenerationError, resolveGenerationStatus } from '../utils/generation'

const STORAGE_PREFIX = 'conversations_'
const STORAGE_VERSION = 2

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

function hasAuthToken() {
  return !!localStorage.getItem('authToken')
}

function isVideoType(type) {
  return type === 'text2video' || type === 'image2video'
}

function generationArea(type) {
  return isVideoType(type) ? 'video' : 'image'
}

function normalizeConversationType(type) {
  if (type === 'img2img' || isVideoType(type)) return type
  return 'text2img'
}

function sourceImages(message) {
  const images = Array.isArray(message?.sourceImages)
    ? message.sourceImages.filter(value => typeof value === 'string' && value)
    : []
  if (images.length) return images
  return message?.sourceImage ? [message.sourceImage] : []
}

function mergeSourceImages(primary, secondary) {
  const primaryImages = sourceImages(primary)
  const secondaryImages = sourceImages(secondary)
  return secondaryImages.length > primaryImages.length ? secondaryImages : primaryImages
}

function recordIdEquals(a, b) {
  return Number(a) === Number(b)
}

function recordForMessage(record, status, errorMessage) {
  return {
    ...record,
    status,
    errorMessage: status === 'completed' ? null : errorMessage
  }
}

function messageStatusForSync(msg) {
  if (msg?.role !== 'assistant') return null
  if (msg.videoTask) return msg.videoTask.status || msg.status || null
  return resolveGenerationStatus(msg.record || { status: msg.status })
}

function messageErrorForSync(msg) {
  const status = messageStatusForSync(msg)
  if (status === 'completed') return null
  return msg.errorMessage || msg.record?.errorMessage || null
}

function recordCreatedTime(record) {
  const time = new Date(record?.createdAt || 0).getTime()
  return Number.isFinite(time) ? time : 0
}

function recordChangedForMessage(msg, record, nextStatus, nextErrorMessage) {
  const current = msg.record || {}
  return !recordIdEquals(msg.recordId || current.id, record.id) ||
    resolveGenerationStatus(msg.record || { status: msg.status }) !== nextStatus ||
    (current.imagePath || '') !== (record.imagePath || '') ||
    !!current.remoteImageAvailable !== !!record.remoteImageAvailable ||
    !!current.recoverable !== !!record.recoverable ||
    (current.sourceImagePath || '') !== (record.sourceImagePath || '') ||
    (msg.errorMessage || '') !== (nextErrorMessage || '')
}

function recordMatchesConversation(record, conv, userMsg = null) {
  if (isVideoType(conv?.type)) return false
  const type = record?.generationType === 'img2img' ? 'img2img' : 'text2img'
  const expectedType = userMsg?.sourceImage ? 'img2img' : (conv?.type || 'text2img')
  return type === expectedType
}

function recordMatchesClientMessage(record, msg) {
  return !!record?.clientMessageId && !!msg?.id && record.clientMessageId === msg.id
}

function isNearMessageTime(record, msg, userMsg) {
  const recordTime = recordCreatedTime(record)
  if (!recordTime) return false
  const start = Number(userMsg?.createdAt || msg?.createdAt || 0)
  if (!start) return false
  return recordTime >= start - 60 * 1000 && recordTime <= start + 6 * 60 * 60 * 1000
}

function sourceImageLooksSame(record, userMsg) {
  const current = userMsg?.sourceImage || ''
  const source = record?.sourceImagePath || ''
  if (!current || current.startsWith('data:') || !source) return true
  return current === source
}

function canRecoverRecordForMessage(record, conv, msg, userMsg) {
  if (recordMatchesClientMessage(record, msg)) return true
  if (msg?.recordId || msg?.record?.id || record?.clientMessageId) return false
  return record?.prompt === userMsg?.content &&
    recordMatchesConversation(record, conv, userMsg) &&
    sourceImageLooksSame(record, userMsg) &&
    isNearMessageTime(record, msg, userMsg)
}

function uid() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

function touchConversation(conversation) {
  const previous = Number(conversation?.updatedAt || 0)
  conversation.updatedAt = Math.max(Date.now(), previous + 1)
}

function defaultTitle(prompt, type) {
  const text = (prompt || '').trim()
  if (text) return text.length > 28 ? `${text.slice(0, 28)}…` : text
  if (type === 'image2video') return '图生视频对话'
  if (type === 'text2video') return '文生视频对话'
  return type === 'img2img' ? '图生图对话' : '文生图对话'
}

export const useConversationsStore = defineStore('conversations', () => {
  const conversations = ref([])
  const activeId = ref(null)
  const sidebarOpen = ref(true)
  const boundKeyCode = ref('')
  const pendingPrompt = ref('')
  const deletedConversationIds = ref(new Set())
  let remoteSyncTimer = null
  let remoteLoading = false
  let remoteSyncing = false
  let pendingRemoteSync = false
  const lastActiveByArea = {
    image: null,
    video: null
  }
  const pendingDeleteRequests = new Map()

  const activeConversation = computed(() =>
    conversations.value.find(c => c.id === activeId.value) ?? null
  )

  const sortedConversations = computed(() =>
    [...conversations.value].sort((a, b) => b.updatedAt - a.updatedAt)
  )

  function persist() {
    persistLocal()
    scheduleRemoteSync()
  }

  function persistLocal() {
    if (!boundKeyCode.value) return
    writeStored(boundKeyCode.value, {
      version: STORAGE_VERSION,
      conversations: conversations.value,
      activeId: activeId.value,
      deletedConversationIds: [...deletedConversationIds.value]
    })
  }

  function bindKeyCode(keyCode) {
    const normalized = (keyCode || '').trim().toUpperCase()
    if (boundKeyCode.value === normalized) return
    boundKeyCode.value = normalized
    const stored = readStored(normalized)
    conversations.value = stored?.conversations ?? []
    activeId.value = stored?.activeId ?? conversations.value[0]?.id ?? null
    lastActiveByArea.image = null
    lastActiveByArea.video = null
    const active = conversations.value.find(conv => conv.id === activeId.value)
    if (active) lastActiveByArea[generationArea(active.type)] = active.id
    deletedConversationIds.value = new Set(
      stored?.version === STORAGE_VERSION ? (stored.deletedConversationIds ?? []) : []
    )
  }

  function resetSession() {
    clearTimeout(remoteSyncTimer)
    remoteSyncTimer = null
    pendingRemoteSync = false
    conversations.value = []
    activeId.value = null
    lastActiveByArea.image = null
    lastActiveByArea.video = null
    boundKeyCode.value = ''
    pendingPrompt.value = ''
    deletedConversationIds.value = new Set()
  }

  function toRemotePayload() {
    return {
      activeId: activeId.value,
      deletedClientIds: [...deletedConversationIds.value],
      conversations: conversations.value.map(conv => ({
        clientId: conv.id,
        title: conv.title,
        type: conv.type,
        createdAt: conv.createdAt,
        updatedAt: conv.updatedAt,
        messages: (conv.messages || []).map(msg => ({
          clientId: msg.id,
          role: msg.role,
          content: msg.content || '',
          sourceImage: msg.sourceImage && !msg.sourceImage.startsWith('data:') ? msg.sourceImage : null,
          recordId: msg.recordId || msg.record?.id || null,
          videoTaskCode: msg.videoTaskCode || msg.videoTask?.taskCode || null,
          status: messageStatusForSync(msg),
          errorMessage: messageErrorForSync(msg),
          createdAt: msg.createdAt
        }))
      }))
    }
  }

  function materializeRemoteConversation(conv) {
    const materialized = {
      id: conv.clientId || uid(),
      title: conv.title || defaultTitle('', conv.type),
      type: normalizeConversationType(conv.type),
      createdAt: conv.createdAt || Date.now(),
      updatedAt: conv.updatedAt || Date.now(),
      messages: (conv.messages || []).map(msg => ({
        id: msg.clientId || uid(),
        role: msg.role,
        content: msg.content || '',
        sourceImage: msg.sourceImage || null,
        sourceImages: msg.sourceImage ? [msg.sourceImage] : [],
        recordId: msg.recordId || null,
        record: msg.recordId ? {
          id: msg.recordId,
          status: msg.status,
          imagePath: msg.recordImagePath || null,
          sourceImagePath: msg.recordSourceImagePath || null,
          remoteImageAvailable: !!msg.recordRemoteImageAvailable,
          taskCode: msg.recordTaskCode || null,
          generationType: msg.recordGenerationType || null,
          recoverable: !!msg.recordRecoverable,
          errorMessage: msg.errorMessage || null
        } : null,
        videoTaskCode: msg.videoTaskCode || msg.videoTask?.taskCode || null,
        videoTask: msg.videoTask || null,
        status: msg.status || null,
        errorMessage: msg.errorMessage ? friendlyGenerationError(msg.errorMessage) : null,
        createdAt: msg.createdAt || Date.now()
      }))
    }
    for (let index = 1; index < materialized.messages.length; index++) {
      const message = materialized.messages[index]
      const previous = materialized.messages[index - 1]
      if (message.role === 'assistant' && previous.role === 'user' && !previous.sourceImage) {
        previous.sourceImage = message.record?.sourceImagePath || null
      }
    }
    return materialized
  }

  function mergeMessage(localMsg, remoteMsg) {
    if (!localMsg) return remoteMsg
    if (!remoteMsg) return localMsg
    const localStatus = resolveGenerationStatus(localMsg.record || { status: localMsg.status })
    const remoteStatus = resolveGenerationStatus(remoteMsg.record || { status: remoteMsg.status })
    if ((localStatus === 'completed' || localStatus === 'failed') && remoteStatus === 'processing') {
      const merged = {
        ...remoteMsg,
        ...localMsg,
        sourceImage: localMsg.sourceImage || remoteMsg.sourceImage || null,
        record: localMsg.record || remoteMsg.record || null,
        videoTask: localMsg.videoTask || remoteMsg.videoTask || null
      }
      merged.sourceImages = mergeSourceImages(localMsg, remoteMsg)
      return merged
    }
    const remoteHasResult = remoteStatus === 'completed' || remoteStatus === 'failed' || !!remoteMsg.recordId
    if (remoteHasResult || localStatus !== 'processing') {
      const merged = {
        ...localMsg,
        ...remoteMsg,
        sourceImage: remoteMsg.sourceImage || localMsg.sourceImage || null,
        record: remoteMsg.record || localMsg.record || null,
        videoTask: remoteMsg.videoTask || localMsg.videoTask || null
      }
      merged.sourceImages = mergeSourceImages(remoteMsg, localMsg)
      return merged
    }
    const merged = {
      ...remoteMsg,
      ...localMsg,
      sourceImage: localMsg.sourceImage || remoteMsg.sourceImage || null,
      record: localMsg.record || remoteMsg.record || null,
      videoTask: localMsg.videoTask || remoteMsg.videoTask || null
    }
    merged.sourceImages = mergeSourceImages(localMsg, remoteMsg)
    return merged
  }

  function mergeMessages(localMessages = [], remoteMessages = []) {
    const localById = new Map(localMessages.map(msg => [msg.id, msg]))
    const remoteIds = new Set(remoteMessages.map(msg => msg.id))
    const merged = remoteMessages.map(remoteMsg => mergeMessage(localById.get(remoteMsg.id), remoteMsg))
    for (const localMsg of localMessages) {
      if (!remoteIds.has(localMsg.id)) merged.push(localMsg)
    }
    return merged.sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0))
  }

  function mergeConversation(localConv, remoteConv) {
    if (!localConv) return remoteConv
    const localIsNewer = (localConv.updatedAt || 0) >= (remoteConv.updatedAt || 0)
    return {
      ...(localIsNewer ? remoteConv : localConv),
      ...(localIsNewer ? localConv : remoteConv),
      messages: mergeMessages(localConv.messages || [], remoteConv.messages || [])
    }
  }

  function applyRemotePayload(payload) {
    const remoteConversations = payload?.conversations || []
    const serverDeletedIds = (payload?.deletedClientIds || []).filter(Boolean)
    deletedConversationIds.value = new Set([
      ...deletedConversationIds.value,
      ...serverDeletedIds
    ])
    const retainedLocalConversations = conversations.value.filter(
      conv => !deletedConversationIds.value.has(conv.id)
    )

    if (!remoteConversations.length) {
      conversations.value = retainedLocalConversations
      if (conversations.value.length) {
        persistLocal()
        return false
      }
      activeId.value = null
      persistLocal()
      return true
    }

    const preferredActiveId = activeId.value
    const localById = new Map(retainedLocalConversations.map(conv => [conv.id, conv]))
    const remoteIds = new Set()

    const merged = remoteConversations
      .filter(conv => {
        const clientId = conv.clientId
        return !clientId || !deletedConversationIds.value.has(clientId)
      })
      .map(conv => {
        const remoteConv = materializeRemoteConversation(conv)
        remoteIds.add(remoteConv.id)
        return mergeConversation(localById.get(remoteConv.id), remoteConv)
      })

    for (const localConv of retainedLocalConversations) {
      if (!remoteIds.has(localConv.id)) {
        merged.push(localConv)
      }
    }

    conversations.value = merged.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
    activeId.value = conversations.value.some(conv => conv.id === preferredActiveId)
      ? preferredActiveId
      : conversations.value.some(conv => conv.id === payload.activeId)
        ? payload.activeId
        : conversations.value[0]?.id || null
    persistLocal()
    return true
  }

  function scheduleRemoteSync() {
    if (!hasAuthToken()) return
    if (remoteLoading || remoteSyncing) {
      pendingRemoteSync = true
      return
    }
    clearTimeout(remoteSyncTimer)
    remoteSyncTimer = setTimeout(() => {
      syncRemote().catch(() => {})
    }, 500)
  }

  async function syncRemote() {
    if (!hasAuthToken()) return false
    clearTimeout(remoteSyncTimer)
    if (remoteLoading || remoteSyncing) {
      pendingRemoteSync = true
      return false
    }
    pendingRemoteSync = false
    remoteSyncing = true
    try {
      const payload = toRemotePayload()
      const { data } = await api.post('/chat/sync', payload)
      if (data.code === 200) {
        applyRemotePayload(data.data)
      }
      return data.code === 200
    } catch (error) {
      console.warn('Chat sync failed', error)
      return false
    } finally {
      remoteSyncing = false
      if (pendingRemoteSync) {
        scheduleRemoteSync()
      }
    }
  }

  async function loadRemote() {
    if (!hasAuthToken() || remoteLoading || remoteSyncing) return false
    remoteLoading = true
    try {
      const { data } = await api.get('/chat/sync')
      if (data.code !== 200) return false
      const applied = applyRemotePayload(data.data)
      if (!applied && conversations.value.length) {
        await syncRemote().catch(() => {})
      }
      return applied
    } finally {
      remoteLoading = false
      if (pendingRemoteSync) {
        scheduleRemoteSync()
      }
    }
  }

  function createConversation(type = 'text2img') {
    const normalizedType = normalizeConversationType(type)
    const conv = {
      id: uid(),
      title: normalizedType === 'img2img'
        ? '新图生图'
        : normalizedType === 'image2video'
          ? '新图生视频'
          : normalizedType === 'text2video'
            ? '新视频'
            : '新对话',
      type: normalizedType,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      messages: []
    }
    conversations.value.unshift(conv)
    activeId.value = conv.id
    lastActiveByArea[generationArea(conv.type)] = conv.id
    persist()
    return conv
  }

  function selectConversation(id) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return null
    activeId.value = id
    lastActiveByArea[generationArea(conv.type)] = conv.id
    persist()
    return conv
  }

  function activateGenerationArea(area = 'image') {
    const targetArea = area === 'video' ? 'video' : 'image'
    const current = activeConversation.value
    if (current) {
      lastActiveByArea[generationArea(current.type)] = current.id
      if (generationArea(current.type) === targetArea) return current
    }

    const rememberedId = lastActiveByArea[targetArea]
    const remembered = rememberedId
      ? conversations.value.find(conv => conv.id === rememberedId && generationArea(conv.type) === targetArea)
      : null
    const target = remembered || sortedConversations.value.find(conv => generationArea(conv.type) === targetArea)
    if (target) return selectConversation(target.id)

    return createConversation(targetArea === 'video' ? 'text2video' : 'text2img')
  }

  function renameConversation(id, title) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return
    const next = (title || '').trim()
    if (!next) return
    conv.title = next
    touchConversation(conv)
    persist()
  }

  function setConversationType(id, type) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return null
    const nextType = normalizeConversationType(type)
    if (conv.type === nextType) return conv
    conv.type = nextType
    lastActiveByArea[generationArea(nextType)] = conv.id
    touchConversation(conv)
    persist()
    return conv
  }

  function deleteConversation(id) {
    if (lastActiveByArea.image === id) lastActiveByArea.image = null
    if (lastActiveByArea.video === id) lastActiveByArea.video = null
    if (id) {
      deletedConversationIds.value.add(id)
    }
    conversations.value = conversations.value.filter(c => c.id !== id)
    if (activeId.value === id) {
      activeId.value = conversations.value[0]?.id ?? null
    }
    persist()
    deleteRemote(id).catch(() => false)
  }

  function deleteRemote(id) {
    if (!id || !hasAuthToken()) return Promise.resolve(false)
    if (pendingDeleteRequests.has(id)) return pendingDeleteRequests.get(id)

    const request = api.delete(`/chat/conversations/${encodeURIComponent(id)}`)
      .then(({ data }) => {
        if (data.code !== 200) return false
        persistLocal()
        return true
      })
      .catch(error => {
        console.warn('Chat delete failed', error)
        return false
      })
      .finally(() => pendingDeleteRequests.delete(id))

    pendingDeleteRequests.set(id, request)
    return request
  }

  async function flushPendingDeletes() {
    if (!hasAuthToken()) return false
    if (pendingDeleteRequests.size) {
      await Promise.allSettled([...pendingDeleteRequests.values()])
    }
    if (deletedConversationIds.value.size) {
      return syncRemote().catch(() => false)
    }
    return true
  }

  function ensureActive(type = 'text2img') {
    if (!activeId.value || !conversations.value.some(c => c.id === activeId.value)) {
      return createConversation(type)
    }
    return activeConversation.value
  }

  function addExchange(conversationId, { prompt, sourceImage, sourceImages: inputImages, type }) {
    const conv = conversations.value.find(c => c.id === conversationId)
    if (!conv) return null
    const now = Date.now()

    const userMsg = {
      id: uid(),
      role: 'user',
      content: prompt,
      sourceImage: sourceImage || null,
      sourceImages: Array.isArray(inputImages) ? inputImages.filter(Boolean) : (sourceImage ? [sourceImage] : []),
      createdAt: now
    }
    const assistantMsg = {
      id: uid(),
      role: 'assistant',
      recordId: null,
      record: null,
      status: 'processing',
      errorMessage: null,
      createdAt: now + 1
    }

    conv.messages.push(userMsg, assistantMsg)
    if (conv.messages.length === 2) {
      conv.title = defaultTitle(prompt, type || conv.type)
    }
    touchConversation(conv)
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
          const nextStatus = resolveGenerationStatus(record)
          const nextErrorMessage = nextStatus === 'completed'
            ? null
            : (record.errorMessage ? friendlyGenerationError(record.errorMessage, record) : null)
          if (recordChangedForMessage(msg, record, nextStatus, nextErrorMessage)) {
            msg.recordId = record.id
            msg.record = recordForMessage(record, nextStatus, nextErrorMessage)
            msg.status = nextStatus
            msg.errorMessage = nextErrorMessage
            if (attachSourceImagePath(conv, msg, record)) {
              changed = true
            }
            changed = true
          }
          matched = true
        }
      }

      if (!matched) {
        const pending = findAssistantForRecordClientMessage(conv, record) || findPendingAssistantForRecord(conv, record)
        if (pending) {
          pending.recordId = record.id
          pending.status = resolveGenerationStatus(record)
          pending.errorMessage = pending.status === 'completed'
            ? null
            : (record.errorMessage ? friendlyGenerationError(record.errorMessage, record) : null)
          pending.record = recordForMessage(record, pending.status, pending.errorMessage)
          attachSourceImagePath(conv, pending, record)
          changed = true
        }
      }
    }

    if (changed) persist()
  }

  function findAssistantForRecordClientMessage(conv, record) {
    if (!record?.clientMessageId) return null
    return (conv.messages || []).find(msg =>
      msg.role === 'assistant' && msg.id === record.clientMessageId
    ) || null
  }

  function findPendingAssistantForRecord(conv, record) {
    const pending = [...(conv.messages || [])].reverse().find(msg => {
      if (msg.role !== 'assistant' || msg.recordId || msg.record?.id) return false
      const status = resolveGenerationStatus(msg.record || { status: msg.status })
      if (status !== 'processing') return false

      const userMsg = previousUserMessage(conv, msg)
      if (!userMsg) return false
      return canRecoverRecordForMessage(record, conv, msg, userMsg)
    })
    if (pending) return pending

    return null
  }

  function previousUserMessage(conv, assistantMsg) {
    const index = conv.messages.findIndex(msg => msg.id === assistantMsg.id)
    if (index <= 0) return null
    const userMsg = conv.messages[index - 1]
    return userMsg?.role === 'user' ? userMsg : null
  }

  function attachVideoInputAssets(conv, assistantMsg, task) {
    const images = (task?.inputAssets || [])
      .filter(asset => asset?.mediaType === 'IMAGE' && asset?.sourceUrl)
      .sort((left, right) => Number(left.sortOrder || 0) - Number(right.sortOrder || 0))
      .map(asset => asset.sourceUrl)
    if (!images.length) return false
    const userMsg = previousUserMessage(conv, assistantMsg)
    if (!userMsg) return false
    const current = sourceImages(userMsg)
    if (JSON.stringify(current) === JSON.stringify(images)) return false
    userMsg.sourceImage = images[0]
    userMsg.sourceImages = images
    return true
  }

  function attachSourceImagePath(conv, assistantMsg, record) {
    if (!record?.sourceImagePath) return false
    const userMsg = previousUserMessage(conv, assistantMsg)
    if (!userMsg) return false
    if (userMsg.sourceImage && !userMsg.sourceImage.startsWith('data:')) return false
    userMsg.sourceImage = record.sourceImagePath
    return true
  }

  function messageSourceImageForAssistant(conv, assistantMsg) {
    return previousUserMessage(conv, assistantMsg)?.sourceImage || ''
  }

  function attachRecordToMessage(messageId, record) {
    if (!messageId || !record?.id) return false

    const conv = conversations.value.find(c =>
      c.messages.some(m => m.id === messageId)
    )
    if (!conv) return false

    const msg = conv.messages.find(m => m.id === messageId && m.role === 'assistant')
    if (!msg) return false

    msg.recordId = record.id
    msg.status = resolveGenerationStatus(record)
    msg.errorMessage = msg.status === 'completed'
      ? null
      : (record.errorMessage ? friendlyGenerationError(record.errorMessage, record) : null)
    msg.record = recordForMessage(record, msg.status, msg.errorMessage)
    attachSourceImagePath(conv, msg, record)
    persist()
    return true
  }

  function attachVideoTaskToMessage(messageId, task) {
    if (!messageId || !task?.taskCode) return false
    const conv = conversations.value.find(c => c.messages.some(message => message.id === messageId))
    if (!conv) return false
    const message = conv.messages.find(item => item.id === messageId && item.role === 'assistant')
    if (!message) return false
    message.videoTaskCode = task.taskCode
    message.videoTask = task
    message.status = task.status || 'processing'
    message.errorMessage = task.status === 'failed' ? (task.errorMessage || '视频生成失败') : null
    attachVideoInputAssets(conv, message, task)
    touchConversation(conv)
    persist()
    return true
  }

  function syncVideoTask(task) {
    if (!task?.taskCode) return false
    let changed = false
    for (const conv of conversations.value) {
      let conversationChanged = false
      for (const message of conv.messages || []) {
        if (message.role !== 'assistant') continue
        const matchesCode = message.videoTaskCode === task.taskCode || message.videoTask?.taskCode === task.taskCode
        const matchesClient = !message.videoTaskCode && task.clientMessageId && message.id === task.clientMessageId
        if (!matchesCode && !matchesClient) continue
        const previous = message.videoTask || {}
        const nextError = task.status === 'failed' ? (task.errorMessage || '视频生成失败') : null
        const inputsChanged = attachVideoInputAssets(conv, message, task)
        if (previous.status !== task.status || previous.detailStatus !== task.detailStatus ||
            JSON.stringify(previous.inputAssets || []) !== JSON.stringify(task.inputAssets || []) ||
            JSON.stringify(previous.results || []) !== JSON.stringify(task.results || []) ||
            (message.errorMessage || null) !== nextError || inputsChanged) {
          message.videoTaskCode = task.taskCode
          message.videoTask = task
          message.status = task.status || 'processing'
          message.errorMessage = nextError
          changed = true
          conversationChanged = true
        }
      }
      if (conversationChanged) touchConversation(conv)
    }
    if (changed) persist()
    return changed
  }

  function syncVideoTasks(tasks) {
    let changed = false
    for (const task of tasks || []) {
      if (!task?.taskCode) continue
      for (const conv of conversations.value) {
        let conversationChanged = false
        for (const message of conv.messages || []) {
          if (message.role !== 'assistant') continue
          const matchesCode = message.videoTaskCode === task.taskCode || message.videoTask?.taskCode === task.taskCode
          const matchesClient = !message.videoTaskCode && task.clientMessageId && message.id === task.clientMessageId
          if (!matchesCode && !matchesClient) continue
          const previous = message.videoTask || {}
          const nextError = task.status === 'failed' ? (task.errorMessage || '视频生成失败') : null
          const inputsChanged = attachVideoInputAssets(conv, message, task)
          if (previous.status !== task.status || previous.detailStatus !== task.detailStatus ||
              JSON.stringify(previous.inputAssets || []) !== JSON.stringify(task.inputAssets || []) ||
              JSON.stringify(previous.results || []) !== JSON.stringify(task.results || []) ||
              (message.errorMessage || null) !== nextError || inputsChanged) {
            message.videoTaskCode = task.taskCode
            message.videoTask = task
            message.status = task.status || 'processing'
            message.errorMessage = nextError
            changed = true
            conversationChanged = true
          }
        }
        if (conversationChanged) touchConversation(conv)
      }
    }
    if (changed) persist()
    return changed
  }

  function failAssistantForRecord(recordId, errorMessage) {
    errorMessage = friendlyGenerationError(errorMessage)
    let changed = false
    for (const conv of conversations.value) {
      for (const msg of conv.messages) {
        if (msg.role !== 'assistant') continue
        if (!recordIdEquals(msg.recordId, recordId) && !recordIdEquals(msg.record?.id, recordId)) continue
        if (resolveGenerationStatus(msg.record || { status: msg.status }) === 'completed') continue
        msg.status = 'failed'
        msg.errorMessage = errorMessage || '生成失败'
        changed = true
      }
    }
    if (changed) persist()
  }

  function syncFromHistoryRecords(records) {
    if (!records?.length) return
    const byId = new Map(records.map(record => [Number(record.id), record]))
    const usedIds = new Set()
    let changed = false

    for (const conv of conversations.value) {
      for (const msg of conv.messages) {
        if (msg.role !== 'assistant') continue
        const id = msg.recordId || msg.record?.id
        if (id != null) usedIds.add(Number(id))
        if (id == null) continue
        const latest = byId.get(Number(id))
        if (!latest) continue

        const nextStatus = resolveGenerationStatus(latest)
        const prevStatus = resolveGenerationStatus(msg.record || { status: msg.status })
        const imageChanged = latest.imagePath !== msg.record?.imagePath ||
          !!latest.remoteImageAvailable !== !!msg.record?.remoteImageAvailable
        const sourceMissing = !!latest.sourceImagePath && !messageSourceImageForAssistant(conv, msg)
        if (nextStatus !== prevStatus || imageChanged || sourceMissing) {
          msg.recordId = latest.id
          msg.status = nextStatus
          msg.errorMessage = nextStatus === 'completed'
            ? null
            : (latest.errorMessage ? friendlyGenerationError(latest.errorMessage, latest) : null)
          msg.record = recordForMessage(latest, nextStatus, msg.errorMessage)
          attachSourceImagePath(conv, msg, latest)
          changed = true
        }
      }

      for (let i = 0; i < conv.messages.length; i++) {
        const msg = conv.messages[i]
        if (msg.role !== 'assistant') continue

        const currentStatus = resolveGenerationStatus(msg.record || { status: msg.status })
        const needsRecovered = currentStatus === 'processing' ||
          !(msg.record?.imagePath || msg.record?.imageUrl || msg.record?.remoteImageAvailable)
        if (!needsRecovered) continue
        if (msg.recordId || msg.record?.id) continue

        const userMsg = conv.messages[i - 1]
        if (!userMsg || userMsg.role !== 'user') continue

        const candidates = records
          .filter(record =>
            record?.id != null &&
            canRecoverRecordForMessage(record, conv, msg, userMsg) &&
            resolveGenerationStatus(record) === 'completed' &&
            !!(record.imagePath || record.imageUrl || record.remoteImageAvailable)
          )
          .sort((a, b) =>
            Math.abs(recordCreatedTime(a) - msg.createdAt) -
            Math.abs(recordCreatedTime(b) - msg.createdAt)
          )

        const latest = candidates[0]
        if (!latest) continue

        msg.recordId = latest.id
        msg.status = resolveGenerationStatus(latest)
        msg.errorMessage = msg.status === 'completed'
          ? null
          : (latest.errorMessage ? friendlyGenerationError(latest.errorMessage, latest) : null)
        msg.record = recordForMessage(latest, msg.status, msg.errorMessage)
        attachSourceImagePath(conv, msg, latest)
        usedIds.add(Number(latest.id))
        changed = true
      }
    }

    if (changed) persist()
  }

  function failLatestAssistant(errorMessage) {
    errorMessage = friendlyGenerationError(errorMessage)
    const conv = activeConversation.value
    if (!conv) return
    const pending = [...conv.messages].reverse().find(
      m => m.role === 'assistant' && m.status === 'processing'
    )
    if (!pending) return
    pending.status = 'failed'
    pending.errorMessage = errorMessage || '生成失败'
    persist()
  }

  function failAssistantMessage(messageId, errorMessage) {
    if (!messageId) return false
    for (const conv of conversations.value) {
      const message = (conv.messages || []).find(item => item.id === messageId && item.role === 'assistant')
      if (!message) continue
      message.status = 'failed'
      message.errorMessage = friendlyGenerationError(errorMessage) || '生成失败'
      touchConversation(conv)
      persist()
      return true
    }
    return false
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
      const status = resolveGenerationStatus(record)
      const errorMessage = status === 'completed'
        ? null
        : (record.errorMessage ? friendlyGenerationError(record.errorMessage, record) : null)
      const assistantMsg = {
        id: uid(),
        role: 'assistant',
        recordId: record.id,
        record: recordForMessage(record, status, errorMessage),
        status,
        errorMessage,
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
    resetSession,
    createConversation,
    selectConversation,
    activateGenerationArea,
    renameConversation,
    setConversationType,
    deleteConversation,
    flushPendingDeletes,
    ensureActive,
    addExchange,
    syncRecord,
    attachRecordToMessage,
    attachVideoTaskToMessage,
    failLatestAssistant,
    failAssistantMessage,
    failAssistantForRecord,
    syncFromHistoryRecords,
    syncVideoTask,
    syncVideoTasks,
    importFromHistory,
    imageUrlForMessage,
    setPendingPrompt,
    consumePendingPrompt,
    loadRemote,
    syncRemote
  }
})
