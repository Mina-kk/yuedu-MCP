<script setup>
import {ref} from 'vue'
import AppIcon from './Icon.vue'
import {callToolParsed} from '../lib/tools.js'
const props=defineProps({store:Object,disabled:Boolean})
const emit=defineEmits(['open'])
const recording=ref(null), busy=ref(false), error=ref('')
const url=ref(''),method=ref('GET'),headers=ref(''),body=ref('')
async function call(name,args={}) {
 const r=await callToolParsed(props.store,name,args)
 if(r.threw||r.isError)throw new Error(r.threw||r.text)
 return r.data
}
async function run(task) {
 if(busy.value||props.disabled)return
 busy.value=true;error.value=''
 try{await task()}catch(e){error.value=e.message||'操作失败'}finally{busy.value=false}
}
async function readRecording(){recording.value=null;const d=await call('get_http_log_recording');if(typeof d?.enabled!=='boolean')throw new Error('录制状态未知');recording.value=d.enabled}
function toggle(){run(async()=>{const next=!recording.value;recording.value=null;await call('set_http_log_recording',{enabled:next});await readRecording();if(recording.value!==next)throw new Error('录制开关未确认')})}
function diagnose(){run(async()=>{const d=await call('export_diagnostic');if(!d?.id)throw new Error('诊断创建结果无效');emit('open','diagnostic',d)})}
function send(){run(async()=>{
 let parsed
 try { parsed=new URL(url.value) } catch { throw new Error('需要不含凭据的 HTTP/HTTPS URL') }
 if(!/^https?:\/\//i.test(url.value)||!['http:','https:'].includes(parsed.protocol)||parsed.username||parsed.password)throw new Error('需要不含凭据的 HTTP/HTTPS URL')
 await readRecording();if(!recording.value)throw new Error('请先开启 HTTP 录制')
 const fields={}
 for(const line of headers.value.split('\n').filter(s=>s.trim())){const i=line.indexOf(':');if(i<1)throw new Error('请求头必须每行 名称: 值');const key=line.slice(0,i).trim();if(!/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(key)||/\r/.test(line))throw new Error('请求头格式无效');fields[key]=line.slice(i+1).trim()}
 const args={url:url.value,method:method.value,headers:JSON.stringify(fields),timeoutSec:30}
 if(method.value==='POST')args.body=body.value
 const d=await call('capture_once',args)
 if(d?.recordingDisabled)throw new Error('录制已关闭，未完成抓包')
 if(!d?.contextId)throw new Error('请求未返回抓包会话')
 emit('open','capture',d)
})}
</script>
<template>
 <details class="section log-actions"><summary class="btn btn-ghost btn-sm"><AppIcon name="chevron-right" :size="16" class="disclosure-chevron" /><span>录制、诊断与单次请求</span></summary>
 <fieldset :disabled="busy || disabled">
 <p>HTTP 录制：{{recording===null?'未知':recording?'开启':'关闭'}}</p>
 <button class="btn btn-ghost btn-sm" type="button" @click="run(readRecording)">读取录制状态</button>
 <button class="btn btn-ghost btn-sm" type="button" :disabled="recording===null" @click="toggle">{{recording?'关闭 HTTP 录制':'开启 HTTP 录制'}}</button>
 <button class="btn btn-ghost btn-sm" type="button" @click="diagnose">创建诊断</button>
 <form aria-label="单次请求" novalidate @submit.prevent="send">
 <input v-model="url" class="input" aria-label="请求 URL" type="url" required placeholder="https://…" />
 <div class="segmented wrap" role="group" aria-label="请求方法"><button class="seg" :class="{on:method===m}" v-for="m in ['GET','POST','HEAD']" :key="m" type="button" :aria-pressed="method===m" @click="method=m">{{m}}</button></div>
 <textarea v-model="headers" class="input textarea" aria-label="请求头（每行 名称: 值）" placeholder="Content-Type: application/json" />
 <textarea v-if="method==='POST'" v-model="body" class="input textarea" aria-label="请求正文" />
 <p>会向目标发送真实请求；仅允许公网地址，URL/query 可能含敏感参数。POST 可能改变目标数据。</p>
 <button class="btn btn-primary btn-sm" type="submit" :disabled="recording!==true || busy">发送请求</button>
 </form>
 </fieldset>
 <p v-if="error" role="alert">{{error}}</p>
 </details>
</template>
<style scoped>
.log-actions summary { display: flex; width: fit-content; max-width: 100%; height: auto; min-height: 32px; white-space: normal; list-style: none; cursor: pointer; }
.log-actions summary::-webkit-details-marker { display: none; }
.log-actions summary::marker { content: ""; }
.disclosure-chevron { flex: none; transition: transform .18s; }
.log-actions[open] .disclosure-chevron { transform: rotate(90deg); }
.log-actions fieldset { border: 0; padding: 0; margin: 12px 0 0; min-width: 0; display: flex; flex-wrap: wrap; gap: 8px; }
.log-actions fieldset > p, .log-actions form { flex-basis: 100%; min-width: 0; }
.log-actions form { display: grid; gap: 8px; }
.log-actions p { margin: 0; line-height: 1.6; }
.log-actions form > .btn { justify-self: start; }
.log-actions [role="alert"] { color: var(--bad); margin-top: 8px; }
.log-actions :disabled { opacity: .46; }
.log-actions fieldset:disabled { opacity: 1; }
</style>
