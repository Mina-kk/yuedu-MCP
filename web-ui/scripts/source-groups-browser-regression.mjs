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
const evidence = resolve(process.env.GROUP_EVIDENCE || join(tmpdir(), 'ui211-groups'))
const cases = [{width:320,theme:'light',palette:'cherry'}]
const now=Date.parse('2026-11-15T12:07:56Z')
const fixture=[
 {projectId:'axx-new-001',bookSourceName:'傲轩小说',bookSourceUrl:'https://www.axxsw.net/',updatedAt:now},
 {projectId:'other-001',bookSourceName:'傲轩小说',bookSourceUrl:'https://other-reader.example.org/books',updatedAt:now-5000},
 {projectId:'axx-history-002',bookSourceName:'傲轩小说',bookSourceUrl:'https://www.axxsw.net/',updatedAt:now-26000},
 {projectId:'library-001',bookSourceName:'中华典藏',bookSourceUrl:'https://www.zhonghuadiancang.com/',updatedAt:now-40000},
 {projectId:'axx-history-003',bookSourceName:'傲轩小说',bookSourceUrl:'https://www.axxsw.net/',updatedAt:now-113000},
 {projectId:'other-002',bookSourceName:'傲轩小说',bookSourceUrl:'https://m.other-reader.example.org/books',updatedAt:now-120000},
 {projectId:'axx-history-004',bookSourceName:'傲轩小说 · 首个保存版本',bookSourceUrl:'https://www.axxsw.net/',updatedAt:now-180000}
].map(row=>({...row,stage:'VALIDATED',versionCount:row.projectId.startsWith('axx-')?4:1}))
const results = { kind: 'BROWSER_FIXTURE_NOT_ANDROID_EVIDENCE', expectedCases: cases.length, fixtureCount: fixture.length, cases: [], errors: [] }
await mkdir(evidence, { recursive: true })
const profile = await mkdtemp(join(tmpdir(), 'ui211-chrome-'))
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
    await cdp('Input.dispatchMouseEvent',{type:'mouseMoved',x:1,y:1});await delay(200)
    const r = await cdp('Page.captureScreenshot', { format: 'png' })
    await writeFile(join(evidence, name), Buffer.from(r.data, 'base64'))
  }
  await cdp('Page.enable'); await cdp('Runtime.enable')
  results.browser = await cdp('Browser.getVersion')

  for(const settings of cases){
    const key=`${settings.palette}-${settings.theme}-${settings.width}`
    lastCase={key,...settings,passed:false};results.cases.push(lastCase)
    const init=({fixture,settings})=>{
      window.groupQA={calls:[],fixture}
      const text=d=>({content:[{type:'text',text:typeof d==='string'?d:JSON.stringify(d)}]})
      window.StudioBridge={postMessage(raw){
        const req=JSON.parse(raw);groupQA.calls.push(req);let result={}
        if(req.method==='app.bootstrap')result={versionName:'2.0.11',service:{running:true},appearance:{theme:settings.theme,palette:settings.palette},capabilities:{}}
        if(req.method==='tools.list')result={tools:[]}
        if(req.method==='tools.call'){
          const {name,arguments:a}=req.params
          if(name==='list_sources'){
            const matched=fixture.filter(x=>!a.query||[x.bookSourceName,x.bookSourceUrl].some(v=>v.toLowerCase().includes(a.query.toLowerCase())))
            const offset=a.offset||0,limit=Math.min(a.limit||20,3),items=matched.slice(offset,offset+limit)
            result=text({items,total:matched.length,offset,limit,hasMore:offset+items.length<matched.length})
          }else if(name==='get_source'){
            const row=fixture.find(x=>x.projectId===a.key)
            result=text(` {"bookSourceName":${JSON.stringify(row.bookSourceName)},"bookSourceUrl":${JSON.stringify(row.bookSourceUrl)},"qaID":${JSON.stringify(row.projectId)},"big":9007199254740993} `)
          }else result=text({items:[]})
        }
        if(req.method==='sources.deliver')result=req.params.action==='targets'?{targets:[{packageName:'com.legado.app.release',label:'阅读'},{packageName:'io.legado.app.release',label:'另一阅读'}]}:{copied:true,saved:true,launched:true}
        queueMicrotask(()=>this.onmessage?.({data:JSON.stringify({id:req.id,ok:true,result})}))
      }}
    }
    await cdp('Page.addScriptToEvaluateOnNewDocument',{source:`(${init.toString()})(${JSON.stringify({fixture,settings})})`})
    await cdp('Emulation.setDeviceMetricsOverride',{width:settings.width,height:844,deviceScaleFactor:1,mobile:false})
    await cdp('Page.navigate',{url:server.resolvedUrls.local[0]})
    await poll(()=>evaluate(()=>!!document.querySelector('nav.tabbar button')),'App ready')
    await click('nav.tabbar button',1)
    await poll(()=>evaluate(()=>document.querySelectorAll('.source-group').length===3),'all page summaries grouped into three sites')
    lastCase.initial=await evaluate(()=>({groups:[...document.querySelectorAll('.source-group')].map(g=>({domain:g.dataset.sourceDomain,text:g.textContent,rows:[...g.querySelectorAll('.source-entry')].filter(e=>e.getClientRects().length).map(e=>e.dataset.projectId)})),calls:groupQA.calls.filter(x=>x.method==='tools.call'&&x.params.name==='list_sources').map(x=>x.params.arguments)}))
    assert.deepEqual(lastCase.initial.groups.map(x=>x.domain),['axxsw.net','example.org','zhonghuadiancang.com'])
    assert.deepEqual(lastCase.initial.groups.map(x=>x.rows),[['axx-new-001'],['other-001'],['library-001']])
    assert.deepEqual(lastCase.initial.calls.map(x=>x.offset||0),[0,3,6],'collect across actual response page sizes')
    assert.ok(lastCase.initial.groups[0].text.includes('4'),'group reports all four records across pages')
    await evaluate(()=>document.querySelector('main').scrollTop=0);await screenshot(`${key}-grouped.png`)
    const group='[data-source-domain="axxsw.net"]'
    await click(`${group} [data-source-group-toggle]`)
    const ids=await evaluate(()=>[...document.querySelectorAll('[data-source-domain="axxsw.net"] .source-entry')].filter(e=>e.getClientRects().length).map(e=>e.dataset.projectId))
    assert.deepEqual(ids,['axx-new-001','axx-history-002','axx-history-003','axx-history-004'])
    lastCase.expandedIds=ids
    const old='[data-project-id="axx-history-003"]'
    await click(`${old} [data-source-action="copy"]`)
    const copy=await evaluate(()=>groupQA.calls.filter(c=>c.method==='sources.deliver'&&c.params.action==='copy').at(-1).params)
    assert.deepEqual(copy,{projectIds:['axx-history-003'],action:'copy'})
    await click(`${old} .source-row`)
    await poll(()=>evaluate(()=>!!document.querySelector('textarea[aria-label="书源 JSON"]')),'history detail')
    assert.ok(await evaluate(()=>document.querySelector('textarea[aria-label="书源 JSON"]').value.includes('axx-history-003')))
    await click('[data-detail-back]')
    assert.equal(await evaluate(()=>document.querySelector('[data-source-domain="axxsw.net"] [data-source-group-toggle]').getAttribute('aria-expanded')),'true','Back retains expanded group')
    await click(`${old} [data-source-more]`)
    await click(`${old} [data-reader-import]`)
    await poll(()=>evaluate(()=>!!document.querySelector('.reader-import-dialog')),'reader choices')
    assert.deepEqual(await evaluate(()=>groupQA.calls.filter(c=>c.method==='sources.deliver'&&c.params.action==='targets').at(-1).params),{projectIds:['axx-history-003'],action:'targets'})
    await click('[data-reader-cancel]')
    assert.equal(await evaluate(()=>groupQA.calls.some(c=>c.method==='sources.deliver'&&c.params.action==='reader')),false)
    await click(`${group} [data-source-group-toggle]`)
    await click('[data-selection-toggle]')
    await click('input[aria-label="全选当前页"]')
    await click('[data-export-selected]')
    const selected=await evaluate(()=>groupQA.calls.filter(c=>c.method==='sources.deliver'&&c.params.action==='save').at(-1).params)
    assert.deepEqual([...selected.projectIds].sort(),['axx-new-001','library-001','other-001'],'collapsed history must not be silently selected')
    await byLabel('取消选择')
    await click(`${group} [data-source-group-toggle]`)
    await click(`${old} .source-row`);await click('[data-detail-back]')
    await screenshot(`${key}-expanded-history.png`)
    lastCase.geometry=await evaluate(()=>({overflow:document.documentElement.scrollWidth>innerWidth+1,controls:[...document.querySelectorAll('.source-group button')].filter(e=>e.getClientRects().length).map(e=>{const r=e.getBoundingClientRect();return {text:e.textContent.trim().slice(0,70),left:r.left,right:r.right}})}))
    assert.equal(lastCase.geometry.overflow,false)
    assert.ok(lastCase.geometry.controls.every(x=>x.left>=-.5&&x.right<=settings.width+.5),'group controls fit')
    assert.equal(await evaluate(()=>groupQA.calls.some(c=>c.method==='tools.call'&&['save_source','delete_projects','archive_source_versions'].includes(c.params.name))),false)
    lastCase.exactHistoryCopy=copy;lastCase.visibleOnlySelection=selected;lastCase.passed=true
  }
  assert.equal(results.cases.length,results.expectedCases);assert.deepEqual(results.errors,[])
  console.log('PASS production-App source groups: cross-page aggregation, history ID, direct actions, Back, visible-only selection')
}catch(error){results.errors.push({case:lastCase?.key,message:error.message});console.error(error);process.exitCode=1}
finally{clearTimeout(deadline);await writeFile(join(evidence,'results.json'),JSON.stringify(results,null,2));ws?.close();chrome?.kill('SIGTERM');await server.close();await delay(300);await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200})}
