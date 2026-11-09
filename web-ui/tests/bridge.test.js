import { describe, it, expect, vi, beforeEach } from 'vitest'

// 桥接契约测试：id 关联、超时、错误、事件、无 bridge 的显式未连接状态。
// 测试里用假 window.StudioBridge 模拟 Android WebMessageListener 注入的对象。

async function loadBridge() {
  vi.resetModules()
  return import('../src/lib/bridge.js')
}

function makeFakeBridge() {
  const sent = []
  const bridge = {
    sent,
    postMessage(raw) {
      sent.push(JSON.parse(raw))
    }
  }
  return bridge
}

describe('bridge: Android StudioBridge', () => {
  beforeEach(() => {
    delete window.StudioBridge
  })

  it('无 StudioBridge 时报告未连接，而不是假装在线', async () => {
    const { createBridge } = await loadBridge()
    const b = createBridge()
    expect(b.kind).toBe('none')
    expect(b.connected).toBe(false)
    await expect(b.call('app.bootstrap')).rejects.toMatchObject({ code: 'runtime_unavailable' })
  })

  it('postMessage 发 {id,method,params}，onmessage 按 id 结算 Promise', async () => {
    window.StudioBridge = makeFakeBridge()
    const { createBridge } = await loadBridge()
    const b = createBridge()
    expect(b.kind).toBe('android')
    const p = b.call('app.bootstrap', {})
    const req = window.StudioBridge.sent[0]
    expect(req.method).toBe('app.bootstrap')
    expect(typeof req.id).toBe('string')
    expect(req.params).toEqual({})
    window.StudioBridge.onmessage({ data: JSON.stringify({ id: req.id, ok: true, result: { versionName: '2.0.0' } }) })
    await expect(p).resolves.toEqual({ versionName: '2.0.0' })
  })

  it('错误回包 reject 带 code/message', async () => {
    window.StudioBridge = makeFakeBridge()
    const { createBridge } = await loadBridge()
    const b = createBridge()
    const p = b.call('tools.call', { name: 'x' })
    const req = window.StudioBridge.sent[0]
    window.StudioBridge.onmessage({ data: JSON.stringify({ id: req.id, ok: false, error: { code: 'not_found', message: '未知工具' } }) })
    await expect(p).rejects.toMatchObject({ code: 'not_found', message: '未知工具' })
  })

  it('超时拒绝 pending，不会永久 loading', async () => {
    vi.useFakeTimers()
    window.StudioBridge = makeFakeBridge()
    const { createBridge } = await loadBridge()
    const b = createBridge({ timeoutMs: 1000 })
    const p = b.call('tools.list')
    const assertion = expect(p).rejects.toMatchObject({ code: 'timeout' })
    vi.advanceTimersByTime(1001)
    await assertion
    vi.useRealTimers()
  })

  it('无关 id / 非 JSON / 事件消息不会破坏其它 pending', async () => {
    window.StudioBridge = makeFakeBridge()
    const { createBridge } = await loadBridge()
    const b = createBridge()
    const p = b.call('tools.list')
    const req = window.StudioBridge.sent[0]
    window.StudioBridge.onmessage({ data: 'not json' })
    window.StudioBridge.onmessage({ data: JSON.stringify({ id: 'other', ok: true, result: 1 }) })
    window.StudioBridge.onmessage({ data: JSON.stringify({ id: req.id, ok: true, result: { tools: [] } }) })
    await expect(p).resolves.toEqual({ tools: [] })
  })

  it('宿主事件 studio:back / studio:insets 派发给监听者（{event,payload} 信封）', async () => {
    window.StudioBridge = makeFakeBridge()
    const { createBridge } = await loadBridge()
    const b = createBridge()
    const seen = []
    b.onEvent((name, payload) => seen.push([name, payload]))
    window.StudioBridge.onmessage({ data: JSON.stringify({ event: 'studio:back', payload: {} }) })
    window.StudioBridge.onmessage({ data: JSON.stringify({ event: 'studio:insets', payload: { top: 24, bottom: 0, ime: 0 } }) })
    expect(seen).toEqual([
      ['studio:back', {}],
      ['studio:insets', { top: 24, bottom: 0, ime: 0 }]
    ])
  })

  it('宿主用 window CustomEvent 派发时，同一个 handler 从 event.detail 读取', async () => {
    window.StudioBridge = makeFakeBridge()
    const { createBridge } = await loadBridge()
    const b = createBridge()
    const seen = []
    b.onEvent((name, payload) => seen.push([name, payload]))
    window.dispatchEvent(new CustomEvent('studio:back', { detail: {} }))
    window.dispatchEvent(new CustomEvent('studio:insets', { detail: { top: 12, bottom: 4, ime: 300 } }))
    window.dispatchEvent(new CustomEvent('studio:verification-browser', { detail: { action: 'open', id: 's1' } }))
    expect(seen).toEqual([
      ['studio:back', {}],
      ['studio:insets', { top: 12, bottom: 4, ime: 300 }],
      ['studio:verification-browser', { action: 'open', id: 's1' }]
    ])
  })

  it('宿主关闭时 reject 全部 pending', async () => {
    window.StudioBridge = makeFakeBridge()
    const { createBridge } = await loadBridge()
    const b = createBridge()
    const p1 = b.call('app.bootstrap')
    const p2 = b.call('tools.list')
    b.dispose('host_gone')
    await expect(p1).rejects.toMatchObject({ code: 'host_gone' })
    await expect(p2).rejects.toMatchObject({ code: 'host_gone' })
  })
})

describe('bridge: Web MCP runtime', () => {
  beforeEach(() => {
    delete window.StudioBridge
  })

  function jsonResponse(payload, { status = 200, headers = {} } = {}) {
    const map = { 'content-type': 'application/json', ...headers }
    return {
      ok: status >= 200 && status < 300,
      status,
      headers: { get: (k) => map[String(k).toLowerCase()] ?? null },
      text: async () => (typeof payload === 'string' ? payload : JSON.stringify(payload))
    }
  }

  it('显式配置的 MCP endpoint 走标准 Streamable HTTP initialize→tools/list，并带协议版本头', async () => {
    const calls = []
    const fetchImpl = vi.fn(async (url, init) => {
      const body = JSON.parse(init.body)
      calls.push({ url, headers: init.headers, body })
      if (body.method === 'initialize') {
        return jsonResponse(
          {
            jsonrpc: '2.0',
            id: body.id,
            result: { protocolVersion: '2025-03-26', capabilities: { tools: {} }, serverInfo: { name: 'legado-source-studio', version: '2.0.0' } }
          },
          { headers: { 'mcp-session-id': 'sess-1' } }
        )
      }
      if (body.method === 'notifications/initialized') return jsonResponse('', { status: 202 })
      return jsonResponse({ jsonrpc: '2.0', id: body.id, result: { tools: [{ name: 'list_sources' }] } })
    })
    const { createWebRuntimeBridge } = await loadBridge()
    const b = createWebRuntimeBridge({ endpoint: 'http://127.0.0.1:12345/mcp', token: 'secret-token', fetchImpl })
    expect(b.kind).toBe('web')
    const result = await b.call('tools.list')
    expect(result).toEqual({ tools: [{ name: 'list_sources' }] })
    expect(calls[0].body.method).toBe('initialize')
    expect(calls[0].headers.Authorization).toBe('Bearer secret-token')
    expect(calls[1].body.method).toBe('notifications/initialized')
    expect(calls[1].headers['Mcp-Session-Id']).toBe('sess-1')
    expect(calls[2].body.method).toBe('tools/list')
    // 初始化之后的请求必须带 MCP-Protocol-Version
    expect(calls[2].headers['MCP-Protocol-Version']).toBe('2025-03-26')
    expect(JSON.stringify(b.describe())).not.toContain('secret-token')
  })

  it('notifications/initialized 返回 401 必须报错，不能静默继续', async () => {
    const fetchImpl = vi.fn(async (url, init) => {
      const body = JSON.parse(init.body)
      if (body.method === 'initialize') {
        return jsonResponse({ jsonrpc: '2.0', id: body.id, result: { protocolVersion: '2024-11-05' } })
      }
      return jsonResponse('unauthorized', { status: 401 })
    })
    const { createWebRuntimeBridge } = await loadBridge()
    const b = createWebRuntimeBridge({ endpoint: 'http://x/mcp', fetchImpl })
    await expect(b.call('tools.list')).rejects.toMatchObject({ code: 'unauthorized' })
  })

  it('SSE 里必须按 id 匹配响应，不能拿最后一条', async () => {
    const fetchImpl = vi.fn(async (url, init) => {
      const body = JSON.parse(init.body)
      if (body.method === 'initialize') {
        return jsonResponse({ jsonrpc: '2.0', id: body.id, result: { protocolVersion: '2024-11-05' } })
      }
      if (body.method === 'notifications/initialized') return jsonResponse('', { status: 202 })
      const sse = [
        'event: message',
        `data: ${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/progress', params: { progress: 1 } })}`,
        '',
        'event: message',
        `data: ${JSON.stringify({ jsonrpc: '2.0', id: 999, result: { tools: [{ name: 'wrong' }] } })}`,
        '',
        'event: message',
        `data: ${JSON.stringify({ jsonrpc: '2.0', id: body.id, result: { tools: [{ name: 'right' }] } })}`,
        ''
      ].join('\n')
      return jsonResponse(sse, { headers: { 'content-type': 'text/event-stream' } })
    })
    const { createWebRuntimeBridge } = await loadBridge()
    const b = createWebRuntimeBridge({ endpoint: 'http://x/mcp', fetchImpl })
    const result = await b.call('tools.list')
    expect(result.tools).toEqual([{ name: 'right' }])
  })

  it('SSE 里没有匹配 id 的响应时明确报错', async () => {
    const fetchImpl = vi.fn(async (url, init) => {
      const body = JSON.parse(init.body)
      if (body.method === 'initialize') return jsonResponse({ jsonrpc: '2.0', id: body.id, result: { protocolVersion: '2024-11-05' } })
      if (body.method === 'notifications/initialized') return jsonResponse('', { status: 202 })
      return jsonResponse(`data: ${JSON.stringify({ jsonrpc: '2.0', id: 4242, result: {} })}\n\n`, {
        headers: { 'content-type': 'text/event-stream' }
      })
    })
    const { createWebRuntimeBridge } = await loadBridge()
    const b = createWebRuntimeBridge({ endpoint: 'http://x/mcp', fetchImpl })
    await expect(b.call('tools.list')).rejects.toMatchObject({ code: 'bad_response' })
  })

  it('timeoutMs 真正通过 AbortController 中止请求', async () => {
    let capturedSignal = null
    const fetchImpl = vi.fn((url, init) => {
      capturedSignal = init.signal
      return new Promise((resolve, reject) => {
        init.signal.addEventListener('abort', () => {
          const err = new Error('aborted')
          err.name = 'AbortError'
          reject(err)
        })
      })
    })
    const { createWebRuntimeBridge } = await loadBridge()
    const b = createWebRuntimeBridge({ endpoint: 'http://x/mcp', fetchImpl, timeoutMs: 40 })
    await expect(b.call('tools.list')).rejects.toMatchObject({ code: 'timeout' })
    expect(capturedSignal).toBeTruthy()
    expect(capturedSignal.aborted).toBe(true)
  })

  it('网络/CORS 失败如实报错，不造数据', async () => {
    const fetchImpl = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    const { createWebRuntimeBridge } = await loadBridge()
    const b = createWebRuntimeBridge({ endpoint: 'http://127.0.0.1:1/mcp', fetchImpl })
    await expect(b.call('tools.list')).rejects.toMatchObject({ code: 'network_error' })
  })

  it('web 模式不支持宿主方法时明确 unsupported', async () => {
    const fetchImpl = vi.fn()
    const { createWebRuntimeBridge } = await loadBridge()
    const b = createWebRuntimeBridge({ endpoint: 'http://x/mcp', fetchImpl })
    await expect(b.call('connection.copy', { kind: 'token' })).rejects.toMatchObject({ code: 'unsupported' })
  })
})
