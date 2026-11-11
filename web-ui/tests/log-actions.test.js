import { mount, flushPromises } from '@vue/test-utils'
import { it, expect, vi } from 'vitest'
import LogsPage from '../src/pages/LogsPage.vue'
const result=d=>({content:[{type:'text',text:JSON.stringify(d)}]})
const btn=(w,t)=>w.findAll('button').find(b=>b.text()===t)
it('专用表单回读录制开关，禁止关闭时发请求，诊断创建后打开',async()=>{
 let enabled=false
 const store={state:{bridgeKind:'android'},bridge:{call:vi.fn()},toast:vi.fn(),callTool:vi.fn((name,args)=>{
  if(name==='get_http_log_recording')return result({enabled})
  if(name==='set_http_log_recording'){enabled=args.enabled;return result('ok')}
  if(name==='export_diagnostic')return result({id:'snapshot-1'})
  if(name==='get_diagnostic_snapshot')return result({body:'diagnostic-body'})
  return result({items:[]})
 })}
 const w=mount(LogsPage,{props:{store}});await flushPromises()
 expect(btn(w,'读取录制状态')).toBeDefined()
 await btn(w,'读取录制状态').trigger('click');await flushPromises()
 expect(btn(w,'发送请求').element.disabled).toBe(true)
 await btn(w,'开启 HTTP 录制').trigger('click');await flushPromises()
 expect(store.callTool).toHaveBeenLastCalledWith('get_http_log_recording',{})
 await w.get('[aria-label="请求 URL"]').setValue('https://example.com')
 await btn(w,'POST').trigger('click')
 await w.get('[aria-label="请求头（每行 名称: 值）"]').setValue('X-Test: value')
 await w.get('[aria-label="请求正文"]').setValue('body')
 await w.get('[aria-label="单次请求"]').trigger('submit');await flushPromises()
 expect(store.callTool).toHaveBeenCalledWith('capture_once',expect.objectContaining({url:'https://example.com',method:'POST',headers:'{"X-Test":"value"}',body:'body'}))
 await btn(w,'创建诊断').trigger('click');await flushPromises()
 expect(w.text()).toContain('diagnostic-body')
 w.unmount()
})
