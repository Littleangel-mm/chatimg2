<script setup>
import { onUnmounted, ref, useAttrs, watch } from 'vue'
import api from '../../api'

defineOptions({ inheritAttrs: false })

const props = defineProps({
  sourceUrl: {
    type: String,
    default: ''
  },
  previewPath: {
    type: String,
    default: ''
  },
  alt: {
    type: String,
    default: ''
  }
})

const attrs = useAttrs()
const displayUrl = ref('')
let objectUrl = ''
let loadVersion = 0

function releaseObjectUrl() {
  if (!objectUrl) return
  URL.revokeObjectURL(objectUrl)
  objectUrl = ''
}

function directUrl(value) {
  const url = (value || '').trim()
  if (!url) return ''
  if (/^(data:|blob:|https:\/\/)/i.test(url)) return url
  if (/^http:\/\//i.test(url)) {
    return window.location.protocol === 'https:' ? '' : url
  }
  return url.startsWith('/') ? url : `/${url}`
}

async function loadImage() {
  const version = ++loadVersion
  releaseObjectUrl()
  displayUrl.value = ''

  if (!props.previewPath) {
    displayUrl.value = directUrl(props.sourceUrl)
    return
  }

  try {
    const response = await api.get(props.previewPath, { responseType: 'blob' })
    const nextObjectUrl = URL.createObjectURL(response.data)
    if (version !== loadVersion) {
      URL.revokeObjectURL(nextObjectUrl)
      return
    }
    objectUrl = nextObjectUrl
    displayUrl.value = nextObjectUrl
  } catch {
    if (version === loadVersion) displayUrl.value = directUrl(props.sourceUrl)
  }
}

watch(() => [props.previewPath, props.sourceUrl], loadImage, { immediate: true })

onUnmounted(() => {
  loadVersion += 1
  releaseObjectUrl()
})
</script>

<template>
  <img
    v-bind="attrs"
    :src="displayUrl || undefined"
    :alt="alt"
    referrerpolicy="no-referrer"
  />
</template>
