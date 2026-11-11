import { mount, flushPromises } from '@vue/test-utils'
import {it,expect,vi} from 'vitest'
import LogExportPanel from '../src/components/LogExportPanel.vue'
it('系统分享沿用选择范围格式脱敏，确认风险后只报告已打开',async()=>{
 const store={state:{bridgeKind:'android',bootstrap:{capabilities:{logShare:true}}},toast:vi.fn(),bridge:{call:vi.fn().mockResolvedValue({launched:true,count:2,partial:false,omittedResources:0,missingResources:0})}}
 const w=mount(LogExportPanel,{props:{store,kind:'http',ids:['9','10'],date:'2026-01-01'}})
 const b=t=>w.findAll('button').find(x=>x.text()===t)
 expect(b('系统分享')).toBeDefined()
 await b('已选集合').trigger('click');await b('CSV').trigger('click');await w.get('input[type="checkbox"]').setValue(false)
 await b('系统分享').trigger('click');expect(store.bridge.call).not.toHaveBeenCalled()
 expect(w.text()).toContain('其他应用');await b('确认分享').trigger('click');await flushPromises()
 expect(store.bridge.call).toHaveBeenCalledTimes(1)
 expect(store.bridge.call).toHaveBeenCalledWith('logs.share',{kind:'http',scope:'selected',format:'CSV',redact:false,ids:['9','10']})
 expect(store.toast).toHaveBeenCalledWith('已打开系统分享（2 条），不代表已送达');w.unmount()
})
it('未声明能力不提供分享，错误或非launched回执不报成功',async()=>{
 const store={state:{bridgeKind:'android',bootstrap:{capabilities:{}}},toast:vi.fn(),bridge:{call:vi.fn().mockResolvedValue({saved:true})}}
 const w=mount(LogExportPanel,{props:{store,kind:'operation'}})
 expect(w.findAll('button').some(x=>x.text()==='系统分享')).toBe(false)
 w.unmount()
 const active=mount(LogExportPanel,{props:{store:{...store,state:{bridgeKind:'android',bootstrap:{capabilities:{logShare:true}}}},kind:'operation'}})
 const b=t=>active.findAll('button').find(x=>x.text()===t)
 await b('系统分享').trigger('click');await b('确认分享').trigger('click');await flushPromises()
 expect(store.toast).not.toHaveBeenCalled();expect(active.get('[role="alert"]').text()).toContain('分享结果无效');active.unmount()
})
it.each([
 [{saved:true,count:1,partial:true,missingResources:1,omittedResources:0},'部分导出'],
 [{saved:true,count:1,partial:false,missingResources:0,omittedResources:2},'省略 2 个二进制资源'],
])('资源缺失和主动省略不能被成功toast掩盖 %j',async(result,message)=>{
 const store={state:{bridgeKind:'android'},toast:vi.fn(),bridge:{call:vi.fn().mockResolvedValue(result)}}
 const w=mount(LogExportPanel,{props:{store,kind:'capture'}})
 await w.findAll('button').find(x=>x.text()==='保存文件').trigger('click');await flushPromises()
 expect(w.get('[role="alert"]').text()).toContain(message)
 expect(w.text()).toContain('不是完整备份');expect(store.toast).not.toHaveBeenCalled();expect(w.emitted('close')).toBeUndefined();w.unmount()
})
it('取消保存不报成功，旧正文回执被拒绝',async()=>{
 const store={state:{bridgeKind:'android'},toast:vi.fn(),bridge:{call:vi.fn().mockResolvedValueOnce({saved:false,cancelled:true}).mockResolvedValueOnce({text:'old',count:1})}}
 const w=mount(LogExportPanel,{props:{store,kind:'http'}})
 const save=()=>w.findAll('button').find(x=>x.text()==='保存文件').trigger('click')
 await save();await flushPromises();expect(w.find('[role="alert"]').exists()).toBe(false)
 await save();await flushPromises();expect(w.get('[role="alert"]').text()).toContain('导出结果无效')
 expect(store.toast).not.toHaveBeenCalled();expect(store.bridge.call).toHaveBeenCalledTimes(2);w.unmount()
})
it('导出按明确全库范围取宿主完整内容，不使用分页摘要，支持格式脱敏',async()=>{
 const store={state:{bridgeKind:'android'},toast:vi.fn(),bridge:{call:vi.fn().mockResolvedValue({saved:true,count:501,partial:false,omittedResources:0,missingResources:0})}}
 const w=mount(LogExportPanel,{props:{store,kind:'http',ids:['9'],date:'2026-01-01'}})
 const b=t=>w.findAll('button').find(x=>x.text()===t)
 await b('CSV').trigger('click');await w.get('input[type="checkbox"]').setValue(false)
 await b('保存文件').trigger('click');await flushPromises()
 expect(store.bridge.call).toHaveBeenNthCalledWith(1,'logs.export',{kind:'http',scope:'all',format:'CSV',redact:false})
 expect(store.bridge.call).toHaveBeenCalledTimes(1)
 expect(store.toast).toHaveBeenCalledWith('已导出 501 条');w.unmount()
})
