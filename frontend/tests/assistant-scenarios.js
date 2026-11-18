import { createPinia, setActivePinia } from 'pinia'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { useConversationsStore } from '../src/stores/conversations'
import ChatMessage from '../src/components/chat/ChatMessage.vue'
import api from '../src/api'

export async function run(assert) {
  setActivePinia(createPinia())
  const store = useConversationsStore()
  const conversation = store.createConversation('prompt_assistant')
  const exchange = store.addExchange(conversation.id, { prompt: '保留人物，改为海边', type: 'prompt_assistant' })
  assert.equal(exchange.assistantMsg.id, `assistant-${exchange.userMsg.id}`)

  let html = await renderToString(createSSRApp(ChatMessage, { message: exchange.assistantMsg, assistantMode: true }))
  assert.ok(html.includes('正在整理思路'))
  assert.ok(!html.includes('正在认真画'))

  store.updateAssistantMessage(exchange.assistantMsg.id, {
    reply: '保留人物，背景改为海边。', prompt: 'Same person on a beach',
    questions: ['需要夕阳吗？'], negativePrompt: 'blur'
  })
  html = await renderToString(createSSRApp(ChatMessage, { message: exchange.assistantMsg, assistantMode: true }))
  assert.ok(html.includes('Same person on a beach'))
  assert.ok(html.includes('保留人物，背景改为海边。'))
  assert.ok(!html.includes('assistant-loading'), 'completed text reply must not show a spinner or waiting result')
  assert.ok(html.includes('需要夕阳吗？'))

  let synced
  api.defaults.adapter = async config => {
    synced = JSON.parse(config.data)
    // Simulate a stale browser sync response during reply completion.
    const stale = structuredClone(synced)
    const message = stale.conversations[0].messages[1]
    message.content = ''
    message.metadata = '{"kind":"prompt_assistant"}'
    message.status = 'processing'
    return { data: { code: 200, data: stale }, status: 200, statusText: 'OK', headers: {}, config }
  }
  localStorage.setItem('authToken', 'test-token')
  assert.equal(await store.syncRemote(), true)
  assert.equal(synced.conversations[0].messages[1].status, 'completed')
  assert.equal(store.activeConversation.messages[1].content, '保留人物，背景改为海边。')
  assert.equal(store.activeConversation.messages[1].status, 'completed')

  // Reload on another device from a real, completed server reply.
  api.defaults.adapter = async config => ({
    data: { code: 200, data: synced }, status: 200, statusText: 'OK', headers: {}, config
  })
  store.resetSession()
  await store.loadRemote()
  const restored = store.activeConversation.messages[1]
  assert.equal(restored.assistant.prompt, 'Same person on a beach')
  assert.equal(restored.status, 'completed')

  store.failAssistantMessage(restored.id, '提示词助手 API 连接超时，请重试')
  html = await renderToString(createSSRApp(ChatMessage, { message: restored, assistantMode: true }))
  assert.ok(html.includes('助手回复失败'))
  assert.ok(html.includes('提示词助手 API 连接超时，请重试'))
  assert.ok(!html.includes('assistant-loading'))
  store.resetSession()
  localStorage.removeItem('authToken')
  console.log('PASS: text reply rendering, stable message IDs, completed sync, stale sync protection, cross-device history, explicit failures')
}
