import { exportText, isAndroidRuntime } from './files.js'

// Host owns arrays; browsers can still download an existing draft verbatim.
export async function deliverSources(store, payload, action, packageName) {
  const browserDraft = !isAndroidRuntime(store)
  if (browserDraft && (action !== 'save' || typeof payload?.source !== 'string' ||
      ['projectIds', 'scope', 'token'].some(key => Object.prototype.hasOwnProperty.call(payload, key)))) {
    throw new Error('浏览器模式仅支持导出当前草稿；全库、选中及其他交付操作需要 Android 宿主')
  }
  const result = browserDraft
    ? await exportText(store, { name: 'book-source.json', text: payload.source, mime: 'application/json' })
    : await store.bridge.call('sources.deliver', {
      ...payload, action, ...(packageName ? { packageName } : {})
    })
  if (result?.cancelled === true) {
    if (action === 'save' && result.saved !== false) throw new Error('文件保存取消回执无效')
    return { cancelled: true }
  }
  if (action === 'targets') {
    if (!Array.isArray(result?.targets)) throw new Error('客户端列表回执无效')
    return result
  }
  if (action === 'copy' && result?.copied !== true) throw new Error('复制未确认')
  if (action === 'share' && result?.launched !== true) throw new Error('分享未唤起')
  if (action === 'save' && result?.saved !== true) throw new Error('文件保存未确认')
  if (action === 'reader' && !(result?.launched === true && result.confirmed === false && result.confirmation === 'pending_user')) throw new Error('阅读唤起回执无效')
  store.toast(action === 'reader' ? '已唤起阅读勾选页，待用户确认；阅读端未覆盖'
    : action === 'share' ? '已打开系统分享，未确认接收方保存'
      : action === 'copy' ? '已复制完整书源数组' : browserDraft ? '已导出当前草稿' : '已导出完整书源数组')
  return result
}
