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
/** 聊天生成图和参考图走后端接口，线上只需要反代 /api */
export const API_IMAGE_BASE_PATH = '/api/images/'

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

export default api
export { generateApi }

export function getImageUrl(record) {
  if (!record) return ''
  const path = record.imagePath || record.imageUrl || ''
  return normalizeImageUrl(path, API_IMAGE_BASE_PATH)
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
  let url = path
  if (!url.startsWith('http://') && !url.startsWith('https://') && !url.startsWith('/')) {
    url = normalizeImageUrl(path, IMAGE_BASE_PATH)
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
