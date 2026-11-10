// Actual production App rendering and pointer actions; host I/O is a fixture.
// CHROMIUM selects an actual engine. No raw tokens exist in this test.
import { spawn } from 'node:child_process'
import { readFile, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { resolve, join, extname, sep } from 'node:path'
import { tmpdir } from 'node:os'
import { createServer } from 'node:http'
import assert from 'node:assert/strict'
const dist=resolve('dist'),out=resolve(process.env.AUTH_EVIDENCE||join(tmpdir(),'auth-browser'))
await mkdir(out,{recursive:true});const temp=await mkdtemp(join(tmpdir(),'auth-chrome-'))
const server=createServer(async(req,res)=>{try{const p=resolve(dist,'.'+new URL(req.url,'http://localhost').pathname.replace(/\/$/,'/index.html'));if(!p.startsWith(dist+sep))throw Error();res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css','.wasm':'application/wasm'})[extname(p)]||'application/octet-stream');res.end(await readFile(p))}catch{res.writeHead(404);res.end()}})
await new Promise(r=>server.listen(0,'127.0.0.1',r))
const chrome=spawn(process.env.CHROMIUM||'/usr/bin/chromium',['--headless','--no-sandbox','--disable-dev-shm-usage','--disable-gpu','--no-zygote','--single-process','--disable-background-networking','--remote-allow-origins=*','--remote-debugging-port=0',`--user-data-dir=${temp}`,'about:blank'],{stdio:['ignore','ignore','ignore']})
let ws,diagnostic;const delay=ms=>new Promise(r=>setTimeout(r,ms));const results=[]
const deadline=setTimeout(()=>{chrome.kill('SIGTERM');server.close();process.exit(124)},180000)
try{
 async function poll(fn){for(let n=0;n<100;n++){try{let r=await fn();if(r)return r}catch{}await delay(60)}throw Error('poll timeout')}
 const port=await poll(async()=>Number((await readFile(join(temp,'DevToolsActivePort'),'utf8')).split('\n')[0]))
 const target=await poll(async()=>(await(await fetch(`http://127.0.0.1:${port}/json`)).json()).find(t=>t.type==='page'))
 ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise(r=>ws.addEventListener('open',r,{once:true}))
 let id=0;const pending=new Map();const errors=[]
 ws.addEventListener('message',e=>{const m=JSON.parse(e.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result)}else if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails)})
 const send=(method,params={})=>new Promise((resolve,reject)=>{const n=++id;pending.set(n,{resolve,reject});ws.send(JSON.stringify({id:n,method,params}))})
 const js=async expression=>{const r=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value}
 diagnostic=()=>js(`({text:document.body.innerText,calls:window.authQA?.calls,buttons:[...document.querySelectorAll('[aria-label="访问令牌校验"]')].map(e=>({role:e.getAttribute('role'),checked:e.getAttribute('aria-checked'),disabled:e.disabled}))})`)
 await send('Page.enable');await send('Runtime.enable');const version=await send('Browser.getVersion')
 await send('Page.addScriptToEvaluateOnNewDocument',{source:`
 window.authQA={required:true,calls:[],hold:false};
 const boot=()=>({versionName:'2.0.5 fixture',versionCode:206,service:{running:true,starting:false,port:58823,tokenRequired:authQA.required,endpoints:['http://127.0.0.1:58823/mcp'],compact:false},runtime:{userAgent:'Mozilla/5.0 layout fixture',bookSourceType:0},reader:{baseUrl:'',tokenSaved:false},appearance:{theme:'dark',palette:'lime'},capabilities:{toolCount:73,tokenRequiredEditable:true,tokenRequiredLocked:false}});
 window.StudioBridge={postMessage(raw){const m=JSON.parse(raw);authQA.calls.push(m);let result={};
 if(m.method==='app.bootstrap')result=boot();
 if(m.method==='tools.list')result={tools:Array.from({length:73},(_,i)=>({name:'fixture_tool_'+i,description:'Layout fixture',inputSchema:{type:'object',properties:{}}}))};
 if(m.method==='connection.copy')result={copied:true};
 const respond=()=>this.onmessage?.({data:JSON.stringify({id:m.id,ok:true,result})});
 if(m.method==='settings.save'){const finish=()=>{if(typeof m.params.tokenRequired==='boolean')authQA.required=m.params.tokenRequired;result=boot();respond()};if(authQA.hold)authQA.release=finish;else queueMicrotask(finish)}else queueMicrotask(respond());
 }};`})
 const toggle='main [role="switch"][aria-label="访问令牌校验"]'
 async function click(selector){await js(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'center'})`);await delay(100);const p=await js(`(()=>{const e=document.querySelector(${JSON.stringify(selector)}),r=e.getBoundingClientRect(),h=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);return {x:r.x+r.width/2,y:r.y+r.height/2,ok:e.contains(h)&&!e.disabled&&r.top>=0&&r.bottom<=innerHeight}})()`);assert.ok(p.ok,JSON.stringify({selector,p}));await send('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,x:p.x,y:p.y});await send('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,x:p.x,y:p.y});await delay(100)}
 const shot=async label=>{const r=await send('Page.captureScreenshot',{format:'png'});await writeFile(join(out,label+'.png'),Buffer.from(r.data,'base64'))}
 for(const width of [275,390]){
  await send('Emulation.setDeviceMetricsOverride',{width,height:700,mobile:true,deviceScaleFactor:1});await send('Page.navigate',{url:`http://127.0.0.1:${server.address().port}/`});await poll(()=>js(`!!document.querySelector(${JSON.stringify(toggle)})&&!document.querySelector(${JSON.stringify(toggle)}).disabled`))
  assert.equal(await js(`document.querySelector(${JSON.stringify(toggle)}).getAttribute('aria-checked')`),'true')
  await click(toggle);await poll(()=>js(`!!document.querySelector('[role=alertdialog]')`));await delay(400)
  const geometry=await js(`(()=>{const d=document.querySelector('[role=alertdialog]'),r=d.getBoundingClientRect();return {rect:r.toJSON(),height:innerHeight,mainWidth:document.querySelector('main').scrollWidth,buttons:[...d.querySelectorAll('button')].map(e=>{const q=e.getBoundingClientRect();return {hit:e.contains(document.elementFromPoint(q.x+q.width/2,q.y+q.height/2)),rect:q.toJSON()}})}})()`)
  assert.ok(geometry.buttons.every(b=>b.hit)&&geometry.rect.top>=0&&geometry.rect.bottom<=700);await shot(`${width}-confirm`)
  await click('[role=alertdialog] .btn-ghost');assert.equal(await js(`authQA.calls.filter(c=>c.method==='settings.save').length`),0)
  await click(toggle);await js(`window.dispatchEvent(new CustomEvent('studio:back'))`);await poll(()=>js(`!document.querySelector('[role=alertdialog]')`));assert.equal(await js(`authQA.calls.filter(c=>c.method==='settings.save').length`),0)
  await click(toggle);await js(`authQA.hold=true`);await click('[role=alertdialog] .btn-danger');assert.equal(await js(`document.querySelector(${JSON.stringify(toggle)}).disabled`),true)
  await js(`window.dispatchEvent(new CustomEvent('studio:back'))`);assert.equal(await js(`!!document.querySelector('[role=alertdialog]')`),false)
  assert.equal(await js(`authQA.calls.filter(c=>c.method==='settings.save').length`),1)
  await js(`authQA.hold=false;authQA.release()`);await poll(()=>js(`document.querySelector(${JSON.stringify(toggle)}).getAttribute('aria-checked')==='false'`));await delay(500)
  const offVisual=await js(`(()=>{const s=document.querySelector(${JSON.stringify(toggle)});return {on:s.classList.contains('on'),transform:getComputedStyle(s.querySelector('i')).transform}})()`);assert.equal(offVisual.on,false);assert.equal(offVisual.transform,'none');await shot(`${width}-off`)
  const payload=await js(`authQA.calls.filter(c=>c.method==='settings.save')[0].params`);assert.deepEqual(payload,{tokenRequired:false})
  await click(toggle);await poll(()=>js(`document.querySelector(${JSON.stringify(toggle)}).getAttribute('aria-checked')==='true'`))
  await click('header button[aria-label="设置"]');await delay(500);await click('.sheet [role="switch"][aria-label="访问令牌校验"]');assert.equal(await js(`document.querySelector('.sheet [role=switch][aria-label="访问令牌校验"]').getAttribute('aria-checked')`),'false');await click('.sheet .sheet-foot .btn-primary');await poll(()=>js(`!!document.querySelector('[role=alertdialog]')`));await click('[role=alertdialog] .btn-danger');await poll(()=>js(`authQA.required===false&&!document.querySelector('.sheet .sheet-foot .btn-primary').disabled`));assert.equal(await js(`document.querySelector('.sheet [role=switch][aria-label="访问令牌校验"]').getAttribute('aria-checked')`),'false')
  await js(`window.dispatchEvent(new CustomEvent('studio:back'))`);await poll(()=>js(`!document.querySelector('.sheet')`));assert.equal(await js(`document.querySelector(${JSON.stringify(toggle)}).getAttribute('aria-checked')`),'false')
  assert.equal(await js(`document.querySelectorAll('select').length`),0)
  results.push({width,homeCancelAndBackNoWrite:true,homeOffOnReadback:true,busyAndBackProtected:true,settingsOffSaveConfirm:true,noNativeSelect:true,geometry})
 }
 assert.equal(errors.length,0);await writeFile(join(out,'results.json'),JSON.stringify({version,scope:'Real Chromium production App pointer/geometry; host storage is fixture, not actual HTTP/Android',results},null,2));console.log(JSON.stringify({engine:version.product,cases:results.length,pass:true}))
}catch(error){if(diagnostic){const state=await diagnostic();await writeFile(join(out,'failure.json'),JSON.stringify(state,null,2));console.error(JSON.stringify(state))}throw error}finally{clearTimeout(deadline);ws?.close();chrome.kill('SIGTERM');server.close();await delay(500);await rm(temp,{recursive:true,force:true,maxRetries:5,retryDelay:200})}
