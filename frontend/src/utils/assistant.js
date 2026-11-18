import { resolveGenerationStatus } from './generation'

export function isAssistantReply(message) {
  return message?.role === 'assistant' && !message.record && !message.recordId && !message.videoTask &&
    !!(message.assistant || message.metadata)
}

export function messageStatus(message) {
  if (isAssistantReply(message)) return message.status || 'processing'
  return resolveGenerationStatus(message?.record || { status: message?.status })
}
