// UI210 supersedes the UI209 44px paint-only visual contract; old test unchanged.
// Production App + all CSS; only Android bridge IO is fixture data.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const evidence = resolve(process.env.COMPACT_EVIDENCE || join(tmpdir(), 'ui210-compact'))
const cases = [{ width: 390, theme: 'light', palette: 'cherry' }, { width: 320, theme: 'dark', palette: 'cherry' }]
const fixture = Array.from({ length: 6 }, (_, i) => ({ projectId: `ui209-gradient-${i}`, bookSourceName: i === 5 ? '纸间 · 简体 · 完整长名称保留不省略测试书源' : '纸间 · 简体', bookSourceUrl: `https://source-${i}.example.invalid/books`, stage: 'VALIDATED', versionCount: 3, updatedAt: Date.parse('2026-11-12T11:48:24Z') }))
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
    return evaluate(selector => [...document.querySelectorAll(selector)].filter(e=>e.getClientRects().length).map(e=>{
      const s=getComputedStyle(e), p=getComputedStyle(e,'::before'), r=e.getBoundingClientRect()
      const hasPaint=p.content!=='none' && p.content!=='normal', paint=hasPaint?p:s
      const range=document.createRange();range.selectNodeContents(e)
      return {label:e.textContent.trim(),height:r.height,width:r.width,font:s.fontSize,color:s.color,
        image:paint.backgroundImage,background:paint.backgroundColor,border:paint.borderColor,radius:paint.borderRadius,
        paintHeight:hasPaint?parseFloat(p.height):r.height,hasPaint,outerBackground:s.backgroundColor,outerImage:s.backgroundImage,
        pointer:hasPaint?p.pointerEvents:null,opacity:s.opacity,disabled:e.disabled,
        textFits:[...range.getClientRects()].every(t=>t.left>=r.left-1&&t.right<=r.right+1&&t.top>=r.top-1&&t.bottom<=r.bottom+1)}
    }),selector)
  }
  for(const settings of cases) {
    const key=`${settings.palette}-${settings.theme}-${settings.width}`
    lastCase={key,...settings,passed:false,checks:[],failures:[]};results.cases.push(lastCase)
    if(initScript)await cdp('Page.removeScriptToEvaluateOnNewDocument',{identifier:initScript})
    const init=({fixture,settings})=>{
      window.__compactCalls=[]
      const text=v=>({content:[{type:'text',text:typeof v==='string'?v:JSON.stringify(v)}]})
      window.StudioBridge={postMessage(raw){
        const req=JSON.parse(raw);window.__compactCalls.push(req);let result={}
        if(req.method==='app.bootstrap')result={versionName:'2.0.9',service:{running:true},appearance:{theme:settings.theme,palette:settings.palette},capabilities:{}}
        if(req.method==='tools.list')result={tools:[]}
        if(req.method==='sources.deliver'&&req.params.action==='targets')result={targets:[
          {label:'阅读 原版 · 完整客户端名称换行展示不裁剪 · 测试版',packageName:'io.legado.app.release'},
          {label:'阅读',packageName:'com.legado.app.release'}]}
        if(req.method==='tools.call'){
          const {name,arguments:a}=req.params
          if(name==='list_sources')result=text({items:fixture,total:fixture.length,hasMore:false})
          else if(name==='get_source'){const row=fixture.find(x=>x.projectId===a.key);result=text(JSON.stringify({bookSourceName:row.bookSourceName,bookSourceUrl:row.bookSourceUrl},null,2))}
          else result=text({items:[]})
        }
        queueMicrotask(()=>this.onmessage?.({data:JSON.stringify({id:req.id,ok:true,result})}))
      }}
    }
    initScript=(await cdp('Page.addScriptToEvaluateOnNewDocument',{source:`(${init.toString()})(${JSON.stringify({fixture,settings})})`})).identifier
    await cdp('Emulation.setDeviceMetricsOverride',{width:settings.width,height:844,deviceScaleFactor:1,mobile:false})
    await cdp('Page.navigate',{url:server.resolvedUrls.local[0]})
    await poll(()=>evaluate(()=>!!document.querySelector('[data-connection-local]')),'MCP App')
    await cdp('Input.dispatchMouseEvent',{type:'mouseMoved',x:0,y:0});await delay(220)
    lastCase.mcp=await inspect('main .btn')
    lastCase.mcpReference=await inspect('[data-connection-local]')
    const ref=lastCase.mcpReference[0]
    assert.equal(ref.height,32,'MCP existing btn-sm baseline must remain 32px')
    assert.equal(ref.radius,'9px');assert.equal(ref.font,'12.5px')
    if(process.env.COMPACT_BASELINE){
      const before=JSON.parse(await readFile(join(process.env.COMPACT_BASELINE,'results.json'),'utf8')).cases.find(x=>x.key===key)
      assert.deepEqual(lastCase.mcp,before.mcp,'All actual MCP button computed paint/size unchanged from RED')
      lastCase.mcpUnchanged=true
    }
    await screenshot(`${key}-mcp.png`)
    await click('nav button',1)
    await poll(()=>evaluate(()=>document.querySelectorAll('.source-row').length===6),'filled sources')
    lastCase.appearance=await evaluate(()=>({mode:document.documentElement.dataset.mode,palette:document.documentElement.dataset.theme}))
    assert.equal(lastCase.appearance.mode,settings.theme);assert.equal(lastCase.appearance.palette,settings.palette)
    const collect=async selector=>{
      await cdp('Input.dispatchMouseEvent',{type:'mouseMoved',x:0,y:0});await delay(220)
      const controls=await inspect(selector);lastCase.checks.push(...controls)
      for(const c of controls){
        if(c.height<44||c.width<44)lastCase.failures.push(`${c.label}: hit below 44`)
        if(!c.hasPaint||c.paintHeight!==32)lastCase.failures.push(`${c.label}: paint ${c.paintHeight}, expected 32px pseudo`)
        if(c.outerImage!=='none'||c.outerBackground!=='rgba(0, 0, 0, 0)'||c.pointer!=='none')lastCase.failures.push(`${c.label}: outer filled or pseudo intercepts`)
        for(const f of ['image','background','border','color','radius','font'])if(c[f]!==ref[f])lastCase.failures.push(`${c.label}: ${f} differs from MCP`)
        if(!c.textFits)lastCase.failures.push(`${c.label}: text clipped`)
      }
    }
    await collect('.source-row-actions .btn,.source-toolbar .btn,.source-search .btn,.source-advanced > .btn')
    await screenshot(`${key}-list.png`)
    lastCase.sourceTextPreserved=await evaluate(fixture=>[...document.querySelectorAll('.source-row')].every((e,i)=>e.querySelector('.source-name').textContent===fixture[i].bookSourceName&&e.querySelector('.source-url').textContent===fixture[i].bookSourceUrl&&e.querySelector('.source-meta').textContent.includes('2026')),fixture)
    assert.ok(lastCase.sourceTextPreserved)
    // Click the transparent 44px target margin, outside the 32px fill.
    const margin=await evaluate(()=>{
      const e=document.querySelectorAll('[data-source-more]')[5];e.scrollIntoView({block:'center'})
      const r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+2
      return {x,y,hit:document.elementFromPoint(x,y)===e}
    })
    assert.ok(margin.hit,'Transparent hit margin remains clickable')
    await cdp('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,x:margin.x,y:margin.y})
    await cdp('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,x:margin.x,y:margin.y})
    await poll(()=>evaluate(()=>!!document.querySelector('.source-row-more')),'More from transparent target margin')
    lastCase.hitMarginClicked=true
    await collect('.source-row-more .btn:not(.danger)')
    await click('.source-row-more button',0)
    await poll(()=>evaluate(()=>document.querySelectorAll('.reader-target').length===2),'real Vue reader modal')
    lastCase.reader=await inspect('.reader-target')
    assert.ok(lastCase.reader.every(x=>x.height>=44&&x.textFits),'Natural long client rows must not clip')
    await screenshot(`${key}-reader-modal.png`)
    await click('.reader-import-dialog .actions button')
    assert.equal(await evaluate(()=>!!document.querySelector('.reader-import-dialog')),false,'Cancel closes modal')
    lastCase.readerCalls=await evaluate(()=>window.__compactCalls.filter(x=>x.method==='sources.deliver'))
    assert.deepEqual(lastCase.readerCalls.map(x=>x.params.action),['targets'],'Cancel must not launch')
    assert.deepEqual(lastCase.readerCalls[0].params.projectIds,[fixture[5].projectId])
    lastCase.readerEvidence='REAL_VUE_CLICK_MORE_IMPORT_CANCEL_WITH_BRIDGE_FIXTURE_NOT_ANDROID'
    await click('.source-row',5)
    await poll(()=>evaluate(()=>!!document.querySelector('.source-management')),'source detail')
    await collect('.source-management .btn:not(.danger)')
    await screenshot(`${key}-detail.png`)
    if(settings.width===320){
      await evaluate(()=>document.querySelectorAll('.sources-page .btn').forEach(e=>e.style.setProperty('font-size','18.75px','important')))
      lastCase.largeText=await inspect('.source-management .btn')
      assert.ok(lastCase.largeText.every(x=>x.textFits&&x.height>=44&&x.paintHeight===x.height-12),'150% text: natural button height, no clipping')
      await screenshot(`${key}-detail-large-text.png`)
      await evaluate(()=>document.querySelectorAll('.sources-page .btn').forEach(e=>e.style.removeProperty('font-size')))
    }
    lastCase.semanticChipsPreserved=await evaluate(()=>{
      const sheet=[...document.styleSheets].find(s=>s.ownerNode?.dataset.viteDevId?.endsWith('/bubbles.css'))
      const nodes=['ok','bad','warn'].map(kind=>{const e=document.createElement('span');e.className=`chip ${kind}`;e.textContent=kind;document.body.appendChild(e);return e})
      const colors=()=>nodes.map(e=>{const s=getComputedStyle(e);return[s.color,s.background,s.borderColor]})
      const after=colors();sheet.disabled=true;const before=colors();sheet.disabled=false;nodes.forEach(e=>e.remove())
      return JSON.stringify(after)===JSON.stringify(before)
    })
    assert.ok(lastCase.semanticChipsPreserved)
    lastCase.overflow=await evaluate(()=>[...document.querySelectorAll('html,body,#app,.app-shell,main,.sources-page,.source-entry,.source-management')].filter(e=>e.clientWidth&&e.scrollWidth>e.clientWidth+1).map(e=>e.className))
    lastCase.passed=!lastCase.failures.length&&!lastCase.overflow.length
    await writeFile(join(evidence,'results.json'),JSON.stringify(results,null,2))
    console.log(`${lastCase.passed?'PASS':'FAIL'} ${key}: ${lastCase.checks.length} controls; failures=${lastCase.failures.length}; first=${lastCase.failures[0]||'none'}`)
  }
  assert.equal(results.cases.length,results.expectedCases)
  assert.ok(results.cases.every(x=>x.passed),'MCP identical paint/color/font/radius, 32px visual fill, 44px hit, no clipping')
  assert.deepEqual(results.errors,[])
} catch(error){results.errors.push({case:lastCase?.key,message:error.message});console.error(error);process.exitCode=1}
finally{
  clearTimeout(deadline);await writeFile(join(evidence,'results.json'),JSON.stringify(results,null,2))
  ws?.close();chrome?.kill('SIGTERM');await server.close();await delay(300)
  await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200})
}
