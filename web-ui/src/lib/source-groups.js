// Display-only counterpart of SourceCatalog / DomainKey. Rows remain untouched.
const compoundSuffixes = new Set(['com.cn', 'net.cn', 'org.cn', 'gov.cn', 'edu.cn', 'co.uk', 'org.uk', 'com.au', 'co.jp', 'com.hk', 'com.tw'])
export function sourceDomain(siteUrl) {
  const raw = String(siteUrl ?? '').trim()
  if (!raw) return '未识别域名'
  try {
    const url = new URL(raw.includes(':') ? raw : `https://${raw}`)
    if (!['http:', 'https:'].includes(url.protocol)) return raw
    const host = url.hostname.replace(/^\[|\]$/g, '').replace(/^\.+|\.+$/g, '').toLowerCase()
    if (!host) return raw
    if (host === 'localhost' || /^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host.includes(':')) return host
    const parts = host.split('.')
    const suffix = parts.slice(-2).join('.')
    return parts.length <= 2 ? host : parts.slice(compoundSuffixes.has(suffix) ? -3 : -2).join('.')
  } catch { return raw }
}
export function groupSources(rows) {
  const domains = new Map()
  for (const row of rows) {
    const domain = sourceDomain(row.bookSourceUrl)
    if (!domains.has(domain)) domains.set(domain, [])
    domains.get(domain).push(row)
  }
  return [...domains].map(([domain, items]) => {
    items.sort((a, b) => (Number(b.updatedAt) || 0) - (Number(a.updatedAt) || 0))
    return { domain, items, total: items.length, latest: items[0] }
  }).sort((a, b) => (Number(b.latest.updatedAt) || 0) - (Number(a.latest.updatedAt) || 0))
}
// Source-list references use TaskContextStore.read: body is a UTF-16 character slice.
// Bound one referenced summary result, not the size of the user's source library.
const MAX_RESULT_CHARS = 8 * 1024 * 1024
export async function readSourceListResult(parsed, readResult, isCurrent = () => true) {
  function dataOf(response) {
    if (response.threw || response.isError) throw new Error(response.threw || response.text || '书源列表读取失败')
    return response.data
  }
  const data = dataOf(parsed)
  if (!data?.resultId && !data?.resultContextId) return data
  const { resultId, resultContextId, resultChars, totalChars } = data
  if (typeof resultId !== 'string' || !resultId || typeof resultContextId !== 'string' || !resultContextId || !Number.isSafeInteger(resultChars) || resultChars <= 0 || resultChars > MAX_RESULT_CHARS || totalChars !== resultChars) throw new Error('书源列表引用无效或超过读取上限')
  const chunks = []
  let offset = 0
  while (offset < resultChars) {
    if (!isCurrent()) return null
    const response = await readResult({ resultId, contextId: resultContextId, offset, limit: 6000 })
    if (!isCurrent()) return null
    const part = dataOf(response)
    if (!part || part.id !== resultId || part.contextId !== resultContextId || part.kind !== 'result' || part.found !== true || part.totalChars !== resultChars || part.offset !== offset || typeof part.body !== 'string' || !part.body.length || part.body.length > 6000 || !Number.isSafeInteger(part.nextOffset) || part.nextOffset !== offset + part.body.length || part.nextOffset > resultChars || part.hasMore !== (part.nextOffset < resultChars)) throw new Error('书源列表引用分片无效或未前进')
    chunks.push(part.body)
    offset = part.nextOffset
  }
  const raw = chunks.join('')
  if (raw.length !== resultChars) throw new Error('书源列表引用总长度不一致')
  try { return JSON.parse(raw) } catch { throw new Error('书源列表引用不是完整 JSON') }
}

// Validate every summary page before it can contribute to a complete UI group.
export function validateSourcePage(data, { total, offset, seen, limit }) {
  if (!data || !Array.isArray(data.items) || typeof data.hasMore !== 'boolean') throw new Error('书源列表分页格式错误')
  if (!Number.isSafeInteger(data.total) || data.total < 0 || (total !== null && total !== data.total)) throw new Error('书源列表总数无效或读取期间发生变化，请刷新')
  if (data.items.length > limit) throw new Error('书源列表分页超过请求上限')
  const batchIds = new Set()
  for (const row of data.items) {
    if (typeof row?.projectId !== 'string' || !row.projectId.trim() || seen.has(row.projectId) || batchIds.has(row.projectId)) throw new Error('书源列表分页重复或项目 ID 无效')
    batchIds.add(row.projectId)
  }
  const nextOffset = offset + data.items.length
  if (nextOffset > data.total || data.hasMore !== (nextOffset < data.total)) throw new Error('书源列表分页与总数不一致')
  if (data.hasMore && !data.items.length) throw new Error('书源列表分页未前进')
  return { items: data.items, total: data.total, hasMore: data.hasMore, nextOffset }
}
