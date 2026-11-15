// Incremental UI208 evidence: production App/CSS, explicit bridge fixtures only.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const evidence = resolve(process.env.SOURCE_EVIDENCE || join(tmpdir(), 'ui208-mobile'))
const cases = [{ width: 390, theme: 'light', scale: 1 }, { width: 390, theme: 'dark', scale: 1 },
  { width: 320, theme: 'light', scale: 1.3 }, { width: 320, theme: 'dark', scale: 1.3 }]
  .filter(c => !process.env.MOBILE_CASE || `${c.theme}-${c.width}-${c.scale}` === process.env.MOBILE_CASE)
const names = ['纸间 · 简体', '纸间 · 简体', '这是一个用于窄屏大字阅读的超长中文书源名称必须完整显示不能省略也不能逐字竖排',
  'LongEnglishSourceNameWithoutWordBoundary'.repeat(2)]
const fixture = Array.from({ length: 24 }, (_, i) => ({ projectId: `ui208-fixture-${i + 1}`,
  bookSourceName: names[i] || `手机书源 ${i + 1}`, bookSourceUrl: `https://source-${i + 1}.example.invalid/${i === 2 ? 'longpath'.repeat(10) : 'books'}`,
  stage: 'VALIDATED', versionCount: 3, updatedAt: Date.parse('2026-11-12T11:48:24Z') - i * 1000 }))
const results = { kind: 'BROWSER_FIXTURE_NOT_ANDROID_EVIDENCE', expectedCases: cases.length, fixtureCount: fixture.length, cases: [], errors: [] }
await mkdir(evidence, { recursive: true })
const profile = await mkdtemp(join(tmpdir(), 'ui208-chrome-'))
const server = await createServer({ root, server: { host: '127.0.0.1', port: 0, hmr: false }, logLevel: 'error' })
let chrome, ws, lastCase, initScript
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const deadline = setTimeout(() => { chrome?.kill('SIGTERM'); process.exit(124) }, 150_000)
try {
  await server.listen()
  chrome = spawn(process.env.CHROMIUM_PATH || '/usr/bin/chromium', ['--headless', '--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu',
    '--disable-background-networking', '--no-first-run', '--no-default-browser-check', '--no-zygote', '--single-process',
    '--remote-allow-origins=*', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] })
  let chromeLog = ''
  chrome.stderr.on('data', data => { chromeLog = (chromeLog + data).slice(-6000) })
  async function poll(fn, description) {
    for (let i = 0; i < 100; i++) {
      if (chrome.exitCode !== null) throw new Error(`Chromium stopped: ${chromeLog}`)
      try { const value = await fn(); if (value) return value } catch { /* bounded readiness only */ }
      await delay(60)
    }
    throw new Error(`Timed out: ${description}`)
  }
  const port = await poll(async () => Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]), 'browser port')
  const target = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find(x => x.type === 'page')
  ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }) })
  let seq = 0
  const pending = new Map()
  ws.addEventListener('message', event => {
    const obj = JSON.parse(event.data)
    if (obj.method === 'Runtime.exceptionThrown') results.errors.push(obj.params.exceptionDetails)
    if (!obj.id) return
    const entry = pending.get(obj.id)
    if (!entry) return
    pending.delete(obj.id); clearTimeout(entry.timer)
    obj.error ? entry.reject(new Error(JSON.stringify(obj.error))) : entry.resolve(obj.result)
  })
  const cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++seq, timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 12000)
    pending.set(id, { resolve, reject, timer }); ws.send(JSON.stringify({ id, method, params }))
  })
  async function evaluate(fn, arg) {
    const result = await cdp('Runtime.evaluate', { expression: `(${fn.toString()})(${JSON.stringify(arg) ?? ''})`, returnByValue: true, awaitPromise: true })
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails))
    return result.result.value
  }
  async function click(selector, index = 0) {
    const info = await evaluate(({ selector, index }) => {
      const el = document.querySelectorAll(selector)[index]
      if (!el) throw new Error(`Missing ${selector}[${index}]`)
      el.scrollIntoView({ block: 'center', inline: 'nearest' })
      const r = el.getBoundingClientRect(), main = document.querySelector('main').getBoundingClientRect()
      const x = r.left + r.width / 2, y = r.top + r.height / 2, hit = document.elementFromPoint(x, y)
      return { x, y, rect: r.toJSON(), main: main.toJSON(), enabled: !el.disabled,
        hit: hit === el || el.contains(hit), visible: r.left >= 0 && r.right <= innerWidth && r.top >= 0 && r.bottom <= innerHeight &&
          (!el.closest('main') || (r.top >= main.top && r.bottom <= main.bottom)) }
    }, { selector, index })
    assert.ok(info.visible && info.hit && info.enabled, `Unreachable ${selector}: ${JSON.stringify(info)}`)
    await cdp('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, x: info.x, y: info.y })
    await cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, x: info.x, y: info.y })
    await delay(80)
    return info
  }
  async function byLabel(label, scope = 'main') {
    const ok = await evaluate(({ label, scope }) => {
      document.querySelectorAll('[data-ui208-click]').forEach(e => e.removeAttribute('data-ui208-click'))
      const matches = [...document.querySelector(scope).querySelectorAll('button')].filter(e => e.getClientRects().length && e.textContent.trim() === label)
      if (matches.length !== 1) return matches.length
      matches[0].setAttribute('data-ui208-click', ''); return true
    }, { label, scope })
    assert.equal(ok, true, `Unique visible button: ${label}, got ${ok}`)
    return click('[data-ui208-click]')
  }
  async function screenshot(name) {
    const r = await cdp('Page.captureScreenshot', { format: 'png' })
    await writeFile(join(evidence, name), Buffer.from(r.data, 'base64'))
  }
  async function enlarge(scale) {
    await evaluate(scale => {
      const es = [...document.querySelectorAll('body,body *')].filter(x => x instanceof HTMLElement)
      const sizes = es.map(e => parseFloat(getComputedStyle(e).fontSize))
      es.forEach((e, i) => { e.style.fontSize = `${sizes[i] * scale}px` })
    }, scale)
  }
  await cdp('Page.enable'); await cdp('Runtime.enable')
  results.browser = await cdp('Browser.getVersion')
  await cdp('Emulation.setTimezoneOverride', { timezoneId: 'Asia/Shanghai' })
  for (const settings of cases) {
    const key = `${settings.theme}-${settings.width}-${settings.scale}`
    lastCase = { key, ...settings, passed: false }; results.cases.push(lastCase)
    if (initScript) await cdp('Page.removeScriptToEvaluateOnNewDocument', { identifier: initScript })
    const init = ({ fixture, theme, key }) => {
      window.mobileQA = { key, fixture, calls: [], deleted: [], sessions: [] }
      const text = value => ({ content: [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value) }] })
      window.StudioBridge = { postMessage(raw) {
        const req = JSON.parse(raw); mobileQA.calls.push(req)
        let result = {}
        if (req.method === 'app.bootstrap') result = { versionName: '2.0.8', service: { running: true }, appearance: { theme, palette: 'lime' }, capabilities: {} }
        if (req.method === 'tools.list') result = { tools: [] }
        if (req.method === 'verification.list') result = { sessions: mobileQA.sessions }
        if (req.method === 'tools.call') {
          const { name, arguments: a } = req.params
          if (name === 'list_sources') {
            const rows = fixture.filter(x => !mobileQA.deleted.includes(x.projectId)), off = a.offset || 0, limit = a.limit || 20
            result = text({ total: rows.length, hasMore: off + limit < rows.length, items: rows.slice(off, off + limit) })
          } else if (name === 'get_source') {
            const row = fixture.find(x => x.projectId === a.key)
            result = text(JSON.stringify({ bookSourceName: row.bookSourceName, bookSourceUrl: row.bookSourceUrl, enabled: true }, null, 2))
          } else if (name === 'delete_projects') {
            mobileQA.deleted.push(...a.ids); result = text(`已删除 ${a.ids.length} 个项目`)
          } else result = text({ items: [] })
        }
        if (req.method === 'sources.deliver') {
          const a = req.params, count = a.scope === 'all' ? fixture.length : a.projectIds?.length || 1
          result = { count, action: a.action, scope: a.scope || 'selected', copied: a.action === 'copy', saved: a.action === 'save', launched: ['share','reader'].includes(a.action) }
        }
        queueMicrotask(() => this.onmessage?.({ data: JSON.stringify({ id: req.id, ok: true, result }) }))
      } }
    }
    initScript = (await cdp('Page.addScriptToEvaluateOnNewDocument', { source: `(${init.toString()})(${JSON.stringify({ fixture, theme: settings.theme, key })})` })).identifier
    await cdp('Emulation.setDeviceMetricsOverride', { width: settings.width, height: 844, deviceScaleFactor: 1, mobile: false })
    await cdp('Page.navigate', { url: server.resolvedUrls.local[0] })
    await poll(() => evaluate(key => mobileQA?.key === key && !!document.querySelector('nav button'), key), 'App')
    await click('nav button', 1)
    await poll(() => evaluate(() => document.querySelectorAll('.source-row').length === 20), 'populated source page')
    await enlarge(settings.scale)
    await evaluate(() => { document.querySelector('main').scrollTop = 0 })
    await delay(70)
    await screenshot(`${key}-first-screen.png`)
    const geometry = await evaluate(() => {
      const main = document.querySelector('main').getBoundingClientRect()
      const toolbar = [...document.querySelectorAll('.source-toolbar button')].map(e => {
        const range = document.createRange(); range.selectNodeContents(e)
        return { label: e.textContent.trim(), lines: new Set([...range.getClientRects()].filter(r => r.width > 0).map(r => Math.round(r.top))).size }
      })
      const input = document.querySelector('.source-search input'), style = getComputedStyle(input)
      const ctx = document.createElement('canvas').getContext('2d'); ctx.font = `${style.fontSize} ${style.fontFamily}`
      const placeholderFits = ctx.measureText(input.placeholder).width <= input.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)
      const entries = [...document.querySelectorAll('.source-entry')].map(e => {
        const name = e.querySelector('.source-name'), r = e.getBoundingClientRect(), nr = name.getBoundingClientRect()
        return { name: name.textContent, row: r.toJSON(), nameRect: nr.toJSON(), meta: e.querySelector('.source-meta')?.textContent,
          actions: [...e.querySelectorAll('[data-source-action]')].map(a => ({ action: a.dataset.sourceAction, rect: a.getBoundingClientRect().toJSON() })) }
      })
      return { toolbar, placeholderFits, main: main.toJSON(), header: document.querySelector('header').getBoundingClientRect().toJSON(), entries,
        overflow: [...document.querySelectorAll('html,body,#app,.app-shell,main,.source-entry,.source-row,.source-controls')]
          .filter(e => e.getClientRects().length && e.scrollWidth > e.clientWidth + 1).map(e => ({ class: e.className, width: e.clientWidth, scrollWidth: e.scrollWidth })),
        initialCheckboxes: [...document.querySelectorAll('.source-entry input[type=checkbox]')].filter(e => e.getClientRects().length).length,
        destructiveOutsideCard: document.querySelectorAll('.source-entry > .btn-danger').length,
        initialVisibleActions: [...document.querySelectorAll('[data-source-action]')].filter(e => e.getBoundingClientRect().bottom <= main.bottom).length }
    })
    lastCase.geometry = geometry
    assert.deepEqual(geometry.overflow, [], 'No horizontal overflow')
    assert.ok(geometry.placeholderFits, 'Search placeholder stays readable at narrow enlarged size')
    assert.ok(geometry.toolbar.every(x => x.lines === 1), `Toolbar labels must not split words: ${JSON.stringify(geometry.toolbar)}`)
    assert.equal(geometry.initialCheckboxes, 0, 'Selection UI only in manage mode')
    assert.equal(geometry.destructiveOutsideCard, 0, 'Deletion must not own a row outside card')
    assert.ok(geometry.initialVisibleActions >= 3, 'First screen exposes direct row actions')
    for (let i = 0; i < geometry.entries.length; i++) {
      const entry = geometry.entries[i]
      assert.equal(entry.name, fixture[i].bookSourceName, 'Names remain complete')
      assert.ok(entry.nameRect.width > settings.width * .55, 'Title has useful width, not a single-character column')
      assert.ok(entry.meta.includes('3') && entry.meta.includes('2026-11-12'), 'Version and timestamp retained')
      assert.deepEqual(entry.actions.map(x => x.action), ['copy', 'save', 'share'])
      assert.ok(entry.actions.every(x => x.rect.height >= 43), 'Touch targets are at least 44px')
    }
    for (const action of ['copy', 'save', 'share']) {
      await click(`[data-source-action="${action}"]`)
      const req = await evaluate(() => mobileQA.calls.filter(x => x.method === 'sources.deliver').at(-1))
      assert.equal(req.params.action, action)
      assert.deepEqual(req.params.projectIds, [fixture[0].projectId])
    }
    await click('[data-export-all]'); await click('[data-share-all]')
    const all = await evaluate(() => mobileQA.calls.filter(x => x.method === 'sources.deliver' && x.params.scope === 'all').map(x => x.params))
    assert.deepEqual(all.map(x => x.action), ['save', 'share'])
    assert.ok(all.every(x => x.projectIds === undefined && x.token === undefined), 'All delivery cannot be a paginated selection or analyzer token')
    assert.equal(await evaluate(() => mobileQA.calls.filter(x => x.method === 'sources.analyze').length), 0)
    await click('[data-selection-toggle]')
    await click('.source-entry input[type="checkbox"]', 9)
    const hit = await click('.source-row', 9)
    const oldOffset = hit.rect.top - hit.main.top
    await poll(() => evaluate(() => !!document.querySelector('textarea[aria-label="书源 JSON"]')), 'detail')
    assert.equal(await evaluate(() => document.querySelector('main').scrollTop), 0)
    await screenshot(`${key}-detail.png`)
    await click('[data-detail-back]')
    await poll(() => evaluate(() => !document.querySelector('[data-detail-back]')), 'back')
    const returned = await evaluate(() => ({ checked: document.querySelectorAll('.source-entry input[type=checkbox]')[9].checked,
      offset: document.querySelectorAll('.source-row')[9].getBoundingClientRect().top - document.querySelector('main').getBoundingClientRect().top }))
    assert.equal(returned.checked, true); assert.ok(Math.abs(returned.offset - oldOffset) <= 1, 'Scroll position retained')
    await byLabel('分享选中')
    assert.deepEqual(await evaluate(() => mobileQA.calls.filter(x => x.method === 'sources.deliver').at(-1).params.projectIds), [fixture[9].projectId])
    await click('[data-source-action="share"]', 19)
    await screenshot(`${key}-last-card.png`)
    const toast = await evaluate(() => ({ bottom: document.querySelector('.toast')?.getBoundingClientRect().bottom, navTop: document.querySelector('nav').getBoundingClientRect().top }))
    assert.ok(toast.bottom <= toast.navTop - 4, `Share status must not cover navigation: ${JSON.stringify(toast)}`)
    assert.equal(await evaluate(() => mobileQA.deleted.length), 0)
    if (settings.width === 390 && settings.theme === 'light') {
      await click('nav button', 3)
      await poll(() => evaluate(() => mobileQA.calls.some(x => x.method === 'verification.list')), 'verification list')
      await evaluate(() => mobileQA.sessions.push({ id: 'ui208-second-session', domain: 'second.example.invalid', url: 'https://second.example.invalid/', purpose: '第二次验证测试', status: 'WAITING', kind: 'webview', canOpen: true }))
      await poll(() => evaluate(() => document.querySelector('main').textContent.includes('第二次验证测试')), 'second session appears without leaving page')
      await screenshot(`${key}-second-verification.png`)
      lastCase.secondSessionWithoutNavigation = true
    }
    lastCase.passed = true
    await writeFile(join(evidence, 'results.json'), JSON.stringify(results, null, 2))
    console.log(`PASS ${key}: direct individual/all/selected actions, filled layout and scrolling`)
  }
  assert.equal(results.cases.length, results.expectedCases)
  assert.deepEqual(results.errors, [])
} catch (error) {
  results.errors.push({ case: lastCase?.key, message: error.message }); console.error(error); process.exitCode = 1
} finally {
  clearTimeout(deadline)
  await writeFile(join(evidence, 'results.json'), JSON.stringify(results, null, 2))
  ws?.close(); chrome?.kill('SIGTERM'); await server.close(); await delay(300)
  await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 })
}
