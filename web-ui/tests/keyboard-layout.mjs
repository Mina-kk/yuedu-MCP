// Real Chromium geometry regression. Run with Node >=22; no APK/device mutation.
import { spawn } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
const profile = await mkdtemp(join(tmpdir(), 'keyboard-layout-'))
const vite = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '5199', '--strictPort'], { stdio: 'ignore' })
const chrome = spawn(process.env.CHROMIUM || '/usr/bin/chromium', ['--headless', '--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu', '--no-zygote', '--single-process', '--remote-allow-origins=*', '--remote-debugging-port=9299', `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' })
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
  await send('Page.addScriptToEvaluateOnNewDocument',{source:`window.StudioBridge={postMessage(raw){ const m=JSON.parse(raw); const result=m.method==='tools.list'?{tools:[{name:'keyboard_fixture',description:'Keyboard layout fixture',inputSchema:{type:'object',properties:{}}}]}:m.method==='app.bootstrap'?{service:{running:true},appearance:{theme:'dark',palette:'lime'}}:{}; queueMicrotask(()=>this.onmessage?.({data:JSON.stringify({id:m.id,ok:true,result})})); }};`})
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
  console.log('PASS: resized IME geometry, visible detail back, navigation restore, Vue return')
} finally {
  ws?.close(); chrome.kill('SIGTERM'); vite.kill('SIGTERM')
  await delay(500); await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200})
}
