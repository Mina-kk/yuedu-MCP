<script setup>
import { computed } from 'vue'
const props = defineProps({ bootstrap: { type: Object, default: null } })
const version = computed(() => {
  const name = props.bootstrap?.versionName
  const code = props.bootstrap?.versionCode
  return `${typeof name === 'string' && name.trim() ? name : '版本未知'}${Number.isInteger(code) ? `（${code}）` : ''}`
})
</script>

<template>
  <article class="section feature-guide" aria-label="功能指南">
    <h2>功能指南</h2>
    <p class="hint">应用版本：{{ version }}</p>
    <h3>应用做什么</h3>
    <p>提供书源管理、规则执行、网络取证与 MCP 工具。模型推理由外部 AI 客户端负责，应用不负责模型调用，也不保存模型密钥。</p>
    <h3>人工工作流</h3>
    <ol>
      <li>在书源页查看、编辑或导入书源，先保存并检查原文。</li>
      <li>在设置中配置阅读 Web 服务地址；测试连接仅证明连接与鉴权，不代表书源可用。</li>
      <li>按需在日志页抓包取证；遇到登录或验证码，在验证中心打开隔离浏览器并按实际页面完成。</li>
      <li>导入阅读客户端后仍需用户确认，并在阅读端搜索、打开目录和样章。</li>
    </ol>
    <h3>AI 工作流</h3>
    <ol>
      <li>启动 MCP 服务，按客户端所在设备选择同机或局域网配置；鉴权开启时设置令牌。</li>
      <li>建立任务上下文，读取现有书源或检索候选语料，先取实际页面证据再修改规则。</li>
      <li>保存后回读，执行静态检查与沙箱样本测试，再同步阅读端调试和验证。</li>
    </ol>
    <h3>抓包能力与限制</h3>
    <p>OkHttp 供给抓包记录由应用网络层供给的请求，并非系统全流量抓包。无头抓包用于自动加载，不支持人工点击；可交互浏览器抓包用于点击、翻页和观察请求。WebView 请求的覆盖受供给路径限制，未记录不等于没有请求；结束后核对已保存记录。</p>
    <h3>验证边界</h3>
    <p>静态检查、沙箱样本通过不等于阅读端通过。未连接阅读端时只能报告“阅读端未覆盖”。阅读导入入口被唤起不等于导入完成；隔离浏览器和外部浏览器的 Cookie 不保证共享。</p>
    <h3>安全与敏感 URL</h3>
    <p>URL 路径与查询参数、请求头、正文、Cookie 和日志可能含凭证及个人信息。分享或导出前检查并脱敏；请求头脱敏不保证敏感 URL 已被移除。复制的令牌和客户端 JSON 也可能含凭证，只交给可信客户端。</p>
    <p>关闭访问令牌校验后，可访问服务地址的客户端无需令牌即可调用工具。只在可信网络使用，不要将端口直接暴露公网。破坏性操作前核对目标并保留备份。</p>
  </article>
</template>

<style scoped>
.feature-guide { color: var(--text); background: var(--surface); overflow-wrap: anywhere; }
.feature-guide h3 { margin-top: 1.25rem; }
.feature-guide p, .feature-guide li { line-height: 1.7; }
.feature-guide ol { padding-left: 1.4rem; }
</style>
