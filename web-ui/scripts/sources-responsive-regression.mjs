// Full production App + all production CSS; only the Android bridge is a fixture.
// Run Node/Chromium inside the task's shared, resource-limited systemd slice.
// SOURCE_WIDTHS / SOURCE_SCALES / SOURCE_THEMES narrow the default 36-case matrix.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const evidence = resolve(process.env.SOURCE_EVIDENCE || join(tmpdir(), 'sources-responsive-evidence'))
const widths = (process.env.SOURCE_WIDTHS || '275,320,360,390,480,900').split(',').map(Number)
const scales = (process.env.SOURCE_SCALES || '1,1.3,1.6').split(',').map(Number)
const themes = (process.env.SOURCE_THEMES || 'light,dark').split(',')
const updatedAt = Date.parse('2026-11-10T15:08:59Z')
const names = [
  '大帝书阁', '书海阁',
  '这是一个用于窄屏大字阅读的超长中文书源名称必须完整显示不能省略也不能逐字竖排'.repeat(2),
  'VeryLongEnglishBookSourceNameWithoutAnyWordBoundary'.repeat(3),
  'A very long English book source title with spaces that should wrap naturally without losing its full name',
]
const fixture = Array.from({ length: 20 }, (_, index) => ({
  projectId: `responsive-source-${index + 1}`,
  bookSourceName: names[index] || `横排书源 ${index + 1}`,
  bookSourceUrl: index === 2 || index === 3
    ? `https://source-${index + 1}.example.invalid/${'unbrokenpathsegment'.repeat(18)}?chapter=${'1234567890'.repeat(8)}`
    : `https://source-${index + 1}.example.invalid/books`,
  stage: 'VALIDATED', versionCount: 3, updatedAt,
}))
const results = { fixtureCount: fixture.length, expectedCases: widths.length * scales.length * themes.length, cases: [], errors: [] }
await mkdir(evidence, { recursive: true })
const profile = await mkdtemp(join(tmpdir(), 'sources-responsive-chrome-'))
// Concurrent shell work must not hot-reload the page halfway through a pointer test.
const server = await createServer({ root, server: { host: '127.0.0.1', port: 0, hmr: false }, logLevel: 'error' })
let chrome, ws, lastCase
const browserErrors = []
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const overallTimeout = setTimeout(() => {
  console.error('FAIL: sources-responsive overall timeout (295s)')
  chrome?.kill('SIGTERM')
  process.exit(124)
}, 295_000)

try {
  await server.listen()
  const origin = server.resolvedUrls.local[0]
  chrome = spawn(process.env.CHROMIUM_PATH || '/usr/bin/chromium', [
    '--headless', '--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu',
    '--disable-background-networking', '--no-first-run', '--no-default-browser-check',
    '--no-zygote', '--single-process', '--remote-allow-origins=*', '--remote-debugging-port=0',
    `--user-data-dir=${profile}`, 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] })
  let chromeLog = ''
  chrome.stderr.on('data', data => { chromeLog = (chromeLog + data).slice(-12000) })
  async function poll(fn, description = 'browser condition') {
    let cause
    for (let attempt = 0; attempt < 100; attempt++) {
      try { const value = await fn(); if (value) return value } catch (error) { cause = error.message }
      if (chrome.exitCode !== null) throw new Error(`Chromium exited: ${chromeLog}`)
      await delay(60)
    }
    throw new Error(`Timed out: ${description}${cause ? ` (${cause})` : ''}`)
  }
  const port = await poll(async () => Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]), 'Chromium port')
  const target = await poll(async () => (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find(item => item.type === 'page'), 'Chromium target')
  ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }) })
  let requestId = 0
  const pending = new Map()
  ws.addEventListener('message', event => {
    const response = JSON.parse(event.data)
    if (response.id) {
      const request = pending.get(response.id)
      if (!request) return
      pending.delete(response.id)
      clearTimeout(request.timer)
      response.error ? request.reject(new Error(JSON.stringify(response.error))) : request.resolve(response.result)
    } else if (response.method === 'Runtime.exceptionThrown') browserErrors.push(response.params.exceptionDetails)
  })
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++requestId
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 15000)
    pending.set(id, { resolve, reject, timer })
    ws.send(JSON.stringify({ id, method, params }))
  })
  async function evaluate(fn, arg) {
    const expression = `(${fn.toString()})(${JSON.stringify(arg) ?? ''})`
    const response = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (response.exceptionDetails) throw new Error(JSON.stringify(response.exceptionDetails))
    return response.result.value
  }
  async function pointer(point) {
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, ...point })
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, ...point })
  }
  async function click(selector, index = 0) {
    const geometry = await evaluate(({ selector, index }) => {
      const el = document.querySelectorAll(selector)[index]
      if (!el) throw new Error(`Missing target ${selector}[${index}]`)
      el.scrollIntoView({ block: 'center', inline: 'nearest' })
      const r = el.getBoundingClientRect(), main = document.querySelector('main').getBoundingClientRect()
      const point = { x: r.x + r.width / 2, y: r.y + r.height / 2 }
      const hit = document.elementFromPoint(point.x, point.y)
      return { point, rect: r.toJSON(), main: main.toJSON(), hit: el === hit || el.contains(hit),
        enabled: !el.disabled, inside: r.left >= 0 && r.right <= innerWidth && r.top >= 0 && r.bottom <= innerHeight &&
          (!el.closest('main') || (r.top >= main.top && r.bottom <= main.bottom)) }
    }, { selector, index })
    assert.ok(geometry.hit && geometry.inside && geometry.enabled, `Target inaccessible ${selector}[${index}]: ${JSON.stringify(geometry)}`)
    await pointer(geometry.point)
    await delay(35)
    return geometry
  }
  async function screenshot(name) {
    const shot = await send('Page.captureScreenshot', { format: 'png' })
    await writeFile(join(evidence, name), Buffer.from(shot.data, 'base64'))
  }
  // Text-only enlargement: snapshot *all* computed sizes before applying any.
  // This enlarges even px-based typography; rem-only root changes miss this bug.
  // Never change widths, zoom, overflow, wrapping, line clamps or font families.
  async function enlargeText(scale) {
    return evaluate(scale => {
      const elements = [...document.querySelectorAll('body, body *')].filter(el => el instanceof HTMLElement)
      // Restore first so newly mounted details/dialogs do not inherit an already
      // enlarged parent while their own baseline sizes are being measured.
      elements.forEach(el => {
        if (el.dataset.qaOriginalFont === undefined) el.dataset.qaOriginalFont = el.style.fontSize
        else el.style.fontSize = el.dataset.qaOriginalFont
      })
      const sizes = elements.map(el => parseFloat(getComputedStyle(el).fontSize))
      elements.forEach((el, index) => { el.style.fontSize = `${sizes[index] * scale}px` })
      return { actual: parseFloat(getComputedStyle(document.querySelector('.source-name')).fontSize), scale }
    }, scale)
  }
  await send('Page.enable')
  await send('Runtime.enable')
  results.browser = await send('Browser.getVersion')
  await send('Emulation.setTimezoneOverride', { timezoneId: 'Asia/Shanghai' })
  let initScript
  for (const theme of themes) for (const scale of scales) for (const width of widths) {
    const key = `${theme}-${width}-${scale}`
    lastCase = { key, width, scale, theme, geometry: null, interaction: null, failures: [] }
    results.cases.push(lastCase)
    if (initScript) await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: initScript })
    const init = ({ fixture, theme, key }) => {
      const text = value => ({ content: [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value) }] })
      window.sourceQA = { fixture, key, calls: [], deleted: [] }
      window.StudioBridge = { postMessage(raw) {
        const req = JSON.parse(raw)
        sourceQA.calls.push(req)
        let result = {}
        if (req.method === 'app.bootstrap') result = { service: { running: true }, versionName: '2.0.3-fixture', appearance: { theme, palette: 'lime' }, capabilities: {} }
        if (req.method === 'tools.list') result = { tools: [] }
        if (req.method === 'tools.call') {
          const { name, arguments: args } = req.params
          if (name === 'list_sources') {
            const rows = sourceQA.fixture.filter(row => !sourceQA.deleted.includes(row.projectId))
            const offset = args.offset || 0, limit = args.limit || 20
            result = text({ total: rows.length, hasMore: offset + limit < rows.length, items: rows.slice(offset, offset + limit) })
          } else if (name === 'get_source') {
            const row = sourceQA.fixture.find(row => row.projectId === args.key)
            result = text(JSON.stringify({ bookSourceName: row.bookSourceName, bookSourceUrl: row.bookSourceUrl, enabled: true }, null, 2))
          } else if (name === 'delete_projects') {
            sourceQA.deleted.push(...args.ids)
            result = text(`已删除 ${args.ids.length} 个项目`)
          } else result = text({ items: [] })
        }
        queueMicrotask(() => this.onmessage?.({ data: JSON.stringify({ id: req.id, ok: true, result }) }))
      } }
    }
    initScript = (await send('Page.addScriptToEvaluateOnNewDocument', { source: `(${init.toString()})(${JSON.stringify({ fixture, theme, key })})` })).identifier
    await send('Emulation.setDeviceMetricsOverride', { width, height: 844, deviceScaleFactor: 1, mobile: false })
    await send('Page.navigate', { url: origin })
    await poll(() => evaluate(key => window.sourceQA?.key === key && !!document.querySelector('nav button'), key), 'fresh production App navigation')
    await click('nav button', 1)
    await poll(() => evaluate(() => document.querySelectorAll('.source-row').length === 20), '20 production source rows')
    const typography = await enlargeText(scale)
    await delay(60)
    const geometry = await evaluate(() => {
      const box = el => el.getBoundingClientRect().toJSON()
      const glyphs = el => {
        const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
        const rects = [], lines = new Set()
        while (walker.nextNode()) {
          const node = walker.currentNode
          for (let index = 0; index < node.length; index++) {
            if (/\s/.test(node.textContent[index])) continue
            const range = document.createRange()
            range.setStart(node, index); range.setEnd(node, index + 1)
            const r = range.getBoundingClientRect()
            rects.push(r.toJSON()); lines.add(Math.round(r.top * 10) / 10)
          }
        }
        return { lineCount: lines.size, glyphCount: rects.length, left: Math.min(...rects.map(r => r.left)), right: Math.max(...rects.map(r => r.right)),
          top: Math.min(...rects.map(r => r.top)), bottom: Math.max(...rects.map(r => r.bottom)),
          maxLineGlyphs: Math.max(...[...lines].map(y => rects.filter(r => Math.abs(r.top - y) < .1).length)) }
      }
      const rows = [...document.querySelectorAll('.source-row')].map(row => {
        const name = row.querySelector('.source-name'), url = row.querySelector('.source-url'), meta = row.querySelector('.source-meta')
        return { name: { text: name.textContent, rect: box(name), ...glyphs(name), fontSize: parseFloat(getComputedStyle(name).fontSize),
            overflow: getComputedStyle(name).overflow, textOverflow: getComputedStyle(name).textOverflow, lineClamp: getComputedStyle(name).webkitLineClamp },
          url: { text: url.textContent, rect: box(url), ...glyphs(url) },
          meta: { rect: box(meta), children: [...meta.children].map(el => ({ text: el.textContent, rect: box(el), ...glyphs(el), fontSize: parseFloat(getComputedStyle(el).fontSize) })) },
          rect: box(row), clientWidth: row.clientWidth, scrollWidth: row.scrollWidth,
          contentWidth: row.clientWidth - parseFloat(getComputedStyle(row).paddingLeft) - parseFloat(getComputedStyle(row).paddingRight),
        }
      })
      const overflow = [...document.querySelectorAll('html, body, #app, .app-shell, main, .page, .source-controls, .source-controls > .section, .source-list, .source-entry, .source-row')]
        .filter(el => el.getClientRects().length && el.scrollWidth > el.clientWidth + 1)
        .map(el => ({ tag: el.tagName, className: el.className, clientWidth: el.clientWidth, scrollWidth: el.scrollWidth }))
      const area = document.querySelector('.page').getBoundingClientRect()
      const overflowDetails = overflow.length ? [...document.querySelectorAll('.source-controls *')]
        .filter(el => el.getClientRects().length && el.getBoundingClientRect().right > area.right + 1)
        .map(el => ({ tag: el.tagName, className: el.className, text: el.textContent.slice(0, 100), rect: box(el), parent: box(el.parentElement) })).slice(0, 30) : []
      return { rows, overflow, overflowDetails, viewport: innerWidth, theme: document.documentElement.dataset.mode,
        css: [...document.querySelectorAll('style[data-vite-dev-id]')].map(el => el.dataset.viteDevId) }
    })
    lastCase.geometry = geometry
    const check = (ok, message) => { if (!ok) lastCase.failures.push(message) }
    check(geometry.theme === theme, `Expected ${theme}, got ${geometry.theme}`)
    check(geometry.rows.length === fixture.length, 'Must render all 20 fixtures')
    check(geometry.overflow.length === 0, `Horizontal overflow: ${JSON.stringify(geometry.overflow)}`)
    for (const css of ['app.css', 'v8.css', 'v9.css', 'v10.css', 'shell.css', 'SourcesPage.vue']) check(geometry.css.some(path => path.includes(css)), `Missing production CSS ${css}`)
    check(Math.abs(typography.actual - 13.5 * scale) < .05, `Name font must really enlarge, not shrink: ${JSON.stringify(typography)}`)
    for (const [index, row] of geometry.rows.entries()) {
      const label = `row ${index + 1} (${fixture[index].bookSourceName.slice(0, 20)})`
      check(row.name.text === fixture[index].bookSourceName, `${label}: full name changed`)
      check(row.url.text === fixture[index].bookSourceUrl, `${label}: full URL changed`)
      check(Math.abs(row.name.rect.width - row.contentWidth) < 1, `${label}: title does not own the full content row (${row.name.rect.width}/${row.contentWidth}px)`)
      check(row.meta.rect.top >= row.name.rect.bottom - 1, `${label}: metadata competes beside title`)
      check(row.name.textOverflow !== 'ellipsis' && ['none', '0', ''].includes(row.name.lineClamp), `${label}: title must not be truncated`)
      for (const [part, text] of [['name', row.name], ['URL', row.url], ...row.meta.children.map((text, i) => [`metadata ${i}`, text])]) {
        check(text.glyphCount > 0 && text.left >= row.rect.left - 1 && text.right <= row.rect.right + 1, `${label}: ${part} glyphs escape card`)
        check(text.top >= text.rect.top - 1 && text.bottom <= text.rect.bottom + 1, `${label}: ${part} glyphs escape own text box`)
      }
      check(row.meta.children.map(child => child.text).join('|') === 'VALIDATED|3 版本|2026-11-10 23:08:59', `${label}: metadata or full timestamp missing`)
      if (index < 2) check(row.name.lineCount === 1, `${label}: short Chinese title must be horizontal, observed ${row.name.lineCount} lines`)
      if (index === 2 || index === 3) {
        check(row.name.lineCount > 1 && row.name.maxLineGlyphs >= 4, `${label}: long title must wrap without a single-character column`)
        check(row.url.lineCount > 1, `${label}: long URL must wrap`)
      }
    }
    // Capture actual cards, not an empty application shell. Full-page screenshots
    // cannot reveal off-screen rows inside main's scroll container.
    await evaluate(() => document.querySelector('.source-row').scrollIntoView({ block: 'start' }))
    await screenshot(`${key}-cards.png`)
    if (width === widths[0]) {
      await evaluate(() => document.querySelectorAll('.source-row')[2].scrollIntoView({ block: 'start' }))
      await screenshot(`${key}-long-title.png`)
    }
    // Exercise production handlers with actual pointer input from a scrolled list.
    // Geometrically broken RED cases skip interaction, not geometry assertions.
    if (!lastCase.failures.length) {
      await click('.source-entry > input[type="checkbox"]', 9)
      check(await evaluate(() => document.querySelectorAll('.source-entry > input')[9].checked), 'Checkbox did not select target')
      const rowHit = await click('.source-row', 9)
      const listTop = rowHit.rect.top - rowHit.main.top
      await poll(() => evaluate(() => !!document.querySelector('textarea[aria-label="书源 JSON"]')), 'source detail after row click')
      await enlargeText(scale)
      const detail = await evaluate(() => ({ top: document.querySelector('main').scrollTop,
        text: document.querySelector('textarea').value,
        listHidden: getComputedStyle(document.querySelector('.source-list').closest('.section')).display === 'none',
        key: sourceQA.calls.filter(req => req.method === 'tools.call' && req.params.name === 'get_source').at(-1)?.params.arguments.key }))
      check(detail.top === 0 && detail.listHidden, 'Detail must open at top and replace list')
      check(detail.key === fixture[9].projectId && JSON.parse(detail.text).bookSourceName === fixture[9].bookSourceName, 'Clicked source/detail mismatch')
      const backHit = await click('[data-detail-back]')
      await poll(() => evaluate(() => !document.querySelector('[data-detail-back]') && document.querySelectorAll('.source-row').length === 20), 'return to list')
      const returned = await evaluate(() => ({ offset: document.querySelectorAll('.source-row')[9].getBoundingClientRect().top - document.querySelector('main').getBoundingClientRect().top,
        checked: document.querySelectorAll('.source-entry > input')[9].checked, top: document.querySelector('main').scrollTop }))
      check(Math.abs(returned.offset - listTop) <= 1 && returned.top > 50 && returned.checked, 'Return must restore scrolled list and selection')
      const deleteHit = await click('.source-entry > button.btn', 9)
      await poll(() => evaluate(() => !!document.querySelector('[role="alertdialog"]')), 'delete confirmation')
      await enlargeText(scale)
      await delay(300)
      await evaluate(() => {
        const cancel = [...document.querySelectorAll('[role="alertdialog"] button')].find(el => el.textContent.trim() === '取消')
        if (!cancel) throw new Error('No cancel button')
        cancel.setAttribute('data-qa-cancel', '')
      })
      await click('[data-qa-cancel]')
      await poll(() => evaluate(() => !document.querySelector('[role="alertdialog"]')), 'cancel deletion')
      check(await evaluate(() => sourceQA.deleted.length === 0 && document.querySelectorAll('.source-row').length === 20), 'Cancel must not delete data')
      // The last card's delete action must remain reachable at the same size.
      const lastDeleteHit = await click('.source-entry > button.btn', 19)
      await poll(() => evaluate(() => !!document.querySelector('[role="alertdialog"]')), 'last source delete confirmation')
      await enlargeText(scale)
      await delay(300)
      await evaluate(() => [...document.querySelectorAll('[role="alertdialog"] button')].find(el => el.textContent.trim() === '取消').setAttribute('data-qa-cancel', ''))
      await click('[data-qa-cancel]')
      lastCase.interaction = { rowHit, backHit, deleteHit, lastDeleteHit, detail, returned, passed: true }
    }
    await writeFile(join(evidence, 'results.json'), JSON.stringify(results, null, 2))
    console.log(`${lastCase.failures.length ? 'FAIL' : 'PASS'} ${key}: ${geometry.rows.length} cards, ${lastCase.failures.length} layout/interaction failures${lastCase.failures.length ? `; ${lastCase.failures.slice(0, 4).join('; ')}` : ', selection/detail/return/delete-cancel hit-tested'}`)
  }
  results.errors.push(...browserErrors)
  assert.equal(results.cases.length, results.expectedCases, 'Incomplete matrix')
  const failed = results.cases.filter(row => row.failures.length)
  assert.equal(failed.length, 0, `${failed.length}/${results.expectedCases} cases failed; see ${evidence}/results.json`)
  assert.deepEqual(browserErrors, [], 'Unhandled production browser errors')
  console.log(`PASS: ${results.cases.length} real Chromium cases / ${results.cases.length * fixture.length} source cards; all production CSS; complete titles, metadata, URL wrapping and real pointer actions`)
} catch (error) {
  results.errors.push({ case: lastCase?.key, message: error.message })
  console.error(error)
  process.exitCode = 1
} finally {
  clearTimeout(overallTimeout)
  await writeFile(join(evidence, 'results.json'), JSON.stringify(results, null, 2))
  ws?.close()
  chrome?.kill('SIGTERM')
  await server.close()
  await delay(300)
  await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 })
}
