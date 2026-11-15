import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import api from '../api'

const FALLBACK_MODEL = {
  id: 'ty-me-sd20-933',
  name: '官渠-Seedance 2.0 933 稳定满血版',
  category: 'video',
  flowTypes: [],
  inputKeys: [],
  costPerSecond: 100
}

const SUPPORTED_MODEL_ID = FALLBACK_MODEL.id
const SUBMIT_ATTEMPTS = 3
const SUBMIT_RETRY_DELAY_MS = 800

function preferredModels(data) {
  if (!Array.isArray(data)) return []
  return data.filter(item => item?.id === SUPPORTED_MODEL_ID)
}

function unwrap(response) {
  const body = response?.data
  if (body?.code !== 200) {
    const error = new Error(body?.message || '请求失败，请稍后重试')
    error.recoverable = false
    throw error
  }
  return body.data
}

function messageOf(error, fallback) {
  const backendMessage = error?.response?.data?.message
  if (backendMessage) return backendMessage
  if (error?.response) return fallback
  return error?.message || fallback
}

function requestError(error, fallback) {
  const wrapped = new Error(messageOf(error, fallback))
  const status = Number(error?.response?.status)
  wrapped.status = Number.isFinite(status) ? status : null
  wrapped.recoverable = error?.recoverable !== false &&
    (!wrapped.status || wrapped.status === 408 || wrapped.status === 429 || wrapped.status >= 500)
  return wrapped
}

export const useVideosStore = defineStore('videos', () => {
  const tasks = ref([])
  const models = ref([])
  const loading = ref(false)
  const submitting = ref(false)
  const uploading = ref(false)
  const initialized = ref(false)
  const processingTasks = computed(() => tasks.value.filter(task =>
    task.status === 'processing' && task.detailStatus !== 'submission_unknown'
  ))
  const pendingSubmissions = new Map()
  let refreshPromise = null

  function mergeTask(task) {
    if (!task?.taskCode) return task
    const index = tasks.value.findIndex(item => item.taskCode === task.taskCode)
    if (index >= 0) {
      tasks.value[index] = { ...tasks.value[index], ...task }
    } else {
      tasks.value.push(task)
    }
    return task
  }

  async function loadModels() {
    try {
      const data = unwrap(await api.get('/video/models'))
      const preferred = preferredModels(data)
      models.value = preferred.length ? preferred : [FALLBACK_MODEL]
    } catch (error) {
      models.value = [FALLBACK_MODEL]
      throw new Error(messageOf(error, '暂时无法获取视频模型'))
    }
    return models.value
  }

  async function loadHistory() {
    loading.value = !initialized.value
    try {
      const data = unwrap(await api.get('/video/generations'))
      tasks.value = Array.isArray(data) ? data : []
      initialized.value = true
      return tasks.value
    } finally {
      loading.value = false
    }
  }

  async function uploadImage(file) {
    uploading.value = true
    try {
      const form = new FormData()
      form.append('file', file)
      form.append('mediaType', 'IMAGE')
      return unwrap(await api.post('/video/assets', form))
    } catch (error) {
      throw new Error(messageOf(error, '参考图上传失败，请稍后重试'))
    } finally {
      uploading.value = false
    }
  }

  function retryableSubmit(error) {
    if (error?.recoverable === false) return false
    const status = Number(error?.response?.status)
    return !error?.response || status === 408 || status === 429 || status >= 500
  }

  function delay(ms) {
    return new Promise(resolve => window.setTimeout(resolve, ms))
  }

  async function reconcileSubmission(clientMessageId) {
    if (!clientMessageId) return null
    try {
      const history = unwrap(await api.get('/video/generations'))
      if (!Array.isArray(history)) return null
      history.forEach(mergeTask)
      return history.find(task => task.clientMessageId === clientMessageId) || null
    } catch {
      return null
    }
  }

  async function submitWithRetry(payload) {
    let lastError
    for (let attempt = 1; attempt <= SUBMIT_ATTEMPTS; attempt += 1) {
      try {
        const task = unwrap(await api.post('/video/generations', payload, {
          headers: payload.clientMessageId ? { 'X-Request-ID': payload.clientMessageId } : undefined
        }))
        return mergeTask(task)
      } catch (error) {
        lastError = error
        if (!retryableSubmit(error)) break
        const recovered = await reconcileSubmission(payload.clientMessageId)
        if (recovered) return recovered
        if (attempt < SUBMIT_ATTEMPTS) {
          await delay(SUBMIT_RETRY_DELAY_MS * attempt)
        }
      }
    }
    const recovered = await reconcileSubmission(payload.clientMessageId)
    if (recovered) return recovered
    const error = requestError(lastError, '提交请求未被后端确认，请检查网络后重试')
    error.recoverable = false
    throw error
  }

  function submit(payload) {
    const key = payload.clientMessageId || ''
    if (key && pendingSubmissions.has(key)) return pendingSubmissions.get(key)
    submitting.value = true
    const request = submitWithRetry(payload).finally(() => {
      if (key) pendingSubmissions.delete(key)
      submitting.value = pendingSubmissions.size > 0
    })
    if (key) pendingSubmissions.set(key, request)
    return request
  }

  async function refreshTask(taskCode) {
    try {
      return mergeTask(unwrap(await api.get(`/video/generations/${encodeURIComponent(taskCode)}`)))
    } catch (error) {
      throw new Error(messageOf(error, '视频任务状态刷新失败'))
    }
  }

  async function refreshProcessing() {
    if (refreshPromise) return refreshPromise
    const codes = processingTasks.value.map(task => task.taskCode)
    if (!codes.length) return []
    refreshPromise = Promise.allSettled(codes.map(refreshTask)).finally(() => {
      refreshPromise = null
    })
    return refreshPromise
  }

  return {
    tasks,
    models,
    loading,
    submitting,
    uploading,
    initialized,
    processingTasks,
    loadModels,
    loadHistory,
    uploadImage,
    submit,
    refreshTask,
    refreshProcessing
  }
})
