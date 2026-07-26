export const POLICY_ADVICE_PREFIX = 'CONTENT_POLICY_ADVICE:'
const LEGACY_POLICY_ADVICE_PREFIX = '内容安全提示：'

export function hasGenerationResult(record) {
  return !!(record?.imagePath || record?.imageUrl || record?.remoteImageAvailable)
}

export function resolveGenerationStatus(record) {
  if (hasGenerationResult(record)) return 'completed'
  const status = String(record?.status || '').toLowerCase()
  if (status === 'failed') return 'failed'
  return 'processing'
}

export function extractPolicyAdvice(text) {
  if (!text) return ''
  const markerIndex = text.indexOf(POLICY_ADVICE_PREFIX)
  if (markerIndex >= 0) {
    return text.slice(markerIndex + POLICY_ADVICE_PREFIX.length).trim()
  }
  const legacyIndex = text.indexOf(LEGACY_POLICY_ADVICE_PREFIX)
  if (legacyIndex >= 0) {
    return text.slice(legacyIndex + LEGACY_POLICY_ADVICE_PREFIX.length).trim()
  }
  return ''
}

export function friendlyGenerationError(message, record = null) {
  const text = message || ''
  const policyAdvice = extractPolicyAdvice(text)
  if (policyAdvice) return `${POLICY_ADVICE_PREFIX}${policyAdvice}`

  const rawApiError = /API|503|model_not_found|No available channel|request id|distributor|GPT-Pro/i.test(text)
  if (record?.generationType === 'img2img' || rawApiError) {
    return '生成失败，请更换图片重试'
  }
  return text || '生成失败'
}
