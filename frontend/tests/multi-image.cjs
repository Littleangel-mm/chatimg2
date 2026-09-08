const { chromium } = require('playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')

async function main() {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' })
  const output = path.join(os.tmpdir(), 'chatimg2-multi-image-ui')
  fs.mkdirSync(output, { recursive: true })
  try {
    for (const width of [1440, 390]) {
      const context = await browser.newContext({ viewport: { width, height: 900 } })
      const page = await context.newPage()
      const errors = []
      page.on('pageerror', error => errors.push(error.message))
      let submitted = null
      let remote = { conversations: [], deletedClientIds: [] }
      const user = { id: 7, email: 'test@qq.com', role: 'USER', totalCredits: 100, usedCredits: 0, remainingCredits: 100 }
      await page.addInitScript(user => {
        localStorage.setItem('authToken', 'ui-test')
        localStorage.setItem('authUser', JSON.stringify(user))
      }, user)
      await page.route('**/api/**', async route => {
        const request = route.request()
        const url = new URL(request.url())
        if (!url.pathname.startsWith('/api/')) return route.continue()
        let data = []
        if (url.pathname === '/api/auth/me') data = user
        if (url.pathname === '/api/chat/sync') {
          if (request.method() === 'POST') remote = request.postDataJSON()
          data = remote
        }
        if (url.pathname === '/api/user/generate') {
          submitted = request.postDataBuffer().toString()
          const message = submitted.match(/name="clientMessageId"\r\n\r\n([^\r]+)/)?.[1]
          data = { id: 12, clientMessageId: message, status: 'completed', generationType: 'img2img',
            sourceImagePath: 'sources/test/first.png', sourceImagePaths: ['sources/test/first.png', 'sources/test/second.png'],
            imagePath: 'test/result.png', taskCode: 'UI-TEST', creditsCost: 20 }
        }
        await route.fulfill({ json: { code: 200, data } })
      })
      await page.goto(process.env.TEST_URL || 'http://127.0.0.1:5175')
      if (width < 768) await page.locator('.sidebar-toggle').click()
      await page.getByRole('button', { name: '图生图', exact: true }).click()
      const dataUrl = await page.evaluate(() => {
        const canvas = document.createElement('canvas')
        canvas.width = canvas.height = 64
        const ctx = canvas.getContext('2d')
        ctx.fillStyle = '#c03859'
        ctx.fillRect(0, 0, 64, 64)
        ctx.fillStyle = '#30b697'
        ctx.fillRect(16, 16, 32, 32)
        return canvas.toDataURL('image/png')
      })
      const png = Buffer.from(dataUrl.split(',')[1], 'base64')
      await page.route('**/img/**', route => route.fulfill({ contentType: 'image/png', body: png }))
      const files = ['first.png', 'second.png'].map(name => ({ name, mimeType: 'image/png', buffer: png }))
      const input = page.locator('.upload-row input[type=file]')
      await input.setInputFiles(files)
      await page.waitForFunction(() => document.querySelectorAll('.upload-chip img').length === 2)
      await page.locator('.upload-chip .clear').first().click()
      await page.waitForFunction(() => document.querySelectorAll('.upload-chip img').length === 1)
      await input.setInputFiles(files[0])
      await page.waitForFunction(() => document.querySelectorAll('.upload-chip img').length === 2)
      await page.screenshot({ path: path.join(output, 'references-' + width + '.png') })
      await page.locator('textarea.prompt').fill('Combine both reference images')
      await page.locator('.send-btn').click()
      await page.waitForFunction(() => document.querySelectorAll('.source-thumb').length === 2
        && [...document.querySelectorAll('.source-thumb')].every(img => img.complete && img.naturalWidth > 0))
      await page.waitForFunction(() => {
        const img = document.querySelector('.assistant-image img')
        return img && img.complete && img.naturalWidth > 0
      })
      assert.equal((submitted.match(/name="sourceImages"/g) || []).length, 2)
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
      assert.deepEqual(errors, [])
      await page.screenshot({ path: path.join(output, 'result-' + width + '.png') })
      console.log('PASS', width, 'select/remove/submit two references; both previews and result render')
      await context.close()
    }
    console.log('Screenshots:', output)
  } finally {
    await browser.close()
  }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
