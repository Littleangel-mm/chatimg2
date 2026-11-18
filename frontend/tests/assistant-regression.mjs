import assert from 'node:assert/strict'
import { createServer } from 'vite'

const memory = new Map()
globalThis.localStorage = {
  getItem: key => memory.get(key) ?? null,
  setItem: (key, value) => memory.set(key, String(value)),
  removeItem: key => memory.delete(key)
}
const server = await createServer({ server: { middlewareMode: true } })
try {
  const { run } = await server.ssrLoadModule('/tests/assistant-scenarios.js')
  await run(assert)
} finally {
  await server.close()
}
