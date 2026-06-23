import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '../api'

export const useInspirationStore = defineStore('inspiration', () => {
  const items = ref([])
  const mediaType = ref('image')
  const page = ref(0)
  const size = ref(30)
  const total = ref(0)
  const totalPages = ref(0)
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref('')

  const hasMore = computed(() => page.value + 1 < totalPages.value)

  async function fetchPage(p = 0, { append = false } = {}) {
    loading.value = true
    error.value = ''
    try {
      const { data } = await api.get('/inspiration', {
        params: { mediaType: mediaType.value, page: p, size: size.value }
      })
      if (data.code !== 200) throw new Error(data.message)
      const payload = data.data
      items.value = append ? [...items.value, ...(payload.list || [])] : (payload.list || [])
      total.value = payload.total
      totalPages.value = payload.totalPages
      page.value = payload.page
      loaded.value = true
      return payload
    } catch (e) {
      error.value = e.response?.data?.message || e.message || '加载失败'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function ensureLoaded() {
    if (loaded.value || loading.value) return
    await fetchPage(0).catch(() => {})
  }

  function setMediaType(type) {
    if (mediaType.value === type) return
    mediaType.value = type
    items.value = []
    loaded.value = false
    page.value = 0
    totalPages.value = 0
    total.value = 0
  }

  async function loadMore() {
    if (loading.value || !hasMore.value) return
    await fetchPage(page.value + 1, { append: true })
  }

  return {
    items, mediaType, page, size, total, totalPages, loading, loaded, error,
    hasMore, fetchPage, ensureLoaded, setMediaType, loadMore
  }
})
