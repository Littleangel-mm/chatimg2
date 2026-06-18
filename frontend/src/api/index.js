import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 15000
})

const generateApi = axios.create({
  baseURL: '/api',
  timeout: 120000
})

export default api
export { generateApi }

export function getImageUrl(record) {
  if (!record) return ''
  if (record.localPath) {
    const name = record.localPath.replace(/\\/g, '/').split('/').pop()
    return `/img/${name}`
  }
  return record.imageUrl || ''
}
