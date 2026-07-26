import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 60000
})

const generateApi = axios.create({
  baseURL: '/api',
  timeout: 600000
})

function authToken() {
  return localStorage.getItem('authToken') || ''
}

function attachAuth(config) {
  const token = authToken()
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}

api.interceptors.request.use(attachAuth)
generateApi.interceptors.request.use(attachAuth)

/** 灵感画廊媒体仍走静态资源，视频播放可以继续使用浏览器 Range 请求 */
export const IMAGE_BASE_PATH = '/img/'
/** 聊天生成图、参考图和灵感画廊统一走 /img/ 静态资源 */
export const API_IMAGE_BASE_PATH = '/img/'

export function normalizeImageUrl(path, basePath = API_IMAGE_BASE_PATH) {
  if (!path) return ''
  const raw = String(path).trim()
  if (raw.startsWith('data:')) return raw
  if (raw.startsWith('http://') || raw.startsWith('https://')) return raw

  let normalized = raw.replaceAll('\\', '/')
  const apiImagesIndex = normalized.lastIndexOf('/api/images/')
  if (apiImagesIndex >= 0) {
    normalized = normalized.slice(apiImagesIndex + '/api/images/'.length)
  }
  const imgIndex = normalized.lastIndexOf('/img/')
  if (imgIndex >= 0) {
    normalized = normalized.slice(imgIndex + '/img/'.length)
  } else if (normalized.startsWith('/api/images/')) {
    normalized = normalized.slice('/api/images/'.length)
  } else if (normalized.startsWith('api/images/')) {
    normalized = normalized.slice('api/images/'.length)
  } else if (normalized.startsWith('/img/')) {
    normalized = normalized.slice('/img/'.length)
  } else if (normalized.startsWith('img/')) {
    normalized = normalized.slice('img/'.length)
  }

  normalized = normalized.replace(/^\/+/, '')
  if (!normalized) return ''
  const encoded = normalized
    .split('/')
    .filter(Boolean)
    .map(encodePathPart)
    .join('/')
  return `${basePath}${encoded}`
}

function encodePathPart(part) {
  try {
    return encodeURIComponent(decodeURIComponent(part))
  } catch {
    return encodeURIComponent(part)
  }
}

function isRemoteUrl(path) {
  return path.startsWith('http://') || path.startsWith('https://') || path.startsWith('data:')
}

function isApiMediaPath(path) {
  return path.startsWith('/api/inspiration/media/') || path.startsWith('api/inspiration/media/')
}

export default api
export { generateApi }

export function getImageUrl(record) {
  if (!record) return ''
  const path = record.imagePath || record.imageUrl || ''
  if (!path && record.displayUrl) return record.displayUrl
  return normalizeImageUrl(path, API_IMAGE_BASE_PATH)
}

export async function getRemoteImageDisplayUrl(record) {
  if (!record?.remoteImageAvailable || !record.id) return ''
  const keyCode = localStorage.getItem('keyCode') || ''
  const params = keyCode ? { keyCode } : undefined
  const { data } = await api.get(`/user/generate/${record.id}/display-url`, { params })
  if (data?.code !== 200) throw new Error(data?.message || 'Remote image is unavailable')
  return data?.data?.displayUrl || ''
}

export async function requestImageBackup(record, displayUrl = '') {
  if (!record?.remoteImageAvailable || !record.id) return Promise.resolve()
  const keyCode = localStorage.getItem('keyCode') || ''
  const params = keyCode ? { keyCode } : undefined
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 30000)
  try {
    const remoteUrl = displayUrl || record.displayUrl || await getRemoteImageDisplayUrl(record)
    if (!remoteUrl) throw new Error('Remote image is unavailable')
    const response = await fetch(remoteUrl, { signal: controller.signal, mode: 'cors' })
    if (!response.ok) throw new Error(`Image fetch failed: ${response.status}`)
    const image = await response.blob()
    if (!image.size || image.size > 20 * 1024 * 1024) throw new Error('Invalid image size')
    const form = new FormData()
    form.append('image', image, `${record.taskCode || 'generated-image'}.png`)
    return await api.post(`/user/generate/${record.id}/backup-upload`, form, { params })
  } catch {
    return api.post(`/user/generate/${record.id}/backup`, null, { params })
  } finally {
    clearTimeout(timeout)
  }
}

/** 灵感画廊图片地址：本地相对路径拼 /img/，远程 URL 直接使用 */
function appendCacheBust(url, updatedAt) {
  if (!url || !updatedAt) return url
  const v = encodeURIComponent(String(updatedAt))
  return url + (url.includes('?') ? '&' : '?') + `_v=${v}`
}

export function getInspirationImageUrl(item) {
  if (!item) return ''
  const path = item.imageUrl || item.sourceUrl || ''
  if (!path) return ''
  const raw = String(path).trim()
  let url = raw
  if (!isRemoteUrl(raw) && !isApiMediaPath(raw)) {
    url = normalizeImageUrl(raw, IMAGE_BASE_PATH)
  }
  return appendCacheBust(url, item.updatedAt)
}

/** 本地 /img/ 或媒体代理失败时回退到远程 sourceUrl */
export function getInspirationFallbackUrl(item) {
  if (!item?.sourceUrl) return ''
  const primary = item.imageUrl || ''
  if (primary === item.sourceUrl) return ''
  if (primary.startsWith('http://') || primary.startsWith('https://')) return ''
  return appendCacheBust(item.sourceUrl, item.updatedAt)
}
