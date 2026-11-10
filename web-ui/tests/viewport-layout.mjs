// Complete production App/CSS, populated bridge fixtures, real Chromium layout.
// CHROMIUM may point to a genuinely old engine; no UA impersonation is used.

import { spawn } from 'node:child_process'
import { mkdtemp, rm, mkdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { build, preview } from 'vite'

const evidence = resolve(process.env.VIEWPORT_EVIDENCE || join(tmpdir(), 'viewport-evidence'))
const temp = await mkdtemp(join(tmpdir(), 'viewport-layout-'))
const observations = []
const failures = []
const delay = ms => new Promise(r => setTimeout(r, ms))
let browser, server, ws
const overall = setTimeout(() => { browser?.kill('SIGTERM'); console.error('viewport-layout timeout (300s)'); process.exit(124) }, 300_000)
const check = (condition, message) => { if (!condition) failures.push(message) }
try {
  await mkdir(evidence, { recursive: true })
  await build({ logLevel: 'error', build: { outDir: join(temp, 'dist'), emptyOutDir: true } })
  server = await preview({ logLevel: 'error', build: { outDir: join(temp, 'dist') }, preview: { host: '127.0.0.1', port: 0 } })
  const url = server.resolvedUrls.local[0]
  const port = Number(process.env.VIEWPORT_CDP_PORT || 9316)
  browser = spawn(process.env.CHROMIUM || '/usr/bin/chromium', ['--headless', '--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu', '--disable-background-networking', '--disable-component-update', '--no-zygote', '--single-process', '--remote-allow-origins=*', `--remote-debugging-port=${port}`, `--user-data-dir=${join(temp, 'profile')}`, 'about:blank'], { stdio: ['ignore', 'ignore', 'inherit'] })
  async function poll(fn) {
    let last
    for (let n = 0; n < 150; n++) { try { const value = await fn(); if (value) return value } catch (e) { last = e } await delay(100) }
    throw Error(`Timed out: ${last?.message || ''}`)
  }
  const target = await poll(async () => (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find(t => t.type === 'page'))
  ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise(r => ws.addEventListener('open', r, { once: true }))
  let serial = 0
  const pending = new Map()
  const pageErrors = []
  ws.addEventListener('message', e => {
    const message = JSON.parse(e.data)
    if (message.id) {
      const request = pending.get(message.id)
      pending.delete(message.id)
      if (request) message.error ? request.reject(Error(JSON.stringify(message.error))) : request.resolve(message.result)
    }
    if (message.method === 'Runtime.exceptionThrown') pageErrors.push(message.params.exceptionDetails)
  })
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++serial; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params }))
  })
  const evaluate = async expression => {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) throw Error(JSON.stringify(r.exceptionDetails))
    return r.result.value
  }
  await send('Page.enable')
  await send('Runtime.enable')
  const version = await send('Browser.getVersion')
  observations.push({ test: 'engine', ...version, cssCapabilitySimulation: false })
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `
    window.qa = { calls: [] };
    window.StudioBridge = { postMessage(raw) {
      const m = JSON.parse(raw); qa.calls.push(m); let result = {};
      if (m.method === 'app.bootstrap') result = { versionName: 'viewport-fixture', service: { running: true, port: 8787 }, appearance: { theme: 'light', palette: 'lime' } };
      if (m.method === 'tools.list') result = { tools: Array.from({length:60}, (_, i) => ({name:'viewport_tool_'+i, description:'Populated tool fixture '+i, inputSchema:{type:'object',properties:{}}})) };
      if (m.method === 'tools.call') {
        const name = m.params.name;
        let data = {items:[]};
        if (name === 'list_sources') data = {items:Array.from({length:30},(_,i)=>({projectId:'viewport-source-'+i,bookSourceName:'Viewport source '+i,bookSourceUrl:'https://example.invalid/'+i}))};
        if (name === 'list_skills') data = {skills:Array.from({length:30},(_,i)=>({id:'viewport-skill-'+i,name:'Viewport skill '+i,builtIn:true,enabled:true}))};
        if (name === 'get_logs') data = {items:Array.from({length:30},(_,i)=>({id:i+1,message:'Viewport log '+i}))};
        result = {content:[{type:'text',text:JSON.stringify(data)}]};
      }
      if (m.method === 'verification.list') result={sessions:Array.from({length:30},(_,i)=>({id:'viewport-session-'+i,url:'https://example.invalid/'+i,domain:'example.invalid',kind:'webview',status:'WAITING',canOpen:true}))};
      if (m.method === 'verification.browser.close') result={closed:true};
      queueMicrotask(() => this.onmessage?.({data:JSON.stringify({id:m.id,ok:true,result})}));
    }};
  ` })
  const resize = (width, height) => send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: true })
  await resize(390, 844)
  await send('Page.navigate', { url })
  await poll(() => evaluate(`document.querySelectorAll('.tool-row').length === 60`))

  await delay(400)
  const capture = async name => {
    const shot = await send('Page.captureScreenshot', { format: 'png' })
    await writeFile(join(evidence, `${name}.png`), Buffer.from(shot.data, 'base64'))
  }
  const geometry = () => evaluate(`(() => {
    const r = selector => document.querySelector(selector)?.getBoundingClientRect().toJSON();
    const hit = el => { const b=el.getBoundingClientRect(),h=document.elementFromPoint(b.x+b.width/2,b.y+b.height/2); return h===el||el.contains(h) };
    return { viewport:{height:innerHeight,width:innerWidth,visualHeight:visualViewport.height,offsetTop:visualViewport.offsetTop,offsetLeft:visualViewport.offsetLeft,scale:visualViewport.scale},
      supportsDvh:CSS.supports('height','100dvh'), shell:r('.app-shell'),main:r('main'),nav:r('nav'),header:r('header'),layer:r('.dialog-layer'),dialog:r('.dialog'),scrim:r('.dialog-layer .scrim'),
      mainScroll:document.querySelector('main').scrollHeight, toolScroll:document.querySelector('.tool-list')?.scrollHeight || 0, keyboard:document.documentElement.dataset.keyboard,
      buttons:[...document.querySelectorAll('nav button')].map(el=>({text:el.textContent.trim(),rect:el.getBoundingClientRect().toJSON(),hit:hit(el)})),
      dialogButtons:[...document.querySelectorAll('.dialog button')].map(el=>({text:el.textContent.trim(),rect:el.getBoundingClientRect().toJSON(),hit:hit(el)})),
      covered:[[2,2],[innerWidth-2,2],[2,innerHeight-2],[innerWidth-2,innerHeight-2]].map(([x,y])=>!!document.elementFromPoint(x,y)?.closest('.dialog-layer')),
      css:Object.fromEntries(['--vvh','--vvw','--vv-top','--vv-left','--usable-bottom'].map(k=>[k,getComputedStyle(document.documentElement).getPropertyValue(k)])) };
  })()`)
  const navigationCheck = (g, label, bottom = g.viewport.height) => {
    check(Math.max(g.mainScroll,g.toolScroll) > g.main.height * 2, `${label}: real long content required (including the production tool-list scroll pane)`)
    check(g.shell.height <= bottom + 1 && g.main.bottom <= g.nav.top + 1, `${label}: shell/main must fit available viewport`)
    check(g.buttons.length === 5 && g.buttons.every(b => b.rect.height >= 32 && b.rect.bottom <= bottom + 1 && b.hit), `${label}: all 5 navigation buttons must be reachable before list end`)
  }
  const modalCheck = (g, label, { top = 0, left = 0, height = g.viewport.height, width = g.viewport.width, corners = true } = {}) => {
    check(Math.abs(g.layer.top-top)<1 && Math.abs(g.layer.height-height)<1 && Math.abs(g.layer.left-left)<1 && Math.abs(g.layer.width-width)<1, `${label}: modal layer must cover current visual viewport`)
    check(Math.abs(g.dialog.top+g.dialog.height/2-(top+height/2))<2, `${label}: dialog must be centered in visual viewport`)
    check(g.dialog.top>=top && g.dialog.bottom<=top+height+1 && g.dialog.left>=left && g.dialog.right<=left+width+1, `${label}: dialog must stay in visual viewport`)
    check(g.dialogButtons.length===2 && g.dialogButtons.every(b=>b.hit && b.rect.top>=top && b.rect.bottom<=top+height+1), `${label}: Cancel and Exit must be visible and hittable`)
    if(corners) check(g.covered.every(Boolean), `${label}: backdrop must cover all viewport corners`)
  }
  const initial = await geometry()
  observations.push({ test: 'long-list-initial', ...initial }); navigationCheck(initial, 'long-list-initial')
  await capture('long-list')
  await evaluate(`window.dispatchEvent(new CustomEvent('studio:back'))`)
  await poll(() => evaluate(`!!document.querySelector('.dialog')`))
  await delay(400)
  const exit = await geometry()
  observations.push({ test: 'exit-dialog', ...exit }); modalCheck(exit, 'exit-dialog')
  await capture('exit-dialog')
  // Do not lose the two primary RED observations behind later interaction errors.
  if (failures.length) throw Error(failures.join('\n'))

  await evaluate(`document.querySelector('.dialog .btn-ghost').click()`)
  await poll(() => evaluate(`!document.querySelector('.dialog')`))
  // Exercise the literal CSS fallback, independent of the measuring helper.
  // This is not an engine simulation: the engine above is still unchanged.
  await evaluate(`['--vvh','--vvw','--vv-top','--vv-left','--usable-bottom'].forEach(k=>document.documentElement.style.removeProperty(k))`)
  const fallback = await geometry()
  observations.push({test:'measured-variables-unset-css-fallback',...fallback})
  navigationCheck(fallback,'measured-variables-unset-css-fallback')
  await evaluate(`window.dispatchEvent(new CustomEvent('studio:back'))`)
  await delay(400)
  modalCheck(await geometry(),'measured-variables-unset-dialog-fallback')
  await evaluate(`document.querySelector('.dialog .btn-ghost').click();window.dispatchEvent(new Event('resize'))`)
  try {
    // Real CSS env values, when the engine exposes the CDP safe-area override.
    // Host final insets must replace these, not add/max them after native padding.
    await send('Emulation.setSafeAreaInsetsOverride',{insets:{top:24,bottom:20,left:4,right:4}})
    const browserSafe = await evaluate(`(()=>{const el=document.createElement('div');el.style.cssText='position:absolute;padding-top:var(--safe-top);padding-bottom:var(--safe-bottom)';document.body.append(el);const s=getComputedStyle(el),v={top:s.paddingTop,bottom:s.paddingBottom};el.remove();return v})()`)
    observations.push({test:'browser-safe-area-preserved',...browserSafe})
    check(browserSafe.top==='24px' && browserSafe.bottom==='20px','browser mode must retain env safe areas')
    await evaluate(`window.dispatchEvent(new CustomEvent('studio:insets',{detail:{top:0,bottom:0,left:0,right:0,ime:0,rawIme:0,rawTop:63,rawBottom:63}}))`)
    const safe = await evaluate(`(()=>{const el=document.createElement('div');el.style.cssText='position:absolute;padding:var(--safe-top) var(--safe-right) var(--safe-bottom) var(--safe-left)';document.body.append(el);const s=getComputedStyle(el),v={top:s.paddingTop,bottom:s.paddingBottom,left:s.paddingLeft,right:s.paddingRight};el.remove();return v})()`)
    observations.push({test:'native-final-safe-area-no-double-inset',...safe})
    check(Object.values(safe).every(v=>v==='0px'),'native final insets must not reapply CSS safe-area already consumed by the host')
    await send('Emulation.setSafeAreaInsetsOverride',{insets:{top:0,bottom:0,left:0,right:0}})
  } catch(error) {
    if(!error.message.includes('wasn\'t found')) throw error
    observations.push({test:'native-safe-area-cdp-override',unsupported:true})
  }
  for (const label of ['书源','技能','验证','日志','MCP']) {
    const point = await evaluate(`(() => { const el=[...document.querySelectorAll('nav button')].find(b=>b.textContent.trim()===${JSON.stringify(label)}); const r=el.getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2} })()`)
    await send('Input.dispatchMouseEvent', { type:'mousePressed', button:'left', clickCount:1, ...point })
    await send('Input.dispatchMouseEvent', { type:'mouseReleased', button:'left', clickCount:1, ...point })
    await poll(() => evaluate(`document.querySelector('nav .on').textContent.trim()===${JSON.stringify(label)}`))
    await delay(150)
    observations.push({ test: 'navigation-pointer', label, ...(await geometry()) })
  }

  // Dynamic browser toolbar/keyboard/offset values are a labelled seam
  // simulation, NOT evidence of Android IME or a real browser toolbar.
  await evaluate(`window.qa.realViewport=window.visualViewport; window.qa.viewport=Object.assign(new EventTarget(),{height:748,width:390,offsetTop:0,offsetLeft:0,scale:1});Object.defineProperty(window,'visualViewport',{configurable:true,value:window.qa.viewport});void 0`)
  // Reload uses the native object again; App listeners attach on mount. Trigger
  // window resize here to exercise a changed visual rectangle in the same App.
  await evaluate(`window.dispatchEvent(new Event('resize'))`)
  await delay(100)
  const toolbar = await geometry()
  observations.push({ test:'simulated-browser-toolbar-96px', ...toolbar })
  navigationCheck(toolbar, 'simulated-browser-toolbar-96px', 748)
  check(toolbar.keyboard==='0', '96px browser toolbar is not a keyboard')
  await evaluate(`Object.assign(qa.viewport,{height:156});window.dispatchEvent(new Event('resize'))`)
  await delay(100)
  const shortVisual = await geometry()
  observations.push({test:'simulated-short-visual-viewport-without-ime',...shortVisual})
  navigationCheck(shortVisual,'simulated-short-visual-viewport-without-ime',156)
  check(shortVisual.main.height>=70, 'short visual viewport needs compact chrome without an IME')
  await evaluate(`Object.assign(qa.viewport,{height:748});window.dispatchEvent(new Event('resize'))`)

  await evaluate(`window.dispatchEvent(new CustomEvent('studio:back'))`)
  await delay(400)
  modalCheck(await geometry(), 'simulated-toolbar-dialog', {height:748})
  await evaluate(`Object.assign(qa.viewport,{height:320,width:320,offsetTop:80,offsetLeft:16,scale:1.2});window.dispatchEvent(new Event('resize'))`)
  await delay(100)
  const offset = await geometry()
  observations.push({ test:'simulated-offset-and-zoom', ...offset })
  modalCheck(offset, 'simulated-offset-and-zoom', {top:80,left:16,height:320,width:320})
  check(offset.keyboard==='0', 'pinch zoom must not be classified as an IME')
  await capture('simulated-offset-dialog')
  await evaluate(`document.querySelector('.dialog .btn-ghost').click();Object.defineProperty(window,'visualViewport',{configurable:true,value:qa.realViewport});window.dispatchEvent(new Event('resize'))`)

  // Actual Chromium page-scale change (not a JS viewport replacement). The
  // resulting native visualViewport event must reposition the same App modal.
  await send('Emulation.setPageScaleFactor',{pageScaleFactor:1.5})
  await evaluate(`window.dispatchEvent(new CustomEvent('studio:back'))`)
  await delay(400)
  const zoomed = await geometry()
  observations.push({test:'real-chromium-page-scale',...zoomed})
  check(zoomed.viewport.scale>1,'engine page scale must actually change')
  modalCheck(zoomed,'real-chromium-page-scale',{height:zoomed.viewport.visualHeight,width:390/zoomed.viewport.scale,top:zoomed.viewport.offsetTop,left:zoomed.viewport.offsetLeft,corners:false})
  check(zoomed.keyboard==='0','actual page zoom is not IME')
  check(zoomed.buttons.every(b=>b.rect.left>=zoomed.viewport.offsetLeft && b.rect.right<=zoomed.viewport.offsetLeft+390/zoomed.viewport.scale+1), 'actual page zoom keeps all five navigation controls in the visible width')
  await capture('real-page-scale-dialog')
  await evaluate(`document.querySelector('.dialog .btn-ghost').click()`)
  await poll(()=>evaluate(`!document.querySelector('.dialog')`))
  const zoomNavigation = await geometry()
  observations.push({test:'real-chromium-page-scale-navigation',...zoomNavigation})
  check(zoomNavigation.buttons.every(b=>b.hit && b.rect.bottom<=zoomNavigation.viewport.offsetTop+zoomNavigation.viewport.visualHeight+1),'all five navigation controls remain hittable after dismissing a zoomed dialog')
  await send('Emulation.setPageScaleFactor',{pageScaleFactor:1})

  await resize(275,156)
  await evaluate(`window.dispatchEvent(new CustomEvent('studio:insets',{detail:{top:0,bottom:0,left:0,right:0,ime:0,rawIme:780,rawTop:63,rawBottom:63,density:2.625}}))`)
  await delay(150)
  const ime = await geometry()
  observations.push({test:'simulated-native-already-resized-ime',...ime})
  check(ime.keyboard==='1' && ime.main.height>=108 && ime.main.bottom<=156, 'native rawIme is only a signal, not subtracted twice')
  await evaluate(`window.dispatchEvent(new CustomEvent('studio:insets',{detail:{top:0,bottom:0,ime:0,rawIme:0}}));window.dispatchEvent(new CustomEvent('studio:back'))`)
  await delay(400)
  const short = await geometry()
  observations.push({test:'short-dialog',...short})
  modalCheck(short,'short-dialog')
  await capture('short-dialog')
  await evaluate(`document.querySelector('.dialog .btn-ghost').click();document.querySelector('button[aria-label="设置"]').click()`)
  await delay(500)
  const sheet = await evaluate(`(()=>{const panel=document.querySelector('.sheet'),close=panel.querySelector('button[aria-label="关闭"]'),r=close.getBoundingClientRect();return {panel:panel.getBoundingClientRect().toJSON(),close:r.toJSON(),hit:close.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)),body:panel.querySelector('.sheet-body').getBoundingClientRect().toJSON()}})()`)
  observations.push({test:'short-settings-sheet',...sheet})
  check(sheet.close.top>=0 && sheet.close.bottom<=156 && sheet.hit && sheet.body.height>=32,'short settings close and scroll content remain reachable')
  await evaluate(`document.querySelector('.sheet-body .sheet-foot .btn-primary').scrollIntoView({block:'end'})`)
  await delay(100)
  const save = await evaluate(`(()=>{const el=document.querySelector('.sheet-body .sheet-foot .btn-primary'),r=el.getBoundingClientRect(),b=document.querySelector('.sheet-body').getBoundingClientRect();return {rect:r.toJSON(),body:b.toJSON(),x:r.x+r.width/2,y:r.y+r.height/2,hit:el.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))}})()`)
  observations.push({test:'short-settings-save',...save})
  check(save.hit && save.rect.top>=save.body.top && save.rect.bottom<=save.body.bottom+1,'short settings Save must be reachable inside its real scroll pane')
  if(save.hit){
    await send('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,x:save.x,y:save.y})
    await send('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,x:save.x,y:save.y})
    await poll(()=>evaluate(`qa.calls.some(c=>c.method==='settings.save')`))
  }
  await evaluate(`document.querySelector('.sheet button[aria-label="关闭"]').click()`)

  // Native verification reserves precisely 96 CSS px, even in compact mode.
  for (const height of [156,844]) {
    await resize(275,height)
    await evaluate(`window.dispatchEvent(new CustomEvent('studio:verification-browser',{detail:{action:'open',id:'viewport-session-0'}}))`)
    await delay(150)
    const verify = await evaluate(`(()=>{const h=document.querySelector('header');return {height:h.getBoundingClientRect().height,buttons:[...h.querySelectorAll('button')].map(el=>{const r=el.getBoundingClientRect();return {label:el.textContent.trim(),bottom:r.bottom,top:r.top,hit:el.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))}}),main:getComputedStyle(document.querySelector('main')).display,nav:getComputedStyle(document.querySelector('nav')).display}})()`)
    observations.push({test:'native-verification-toolbar',viewportHeight:height,...verify})
    check(verify.height===96 && verify.main==='none' && verify.nav==='none' && verify.buttons.length===2 && verify.buttons.every(b=>b.top>=0 && b.bottom<=96 && b.hit),'native verification toolbar remains 96px with reachable Back/Complete')
    await evaluate(`window.dispatchEvent(new CustomEvent('studio:verification-browser',{detail:{action:'closed',id:'viewport-session-0'}}))`)
  }
  check(pageErrors.length===0, `No runtime errors: ${JSON.stringify(pageErrors)}`)
  if (failures.length) throw Error(failures.join('\n'))
  console.log(`PASS ${version.product}: full App/CSS, long list navigation, shared modals, dynamic viewport seam, IME signal and 96px verification toolbar`)
} catch (error) {
  console.error(error)
  observations.push({test:'failure',message:error.message})
  process.exitCode = 1
} finally {
  clearTimeout(overall)
  await mkdir(evidence,{recursive:true})
  await writeFile(join(evidence,'results.json'),JSON.stringify({observations,failures},null,2))
  ws?.close(); browser?.kill('SIGTERM')
  if (server) await new Promise(r=>server.httpServer.close(r))
  await delay(400)
  await rm(temp,{recursive:true,force:true,maxRetries:5,retryDelay:200})
}
