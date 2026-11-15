<script setup>
import { onBeforeUnmount, watch } from 'vue'

const props = defineProps({
  src: { type: String, default: '' },
  alt: { type: String, default: '图片预览' }
})

const emit = defineEmits(['close'])

function close() {
  emit('close')
}

function onKeydown(event) {
  if (event.key === 'Escape') close()
}

watch(
  () => props.src,
  (src) => {
    document.body.style.overflow = src ? 'hidden' : ''
    if (src) {
      window.addEventListener('keydown', onKeydown)
    } else {
      window.removeEventListener('keydown', onKeydown)
    }
  }
)

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="lightbox-fade">
      <div
        v-if="src"
        class="image-lightbox"
        role="dialog"
        aria-modal="true"
        :aria-label="alt"
        @click="close"
      >
        <button class="image-lightbox-close" type="button" aria-label="关闭图片预览" @click.stop="close">
          &times;
        </button>
        <img :src="src" :alt="alt" @click.stop />
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.image-lightbox {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 56px 24px 24px;
  background: rgba(20, 16, 18, 0.82);
  backdrop-filter: blur(4px);
}

.image-lightbox img {
  display: block;
  width: auto;
  height: auto;
  max-width: min(94vw, 1440px);
  max-height: calc(100vh - 80px);
  object-fit: contain;
  border-radius: 6px;
  box-shadow: 0 18px 56px rgba(0, 0, 0, 0.42);
}

.image-lightbox-close {
  position: fixed;
  top: 18px;
  right: 22px;
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.36);
  border-radius: 50%;
  background: rgba(20, 16, 18, 0.7);
  color: #fff;
  font-size: 28px;
  line-height: 1;
  cursor: pointer;
}

.image-lightbox-close:hover {
  background: rgba(20, 16, 18, 0.92);
}

.lightbox-fade-enter-active,
.lightbox-fade-leave-active {
  transition: opacity 0.16s ease;
}

.lightbox-fade-enter-from,
.lightbox-fade-leave-to {
  opacity: 0;
}

@media (max-width: 640px) {
  .image-lightbox {
    padding: 52px 12px 12px;
  }

  .image-lightbox img {
    max-width: calc(100vw - 24px);
    max-height: calc(100vh - 64px);
  }

  .image-lightbox-close {
    top: 10px;
    right: 12px;
  }
}
</style>
