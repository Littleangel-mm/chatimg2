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
