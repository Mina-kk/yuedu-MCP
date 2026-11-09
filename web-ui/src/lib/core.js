// Rust 前端核（ui-core → wasm-bindgen）封装。
//
// 生产路径：静态可分析的 `import('../wasm/studio_ui_core.js')`，Vite 会把 wasm-bindgen 的 JS
// 与同目录 .wasm 一起打进 dist（相对 base './' 时 wasm URL 由 Vite 改写为 assets 路径）。
// 绝不用 `new URL('./wasm/...', import.meta.url)` + @vite-ignore：那样生产包里不会有 wasm。
// .wasm 由 Rust worker 的 ui-core/build-web.sh 生成，前端不手写、不伪造。
// 缺失/失败时 status='failed'，UI 显示「Rust 核不可用」，绝不 JS 假替代后声称在线。

const REQUIRED = ['analyze_source_json', 'compare_source_json', 'summarize_evidence', 'filter_sources']

function parseJson(text, what) {
  try {
    return JSON.parse(text)
  } catch {
    throw new Error(`Rust 核返回的不是合法 JSON（${what}）`)
  }
}

export function createCore({ loader } = {}) {
  let api = null
  let status = 'idle'
  let error = ''
  let loading = null

  async function defaultLoader() {
    // 静态字面量：Vite 打包期就能解析到该模块与它引用的 .wasm。
    const mod = await import('../wasm/studio_ui_core.js')
    if (typeof mod.default === 'function') {
      // wasm-bindgen init：不传 URL 时按模块自身 import.meta.url 找同目录 .wasm。
      await mod.default()
    }
    return mod
  }

  async function load() {
    if (loading) return loading
    if (status === 'loaded') return api
    status = 'loading'
    error = ''
    loading = (async () => {
      try {
        const loaded = await (loader || defaultLoader)()
        const missing = REQUIRED.filter((name) => typeof loaded[name] !== 'function')
        if (missing.length) throw new Error(`Rust 核缺少导出：${missing.join(', ')}`)
        api = loaded
        status = 'loaded'
        return api
      } catch (err) {
        api = null
        status = 'failed'
        error = (err && err.message) || 'Rust 核加载失败'
        return null
      } finally {
        loading = null
      }
    })()
    return loading
  }

  function requireApi() {
    if (!api) {
      throw new Error(
        status === 'failed' ? `Rust 核不可用：${error}` : 'Rust 核尚未加载（wasm 未就绪）'
      )
    }
    return api
  }

  return {
    load,
    status: () => status,
    error: () => error,
    version: () => (api && typeof api.core_version === 'function' ? api.core_version() : ''),
    // analyze/compare 只用于校验与展示：返回里的 formatted 是 Rust 重新序列化的 JSON，
    // 大于 2^53 的数字会丢精度，所以编辑/保存永远以用户原文为准，不用这里的 formatted 回写。
    analyze: (text) => parseJson(requireApi().analyze_source_json(String(text ?? '')), 'analyze_source_json'),
    compare: (left, right) =>
      parseJson(requireApi().compare_source_json(String(left ?? ''), String(right ?? '')), 'compare_source_json'),
    filter: (itemsJson, query) =>
      parseJson(requireApi().filter_sources(String(itemsJson ?? '[]'), String(query ?? '')), 'filter_sources'),
    evidence: (text) => parseJson(requireApi().summarize_evidence(String(text ?? '')), 'summarize_evidence')
  }
}

/** 全局单例：App 只加载一次 wasm。 */
export const core = createCore()
