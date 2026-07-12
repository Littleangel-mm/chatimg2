import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 15000
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

/** 与后端静态资源路径一致，数据库只存相对路径，前端拼接展示 */
export const IMAGE_BASE_PATH = '/img/'

export function normalizeImageUrl(path) {
  if (!path) return ''
  const raw = String(path).trim()
  if (raw.startsWith('data:')) return raw
  if (raw.startsWith('http://') || raw.startsWith('https://')) return raw

  let normalized = raw.replaceAll('\\', '/')
  const imgIndex = normalized.lastIndexOf('/img/')
  if (imgIndex >= 0) {
    normalized = normalized.slice(imgIndex + '/img/'.length)
  } else if (normalized.startsWith('/img/')) {
    normalized = normalized.slice('/img/'.length)
  } else if (normalized.startsWith('img/')) {
    normalized = normalized.slice('img/'.length)
  } else if (normalized.startsWith('/')) {
    return normalized
  }

  normalized = normalized.replace(/^\/+/, '')
  if (!normalized) return ''
  const encoded = normalized
    .split('/')
    .filter(Boolean)
    .map(encodePathPart)
    .join('/')
  return `${IMAGE_BASE_PATH}${encoded}`
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
  return normalizeImageUrl(path)
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
    url = normalizeImageUrl(path)
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
