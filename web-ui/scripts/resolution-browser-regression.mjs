// Complete packaged UI on an actual Chromium engine, not a UA emulation.
// Only the host bridge is a labelled fixture. Never visits external sites.
// UI_DIST can point at assets extracted from the released APK for RED evidence.
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { readFile, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { resolve, join, extname, sep } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
const webRoot = resolve(fileURLToPath(new URL('..', import.meta.url)))
const dist = resolve(process.env.UI_DIST || join(webRoot, 'dist'))
const out = resolve(process.env.UI_EVIDENCE || join(tmpdir(), 'ui-resolution'))
const widths = (process.env.UI_WIDTHS || '275,360,390,900').split(',').map(Number)
const port = Number(process.env.UI_CDP_PORT || 9304)
const checks = [], pageErrors = []
await mkdir(out, { recursive: true })
const profile = await mkdtemp(join(tmpdir(), 'resolution-chrome-'))
const mime = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.wasm': 'application/wasm' }
const server = createServer(async (req, res) => {
  try {
    const path = resolve(dist, '.' + decodeURIComponent(new URL(req.url, 'http://localhost').pathname.replace(/\/$/, '/index.html')))
    if (!path.startsWith(dist + sep)) throw Error('outside dist')
    res.setHeader('Content-Type', mime[extname(path)] || 'application/octet-stream')
    res.end(await readFile(path))
  } catch { res.writeHead(404); res.end('not found') }
})
await new Promise(r => server.listen(0, '127.0.0.1', r))
const url = `http://127.0.0.1:${server.address().port}/`
const chrome = spawn(process.env.CHROMIUM || '/usr/bin/chromium', ['--headless', '--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu', '--no-zygote', '--single-process', '--disable-background-networking', '--disable-component-update', '--remote-allow-origins=*', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: ['ignore', 'ignore', 'ignore'] })
const delay = ms => new Promise(r => setTimeout(r, ms))
let ws
const timeout = setTimeout(() => { chrome.kill('SIGTERM'); server.close(); console.error('overall timeout'); process.exit(124) }, 180000)
try {
  async function poll(fn) { for (let i=0;i<100;i++) { try { const v=await fn(); if(v)return v } catch {} await delay(100) } throw Error('poll timeout') }
  const target = await poll(async () => (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find(t => t.type === 'page'))
  ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise(r => ws.addEventListener('open', r, { once: true }))
  let id = 0; const pending = new Map()
  ws.addEventListener('message', e => { const m=JSON.parse(e.data); if (m.id) { const p=pending.get(m.id); pending.delete(m.id); m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result) } else if(m.method==='Runtime.exceptionThrown') pageErrors.push(m.params.exceptionDetails) })
  const send = (method, params={}) => new Promise((resolve,reject) => { const n=++id;pending.set(n,{resolve,reject});ws.send(JSON.stringify({id:n,method,params})) })
  const js = async expression => { const r=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails) throw Error(JSON.stringify(r.exceptionDetails));return r.result.value }
  const version = await send('Browser.getVersion')
  await send('Page.enable'); await send('Runtime.enable')
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `
    const names=['大帝书阁','书海阁','⚡📁星空小说','三九小说','特别长的中文书源名称用于检查完整标题是否仍可阅读','EnglishTitleWithoutSeparators'.repeat(4)];
    window.qaResolution={calls:[],sources:Array.from({length:20},(_,i)=>({projectId:'resolution-'+i,bookSourceName:names[i%names.length],bookSourceUrl:'https://example.invalid/'+(i>3?'long-path-'.repeat(20):'source-'+i),stage:'VALIDATED',versionCount:3,updatedAt:1793250000000}))};
    const toolResult=x=>({content:[{type:'text',text:typeof x==='string'?x:JSON.stringify(x)}]});
    window.StudioBridge={postMessage(raw){const m=JSON.parse(raw);qaResolution.calls.push(m);let result={};
      if(m.method==='app.bootstrap')result={versionName:'QA packaged UI',versionCode:0,service:{running:false},appearance:{theme:'dark',palette:'lime'}};
      if(m.method==='tools.list')result={tools:Array.from({length:73},(_,i)=>({name:'qa_tool_'+i,description:'仅用于布局的工具描述 '.repeat(5),inputSchema:{type:'object',properties:{}}}))};
      if(m.method==='tools.call') {const {name,arguments:a}=m.params;let data={};
        if(name==='list_sources')data={items:qaResolution.sources,total:20,hasMore:false};
        if(name==='get_source')data=JSON.stringify({bookSourceName:'大帝书阁',bookSourceUrl:'https://example.invalid/'});
        if(name==='get_http_logs'||name==='get_logs')data={items:Array.from({length:25},(_,i)=>({id:String(i+1),method:'GET',url:'https://example.invalid/log/'+i,message:'layout fixture '+i,createdAt:1793250000000})),total:25};
        if(name==='list_skills')data={skills:[]};
        result=toolResult(data);
      }
      if(m.method==='verification.list')result={items:[]};
      queueMicrotask(()=>this.onmessage?.({data:JSON.stringify({id:m.id,ok:true,result})}));
    }};` })
  function check(name, ok, details) { checks.push({name,ok:Boolean(ok),details}); console.log(JSON.stringify(checks.at(-1))) }
  const shot = async name => { const p=await send('Page.captureScreenshot',{format:'png'});await writeFile(join(out,name+'.png'),Buffer.from(p.data,'base64')) }
  const navGeometry = () => js(`(()=>{const r=s=>document.querySelector(s).getBoundingClientRect().toJSON();const nav=document.querySelector('nav'),n=nav.getBoundingClientRect();return {height:innerHeight,width:innerWidth,dvh:CSS.supports('height','100dvh'),bodyHeight:document.documentElement.scrollHeight,bodyWidth:document.documentElement.scrollWidth,main:r('main'),shell:r('.app-shell'),nav:r('nav'),hits:[...nav.querySelectorAll('button')].map(e=>{const q=e.getBoundingClientRect();return e.contains(document.elementFromPoint(q.x+q.width/2,q.y+q.height/2))})}})()`)
  const dialogGeometry = () => js(`(()=>{const e=document.querySelector('.delete-dialog,[role=alertdialog]'),r=e.getBoundingClientRect(),layer=e.closest('.sheet-layer').getBoundingClientRect();return {rect:r.toJSON(),layer:layer.toJSON(),viewport:innerHeight,width:innerWidth,centerError:Math.abs(r.top+r.height/2-innerHeight/2),covered:[!!document.elementFromPoint(8,8)?.closest('.sheet-layer'),!!document.elementFromPoint(innerWidth/2,innerHeight-8)?.closest('.sheet-layer')],buttons:[...e.querySelectorAll('button')].map(b=>{const q=b.getBoundingClientRect();return {text:b.textContent.trim(),rect:q.toJSON(),hit:b.contains(document.elementFromPoint(q.x+q.width/2,q.y+q.height/2))}})}})()`)
  for (const width of widths) {
    const height=width>=900?650:780
    await send('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false})
    await send('Page.navigate',{url});await poll(()=>js(`document.querySelectorAll('.tool-row').length===73`));await delay(200)
    let g=await navGeometry()
    check(`${width}:initial-navigation`,g.nav.bottom<=height+1 && g.main.bottom<=g.nav.top+1 && g.hits.length===5 && g.hits.every(Boolean) && g.bodyHeight<=height+1,g)
    await js(`window.dispatchEvent(new CustomEvent('studio:back'))`);await poll(()=>js(`!!document.querySelector('[role=alertdialog]')`));await delay(400)
    g=await dialogGeometry();check(`${width}:exit-centered`,g.centerError<2 && g.covered.every(Boolean) && g.buttons.every(b=>b.hit),g);await shot(`${width}-exit`)
    await js(`window.dispatchEvent(new CustomEvent('studio:back'));window.dispatchEvent(new CustomEvent('studio:route',{detail:{page:'sources'}}))`);await poll(()=>js(`document.querySelectorAll('.source-row').length===20`));await delay(150)
    const names=await js(`[...document.querySelectorAll('.source-row')].slice(0,6).map(e=>{const n=e.querySelector('.source-name'),m=e.querySelector('.source-meta'),q=n.getBoundingClientRect(),r=e.getBoundingClientRect();const range=document.createRange();range.selectNodeContents(n);return {text:n.textContent,row:r.toJSON(),name:q.toJSON(),meta:m.getBoundingClientRect().toJSON(),lines:[...range.getClientRects()].map(v=>v.toJSON()),overflow:e.scrollWidth>e.clientWidth+1}})`)
    check(`${width}:names-not-squeezed`,names.slice(0,2).every(n=>n.lines.length===1 && !n.overflow),names)
    g=await navGeometry();check(`${width}:source-navigation`,g.nav.bottom<=height+1 && g.main.bottom<=g.nav.top+1 && g.hits.every(Boolean) && g.bodyWidth<=width+1,g);await shot(`${width}-sources`)
    await js(`document.querySelector('main').scrollTop=document.querySelector('main').scrollHeight`);await delay(100)
    g=await navGeometry();check(`${width}:navigation-after-scroll`,g.nav.bottom<=height+1 && g.hits.every(Boolean),g)
    await js(`window.dispatchEvent(new CustomEvent('studio:route',{detail:{page:'logs'}}))`);await poll(()=>js(`!!document.querySelector('.log-row')`))
    await js(`[...document.querySelectorAll('main button')].find(b=>b.textContent.trim()==='删除').click()`);await poll(()=>js(`!!document.querySelector('.delete-dialog')`));await delay(400)
    g=await dialogGeometry();check(`${width}:log-delete-centered`,g.centerError<2 && g.covered.every(Boolean) && g.buttons.every(b=>b.hit),g);await shot(`${width}-log-delete`)
    // Explicit visualViewport seam simulation, not an Android keyboard claim.
    // Shared viewport code must apply to the LogsPage Teleport overlay as well.
    await js(`window.__realViewport=visualViewport;Object.defineProperty(window,'visualViewport',{configurable:true,value:Object.assign(new EventTarget(),{height:320,width:${Math.min(width,320)},offsetTop:80,offsetLeft:0,scale:1})});window.dispatchEvent(new Event('resize'))`)
    await delay(150)
    g=await dialogGeometry();check(`${width}:log-delete-visual-viewport`,Math.abs(g.rect.top+g.rect.height/2-240)<2 && g.rect.top>=80 && g.rect.bottom<=400 && g.buttons.every(b=>b.hit),{...g,simulatedVisualViewport:true});await shot(`${width}-log-visual`)
    await js(`Object.defineProperty(window,'visualViewport',{configurable:true,value:window.__realViewport});window.dispatchEvent(new Event('resize'))`)
  }
  check('no-js-exceptions',pageErrors.length===0,pageErrors)
  await writeFile(join(out,'results.json'),JSON.stringify({version,dist,scope:'Real Chromium rendering of packaged production assets; host data is a fixture, not Android persistence',checks},null,2))
  console.log(JSON.stringify({engine:version.product,checks:checks.length,failed:checks.filter(c=>!c.ok).map(c=>c.name)}))
  if(checks.some(c=>!c.ok))process.exitCode=1
} finally {clearTimeout(timeout);ws?.close();chrome.kill('SIGTERM');server.close();await delay(500);await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200})}
