// 文件导入/导出：Android 走宿主 SAF（file.export / file.import），浏览器走下载与 <input type=file>。
// 不接受任意路径；导入大小上限与宿主一致（2MB）。

export const MAX_IMPORT_BYTES = 2 * 1024 * 1024

/** Validate JSON but submit exact object substrings, never JS-number reserialization. */
export function sourceJsonSlices(input) {
  const text = String(input).trim()
  const parsed = JSON.parse(text)
  const entries = Array.isArray(parsed) ? parsed : [parsed]
  if (entries.some(item => !item || typeof item !== 'object' || Array.isArray(item))) {
    throw new Error('每条书源必须是 JSON 对象')
  }
  if (!Array.isArray(parsed)) return [text]
  const slices = []
  let start = -1, depth = 0, quoted = false, escaped = false
  for (let i = 1; i < text.length - 1; i += 1) {
    const ch = text[i]
    if (quoted) {
      if (escaped) escaped = false
      else if (ch === '\\') escaped = true
      else if (ch === '"') quoted = false
      continue
    }
    if (ch === '"') quoted = true
    else if (ch === '{' || ch === '[') {
      if (depth === 0) start = i
      depth += 1
    } else if (ch === '}' || ch === ']') {
      depth -= 1
      if (depth === 0 && start >= 0) { slices.push(text.slice(start, i + 1)); start = -1 }
    }
  }
  if (slices.length !== entries.length) throw new Error('书源数组切分失败')
  return slices
}

function safeName(name, fallback = 'export.json') {
  const raw = String(name || '').trim() || fallback
  // 去掉路径分隔符与控制字符，避免宿主/浏览器把它当路径。
  return raw.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').slice(0, 120)
}

export function isAndroidRuntime(store) {
  return Boolean(store && store.state && store.state.bridgeKind === 'android')
}

/** 导出文本：Android 走宿主 SAF；浏览器生成 Blob 下载。返回 {saved:boolean, cancelled?:boolean, name} */
export async function exportText(store, { name, text, mime = 'application/json' }) {
  const filename = safeName(name)
  if (isAndroidRuntime(store)) {
    const result = await store.bridge.call('file.export', { name: filename, text: String(text ?? ''), mime })
    return { saved: Boolean(result && result.saved), cancelled: Boolean(result && result.cancelled), name: filename }
  }
  const blob = new Blob([String(text ?? '')], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 4000)
  return { saved: true, name: filename }
}

/** 导入文本：Android 走宿主 SAF；浏览器用文件选择器。取消返回 {cancelled:true}。 */
export async function importText(store, { accept = '.json,application/json' } = {}) {
  if (isAndroidRuntime(store)) {
    const result = await store.bridge.call('file.import', {})
    if (!result || result.cancelled || typeof result.text !== 'string') return { cancelled: true }
    if (result.text.length > MAX_IMPORT_BYTES) throw new Error('导入文件超过 2MB 上限')
    return { cancelled: false, name: result.name || '', text: result.text }
  }
  return new Promise((resolve, reject) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = accept
    input.style.display = 'none'
    let settled = false
    const done = (value) => {
      if (settled) return
      settled = true
      input.remove()
      resolve(value)
    }
    input.addEventListener('change', () => {
      const file = input.files && input.files[0]
      if (!file) return done({ cancelled: true })
      if (file.size > MAX_IMPORT_BYTES) {
        input.remove()
        settled = true
        reject(new Error('导入文件超过 2MB 上限'))
        return
      }
      const reader = new FileReader()
      reader.onload = () => done({ cancelled: false, name: file.name, text: String(reader.result || '') })
      reader.onerror = () => {
        input.remove()
        settled = true
        reject(new Error('读取文件失败'))
      }
      reader.readAsText(file)
    })
    // 用户点取消时多数浏览器不发 change；靠窗口重新聚焦兜底，避免永久 pending。
    const onFocus = () => {
      setTimeout(() => {
        if (!settled && (!input.files || input.files.length === 0)) done({ cancelled: true })
      }, 800)
      window.removeEventListener('focus', onFocus)
    }
    window.addEventListener('focus', onFocus)
    document.body.appendChild(input)
    input.click()
  })
}
