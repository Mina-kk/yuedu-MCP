// Real Chromium gestures over the production App, components and CSS.
// Run with PLAYWRIGHT_MODULE pointing at an installed playwright package.
// Keep the entire Node/Chromium process tree inside the shared test budget.
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const viewportWidth = Number(process.env.LOGS_WIDTH || 390)
const theme = process.env.LOGS_THEME || 'light'
const evidence = resolve(process.env.LOGS_EVIDENCE || `${process.env.TMPDIR || root}/logs-browser-evidence`)
await mkdir(evidence, { recursive: true })
const server = await createServer({ root, server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' })
let browser
const observations = []
try {
  await server.listen()
  browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu', '--renderer-process-limit=1'] })
  const context = await browser.newContext({ viewport: { width: viewportWidth, height: 844 }, hasTouch: true, isMobile: true, deviceScaleFactor: 1, timezoneId: 'Asia/Shanghai' })
  context.setDefaultTimeout(20000)
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(({ theme }) => {
    const result = data => ({ content: [{ type: 'text', text: JSON.stringify(data) }] })
    window.__logsQA = { calls: [], exports: [], deleted: [], failIds: [], holdDelete: false, holdList: false }
    window.StudioBridge = {
      postMessage(raw) {
        const req = JSON.parse(raw)
        window.__logsQA.calls.push(req)
        let data = {}
        if (req.method === 'app.bootstrap') data = { appearance: { theme, palette: 'lime' }, capabilities: {}, service: { running: false } }
        if (req.method === 'tools.list') data = { tools: [] }
        if (req.method === 'tools.call') {
          const { name } = req.params
          if (name === 'get_http_logs' || name === 'get_logs') data = result({ total: 40, items: Array.from({ length: 20 }, (_, i) => ({ id: i + 1, url: `https://example.test/qa/${i + 1}`, message: `QA log ${i + 1}` })).filter(row => !window.__logsQA.deleted.includes(String(row.id))) })
          else if (name === 'get_http_log') data = result({ id: req.params.arguments.id, contextId: 'qa-logs', durationMs: 3028,
            error: 'javax.net.ssl.SSLHandshakeException: connection closed' + Array.from({ length: 24 }, (_, i) => `\n\tat com.android.org.conscrypt.ConscryptFileDescriptorSocket.startHandshake(ConscryptFileDescriptorSocket.java:${100 + i})`).join(''),
            url: 'https://example.test/' + 'very-long-path-'.repeat(25), endMarker: 'DETAIL_END_QA' })
          else data = result({ items: [] })
        }
        if (req.method === 'file.export') { window.__logsQA.exports.push(req.params); data = { saved: true } }
        const respond = () => window.StudioBridge.onmessage?.({ data: JSON.stringify({ id: req.id, ok: true, result: data }) })
        if (req.method === 'logs.delete') {
          const { kind, ids } = req.params
          const deletedIds = ids.filter(id => !window.__logsQA.failIds.includes(id))
          data = { kind, deletedIds, missingIds: [], failed: ids.filter(id => window.__logsQA.failIds.includes(id)).map(id => ({ id, code: 'delete_failed' })) }
          const finish = () => { window.__logsQA.deleted.push(...deletedIds); respond() }
          if (window.__logsQA.holdDelete) window.__logsQA.releaseDelete = finish
          else queueMicrotask(finish)
        } else if (req.method === 'tools.call' && req.params.name === 'get_http_logs' && window.__logsQA.holdList) window.__logsQA.releaseList = respond
        else queueMicrotask(respond)
      }
    }
  }, { theme })
  await page.goto(server.resolvedUrls.local[0])
  await page.getByRole('navigation', { name: '主导航' }).getByRole('button', { name: '日志', exact: true }).tap()
  await page.getByRole('button', { name: 'HTTP', exact: true }).tap()
  await page.locator('.log-row').first().waitFor()
  const cdp = await context.newCDPSession(page)

  async function swipe(start, end) {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...start, radiusX: 2, radiusY: 2, force: 1, id: 1 }] })
    for (let step = 1; step <= 16; step++) {
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: start.x + (end.x - start.x) * step / 16, y: start.y + (end.y - start.y) * step / 16, radiusX: 2, radiusY: 2, force: 1, id: 1 }] })
      await page.waitForTimeout(16)
    }
    // Pause the finger before release so a residual fling cannot race the row tap.
    await page.waitForTimeout(160)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    let last = -1, stable = 0
    for (let step = 0; step < 30 && stable < 4; step++) {
      await page.waitForTimeout(60)
      const top = await page.locator('.page-body').evaluate(el => el.scrollTop)
      stable = top === last ? stable + 1 : 0
      last = top
    }
  }
  async function mainSwipe(direction = 'up', onBody = false) {
    const points = await page.evaluate(({ direction, onBody }) => {
      const main = document.querySelector('.page-body').getBoundingClientRect()
      const x = main.x + main.width / 2
      const low = main.top + main.height * .78, high = main.top + main.height * .28
      const y = direction === 'up' ? low : high
      const target = document.elementFromPoint(x, y)
      if (onBody && !target?.closest('.log-detail pre')) throw new Error(`Gesture missed body: ${target?.outerHTML.slice(0, 200)}`)
      return { start: { x, y }, end: { x, y: direction === 'up' ? high : low } }
    }, { direction, onBody })
    await swipe(points.start, points.end)
  }
  async function visibleHit(selector) {
    return page.locator(selector).evaluate(el => {
      const r = el.getBoundingClientRect(), m = document.querySelector('.page-body').getBoundingClientRect()
      const x = r.x + r.width / 2, y = r.y + r.height / 2
      return r.top >= m.top && r.bottom <= m.bottom && el.contains(document.elementFromPoint(x, y))
    })
  }

  // Start from a genuinely swiped list, not a direct scrollTop assignment.
  await mainSwipe()
  const listTop = await page.locator('.page-body').evaluate(el => el.scrollTop)
  assert.ok(listTop > 50, `List did not scroll: ${listTop}`)
  const row = await page.locator('.log-row').evaluateAll(rows => {
    const m = document.querySelector('.page-body').getBoundingClientRect()
    return rows.findIndex(el => { const r = el.getBoundingClientRect(); return r.top > m.top + 8 && r.bottom < m.bottom - 8 })
  })
  assert.ok(row >= 0)
  await page.locator('.log-row').nth(row).tap()
  await page.locator('.log-detail pre').waitFor()
  await page.waitForTimeout(150)
  assert.equal(await page.locator('.page-body').evaluate(el => el.scrollTop), 0)
  assert.ok((await page.locator('.log-detail pre').textContent()).includes('\\n\\t'))
  const before = await page.locator('.page-body').evaluate(el => el.scrollTop)
  await mainSwipe('up', true)
  const after = await page.locator('.page-body').evaluate(el => el.scrollTop)
  const styles = await page.locator('.log-detail pre').evaluate(el => ({ overflowY: getComputedStyle(el).overflowY, overscroll: getComputedStyle(el).overscrollBehaviorY, height: el.clientHeight, scrollHeight: el.scrollHeight, userSelect: getComputedStyle(el).userSelect }))
  observations.push({ test: 'detail-touch', before, after, styles, listTop })
  await page.screenshot({ path: `${evidence}/detail-first-swipe.png` })
  console.log(JSON.stringify(observations.at(-1)))
  assert.ok(after > before + 40, `Swipe starting inside long HTTP JSON must scroll page-body; before=${before}, after=${after}`)
  assert.equal(styles.overflowY, 'visible', 'Detail must not be a nested scroll container')
  assert.notEqual(styles.userSelect, 'none', 'Log text must remain selectable')
  await mainSwipe('down', true)
  assert.ok(await page.locator('.page-body').evaluate(el => el.scrollTop) < after - 40, 'Reverse touch swipe must scroll back up')
  for (let count = 0; count < 50 && !await visibleHit('.log-detail .log-detail-actions'); count++) await mainSwipe('up', true)
  assert.ok(await visibleHit('.log-detail .log-detail-actions'), 'Bottom export and return must be reachable by touch')
  await page.screenshot({ path: `${evidence}/detail-bottom.png` })
  await page.getByRole('button', { name: '导出详情', exact: true }).tap()
  assert.ok(await page.evaluate(() => window.__logsQA.exports.at(-1)?.text.includes('DETAIL_END_QA')))
  await page.locator('.log-detail-actions').getByRole('button', { name: '返回日志列表', exact: true }).tap()
  assert.equal(await page.locator('.page-body').evaluate(el => el.scrollTop), listTop)
  for (const width of [275, 320, 390, 480, 900]) {
    await page.setViewportSize({ width, height: 844 })
    for (let count = 0; count < 12 && await page.locator('.page-body').evaluate(el => el.scrollTop) > 0; count++) await mainSwipe('down')
    const tabs = await page.locator('.page > .section > .segmented').first().evaluate(el => {
      const rect = el.getBoundingClientRect()
      const buttons = [...el.querySelectorAll('button')].map(button => {
        const r = button.getBoundingClientRect(), s = getComputedStyle(button)
        return { text: button.textContent.trim(), x: r.x, y: r.y, width: r.width, height: r.height, align: s.justifyContent }
      })
      return { x: rect.x, width: rect.width, buttons, overflow: document.documentElement.scrollWidth > innerWidth || document.querySelector('.page-body').scrollWidth > innerWidth }
    })
    observations.push({ test: 'equal-tabs', viewportWidth: width, ...tabs })
    console.log(JSON.stringify(observations.at(-1)))
    assert.equal(tabs.buttons.length, 5)
    assert.equal(tabs.overflow, false, `${width}px must not overflow horizontally`)
    assert.ok(Math.max(...tabs.buttons.map(b => b.y)) - Math.min(...tabs.buttons.map(b => b.y)) < 1, 'All five categories stay on one row')
    assert.ok(Math.max(...tabs.buttons.map(b => b.width)) - Math.min(...tabs.buttons.map(b => b.width)) < 1, 'Five categories must be equal width')
    assert.ok(Math.abs((tabs.buttons[0].x + tabs.buttons.at(-1).x + tabs.buttons.at(-1).width) / 2 - (tabs.x + tabs.width / 2)) < 1, 'Categories must fill the bar symmetrically')
    assert.ok(tabs.buttons.every(button => button.align === 'center'))
    await page.screenshot({ path: `${evidence}/categories-${width}.png` })
  }
  await page.setViewportSize({ width: viewportWidth, height: 844 })
  await page.getByRole('button', { name: '删除', exact: true }).first().tap()
  await page.getByRole('dialog', { name: '确认删除日志' }).waitFor()
  await page.waitForTimeout(350)
  const modal = await page.getByRole('dialog', { name: '确认删除日志' }).evaluate(el => {
    const panel = el.getBoundingClientRect(), style = getComputedStyle(el)
    const layer = document.querySelector('.delete-overlay')
    const covered = ['.shell-head', '.tabbar'].map(selector => {
      const box = document.querySelector(selector).getBoundingClientRect()
      return layer.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2))
    })
    const buttons = [...el.querySelectorAll('button')].map(button => {
      const r = button.getBoundingClientRect()
      return { width: r.width, x: r.x, right: r.right, top: r.top, bottom: r.bottom }
    })
    return { topLevel: !document.querySelector('.app-shell').contains(el), covered, buttons, x: panel.x, right: panel.right, padding: parseFloat(style.paddingLeft), radius: parseFloat(style.borderRadius), background: style.backgroundColor, focusedCancel: document.activeElement?.textContent.trim() === '取消', inert: document.querySelector('.app-shell').inert }
  })
  observations.push({ test: 'modal-layer', ...modal })
  console.log(JSON.stringify(observations.at(-1)))
  await page.screenshot({ path: `${evidence}/modal.png` })
  assert.equal(modal.topLevel, true, 'Modal must escape main/app-shell stacking context')
  assert.ok(modal.covered.every(Boolean), 'Overlay must cover header and navigation')
  assert.ok(modal.x >= 16 && modal.right <= viewportWidth - 16)
  assert.ok(modal.padding >= 16 && modal.radius >= 12, 'Dialog needs real panel padding and corners')
  assert.equal(modal.focusedCancel, true)
  assert.equal(modal.inert, true)
  assert.ok(Math.abs(modal.buttons[0].width - modal.buttons[1].width) < 1, 'Modal buttons must be equal width')
  assert.ok(modal.buttons[0].x >= modal.x + 16 && modal.buttons[1].right <= modal.right - 16)
  assert.equal(modal.buttons[0].top, modal.buttons[1].top)
  const frozenTop = await page.locator('.page-body').evaluate(el => el.scrollTop)
  await swipe({ x: viewportWidth / 2, y: 220 }, { x: viewportWidth / 2, y: 100 })
  assert.equal(await page.locator('.page-body').evaluate(el => el.scrollTop), frozenTop, 'Modal must freeze background touch scrolling')
  await page.keyboard.press('Tab')
  assert.equal(await page.evaluate(() => document.activeElement.textContent.trim()), '确认删除')
  await page.keyboard.press('Tab')
  assert.equal(await page.evaluate(() => document.activeElement.textContent.trim()), '取消')
  await page.keyboard.press('Shift+Tab')
  assert.equal(await page.evaluate(() => document.activeElement.textContent.trim()), '确认删除')
  await page.keyboard.press('Escape')
  assert.equal(await page.locator('.delete-overlay').count(), 0)
  await page.getByRole('button', { name: '删除', exact: true }).first().tap()
  await page.evaluate(() => window.dispatchEvent(new CustomEvent('studio:back')))
  await page.locator('.delete-overlay').waitFor({ state: 'detached' })
  await page.getByRole('button', { name: '删除', exact: true }).first().tap()
  await page.getByRole('button', { name: '取消', exact: true }).tap()
  assert.equal(await page.evaluate(() => window.__logsQA.calls.filter(call => call.method === 'logs.delete').length), 0)

  // Exercise actual App back handling while an asynchronous delete is pending.
  await page.evaluate(() => { window.__logsQA.holdDelete = true; window.__logsQA.failIds = ['1'] })
  await page.getByRole('button', { name: '删除', exact: true }).first().tap()
  await page.getByRole('button', { name: '确认删除', exact: true }).tap()
  assert.equal(await page.getByRole('dialog', { name: '确认删除日志' }).getAttribute('aria-busy'), 'true')
  assert.equal(await page.getByRole('button', { name: '取消', exact: true }).isDisabled(), true)
  assert.equal(await page.getByRole('button', { name: '正在删除', exact: true }).isDisabled(), true)
  await page.keyboard.press('Escape')
  await page.evaluate(() => window.dispatchEvent(new CustomEvent('studio:back')))
  assert.equal(await page.locator('.delete-overlay').count(), 1)
  await page.evaluate(() => window.__logsQA.releaseDelete())
  await page.locator('.delete-overlay').waitFor({ state: 'detached' })
  assert.equal(await page.getByRole('checkbox', { name: '选择日志 1', exact: true }).isChecked(), true)
  assert.ok((await page.locator('[role="status"]').allTextContents()).join(' ').includes('delete_failed'))
  await page.evaluate(() => { window.__logsQA.holdDelete = false; window.__logsQA.failIds = [] })
  await page.getByRole('button', { name: '删除所选', exact: true }).tap()
  await page.getByRole('button', { name: '确认删除', exact: true }).tap()
  await page.locator('.delete-overlay').waitFor({ state: 'detached' })
  await page.waitForFunction(() => window.__logsQA.deleted.includes('1'))
  assert.equal(await page.getByRole('checkbox', { name: '选择日志 1', exact: true }).count(), 0)
  assert.equal(await page.evaluate(() => document.querySelector('.app-shell').inert), false)
  observations.push({ test: 'modal-cancel-escape-system-back-busy-failed-retry', passed: true, viewportWidth, theme })

  for (let count = 0; count < 15 && await page.locator('.page-body').evaluate(el => el.scrollTop) > 0; count++) await mainSwipe('down')
  await page.clock.setFixedTime(new Date('2026-12-31T16:15:00Z'))
  await page.getByRole('button', { name: '今天', exact: true }).tap()
  assert.equal(await page.locator('.log-date-filter time').textContent(), '2027-01-01')
  await page.getByRole('searchbox', { name: '过滤日志' }).fill('example.test')
  await page.getByRole('button', { name: '排除抓包事务', exact: true }).tap()
  await page.evaluate(() => { window.__logsQA.holdList = true })
  await page.getByRole('button', { name: '上一天', exact: true }).tap()
  const latestArgs = await page.evaluate(() => window.__logsQA.calls.filter(call => call.method === 'tools.call' && call.params.name === 'get_http_logs').at(-1).params.arguments)
  assert.deepEqual(latestArgs, { limit: 20, offset: 0, query: 'example.test', date: '2026-12-31', includeCapture: true })
  for (const name of ['上一天', '今天', '下一天', '全部日期']) assert.equal(await page.getByRole('button', { name, exact: true }).isDisabled(), true)
  await page.evaluate(() => { window.__logsQA.holdList = false; window.__logsQA.releaseList() })
  await page.getByRole('button', { name: '下一天', exact: true }).tap()
  assert.equal(await page.locator('.log-date-filter time').textContent(), '2027-01-01')
  await page.getByRole('button', { name: '全部日期', exact: true }).tap()
  const allArgs = await page.evaluate(() => window.__logsQA.calls.filter(call => call.method === 'tools.call' && call.params.name === 'get_http_logs').at(-1).params.arguments)
  assert.equal('date' in allArgs, false)
  observations.push({ test: 'local-date-filters', passed: true, viewportWidth, theme, latestArgs })
  assert.deepEqual(errors, [])
  observations.push({ test: 'detail-bottom-export-return', passed: true })
  console.log(`PASS: touch scroll/export/return, five widths, modal locks/back/retry, local dates (${viewportWidth}px ${theme})`)
} catch (error) {
  observations.push({ failed: error.message })
  console.error(error)
  process.exitCode = 1
} finally {
  await writeFile(`${evidence}/results.json`, JSON.stringify(observations, null, 2))
  await browser?.close()
  await server.close()
}
