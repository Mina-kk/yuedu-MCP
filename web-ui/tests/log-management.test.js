import { DOMWrapper, mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import LogsPage from '../src/pages/LogsPage.vue'
const result = data => ({ content: [{type:'text',text:JSON.stringify(data)}] })
const btn = (w,t) => w.findAll('button').find(x=>x.text()===t)
describe('日志管理补齐',()=>{
 it('整日删除先读取真实计数并明确确认，与当前页选择无关',async()=>{
  const store={state:{bridgeKind:'android'},toast:vi.fn(),callTool:vi.fn(()=>result({items:[]})),bridge:{call:vi.fn((m,p)=>m==='logs.dayCount'?{kind:p.kind,date:p.date,count:501}:m==='logs.deleteDay'?{kind:p.kind,date:p.date,deleted:501,remaining:0}:{items:[],total:0})}}
  const w=mount(LogsPage,{props:{store}});await flushPromises()
  await btn(w,'删除该日全部').trigger('click');await flushPromises()
  expect(document.body.textContent).toContain('501')
  expect(store.bridge.call).not.toHaveBeenCalledWith('logs.deleteDay',expect.anything())
  await new DOMWrapper(document.querySelector('.delete-overlay')).findAll('button').find(b=>b.text()==='确认删除').trigger('click');await flushPromises()
  expect(store.bridge.call).toHaveBeenCalledWith('logs.deleteDay',expect.objectContaining({kind:'operation',confirmed:true}))
  expect(w.text()).toContain('已删除 501 条');w.unmount()
 })
 it('浏览器抓包由能力门禁且只发父级打开事件',async()=>{
  const store={state:{bridgeKind:'android',bootstrap:{capabilities:{interactiveCapture:true}}},callTool:vi.fn(()=>result({items:[]})),bridge:{call:vi.fn()},toast:vi.fn()}
  const w=mount(LogsPage,{props:{store}});await flushPromises();await btn(w,'抓包').trigger('click');await flushPromises()
  expect(btn(w,'浏览器抓包').element.disabled).toBe(false);await btn(w,'浏览器抓包').trigger('click')
  expect(w.emitted('open-capture-browser')).toHaveLength(1);expect(store.bridge.call).not.toHaveBeenCalled();w.unmount()
 })
 it('抓包分页追加并可读末条事务，返回不残留详情导出', async()=>{
  const store={state:{bridgeKind:'android'},toast:vi.fn(),bridge:{call:vi.fn()},callTool:vi.fn((name,args)=>result(name==='list_captures'?{items:[{contextId:'cap:12345678-1234-1234-1234-123456789abc'}]}:name==='get_capture'?{total:51,items:Array.from({length:args.offset?1:50},(_,i)=>({id:args.offset+i+1,url:'https://example.com'}))}:name==='get_http_log'?{responseBody:'last-body'}:{items:[]}))}
  const w=mount(LogsPage,{props:{store}});await flushPromises()
  await btn(w,'抓包').trigger('click');await flushPromises();await w.get('.log-row').trigger('click');await flushPromises()
  expect(btn(w,'加载更多事务')).toBeDefined()
  await btn(w,'加载更多事务').trigger('click');await flushPromises()
  await w.get('[data-transaction-id="51"]').trigger('click');await flushPromises()
  expect(w.text()).toContain('last-body')
  w.vm.handleBack();await flushPromises();w.vm.handleBack();await flushPromises()
  await btn(w,'导出').trigger('click');await flushPromises()
  expect(w.get('[aria-label="导出范围"]').text()).not.toContain('当前详情')
  w.unmount()
 })
})
