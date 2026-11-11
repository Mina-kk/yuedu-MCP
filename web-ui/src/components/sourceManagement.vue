<script setup>
import { ref, onBeforeUnmount } from 'vue'
import { prettyJson } from '../lib/tools.js'
const props = defineProps({store:Object, source:String})
const emit = defineEmits(['changed'])
const exclude = ref({excludeLogin:false,excludeCaptcha:false,excludeFailed:false,excludeUnverified:false})
const collapseIdentical = ref(false)
const report = ref(null), error = ref(''), busy = ref(false), targets = ref(null), deleting = ref(null), confirmation = ref('')
const check = ref(null)
let timer, disposed = false
async function call(method, params={}) { return props.store.bridge.call(`sources.${method}`,params) }
async function perform(fn) {
 if (busy.value) return
 busy.value=true; error.value=''
 try { await fn() } catch(e) { error.value=e.message || '操作失败' } finally { busy.value=false }
}
function invalidate() { report.value=null; targets.value=null; deleting.value=null }
function analyze() { return perform(async()=>{ invalidate(); report.value=await call('analyze',{exclude:{...exclude.value},collapseIdentical:collapseIdentical.value}) }) }
function payload() { return props.source != null ? {source:props.source} : {token:report.value?.token} }
function deliver(action, packageName) { return perform(async()=>{
 const res=await call('deliver',{...payload(),action,...(packageName?{packageName}:{})})
 if(action==='targets') { if(!Array.isArray(res.targets)) throw new Error('客户端列表回执无效'); targets.value=res.targets; return }
 if(action==='copy' && res.copied!==true) throw new Error('复制未确认')
 if(action==='share' && res.launched!==true) throw new Error('分享未唤起')
 if(action==='reader' && !(res.launched===true && res.confirmed===false && res.confirmation==='pending_user')) throw new Error('阅读唤起回执无效')
 if(action==='save' && !res.saved) { if(res.cancelled) return; throw new Error('文件保存未确认') }
 props.store.toast(action==='reader'?'已唤起阅读勾选页，待用户确认；阅读端未覆盖':action==='share'?'已打开系统分享，未确认接收方保存':action==='copy'?'已复制完整书源数组':'已导出完整书源数组')
 targets.value=null
 }) }
function askDelete(group) { deleting.value=group || {domain:null,ids:[],all:true}; confirmation.value='' }
function remove() { return perform(async()=>{
 const all = deleting.value.all === true
 const expectedIds = all ? report.value.groups.flatMap(g=>g.ids) : deleting.value.ids
 const res=await call('delete',{token:report.value.token,scope:all?'all':'domain',domain:deleting.value.domain,confirmation:confirmation.value})
 if(res.scope!==(all?'all':'domain') || !Array.isArray(res.deletedIds) || res.deletedCount!==expectedIds.length || res.deletedIds.length!==expectedIds.length || new Set(res.deletedIds).size!==expectedIds.length || !expectedIds.every(id=>res.deletedIds.includes(id)) || !Number.isInteger(res.remainingCount) || res.remainingCount<0) throw new Error('删除回执无效，请重新分析核对实际状态')
 invalidate(); emit('changed')
 if(res.verified!==true) throw new Error('删除事务已完成，但回读发现同 ID 书源再次写入；请重新分析，未自动重删')
 if(all && (res.allCleared!==true || res.remainingCount!==0)) throw new Error(`已删除确认快照 ${res.deletedCount} 条，但全库未清空，回读仍有 ${res.remainingCount} 条；请重新分析，未扩大删除范围`)
 props.store.toast(`已删除 ${res.deletedCount} 条并回读确认${all?'，全库已清空（截至回读）':''}`)
 }) }
async function poll() {
 if(disposed || !check.value) return
 try {
  const result=await call('checkStatus',{id:check.value.id})
  if(disposed) return
  check.value=result
  if(result.state==='running' || result.state==='cancelling') timer=setTimeout(poll,500)
  else { report.value=null; emit('changed') }
 } catch(e) { error.value=e.message; }
}
function startCheck(id) {
 props.store.confirmDialog({kind:'source-check',title:'执行实时沙箱检测？',message:'会联网并执行书源规则；不代表阅读端通过。可取消正在运行的检测。',confirmLabel:'开始检测',onAccept:()=>perform(async()=>{
  check.value=await call('checkStart',{projectId:id}); report.value=null; await poll()
 })})
}
function cancelCheck() { return perform(async()=>{ check.value=await call('checkCancel',{id:check.value.id}); await poll() }) }
onBeforeUnmount(()=>{ disposed=true;clearTimeout(timer); if(check.value?.state==='running') call('checkCancel',{id:check.value.id}).catch(()=>{}) })
defineExpose({invalidate})
</script>
<template>
 <div class="source-management">
  <template v-if="source == null">
   <button class="btn btn-ghost" :disabled="busy || check?.state==='running'" @click="analyze">全库分析与分组</button>
   <p class="hint">不受当前页、搜索或版本筛选限制。默认不排除、不折叠；冲突始终保留。可信检测证据按原文绑定，30 分钟后失效；导出前由宿主重新核对。</p>
   <div class="options">
    <label v-for="(label,key) in {excludeLogin:'排除需登录',excludeCaptcha:'排除验证码/挑战',excludeFailed:'排除沙箱失败',excludeUnverified:'排除未验证'}" :key="key"><input v-model="exclude[key]" type="checkbox" :disabled="busy" @change="invalidate">{{label}}</label>
    <label><input v-model="collapseIdentical" type="checkbox" :disabled="busy" @change="invalidate">安全折叠完全相同副本（不删库）</label>
   </div>
  </template>
  <template v-if="report || source != null">
   <p v-if="source != null" class="hint">以下操作使用当前编辑原文（包括未保存修改），输出完整 JSON 数组；分享可能包含 Cookie/header 等敏感字段。</p>
   <p v-else>全库 {{report.total}} 条 · 保留 {{report.keptCount}} · 排除 {{report.excludedCount}} · 导出 {{report.exportCount ?? report.keptCount}}</p>
   <div class="actions">
    <button class="btn btn-ghost" :disabled="busy" @click="deliver('copy')">复制完整数组</button>
    <button class="btn btn-ghost" :disabled="busy" @click="deliver('share')">分享书源 JSON</button>
    <button class="btn btn-ghost" :disabled="busy" @click="deliver('save')">导出完整数组</button>
    <button class="btn btn-ghost" :disabled="busy" @click="deliver('targets')">导入指定阅读客户端</button>
   </div>
   <div v-if="targets" class="section" aria-label="选择阅读客户端">
    <p>选择客户端，仅唤起勾选页，不代表已导入。</p><p v-if="!targets.length">没有可处理书源导入的阅读客户端。</p>
    <button v-for="target in targets" :key="target.packageName" class="btn btn-ghost" :disabled="busy" @click="deliver('reader',target.packageName)">{{target.label}} · {{target.packageName}}</button>
    <button class="btn btn-ghost" :disabled="busy" @click="targets=null">取消选择客户端</button>
   </div>
  </template>
  <template v-if="report">
   <p class="hint">域名整组 / 全库删除将在数据库同一事务内核对完整快照；库有变化则整批拒绝。只删除明确确认的 ID，不扩大到新增书源。</p>
   <details><summary>重复簇与冲突（冲突全部保留）</summary><pre class="code-block">{{prettyJson({clusters:report.clusters,conflicts:report.conflicts})}}</pre></details>
   <details><summary>保留 / 排除原因与可信证据</summary><pre class="code-block">{{prettyJson({kept:report.kept,excluded:report.excluded})}}</pre></details>
   <details><summary>按域名分组（全部版本）</summary>
    <div v-for="group in report.groups" :key="group.domain" class="group"><strong>{{group.domain}} · {{group.ids.length}} 条</strong><button class="btn btn-ghost danger" :disabled="busy" @click="askDelete(group)">删除该域名整组</button>
     <div v-for="id in group.ids" :key="id"><span>{{id}}</span><button class="btn btn-ghost" :disabled="busy || check?.state==='running'" @click="startCheck(id)">实时检测此源</button></div>
    </div>
   </details>
   <button class="btn btn-ghost danger" :disabled="busy" @click="askDelete(null)">清空本地全库</button>
  </template>
  <div v-if="deleting" class="section danger" role="group" aria-label="破坏性操作确认">
   <p>将不可恢复地删除{{deleting.all?'本地全库':`域名 ${deleting.domain} 整组`}}（包含全部版本），不删除阅读端副本。库有变化将拒绝执行。</p>
   <label>输入“{{deleting.all?'清空全库':deleting.domain}}”确认<input v-model="confirmation" class="input" aria-label="删除确认文字" :disabled="busy"></label>
   <button class="btn danger" :disabled="busy || confirmation !== (deleting.all?'清空全库':deleting.domain)" @click="remove">确认永久删除</button>
   <button class="btn btn-ghost" :disabled="busy" @click="deleting=null">取消删除</button>
  </div>
  <div v-if="check" role="status">实时沙箱检测：{{check.state}}<p>阅读端未覆盖</p><button v-if="check.state==='running' || check.state==='cancelling'" class="btn" :disabled="busy || check.state==='cancelling'" @click="cancelCheck">取消检测</button><pre v-if="check.result" class="code-block">{{prettyJson(check.result)}}</pre><p v-if="check.error">{{check.error}}</p></div>
  <p v-if="error" class="code-block err" role="alert">{{error}}</p>
 </div>
</template>
<style scoped>
.source-management { margin:12px 0; overflow-wrap:anywhere; }
.options { display:flex;flex-wrap:wrap;gap:12px; }
.group { border-bottom:1px solid var(--line);padding:8px 0; }
</style>
