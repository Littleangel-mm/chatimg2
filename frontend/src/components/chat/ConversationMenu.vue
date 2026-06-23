<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'

const props = defineProps({
  conversation: { type: Object, required: true }
})

const emit = defineEmits(['rename', 'delete'])

const open = ref(false)
const renaming = ref(false)
const renameValue = ref('')
const menuRef = ref(null)
const dropdownRef = ref(null)
const dropdownStyle = ref({})

function updateDropdownPos() {
  if (!menuRef.value) return
  const rect = menuRef.value.getBoundingClientRect()
  dropdownStyle.value = {
    top: `${rect.bottom + 4}px`,
    left: `${Math.max(8, rect.right - 140)}px`
  }
}

function toggle(e) {
  e.stopPropagation()
  open.value = !open.value
  if (open.value) nextTick(updateDropdownPos)
}

function startRename() {
  open.value = false
  renaming.value = true
  renameValue.value = props.conversation.title
  nextTick(() => menuRef.value?.querySelector('.rename-input')?.focus())
}

function confirmRename() {
  const title = renameValue.value.trim()
  if (title) emit('rename', props.conversation.id, title)
  renaming.value = false
}

function cancelRename() {
  renaming.value = false
}

function onDelete() {
  open.value = false
  if (confirm(`确定删除「${props.conversation.title}」？`)) {
    emit('delete', props.conversation.id)
  }
}

function onClickOutside(e) {
  if (menuRef.value?.contains(e.target)) return
  if (dropdownRef.value?.contains(e.target)) return
  open.value = false
}

onMounted(() => {
  document.addEventListener('click', onClickOutside)
  window.addEventListener('resize', updateDropdownPos)
  window.addEventListener('scroll', updateDropdownPos, true)
})

onUnmounted(() => {
  document.removeEventListener('click', onClickOutside)
  window.removeEventListener('resize', updateDropdownPos)
  window.removeEventListener('scroll', updateDropdownPos, true)
})
</script>

<template>
  <div ref="menuRef" class="conv-menu" @click.stop>
    <div v-if="renaming" class="rename-box" @click.stop>
      <input
        v-model="renameValue"
        class="rename-input"
        maxlength="40"
        @keyup.enter="confirmRename"
        @keyup.esc="cancelRename"
      />
      <button class="rename-btn ok" type="button" @click="confirmRename">✓</button>
      <button class="rename-btn" type="button" @click="cancelRename">✕</button>
    </div>
    <template v-else>
      <button
        type="button"
        class="menu-trigger"
        :class="{ active: open }"
        title="更多"
        aria-label="对话菜单"
        @click="toggle"
      >
        <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
          <circle cx="3" cy="8" r="1.5" />
          <circle cx="8" cy="8" r="1.5" />
          <circle cx="13" cy="8" r="1.5" />
        </svg>
      </button>
      <Teleport to="body">
        <div v-if="open" ref="dropdownRef" class="dropdown" :style="dropdownStyle" @click.stop>
          <button type="button" @click="startRename">重命名</button>
          <button type="button" class="danger" @click="onDelete">删除对话</button>
        </div>
      </Teleport>
    </template>
  </div>
</template>

<style scoped>
.conv-menu {
  position: relative;
  flex-shrink: 0;
}

.menu-trigger {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.15s, background 0.15s, color 0.15s;
}

.menu-trigger:hover,
.menu-trigger.active {
  background: var(--hover-strong);
  color: var(--text);
}

.rename-box {
  display: flex;
  gap: 4px;
  align-items: center;
}

.rename-input {
  width: 120px;
  padding: 4px 8px;
  font-size: 12px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text);
  outline: none;
}

.rename-btn {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: var(--hover);
  color: var(--text-muted);
  font-size: 12px;
}

.rename-btn.ok {
  color: var(--success);
}
</style>

<style>
.dropdown {
  position: fixed;
  min-width: 140px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 4px;
  box-shadow: var(--shadow-lg);
  z-index: 1000;
}

.dropdown button {
  display: block;
  width: 100%;
  text-align: left;
  padding: 8px 12px;
  border-radius: 6px;
  background: transparent;
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
}

.dropdown button:hover {
  background: var(--hover);
}

.dropdown button.danger {
  color: var(--danger);
}
</style>
