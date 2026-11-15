// UI209 real browser / production App and CSS; bridge fixture is not Android evidence.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const evidence = resolve(process.env.CONTROL_EVIDENCE || join(tmpdir(), 'ui210-controls'))
const cases = [{ width: 390, theme: 'light', scale: 1 }, { width: 320, theme: 'dark', scale: 1 }]
const results = { kind: 'BROWSER_FIXTURE_NOT_ANDROID_EVIDENCE', expectedCases: cases.length, cases: [], errors: [] }
await mkdir(evidence, { recursive: true })
const profile = await mkdtemp(join(tmpdir(), 'ui210-chrome-'))
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
      document.querySelectorAll('[data-ui210-click]').forEach(e => e.removeAttribute('data-ui210-click'))
      const matches = [...document.querySelector(scope).querySelectorAll('button')].filter(e => e.getClientRects().length && e.textContent.trim() === label)
      if (matches.length !== 1) return matches.length
      matches[0].setAttribute('data-ui210-click', ''); return true
    }, { label, scope })
    assert.equal(ok, true, `Unique visible button: ${label}, got ${ok}`)
    return click('[data-ui210-click]')
  }
  async function screenshot(name) {
    await cdp('Input.dispatchMouseEvent', {type:'mouseMoved',x:1,y:1})
    await delay(220)
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
    lastCase = {key, ...settings, passed:false}; results.cases.push(lastCase)
    if (initScript) await cdp('Page.removeScriptToEvaluateOnNewDocument',{identifier:initScript})
    const init = ({theme,key}) => {
      window.controlsQA = {key,calls:[],phase:'active',contextId:'cap:12345678-1234-1234-1234-123456789abc'}
      const text = d=>({content:[{type:'text',text:JSON.stringify(d)}]})
      window.StudioBridge = {postMessage(raw){
        const req=JSON.parse(raw);controlsQA.calls.push(req);let result={}
        if(req.method==='app.bootstrap')result={versionName:'2.0.10',service:{running:true,tokenRequired:true},appearance:{theme,palette:'cherry'},capabilities:{interactiveCapture:true,logShare:true}}
        if(req.method==='tools.list')result={tools:[]}
        if(req.method==='tools.call'){
          const {name,arguments:a}=req.params
          if(['get_logs','get_http_logs'].includes(name))result=text({total:3,items:[1,2,3].map(id=>({id,level:'I',category:'mcp',message:'演示日志 · MCP 请求完成',url:'https://example.invalid/book/123',method:'GET',statusCode:200,timestamp:1794744000000}))})
          else if(name==='list_captures')result=text({total:1,items:[{contextId:controlsQA.contextId,url:'https://example.invalid/',requestCount:2}]})
          else if(name==='get_capture')result=text({total:2,items:[{id:1,url:'https://example.invalid/a',method:'GET',statusCode:200},{id:2,url:'https://example.invalid/b',method:'GET',statusCode:200}]})
          else if(name==='get_http_log_recording')result=text({enabled:true})
          else if(name==='get_log')result=text({id:a.id,message:'演示正文'})
          else result=text({items:[]})
        }
        if(req.method==='logs.dayCount')result={kind:req.params.kind,date:req.params.date,count:3}
        if(req.method==='capture.browser.finish')controlsQA.phase='saved'
        if(req.method.startsWith('capture.browser.'))result={phase:controlsQA.phase,contextId:controlsQA.contextId,canGoBack:false,canGoForward:false,total:2,url:'https://example.invalid/'}
        if(req.method==='capture.browser.transactions')result={items:[{id:1,url:'https://example.invalid/a',method:'GET',statusCode:200},{id:2,url:'https://example.invalid/b',method:'GET',statusCode:200}]}
        if(req.method==='capture.browser.close')result={closed:true,saved:controlsQA.phase==='saved'}
        queueMicrotask(()=>this.onmessage?.({data:JSON.stringify({id:req.id,ok:true,result})}))
      }}
    }
    initScript=(await cdp('Page.addScriptToEvaluateOnNewDocument',{source:`(${init.toString()})(${JSON.stringify({theme:settings.theme,key})})`})).identifier
    await cdp('Emulation.setDeviceMetricsOverride',{width:settings.width,height:844,deviceScaleFactor:1,mobile:false})
    await cdp('Page.navigate',{url:server.resolvedUrls.local[0]})
    await poll(()=>evaluate(key=>window.controlsQA?.key===key&&!!document.querySelector('nav button'),key),'App ready')
    await click('nav button',4)
    await poll(()=>evaluate(()=>document.querySelectorAll('.log-entry').length===3),'real LogsPage fixture list')
    const checkbox=()=>evaluate(()=>{
      const e=document.querySelector('.log-entry input[type=checkbox]'),s=getComputedStyle(e)
      return {appearance:s.appearance,width:e.getBoundingClientRect().width,height:e.getBoundingClientRect().height,border:s.borderStyle,radius:s.borderRadius,checked:e.checked,before:getComputedStyle(e,'::before').content,after:getComputedStyle(e,'::after').content}
    })
    lastCase.checkboxUnchecked=await checkbox()
    assert.equal(lastCase.checkboxUnchecked.appearance,'none','Checkbox must be theme-drawn, not system/native appearance')
    await click('.log-entry input[type=checkbox]')
    assert.equal((await checkbox()).checked,true,'Real pointer updates Vue selected state')
    await cdp('Input.dispatchKeyEvent',{type:'keyDown',key:' ',code:'Space',windowsVirtualKeyCode:32})
    await cdp('Input.dispatchKeyEvent',{type:'keyUp',key:' ',code:'Space',windowsVirtualKeyCode:32})
    await delay(50)
    assert.equal((await checkbox()).checked,false,'Keyboard Space preserves input semantics')
    lastCase.indeterminate=await evaluate(()=>{
      const e=document.querySelector('.log-entry input[type=checkbox]');e.indeterminate=true
      const s=getComputedStyle(e,'::after'),r={content:s.content,transform:s.transform,height:s.height,borderBottomWidth:s.borderBottomWidth,borderBottomStyle:s.borderBottomStyle};e.indeterminate=false;return r
    })
    assert.notEqual(lastCase.indeterminate.content,'none','Mixed selection must be drawn')
    assert.equal(lastCase.indeterminate.height,'0px','Mixed state is a horizontal stroke')
    assert.equal(lastCase.indeterminate.transform,'none','Mixed stroke is not scaled to zero')
    assert.equal(lastCase.indeterminate.borderBottomStyle,'solid')
    assert.equal(lastCase.indeterminate.borderBottomWidth,'2px')
    const statePaint=await evaluate(()=>{
      const e=document.querySelector('.log-entry input[type=checkbox]');e.focus()
      const focus=getComputedStyle(e),f={outlineStyle:focus.outlineStyle,outlineWidth:focus.outlineWidth}
      e.disabled=true;const disabled={opacity:getComputedStyle(e).opacity,matches:e.matches(':disabled')};e.disabled=false
      return {focus:f,disabled}
    })
    assert.equal(statePaint.focus.outlineStyle,'solid');assert.equal(statePaint.focus.outlineWidth,'2px')
    assert.equal(statePaint.disabled.matches,true);assert.ok(Number(statePaint.disabled.opacity)<1)
    lastCase.checkboxStates=statePaint
    async function audit(label){
      const info=await evaluate(()=>{
        const visible=e=>e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden'
        const controls=[...document.querySelectorAll('main button,.capture-shell button')].filter(visible)
        const bad=controls.filter(e=>!e.className||getComputedStyle(e).appearance!=='none').map(e=>({text:e.textContent,classes:e.className,appearance:getComputedStyle(e).appearance}))
        const main=document.querySelector('main'),scope=document.querySelector('.capture-shell')||main
        const overflow=[...scope.querySelectorAll('button,input,textarea,time')].filter(visible).filter(e=>{const r=e.getBoundingClientRect();return r.left<-.5||r.right>innerWidth+.5}).map(e=>({text:e.textContent.slice(0,60),rect:e.getBoundingClientRect().toJSON()}))
        return {bad,overflow,bodyOverflow:document.documentElement.scrollWidth>innerWidth+1,controls:controls.length}
      })
      assert.deepEqual(info.bad,[],`${label} no default system buttons`)
      assert.deepEqual(info.overflow,[],`${label} no clipped horizontal controls`)
      assert.equal(info.bodyOverflow,false,`${label} no horizontal scrolling`)
      ;(lastCase.audits ||= []).push({label,...info})
    }
    await audit('operation')
    await evaluate(()=>document.querySelector('main').scrollTop=0)
    await screenshot(`${key}-operation.png`)
    await byLabel('删除该日全部')
    await poll(()=>evaluate(()=>!!document.querySelector('.delete-dialog')),'delete confirmation')
    await byLabel('取消','.delete-dialog')
    assert.equal(await evaluate(()=>controlsQA.calls.some(c=>['logs.delete','logs.deleteDay'].includes(c.method))),false,'Cancel must not delete')
    await click('details>summary')
    await byLabel('读取录制状态')
    await poll(()=>evaluate(()=>[...document.querySelectorAll('button')].some(e=>e.textContent.trim()==='关闭 HTTP 录制')),'recording readback')
    await audit('diagnostics expanded')
    assert.equal(await evaluate(()=>getComputedStyle(document.querySelector('textarea[aria-label="请求头（每行 名称: 值）"]')).resize),'none','No platform textarea resize handle')
    await evaluate(()=>document.querySelector('main').scrollTop=0)
    await screenshot(`${key}-diagnostics.png`)
    await click('details>summary')
    await byLabel('HTTP')
    await audit('http')
    await screenshot(`${key}-http.png`)
    await byLabel('导出')
    await poll(()=>evaluate(()=>!!document.querySelector('[aria-label="日志导出设置"]')),'export settings')
    await audit('export settings')
    await byLabel('系统分享','[aria-label="日志导出设置"]')
    await audit('export confirm')
    await byLabel('取消分享','[aria-label="日志导出设置"]')
    await evaluate(()=>document.querySelector('main').scrollTop=0)
    await screenshot(`${key}-export.png`)
    await byLabel('取消导出','[aria-label="日志导出设置"]')
    await byLabel('抓包')
    await audit('capture')
    await evaluate(()=>document.querySelector('main').scrollTop=0)
    await screenshot(`${key}-capture.png`)
    await byLabel('浏览器抓包')
    await poll(()=>evaluate(()=>!!document.querySelector('.capture-workbench')),'workbench')
    await audit('workbench')
    await screenshot(`${key}-workbench.png`)
    await byLabel('返回','.capture-workbench')
    await poll(()=>evaluate(()=>!document.querySelector('.capture-workbench')),'close workbench')
    assert.equal(await evaluate(()=>controlsQA.calls.some(c=>['logs.export','logs.share','logs.delete','logs.deleteDay','capture.browser.export','capture.browser.open','capture.browser.start','capture.browser.navigate'].includes(c.method))),false,'Visual probes must not commit network/export/delete writes')
    assert.equal(await evaluate(()=>document.querySelectorAll('select,input[type=date],input[type=color]').length),0)
    lastCase.passed=true
    await writeFile(join(evidence,'results.json'),JSON.stringify(results,null,2))
    console.log(`PASS ${key}: themed checkbox semantics; operation/http/capture/diagnostics/export/workbench skins and safe cancels`)
  }
  assert.equal(results.cases.length,results.expectedCases);assert.deepEqual(results.errors,[])
} catch(error) {
  results.errors.push({case:lastCase?.key,message:error.message});console.error(error);process.exitCode=1
} finally {
  clearTimeout(deadline);await writeFile(join(evidence,'results.json'),JSON.stringify(results,null,2))
  ws?.close();chrome?.kill('SIGTERM');await server.close();await delay(300)
  await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200})
}
