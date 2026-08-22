import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import api from '../api'

const FALLBACK_MODEL = {
  id: 'ty-p-sd2-fast-720',
  name: 'Seedance 2.0 Fast 720p',
  category: 'video',
  flowTypes: [],
  inputKeys: [],
  costPerSecond: 100
}

const SUPPORTED_MODEL_ID = FALLBACK_MODEL.id

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
  const processingTasks = computed(() => tasks.value.filter(task => task.status === 'processing'))

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

  async function submit(payload) {
    submitting.value = true
    try {
      const task = unwrap(await api.post('/video/generations', payload))
      return mergeTask(task)
    } catch (error) {
      throw requestError(error, '视频任务提交失败，请稍后重试')
    } finally {
      submitting.value = false
    }
  }

  async function refreshTask(taskCode) {
    try {
      return mergeTask(unwrap(await api.get(`/video/generations/${encodeURIComponent(taskCode)}`)))
    } catch (error) {
      throw new Error(messageOf(error, '视频任务状态刷新失败'))
    }
  }

  async function refreshProcessing() {
    const codes = processingTasks.value.map(task => task.taskCode)
    if (!codes.length) return []
    return Promise.allSettled(codes.map(refreshTask))
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
