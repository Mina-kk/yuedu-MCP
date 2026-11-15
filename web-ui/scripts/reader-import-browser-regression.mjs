// UI209 real browser / production App and CSS; bridge fixture is not Android evidence.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const evidence = resolve(process.env.READER_EVIDENCE || join(tmpdir(), 'ui209-reader'))
const cases = [{ width: 390, theme: 'light', scale: 1 }, { width: 320, theme: 'dark', scale: 1 }]
const names = ['纸间 · 简体', '纸间 · 简体', '这是一个用于窄屏大字阅读的超长中文书源名称必须完整显示不能省略也不能逐字竖排',
  'LongEnglishSourceNameWithoutWordBoundary'.repeat(2)]
const fixture = Array.from({ length: 24 }, (_, i) => ({ projectId: `ui209-fixture-${i + 1}`,
  bookSourceName: names[i] || `手机书源 ${i + 1}`, bookSourceUrl: `https://source-${i + 1}.example.invalid/${i === 2 ? 'longpath'.repeat(10) : 'books'}`,
  stage: 'VALIDATED', versionCount: 3, updatedAt: Date.parse('2026-11-12T11:48:24Z') - i * 1000 }))
const results = { kind: 'BROWSER_FIXTURE_NOT_ANDROID_EVIDENCE', expectedCases: cases.length, fixtureCount: fixture.length, cases: [], errors: [] }
await mkdir(evidence, { recursive: true })
const profile = await mkdtemp(join(tmpdir(), 'ui209-chrome-'))
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
      document.querySelectorAll('[data-ui209-click]').forEach(e => e.removeAttribute('data-ui209-click'))
      const matches = [...document.querySelector(scope).querySelectorAll('button')].filter(e => e.getClientRects().length && e.textContent.trim() === label)
      if (matches.length !== 1) return matches.length
      matches[0].setAttribute('data-ui209-click', ''); return true
    }, { label, scope })
    assert.equal(ok, true, `Unique visible button: ${label}, got ${ok}`)
    return click('[data-ui209-click]')
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
    const key = `${settings.theme}-${settings.width}`
    lastCase = { key, ...settings, passed: false }; results.cases.push(lastCase)
    if (initScript) await cdp('Page.removeScriptToEvaluateOnNewDocument', { identifier: initScript })
    const init = ({ fixture, theme, key }) => {
      window.readerQA = { key, fixture, calls: [], targets: [
        { packageName: 'com.legado.app.release', label: '阅读 · 未知阅读' },
        { packageName: 'io.legado.app.release', label: '阅读 原版' }
      ] }
      const text = value => ({ content: [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value) }] })
      window.StudioBridge = { postMessage(raw) {
        const req = JSON.parse(raw); readerQA.calls.push(req)
        let result = {}
        if (req.method === 'app.bootstrap') result = { versionName: '2.0.9', service: { running: true }, appearance: { theme, palette: 'lava' }, capabilities: {} }
        if (req.method === 'tools.list') result = { tools: [] }
        if (req.method === 'tools.call') {
          const { name, arguments: a } = req.params
          if (name === 'list_sources') result = text({ total: fixture.length, hasMore: true, items: fixture.slice(0,20) })
          else if (name === 'get_source') {
            const row = fixture.find(x => x.projectId === a.key)
            result = text(` {"bookSourceName":${JSON.stringify(row.bookSourceName)},"bookSourceUrl":${JSON.stringify(row.bookSourceUrl)},"number":9007199254740993} `)
          } else result = text({items:[]})
        }
        if (req.method === 'sources.deliver') {
          const a=req.params
          result = a.action === 'targets' ? {targets:readerQA.targets} : {launched:true,confirmed:false,confirmation:'pending_user',packageName:a.packageName,count:1}
        }
        queueMicrotask(() => this.onmessage?.({data:JSON.stringify({id:req.id,ok:true,result})}))
      } }
    }
    initScript=(await cdp('Page.addScriptToEvaluateOnNewDocument',{source:`(${init.toString()})(${JSON.stringify({fixture,theme:settings.theme,key})})`})).identifier
    await cdp('Emulation.setDeviceMetricsOverride',{width:settings.width,height:844,deviceScaleFactor:1,mobile:false})
    await cdp('Page.navigate',{url:server.resolvedUrls.local[0]})
    await poll(()=>evaluate(key=>readerQA?.key===key&&!!document.querySelector('nav button'),key),'App ready')
    await click('nav button',1)
    await poll(()=>evaluate(()=>document.querySelectorAll('.source-row').length===20),'populated source list')
    await click('[data-source-more]',19)
    await byLabel('导入阅读','.source-row-more')
    await poll(()=>evaluate(()=>!!document.querySelector('.reader-import-dialog [data-reader-target]')),'visible reader chooser from last card')
    const checkDialog=async()=>{
      const geometry=await evaluate(()=>{
        const dialog=document.querySelector('.reader-import-dialog'),r=dialog.getBoundingClientRect()
        const buttons=[...dialog.querySelectorAll('[data-reader-target]')].map(el=>{
          const b=el.getBoundingClientRect(),hit=document.elementFromPoint(b.x+b.width/2,b.y+b.height/2)
          return {package:el.dataset.readerTarget,bounds:b.toJSON(),hit:el===hit||el.contains(hit),gradient:getComputedStyle(el).backgroundImage}
        })
        return {rect:r.toJSON(),buttons,mainTop:document.querySelector('main').scrollTop,h:innerHeight,w:innerWidth}
      })
      assert.ok(geometry.buttons.length===2 && geometry.buttons.every(x=>x.bounds.top>=0&&x.bounds.bottom<=geometry.h&&x.bounds.left>=0&&x.bounds.right<=geometry.w&&x.hit),'Chooser targets must be visible and hit-testable without scrolling to find them')
      return geometry
    }
    lastCase.listChooser=await checkDialog()
    assert.ok(lastCase.listChooser.mainTop>200,'Must reproduce from scrolled list, not top')
    await screenshot(`${key}-last-card-chooser.png`)
    await click('[data-reader-target="io.legado.app.release"]')
    await poll(()=>evaluate(()=>!document.querySelector('.reader-import-dialog')),'chooser closes after handoff')
    const req=await evaluate(()=>readerQA.calls.filter(x=>x.method==='sources.deliver'&&x.params.action==='reader').at(-1).params)
    assert.deepEqual(req,{projectIds:[fixture[19].projectId],action:'reader',packageName:'io.legado.app.release'})
    // Reopen from the same card and exercise App's real page Back integration.
    if(!await evaluate(()=>!!document.querySelector('.source-row-more')))await click('[data-source-more]',19)
    await byLabel('导入阅读','.source-row-more')
    await poll(()=>evaluate(()=>!!document.querySelector('.reader-import-dialog')),'chooser reopen')
    await evaluate(()=>window.dispatchEvent(new CustomEvent('studio:back',{detail:{}})))
    await poll(()=>evaluate(()=>!document.querySelector('.reader-import-dialog')),'Back closes chooser')
    assert.equal(await evaluate(()=>readerQA.calls.filter(x=>x.method==='sources.deliver'&&x.params.action==='reader').length),1,'Back must not launch')
    assert.equal(await evaluate(()=>!!document.querySelector('[role=alertdialog]')),false,'Back must not show exit prompt')
    // Detail uses the same visible modal and freezes the current raw draft.
    await click('.source-row',19)
    await poll(()=>evaluate(()=>!!document.querySelector('textarea[aria-label="书源 JSON"]')),'detail')
    const raw=await evaluate(()=>document.querySelector('textarea[aria-label="书源 JSON"]').value)
    await byLabel('导入阅读','.source-management')
    await poll(()=>evaluate(()=>!!document.querySelector('.reader-import-dialog [data-reader-target]')),'detail chooser')
    lastCase.detailChooser=await checkDialog();await screenshot(`${key}-detail-chooser.png`)
    await click('[data-reader-target="com.legado.app.release"]')
    const detailReq=await evaluate(()=>readerQA.calls.filter(x=>x.method==='sources.deliver'&&x.params.action==='reader').at(-1).params)
    assert.deepEqual(detailReq,{source:raw,action:'reader',packageName:'com.legado.app.release'})
    await poll(()=>evaluate(()=>!document.querySelector('.reader-import-dialog')),'detail handoff')
    // One installed client requires no redundant second tap.
    await evaluate(()=>{readerQA.targets=readerQA.targets.slice(0,1)})
    await byLabel('导入阅读','.source-management')
    await poll(()=>evaluate(()=>readerQA.calls.filter(x=>x.method==='sources.deliver'&&x.params.action==='reader').length===3),'single client auto launch')
    await poll(()=>evaluate(()=>!document.querySelector('.reader-import-dialog [data-reader-target]')),'no redundant single chooser')
    const text=await evaluate(()=>document.body.innerText)
    assert.ok(!text.includes('已导入成功'),'Handoff cannot claim import completed')
    lastCase.singleClientDirect=true;lastCase.passed=true
    await writeFile(join(evidence,'results.json'),JSON.stringify(results,null,2))
    console.log(`PASS ${key}: scrolled-list modal, exact package/source, Back, detail and single-client flow`)
  }
  assert.equal(results.cases.length,results.expectedCases);assert.deepEqual(results.errors,[])
} catch(error) {
  results.errors.push({case:lastCase?.key,message:error.message});console.error(error);process.exitCode=1
} finally {
  clearTimeout(deadline);await writeFile(join(evidence,'results.json'),JSON.stringify(results,null,2))
  ws?.close();chrome?.kill('SIGTERM');await server.close();await delay(300)
  await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200})
}
