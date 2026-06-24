import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 15000
})

const generateApi = axios.create({
  baseURL: '/api',
  timeout: 600000
})

/** 与后端静态资源路径一致，数据库只存相对路径，前端拼接展示 */
export const IMAGE_BASE_PATH = '/img/'

export default api
export { generateApi }

export function getImageUrl(record) {
  if (!record) return ''
  const path = record.imagePath || record.imageUrl || ''
  if (!path) return ''
  if (path.startsWith('http://') || path.startsWith('https://')) return path
  if (path.startsWith('/')) return path
  return `${IMAGE_BASE_PATH}${path}`
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
    url = `${IMAGE_BASE_PATH}${path}`
  }
  return appendCacheBust(url, item.updatedAt)
}

/** 本地 /img/ 加载失败时回退到远程 sourceUrl */
export function getInspirationFallbackUrl(item) {
  if (!item?.sourceUrl) return ''
  const primary = item.imageUrl || ''
  if (!primary || primary.startsWith('http://') || primary.startsWith('https://')) return ''
  if (primary === item.sourceUrl) return ''
  return appendCacheBust(item.sourceUrl, item.updatedAt)
}
