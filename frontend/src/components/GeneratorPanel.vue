<script setup>
import { ref } from 'vue'
import { useUserStore } from '../stores/user'
import { compressImage } from '../utils/image'

const userStore = useUserStore()

const mode = ref('text2img')
const prompt = ref('')
const model = ref('gpt-image-2')
const aspectRatio = ref('1:1')
const sourcePreview = ref('')
const sourceBase64 = ref('')
const error = ref('')
const uploading = ref(false)

const models = [
  { id: 'gpt-image-2', name: 'GPT Image 2' },
  { id: 'gpt-image-1', name: 'GPT Image' },
  { id: 'dall-e-2', name: 'DALL-E 2' },
  { id: 'dall-e-3', name: 'DALL-E 3' }
]

async function onFileChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (!file.type.startsWith('image/')) {
    error.value = '请上传图片文件'
    return
  }
  uploading.value = true
  error.value = ''
  try {
    const compressed = await compressImage(file)
    sourcePreview.value = compressed
    sourceBase64.value = compressed
  } catch {
    error.value = '图片处理失败'
  } finally {
    uploading.value = false
    e.target.value = ''
  }
}

function clearSource() {
  sourcePreview.value = ''
  sourceBase64.value = ''
}

function openKeyModal() {
  window.dispatchEvent(new CustomEvent('open-key-modal'))
}

async function generate() {
  if (!userStore.isActivated) {
    openKeyModal()
    return
  }
  if (!prompt.value.trim()) {
    error.value = '请输入提示词'
    return
  }
  if (mode.value === 'img2img' && !sourceBase64.value) {
    error.value = '请上传参考图片'
    return
  }

  error.value = ''
  try {
    await userStore.generate({
      prompt: prompt.value.trim(),
      type: mode.value,
      model: model.value,
      sourceImage: mode.value === 'img2img' ? sourceBase64.value : undefined
    })
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '生成失败'
  }
}
</script>

<template>
  <div id="generator" class="generator card">
    <div class="mode-tabs">
      <button
        :class="['tab', { active: mode === 'text2img' }]"
        @click="mode = 'text2img'"
      >
        <span class="tab-icon">✨</span>
        Text to Image
      </button>
      <button
        :class="['tab', { active: mode === 'img2img' }]"
        @click="mode = 'img2img'"
      >
        <span class="tab-icon">🖼</span>
        Image to Image
      </button>
    </div>

    <div class="generator-body">
      <div v-if="mode === 'img2img'" class="upload-area">
        <div v-if="uploading" class="upload-label uploading">
          <span class="upload-icon spin">⟳</span>
          <span>压缩图片中...</span>
        </div>
        <div v-else-if="sourcePreview" class="preview-wrap">
          <img :src="sourcePreview" alt="source" class="preview-img" />
          <button class="clear-btn" @click="clearSource">✕</button>
        </div>
        <label v-else class="upload-label">
          <input type="file" accept="image/*" hidden @change="onFileChange" />
          <span class="upload-icon">↑</span>
          <span>Upload a photo</span>
          <span class="upload-hint">PNG, JPG — 自动压缩加速上传</span>
        </label>
      </div>

      <div class="controls">
        <div class="control-row">
          <label>AI Model</label>
          <select v-model="model" class="select">
            <option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }}</option>
          </select>
        </div>
        <div class="control-row">
          <label>Aspect Ratio</label>
          <select v-model="aspectRatio" class="select">
            <option value="1:1">1:1</option>
            <option value="16:9">16:9</option>
            <option value="9:16">9:16</option>
          </select>
        </div>
      </div>

      <textarea
        v-model="prompt"
        class="prompt-input"
        :placeholder="mode === 'text2img'
          ? 'A futuristic cityscape at sunset, neon lights, cyberpunk style...'
          : 'Describe how you want to transform this image...'"
        rows="3"
      />

      <p v-if="error" class="error">{{ error }}</p>

      <div class="generate-row">
        <span class="cost-hint">每次生成消耗 20 积分</span>
        <button
          class="btn-primary generate-btn"
          :disabled="userStore.generating || uploading"
          @click="generate"
        >
          <span v-if="userStore.generating" class="btn-loading">
            <span class="spin">⟳</span> Generating...
          </span>
          <span v-else>Generate</span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.generator {
  padding: 8px;
  max-width: 720px;
  margin: 0 auto;
}

.mode-tabs {
  display: flex;
  gap: 4px;
  padding: 4px;
  background: var(--bg);
  border-radius: var(--radius);
  margin-bottom: 16px;
}

.tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 16px;
  background: transparent;
  color: var(--text-muted);
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  transition: all 0.2s;
}

.tab.active {
  background: var(--bg-card);
  color: var(--text);
  box-shadow: 0 2px 8px rgba(0,0,0,0.3);
}

.tab-icon { font-size: 16px; }

.generator-body { padding: 16px 20px 20px; }

.upload-area { margin-bottom: 16px; }

.upload-label {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 20px;
  border: 2px dashed var(--border);
  border-radius: var(--radius);
  cursor: pointer;
  color: var(--text-muted);
  transition: border-color 0.2s, background 0.2s;
}

.upload-label:hover {
  border-color: var(--accent);
  background: rgba(124, 92, 255, 0.05);
}

.upload-label.uploading { cursor: default; }

.upload-icon { font-size: 28px; color: var(--accent); }

.upload-hint { font-size: 12px; opacity: 0.7; }

.preview-wrap {
  position: relative;
  border-radius: var(--radius);
  overflow: hidden;
  border: 1px solid var(--border);
}

.preview-img {
  width: 100%;
  max-height: 240px;
  object-fit: cover;
  display: block;
}

.clear-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(0,0,0,0.6);
  color: #fff;
  font-size: 12px;
}

.controls {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
}

.control-row label {
  display: block;
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 6px;
}

.select {
  width: 100%;
  padding: 10px 12px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text);
  font-size: 14px;
  outline: none;
}

.prompt-input {
  width: 100%;
  padding: 14px 16px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  color: var(--text);
  font-size: 15px;
  resize: vertical;
  outline: none;
  margin-bottom: 12px;
}

.prompt-input:focus { border-color: var(--accent); }

.error { color: #f87171; font-size: 13px; margin-bottom: 12px; }

.generate-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cost-hint { font-size: 13px; color: var(--text-muted); }

.generate-btn { min-width: 140px; }

.btn-loading { display: inline-flex; align-items: center; gap: 6px; }

.spin {
  display: inline-block;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
