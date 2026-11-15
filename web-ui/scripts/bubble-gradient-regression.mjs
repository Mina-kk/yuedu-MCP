// UI209 gradient-only evidence: production App/CSS, explicit bridge fixtures only.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const evidence = resolve(process.env.BUBBLE_EVIDENCE || join(tmpdir(), 'ui209-bubbles'))
const cases = [{ width: 320, theme: 'light', palette: 'cherry' }, { width: 390, theme: 'dark', palette: 'cherry' }]
const fixture = Array.from({ length: 6 }, (_, i) => ({ projectId: `ui209-gradient-${i}`, bookSourceName: '纸间 · 简体', bookSourceUrl: `https://source-${i}.example.invalid/books`, stage: 'VALIDATED', versionCount: 3, updatedAt: Date.parse('2026-11-12T11:48:24Z') }))
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
  await cdp('Page.enable'); await cdp('Runtime.enable')
  results.browser = await cdp('Browser.getVersion')
  async function inspect(selector) {
    return evaluate(selector => {
      const color = value => {
        const c = document.createElement('canvas'); c.width = c.height = 1
        const ctx = c.getContext('2d'); ctx.fillStyle = value; ctx.fillRect(0,0,1,1)
        return [...ctx.getImageData(0,0,1,1).data].map((v,i) => i === 3 ? v/255 : v)
      }
      const over = (a,b) => a.slice(0,3).map((v,i) => v*a[3]+b[i]*(1-a[3]))
      const lum = rgb => rgb.slice(0,3).map(v => { v/=255; return v<=.04045?v/12.92:((v+.055)/1.055)**2.4 }).reduce((n,v,i)=>n+v*[.2126,.7152,.0722][i],0)
      return [...document.querySelectorAll(selector)].filter(e=>e.getClientRects().length).map(e=>{
        const s=getComputedStyle(e), r=e.getBoundingClientRect(), stops=s.backgroundImage.match(/(?:color\([^)]*\)|rgba?\([^)]*\))/g)||[]
        const parents=[]; for(let p=e;p;p=p.parentElement) parents.unshift(p)
        let bg=[255,255,255]; for(const p of parents) bg=over(color(getComputedStyle(p).backgroundColor),bg)
        const t=lum(color(s.color)), contrast=stops.map(stop=>{ const b=lum(over(color(stop),bg)); return (Math.max(t,b)+.05)/(Math.min(t,b)+.05) })
        const range=document.createRange(); range.selectNodeContents(e)
        return { label:e.textContent.trim(), image:s.backgroundImage, stops, contrast:contrast.length?Math.min(...contrast):null,
          opacity:s.opacity, color:s.color, disabled:e.disabled, rect:r.toJSON(), radius:s.borderRadius,
          textFits:[...range.getClientRects()].every(t=>t.left>=r.left-1&&t.right<=r.right+1),
          touch:e.tagName!=='BUTTON'||(r.height>=44&&r.width>=44) }
      })
    }, selector)
  }
  for(const settings of cases) {
    const key=`${settings.palette}-${settings.theme}-${settings.width}`
    lastCase={key,...settings,passed:false,checks:[]}; results.cases.push(lastCase)
    if(initScript) await cdp('Page.removeScriptToEvaluateOnNewDocument',{identifier:initScript})
    const init=({fixture,settings})=>{
      const text=v=>({content:[{type:'text',text:typeof v==='string'?v:JSON.stringify(v)}]})
      window.StudioBridge={postMessage(raw){
        const req=JSON.parse(raw); let result={}
        if(req.method==='app.bootstrap') result={versionName:'2.0.9',service:{running:true},appearance:{theme:settings.theme,palette:settings.palette},capabilities:{}}
        if(req.method==='tools.list') result={tools:[]}
        if(req.method==='sources.deliver' && req.params.action==='targets') result={targets:[{label:'阅读 原版',packageName:'io.legado.app.release'},{label:'阅读',packageName:'com.legado.app.release'}]}
        if(req.method==='tools.call') {
          const {name,arguments:a}=req.params
          if(name==='list_sources') result=text({items:fixture,total:fixture.length,hasMore:false})
          else if(name==='get_source') { const row=fixture.find(x=>x.projectId===a.key); result=text(JSON.stringify({bookSourceName:row.bookSourceName,bookSourceUrl:row.bookSourceUrl},null,2)) }
          else result=text({items:[]})
        }
        queueMicrotask(()=>this.onmessage?.({data:JSON.stringify({id:req.id,ok:true,result})}))
      }}
    }
    initScript=(await cdp('Page.addScriptToEvaluateOnNewDocument',{source:`(${init.toString()})(${JSON.stringify({fixture,settings})})`})).identifier
    await cdp('Emulation.setDeviceMetricsOverride',{width:settings.width,height:844,deviceScaleFactor:1,mobile:false})
    await cdp('Page.navigate',{url:server.resolvedUrls.local[0]})
    await poll(()=>evaluate(()=>!!document.querySelector('nav button')),'App')
    await click('nav button',1)
    await poll(()=>evaluate(()=>document.querySelectorAll('.source-row').length===6),'filled source page')
    if (process.env.BUBBLE_BASELINE === '1') await evaluate(() => {
      const sheet = [...document.styleSheets].find(s => s.ownerNode?.dataset.viteDevId?.endsWith('/bubbles.css'))
      if (!sheet) throw new Error('Missing bubble CSS for baseline comparison')
      sheet.disabled = true
    })
    lastCase.baseline = process.env.BUBBLE_BASELINE === '1'
    lastCase.appearance=await evaluate(()=>({mode:document.documentElement.dataset.mode,palette:document.documentElement.dataset.theme}))
    assert.equal(lastCase.appearance.mode,settings.theme); assert.equal(lastCase.appearance.palette,settings.palette)
    await click('.source-row-actions button',3)
    await evaluate(()=>{document.querySelector('main').scrollTop=0})
    await screenshot(`${key}-list.png`)
    lastCase.checks.push(...await inspect('.source-row-actions .btn,.source-row-more .btn,.source-toolbar .btn,header .chip'))
    await click('.source-row-actions button',23)
    await click('.source-row-more button',0)
    await poll(()=>evaluate(()=>document.querySelectorAll('.reader-target').length===2),'real reader modal with fixture targets')
    lastCase.checks.push(...await inspect('.reader-import-dialog .btn'))
    lastCase.readerEvidence='REAL_VUE_MODAL_WITH_EXPLICIT_BRIDGE_FIXTURE_TARGETS_NOT_ANDROID'
    await screenshot(`${key}-reader-modal.png`)
    await click('.reader-import-dialog .actions button')
    await click('.source-row',5)
    await poll(()=>evaluate(()=>!!document.querySelector('.source-management')),'real source detail')
    await screenshot(`${key}-detail.png`)
    lastCase.checks.push(...await inspect('.source-management .btn:not(.btn-primary):not(:disabled)'))
    if (!lastCase.baseline) {
      lastCase.paintOnly = await evaluate(() => {
        const sheet = [...document.styleSheets].find(s => s.ownerNode?.dataset.viteDevId?.endsWith('/bubbles.css'))
        const measure = () => [...document.querySelectorAll('.sources-page .btn')].map(e => {
          const r = e.getBoundingClientRect(), s = getComputedStyle(e)
          return { x:r.x, y:r.y, width:r.width, height:r.height, font:s.font, padding:s.padding,
            ...(e.classList.contains('btn-primary') ? {background:s.background, color:s.color} : {}) }
        })
        const after = measure(); sheet.disabled = true; const before = measure(); sheet.disabled = false
        return JSON.stringify(after) === JSON.stringify(before)
      })
      assert.ok(lastCase.paintOnly, 'No geometry/font/padding or primary theme changes')
      lastCase.semanticChipsPreserved = await evaluate(() => {
        const sheet = [...document.styleSheets].find(s => s.ownerNode?.dataset.viteDevId?.endsWith('/bubbles.css'))
        const nodes = ['ok','bad','warn'].map(kind => { const e=document.createElement('span'); e.className=`chip ${kind}`; e.textContent=kind; document.body.appendChild(e); return e })
        const colors=()=>nodes.map(e=>{const s=getComputedStyle(e);return [s.color,s.background,s.borderColor]})
        const after=colors(); sheet.disabled=true; const before=colors(); sheet.disabled=false;nodes.forEach(e=>e.remove())
        return JSON.stringify(after)===JSON.stringify(before)
      })
      assert.ok(lastCase.semanticChipsPreserved, 'Semantic danger/warning/success badges must keep their colors')
    }
    lastCase.overflow=await evaluate(()=>[...document.querySelectorAll('html,body,#app,.app-shell,main,.sources-page,.source-entry,.source-management')].filter(e=>e.clientWidth&&e.scrollWidth>e.clientWidth+1).map(e=>e.className))
    lastCase.failures=lastCase.checks.filter(x=>!x.image.startsWith('linear-gradient(')||x.stops.length<2||x.stops[0]===x.stops[1]||!x.touch||!x.textFits||(!x.disabled&&(x.opacity!=='1'||!Number.isFinite(x.contrast)||x.contrast<4.5)))
    lastCase.passed=!lastCase.failures.length&&!lastCase.overflow.length
    await writeFile(join(evidence,'results.json'),JSON.stringify(results,null,2))
    console.log(`${lastCase.passed?'PASS':'FAIL'} ${key}: ${lastCase.checks.length} controls; failures=${lastCase.failures.length}, overflow=${lastCase.overflow.length}`)
  }
  assert.equal(results.cases.length,results.expectedCases)
  assert.ok(results.cases.every(x=>x.passed),'Distinct gradients, readable opaque text, 44px targets and no overflow')
  assert.deepEqual(results.errors,[])
} catch(error) {
  results.errors.push({case:lastCase?.key,message:error.message}); console.error(error); process.exitCode=1
} finally {
  clearTimeout(deadline)
  await writeFile(join(evidence,'results.json'),JSON.stringify(results,null,2))
  ws?.close(); chrome?.kill('SIGTERM'); await server.close(); await delay(300)
  await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200})
}
