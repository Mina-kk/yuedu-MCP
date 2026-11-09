// Rust 前端核（ui-core → wasm-bindgen）封装。
//
// 生产路径：真实 `import('./wasm/studio_ui_core.js')`，default init 加载同目录 .wasm。
// .wasm 由 Rust worker 的 ui-core/build-web.sh 生成，前端不手写、不伪造。
// 缺失/失败时 status='failed'，UI 显示「Rust 核不可用」，绝不 JS 假替代后声称在线。

const WASM_MODULE = './wasm/studio_ui_core.js'

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
    const mod = await import(/* @vite-ignore */ new URL(WASM_MODULE, import.meta.url).href)
    if (typeof mod.default === 'function') await mod.default()
    return mod
  }

  async function load() {
    if (loading) return loading
    status = 'loading'
    error = ''
    loading = (async () => {
      try {
        const loaded = await (loader || defaultLoader)()
        const required = ['analyze_source_json', 'compare_source_json', 'summarize_evidence', 'filter_sources']
        const missing = required.filter((name) => typeof loaded[name] !== 'function')
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
        status === 'failed'
          ? `Rust 核不可用：${error}`
          : 'Rust 核尚未加载（wasm 未就绪）'
      )
    }
    return api
  }

  return {
    load,
    status: () => status,
    error: () => error,
    version: () => (api && typeof api.core_version === 'function' ? api.core_version() : ''),
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
