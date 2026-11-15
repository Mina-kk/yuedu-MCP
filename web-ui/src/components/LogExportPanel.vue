<script setup>
import { computed, ref } from 'vue'
const props = defineProps({ store:Object, kind:String, ids:Array, detailId:String, date:String })
const emit = defineEmits(['close'])
const scope = ref(props.detailId ? 'single' : 'all')
const format = ref('JSON')
const redact = ref(true)
const anchor = ref('')
const busy = ref(false)
const error = ref('')
const shareConfirm = ref(false)
const canShare = computed(() => props.store.state.bridgeKind === 'android' && props.store.state.bootstrap?.capabilities?.logShare === true)
const formats = computed(() => props.kind === 'capture' ? ['JSON'] : ['crash','diagnostic'].includes(props.kind) ? ['JSON','TEXT'] : ['JSON','TEXT','CSV','HTML'])
async function save(share = false) {
 if (busy.value) return
 busy.value = true; error.value = ''
 try {
  const payload = {kind:props.kind,scope:scope.value,format:format.value,redact:redact.value}
  if(scope.value==='single') payload.ids=[props.detailId]
  if(scope.value==='selected') payload.ids=props.ids
  if(scope.value==='day') payload.date=props.date
  if(scope.value==='anchor') payload.anchor=anchor.value
  if (share && !canShare.value) throw new Error('当前宿主不支持分享')
  const result=await props.store.bridge.call(share ? 'logs.share' : 'logs.export',payload)
  if (!share && result?.saved === false && result?.cancelled === true) return
  if(result?.[share ? 'launched' : 'saved']!==true || !Number.isInteger(result.count) || result.count<0 ||
    typeof result.partial !== 'boolean' || !Number.isInteger(result.missingResources) || result.missingResources<0 ||
    !Number.isInteger(result.omittedResources) || result.omittedResources<0 || (result.missingResources>0 && !result.partial))
    throw new Error(share ? '分享结果无效' : '导出结果无效')
  if (result.partial || result.omittedResources>0) {
   error.value = `${share ? '已打开系统分享，不代表已送达' : '文件已保存'}，但不是完整备份。${result.partial ? `部分导出：缺失或无法读取 ${result.missingResources} 个资源。` : ''}${result.omittedResources>0 ? `脱敏已省略 ${result.omittedResources} 个二进制资源。` : ''}`
   return
  }
  props.store.toast(share ? `已打开系统分享（${result.count} 条），不代表已送达` : `已导出 ${result.count} 条`)
  emit('close')
 } catch(e) { error.value=e.message || '导出失败' }
 finally { busy.value=false }
}
</script>
<template>
 <section class="section log-export" aria-label="日志导出设置">
  <h3>导出日志</h3>
  <div class="segmented wrap" role="group" aria-label="导出范围">
   <button type="button" class="seg" :class="{on:scope===o.v}" v-for="o in [{v:'single',t:'当前详情',show:!!detailId},{v:'selected',t:'已选集合',show:ids?.length>0},{v:'day',t:'当天全部',show:!!date && ['operation','http'].includes(kind)},{v:'all',t:'全库',show:true},{v:'anchor',t:'锚点全部',show:kind==='http'}].filter(o=>o.show)" :key="o.v" :aria-pressed="scope===o.v" :disabled="busy" @click="scope=o.v">{{o.t}}</button>
  </div>
  <input v-if="scope==='anchor'" v-model="anchor" class="input" aria-label="导出锚点" :disabled="busy" />
  <div class="segmented wrap" role="group" aria-label="导出格式"><button type="button" class="seg" :class="{on:format===f}" v-for="f in formats" :key="f" :aria-pressed="format===f" :disabled="busy" @click="format=f">{{f}}</button></div>
  <label><input v-model="redact" type="checkbox" :disabled="busy" />脱敏敏感字段</label>
  <p v-if="kind==='capture' && redact">脱敏导出将省略二进制资源，不是完整备份。</p>
  <p>URL/query、正文或二进制资源仍可能含私人信息；导出或分享前请检查。范围不受当前分页限制。</p>
  <p v-if="error" role="alert">{{error}}</p>
  <button type="button" class="btn btn-primary btn-sm" :disabled="busy" @click="save(false)">{{busy?'正在导出':'保存文件'}}</button>
  <button type="button" class="btn btn-ghost btn-sm" v-if="canShare" :disabled="busy" @click="shareConfirm=true">系统分享</button>
  <div v-if="shareConfirm" role="group" aria-label="分享风险确认">
   <p>文件将交给其他应用；脱敏不能保证清除 URL、正文和资源中的所有私人信息。{{redact?'当前已开启脱敏。':'当前未脱敏，可能包含令牌、Cookie 等敏感数据。'}}请仅分享给可信接收方。</p>
   <button type="button" class="btn btn-primary btn-sm" :disabled="busy" @click="save(true)">确认分享</button>
   <button type="button" class="btn btn-ghost btn-sm" :disabled="busy" @click="shareConfirm=false">取消分享</button>
  </div>
  <button type="button" class="btn btn-ghost btn-sm" :disabled="busy" @click="emit('close')">取消导出</button>
 </section>
</template>
<style scoped>
.log-export { display: flex; flex-wrap: wrap; align-items: start; gap: 8px; }
.log-export > h3, .log-export > p, .log-export > label, .log-export > .segmented, .log-export > [aria-label="分享风险确认"] { flex-basis: 100%; min-width: 0; }
.log-export p { margin: 0; line-height: 1.6; }
.log-export label { display: flex; align-items: center; gap: 8px; }
.log-export [role="alert"] { color: var(--bad); }
.log-export .seg:disabled { opacity: .46; }
.log-export [aria-label="分享风险确认"] { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px; border: 1px solid var(--line); border-radius: 11px; background: var(--surface); }
.log-export [aria-label="分享风险确认"] p { flex-basis: 100%; }
</style>
