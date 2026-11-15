import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '../api'

const TOKEN_KEY = 'authToken'
const USER_KEY = 'authUser'

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref(readUser())
  const loading = ref(false)
  const sendingCode = ref(false)

  const isLoggedIn = computed(() => !!token.value && !!user.value)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const isUser = computed(() => user.value?.role === 'USER')

  function applySession(data) {
    token.value = data.token || token.value
    user.value = {
      id: data.id,
      email: data.email,
      username: data.username,
      role: data.role,
      totalCredits: data.totalCredits,
      usedCredits: data.usedCredits,
      remainingCredits: data.remainingCredits
    }
    if (data.token) localStorage.setItem(TOKEN_KEY, data.token)
    localStorage.setItem(USER_KEY, JSON.stringify(user.value))
  }

  async function sendCode(email, scene) {
    sendingCode.value = true
    try {
      const { data } = await api.post('/auth/email-code', { email, scene })
      if (data.code !== 200) throw new Error(data.message)
      return data
    } finally {
      sendingCode.value = false
    }
  }

  async function register(email, password, code) {
    loading.value = true
    try {
      const { data } = await api.post('/auth/register', { email, password, code })
      if (data.code !== 200) throw new Error(data.message)
      applySession(data.data)
      return data.data
    } finally {
      loading.value = false
    }
  }

  async function login({ account, code, password }) {
    loading.value = true
    try {
      const { data } = await api.post('/auth/login', { account, code, password })
      if (data.code !== 200) throw new Error(data.message)
      applySession(data.data)
      return data.data
    } finally {
      loading.value = false
    }
  }

  async function fetchMe() {
    if (!token.value) return null
    const { data } = await api.get('/auth/me')
    if (data.code !== 200) {
      logout()
      return null
    }
    applySession(data.data)
    return data.data
  }

  async function logout() {
    try {
      if (token.value) await api.post('/auth/logout')
    } catch {
      /* ignore */
    }
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return {
    token,
    user,
    loading,
    sendingCode,
    isLoggedIn,
    isAdmin,
    isUser,
    sendCode,
    register,
    login,
    fetchMe,
    logout
  }
})
