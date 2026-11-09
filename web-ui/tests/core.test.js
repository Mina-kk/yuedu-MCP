import { describe, it, expect, vi } from 'vitest'
import { createCore } from '../src/lib/core.js'

// Rust 核封装：真实 import 失败时必须如实报告不可用，禁止 JS 假替代后声称 Rust 在线。

const fakeApi = {
  default: vi.fn(async () => {}),
  core_version: () => 'studio-ui-core/2.0.0',
  analyze_source_json: (text) => JSON.stringify({ valid: true, sourceCount: 1, sources: [{ bookSourceUrl: 'u' }], issues: [], formatted: text }),
  filter_sources: (items, q) => JSON.stringify({ items: JSON.parse(items), total: JSON.parse(items).length, query: q }),
  compare_source_json: () => JSON.stringify({ equal: true, changedTopLevelFields: [], identityChanged: false }),
  summarize_evidence: () => JSON.stringify({ scope: 'sandbox', status: 'static', label: '静态通过', tone: 'info', passed: false, notes: [] })
}

describe('core (Rust WASM wrapper)', () => {
  it('加载成功才报告 loaded，并暴露真实函数结果', async () => {
    const core = createCore({ loader: async () => fakeApi })
    expect(core.status()).toBe('idle')
    await core.load()
    expect(core.status()).toBe('loaded')
    expect(core.version()).toBe('studio-ui-core/2.0.0')
    const analyzed = core.analyze('{"bookSourceUrl":"u"}')
    expect(analyzed.sourceCount).toBe(1)
  })

  it('加载失败时 status=failed 且调用抛错，不返回假结果', async () => {
    const core = createCore({
      loader: async () => {
        throw new Error('wasm missing')
      }
    })
    await core.load()
    expect(core.status()).toBe('failed')
    expect(core.error()).toMatch(/wasm missing/)
    expect(() => core.analyze('{}')).toThrow(/Rust|不可用|wasm/i)
  })

  it('未加载就调用明确报错', () => {
    const core = createCore({ loader: async () => fakeApi })
    expect(() => core.analyze('{}')).toThrow()
  })

  it('非法 JSON 参数由 Rust 返回结构化 issue 而不是抛异常', async () => {
    const core = createCore({ loader: async () => fakeApi })
    await core.load()
    const out = core.analyze('not json')
    expect(out).toHaveProperty('valid')
  })

  it('filter/compare/evidence 走 Rust 导出，不本地重写', async () => {
    const spy = { ...fakeApi, filter_sources: vi.fn(fakeApi.filter_sources) }
    const core = createCore({ loader: async () => spy })
    await core.load()
    const out = core.filter('[{"name":"a"}]', 'a')
    expect(spy.filter_sources).toHaveBeenCalled()
    expect(out.total).toBe(1)
    expect(core.compare('{}', '{}').equal).toBe(true)
    expect(core.evidence('{}').scope).toBe('sandbox')
  })

  it('Rust 返回非 JSON 时抛错而不是猜', async () => {
    const core = createCore({ loader: async () => ({ ...fakeApi, analyze_source_json: () => '<html>' }) })
    await core.load()
    expect(() => core.analyze('{}')).toThrow()
  })
})
