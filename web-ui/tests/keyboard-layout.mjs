// Real Chromium geometry regression. Run with Node >=22; no APK/device mutation.
import { spawn } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
const profile = await mkdtemp(join(tmpdir(), 'keyboard-layout-'))
const vite = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '5199', '--strictPort'], { stdio: ['ignore', 'ignore', 'inherit'] })
const chrome = spawn(process.env.CHROMIUM || '/usr/bin/chromium', ['--headless', '--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu', '--no-zygote', '--single-process', '--remote-allow-origins=*', '--remote-debugging-port=9299', `--user-data-dir=${profile}`, 'about:blank'], { stdio: ['ignore', 'ignore', 'inherit'] })
// Bound the whole run, including fetch/WebSocket/CDP waits that may never settle.
// The outer cgroup runner also bounds the entire process tree.
const overallTimeout = setTimeout(() => {
  console.error('FAIL: keyboard-layout overall timeout (300s)')
  chrome.kill('SIGTERM'); vite.kill('SIGTERM')
  process.exit(124)
}, 300_000)
const delay = ms => new Promise(r => setTimeout(r, ms))
let ws
try {
  async function poll(fn) { for (let i=0;i<120;i++) { try { const v=await fn(); if(v) return v } catch {} await delay(100) } throw Error('timed out') }
  await poll(async () => (await fetch('http://127.0.0.1:5199')).ok)
  const target = await poll(async () => (await (await fetch('http://127.0.0.1:9299/json')).json()).find(t=>t.type==='page'))
  ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise(r=>ws.addEventListener('open',r,{once:true}))
  let id=0; const pending=new Map()
  ws.addEventListener('message', e=>{ const m=JSON.parse(e.data); if(m.id) { const p=pending.get(m.id); pending.delete(m.id); m.error?p.reject(m.error):p.resolve(m.result) } })
  const send=(method,params={})=>new Promise((resolve,reject)=>{const n=++id;pending.set(n,{resolve,reject});ws.send(JSON.stringify({id:n,method,params}))})
  const evaluate=async expression=>{const r=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value}
  await send('Page.enable')
  // Only the Android bridge is simulated; Vue, CSS, scrolling, hit-testing and
  // pointer/text input run in Chromium. These fixtures never touch device data.
  await send('Page.addScriptToEvaluateOnNewDocument',{source:`
    window.qa={calls:[],source:JSON.stringify({bookSourceName:'IME fixture',bookSourceUrl:'https://example.invalid'},null,2),skill:'# IME fixture'};
    window.StudioBridge={postMessage(raw){
      const m=JSON.parse(raw); let result={};
      if(m.method==='tools.list') result={tools:[{name:'keyboard_fixture',description:'Keyboard layout fixture',inputSchema:{type:'object',properties:{}}}]};
      if(m.method==='app.bootstrap') result={service:{running:true},appearance:{theme:'dark',palette:'lime'}};
      if(m.method==='tools.call') {
        const {name,arguments:a}=m.params; qa.calls.push({name,args:a}); let data={};
        if(name==='list_sources') data={items:[{projectId:'ime-source',bookSourceName:'IME fixture',bookSourceUrl:'https://example.invalid'}]};
        if(name==='get_source') data=qa.source;
        if(name==='save_source') {qa.source=a.source;data={projectId:'ime-source'};}
        if(name==='list_skills') data={skills:[{id:'ime-skill',name:'IME fixture',builtIn:false,enabled:true}]};
        if(name==='get_skill') data=qa.skill;
        if(name==='save_skill') qa.skill=a.markdown;
        result={content:[{type:'text',text:typeof data==='string'?data:JSON.stringify(data)}]};
      }
      queueMicrotask(()=>this.onmessage?.({data:JSON.stringify({id:m.id,ok:true,result})}));
    }};`})
  const resize=(height)=>send('Emulation.setDeviceMetricsOverride',{width:275,height,deviceScaleFactor:1,mobile:false})
  await resize(600)
  await send('Page.navigate',{url:'http://127.0.0.1:5199'})
  try { await poll(()=>evaluate(`!!document.querySelector('.tool-row')`)) }
  catch (error) { console.log(await evaluate(`document.body.innerText`)); throw error }
  await evaluate(`document.querySelector('.tool-row').click()`)
  await poll(()=>evaluate(`!!document.querySelector('textarea')`))
  await evaluate(`document.querySelector('textarea').focus()`)
  await resize(156)
  await evaluate(`window.dispatchEvent(new CustomEvent('studio:insets',{detail:{top:0,bottom:0,ime:0,rawIme:780,rawBottom:63,density:2.625}}))`)
  await delay(250)
  const geometry=await evaluate(`(()=>{ const rect=s=>{const r=document.querySelector(s).getBoundingClientRect();return {top:r.top,bottom:r.bottom,height:r.height}};return {innerHeight,visualHeight:visualViewport.height,keyboard:document.documentElement.dataset.keyboard,main:rect('main'),head:rect('header'),nav:getComputedStyle(document.querySelector('nav')).display} })()`)
  console.log(JSON.stringify(geometry))
  assert.equal(geometry.innerHeight,156)
  assert.equal(geometry.visualHeight,156)
  assert.equal(geometry.nav,'none')
  assert.ok(geometry.main.height>=108,`editor viewport too short: ${geometry.main.height}`)
  assert.ok(geometry.main.bottom<=156)
  const editor = await evaluate(`(()=>{const r=document.querySelector('textarea').getBoundingClientRect();const back=document.querySelector('[data-detail-back]').getBoundingClientRect();return {top:r.top,bottom:r.bottom,backBottom:back.bottom,visible:Math.min(r.bottom,innerHeight)-Math.max(r.top,back.bottom,document.querySelector('main').getBoundingClientRect().top)}})()`)
  console.log('focused editor', JSON.stringify(editor))
  assert.ok(editor.visible>=40, `focused editor hidden: ${JSON.stringify(editor)}`)
  // A bounding rect alone is not clickability: sticky Back used to paint over
  // both the editor and the action after scrolling in the 110px main pane.
  async function hitGeometry(selector, block = 'start') {
    await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:${JSON.stringify(block)}})`)
    await delay(100)
    return evaluate(`(() => {
      const el=document.querySelector(${JSON.stringify(selector)}), r=el.getBoundingClientRect();
      const m=document.querySelector('main').getBoundingClientRect();
      const top=Math.max(r.top,m.top), bottom=Math.min(r.bottom,m.bottom);
      const points=[0.15,0.5,0.85].map(f=>({x:r.left+r.width/2,y:top+(bottom-top)*f}));
      return {rect:r.toJSON(),main:m.toJSON(),visible:bottom-top,points,
        hits:points.map(p=>{const h=document.elementFromPoint(p.x,p.y);return {ok:h===el||el.contains(h),tag:h?.outerHTML.slice(0,160)}})};
    })()`)
  }
  const editorHit=await hitGeometry('textarea')
  console.log('editor hit geometry',JSON.stringify(editorHit))
  assert.ok(editorHit.visible>=40)
  assert.ok(editorHit.hits.every(h=>h.ok),'editor covered: '+JSON.stringify(editorHit))
  // Back stays in the Vue detail, not a native dialog or replacement page.
  await evaluate(`document.querySelector('main').scrollTop=0`)
  const back=await evaluate(`(()=>{const r=document.querySelector('[data-detail-back]').getBoundingClientRect();return {top:r.top,bottom:r.bottom}})()`)
  assert.ok(back.top>=geometry.main.top && back.bottom<=156,JSON.stringify(back))
  await evaluate(`window.dispatchEvent(new CustomEvent('studio:insets',{detail:{top:0,bottom:0,ime:0,rawIme:0}}))`)
  await resize(600)
  await delay(200)
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('nav')).display==='none'`),false)
  await evaluate(`document.querySelector('[data-detail-back]').click()`)
  await poll(()=>evaluate(`!document.querySelector('[data-detail-back]')`))
  assert.equal(await evaluate(`document.querySelectorAll('select,input[type=date],[role=alertdialog]').length`),0)
  async function pointer(point) {
    await send('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,...point})
    await send('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,...point})
  }
  // Exercise both IME and short landscape without an IME signal, in both modes.
  for (const theme of ['light','dark']) for (const ime of [780,0]) for (const page of ['sources','skills']) {
    await resize(600)
    await evaluate(`window.dispatchEvent(new CustomEvent('studio:route',{detail:{page:${JSON.stringify(page)}}}))`)
    const row=page==='sources'?'.source-row':'.skill-row'
    if(page==='skills') {
      await poll(()=>evaluate(`Array.from(document.querySelectorAll('button')).some(b=>b.textContent.includes('自定义'))`))
      await evaluate(`Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes('自定义')).click()`)
    }
    await poll(()=>evaluate(`!!document.querySelector(${JSON.stringify(row)})`))
    await evaluate(`document.querySelector(${JSON.stringify(row)}).click()`)
    await poll(()=>evaluate(`!!document.querySelector('textarea')`))
    await evaluate(`document.documentElement.style.colorScheme=${JSON.stringify(theme)};document.documentElement.dataset.mode=${JSON.stringify(theme)}`)
    await resize(156)
    await evaluate(`window.dispatchEvent(new CustomEvent('studio:insets',{detail:{top:0,bottom:0,ime:0,rawIme:${ime}}}));document.querySelector('main').scrollTop=0`)
    await delay(150)
    const firstBack=await hitGeometry('[data-detail-back]')
    console.log('back',theme,ime,page,JSON.stringify(firstBack))
    assert.ok(firstBack.visible>=32 && firstBack.hits.every(h=>h.ok), 'initial Back inaccessible: '+JSON.stringify({theme,ime,page,...firstBack}))
    const field=await hitGeometry('textarea')
    console.log('edit',theme,ime,page,JSON.stringify(field))
    assert.ok(field.visible>=40 && field.hits.every(h=>h.ok),'editor covered: '+JSON.stringify(field))
    await pointer(field.points[1])
    assert.equal(await evaluate(`document.activeElement.tagName`),'TEXTAREA')
    await evaluate(`document.querySelector('textarea').select()`)
    const text=page==='sources'?JSON.stringify({bookSourceName:'typed '+theme+ime,bookSourceUrl:'https://example.invalid'}):'# typed '+theme+ime
    await send('Input.insertText',{text})
    assert.equal(await evaluate(`document.querySelector('textarea').value`),text)
    await evaluate(`Array.from(document.querySelectorAll('main .btn-primary')).find(b=>b.getClientRects().length && b.textContent.includes('保存')).setAttribute('data-qa-save','')`)
    const selector='[data-qa-save]'
    const action=await hitGeometry(selector)
    console.log('save',theme,ime,page,JSON.stringify(action))
    assert.ok(action.visible>=32 && action.hits.every(h=>h.ok),'save covered: '+JSON.stringify(action))
    const name=page==='sources'?'save_source':'save_skill'
    const before=await evaluate(`qa.calls.filter(c=>c.name===${JSON.stringify(name)}).length`)
    await pointer(action.points[0]) // top of button, not just its uncovered bottom
    await poll(()=>evaluate(`qa.calls.filter(c=>c.name===${JSON.stringify(name)}).length===${before+1}`))
    await poll(()=>evaluate(`qa.${page==='sources'?'source':'skill'}===${JSON.stringify(text)} && document.querySelector('textarea')?.value===${JSON.stringify(text)}`))
    await evaluate(`document.activeElement.blur();window.dispatchEvent(new CustomEvent('studio:insets',{detail:{top:0,bottom:0,ime:0,rawIme:0}}))`)
    const returnBack=await hitGeometry('[data-detail-back]')
    assert.ok(returnBack.visible>=32 && returnBack.hits.every(h=>h.ok),'return Back inaccessible: '+JSON.stringify(returnBack))
    await pointer(returnBack.points[1])
    await poll(()=>evaluate(`!document.querySelector('[data-detail-back]')`))
    assert.ok(await evaluate(`!!document.querySelector(${JSON.stringify(row)})`))
    const navigation=await evaluate(`(() => {
      const main=document.querySelector('main').getBoundingClientRect();
      return Array.from(document.querySelectorAll('nav button')).map(el=>{
        const r=el.getBoundingClientRect(), h=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);
        return {label:el.textContent.trim(),height:r.height,ok:r.top>=main.bottom && r.bottom<=innerHeight && r.left>=0 && r.right<=innerWidth && (h===el||el.contains(h))};
      });
    })()`)
    assert.ok(navigation.length===5 && navigation.every(n=>n.height>=32 && n.ok),'short viewport navigation inaccessible: '+JSON.stringify(navigation))
    console.log('COMBINATION PASS',JSON.stringify({theme,ime,page,navigation}))
  }
  console.log('PASS: real Chromium editor/save hit tests, pointer input, fixture save/readback, IME/short viewport and return; Android IME/persistence simulated')
} finally {
  clearTimeout(overallTimeout)
  ws?.close(); chrome.kill('SIGTERM'); vite.kill('SIGTERM')
  await delay(500); await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200})
}
