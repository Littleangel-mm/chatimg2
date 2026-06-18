<script setup>
import { computed } from 'vue'
import { useUserStore } from '../stores/user'
import { getImageUrl } from '../api'

const userStore = useUserStore()

const items = computed(() => userStore.history)
</script>

<template>
  <section id="gallery" class="gallery-section">
    <div class="container">
      <div class="section-header">
        <h2>Inspiration Gallery</h2>
        <p>Your generated images appear here</p>
      </div>

      <div v-if="items.length === 0" class="empty">
        <span class="empty-icon">🎨</span>
        <p>还没有生成记录，试试上方的 Generator 吧</p>
      </div>

      <div v-else class="gallery-grid">
        <div v-for="(item, index) in items" :key="item.id" class="gallery-item">
          <img
            :src="getImageUrl(item)"
            :alt="item.prompt"
            loading="lazy"
            decoding="async"
            :fetchpriority="index < 4 ? 'high' : 'low'"
          />
          <div class="item-overlay">
            <span class="item-type">{{ item.generationType === 'img2img' ? 'Image to Image' : 'Text to Image' }}</span>
            <p class="item-prompt">{{ item.prompt }}</p>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.gallery-section {
  padding: 80px 0 100px;
}

.section-header {
  text-align: center;
  margin-bottom: 48px;
}

.section-header h2 {
  font-size: 32px;
  font-weight: 700;
  margin-bottom: 8px;
}

.section-header p {
  color: var(--text-muted);
  font-size: 16px;
}

.empty {
  text-align: center;
  padding: 60px 20px;
  color: var(--text-muted);
}

.empty-icon {
  font-size: 48px;
  display: block;
  margin-bottom: 16px;
}

.gallery-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

.gallery-item {
  position: relative;
  aspect-ratio: 1;
  border-radius: var(--radius);
  overflow: hidden;
  border: 1px solid var(--border);
  cursor: pointer;
}

.gallery-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s;
}

.gallery-item:hover img {
  transform: scale(1.05);
}

.item-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(transparent 40%, rgba(0,0,0,0.85));
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  padding: 16px;
  opacity: 0;
  transition: opacity 0.3s;
}

.gallery-item:hover .item-overlay {
  opacity: 1;
}

.item-type {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: #b49cff;
  margin-bottom: 4px;
}

.item-prompt {
  font-size: 13px;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
