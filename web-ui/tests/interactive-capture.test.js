import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { execFileSync } from 'node:child_process'

describe('interactive capture workbench', () => {
  it('provides a separate visible-browser workbench', () => {
    expect(existsSync(resolve('src/components/InteractiveCaptureWorkbench.vue'))).toBe(true)
  })
  it('routes navigation to the interactive host and does not claim a failed finish was saved', async () => {
    const { default: Workbench } = await import('../src/components/InteractiveCaptureWorkbench.vue')
    const call = vi.fn(async (a) => {
      if (a.endsWith('.finish')) throw new Error('settlement failed')
      return { phase: 'active', contextId: 'cap:live', canGoBack: true, canGoForward: true, total: 0 }
    })
    const wrapper = mount(Workbench, { props: { call } }); await flushPromises()
    for (const [label, action] of [['后退', 'back'], ['前进', 'forward'], ['刷新', 'reload']]) {
      await wrapper.findAll('button').find(b => b.text() === label).trigger('click'); await flushPromises()
      expect(call).toHaveBeenCalledWith(`capture.browser.${action}`, {})
    }
    await wrapper.findAll('button').find(b => b.text() === '结束并保存').trigger('click'); await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('结束失败不代表已保存')
    expect(wrapper.findAll('button').find(b => b.text() === '导出会话').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('retains failure diagnostics and permits unsaved close/re-entry without claiming success', async () => {
    const { default: Workbench } = await import('../src/components/InteractiveCaptureWorkbench.vue')
    let phase = 'active'
    const call = vi.fn(async a => {
      if (a.endsWith('.finish')) { phase = 'failed'; throw new Error('DAO failure') }
      if (a.endsWith('.close')) return { closed: true, saved: false, contextId: 'cap:failed', diagnostic: '保存未确认' }
      return { phase, contextId: 'cap:failed', total: 1, diagnostic: phase === 'failed' ? '保存未确认，事务保留' : '' }
    })
    const wrapper = mount(Workbench, { props: { call } }); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '结束并保存').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('保存未确认，事务保留')
    expect(wrapper.text()).toContain('未确认保存')
    await wrapper.findAll('button').find(b => b.text() === '返回').trigger('click'); await flushPromises()
    expect(wrapper.emitted('close')[0][0]).toMatchObject({ closed: true, saved: false })
    wrapper.unmount()
    const reopened = mount(Workbench, { props: { call } }); await flushPromises()
    expect(reopened.text()).toContain('保存未确认，事务保留')
    expect(reopened.find('input').attributes('disabled')).toBeUndefined()
    reopened.unmount()
  })
  it('rejects an unconfirmed close receipt', async () => {
    const { default: Workbench } = await import('../src/components/InteractiveCaptureWorkbench.vue')
    const wrapper = mount(Workbench, { props: { call: async () => ({ phase: 'failed', contextId: 'cap:f' }) } })
    await flushPromises(); await wrapper.findAll('button').find(b => b.text() === '返回').trigger('click'); await flushPromises()
    expect(wrapper.emitted('close')).toBeUndefined()
    wrapper.unmount()
  })
  it('requires explicit export confirmation', async () => {
    const { default: Workbench } = await import('../src/components/InteractiveCaptureWorkbench.vue')
    const call = vi.fn(async (action) => action.endsWith('.state') ? { phase: 'saved', contextId: 'cap:test', total: 1 } : { saved: true })
    const wrapper = mount(Workbench, { props: { call } })
    await flushPromises()
    const button = text => wrapper.findAll('button').find(b => b.text() === text)
    await button('导出会话').trigger('click')
    expect(call.mock.calls.some(([a]) => a.endsWith('.export'))).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    await button('确认导出').trigger('click'); await flushPromises()
    expect(call).toHaveBeenCalledWith('capture.browser.export', {})
    wrapper.unmount()
  })
  it.each([
    { partial: true, missingResources: 2, omittedResources: 0 },
    { partial: false, missingResources: 0, omittedResources: 3 },
  ])('retains incomplete export metadata through refresh and cancelled retry: %j', async metadata => {
    const { default: Workbench } = await import('../src/components/InteractiveCaptureWorkbench.vue')
    let receipt = { saved: true, cancelled: false, count: 7, total: 7, ...metadata }
    const call = vi.fn(async a => a.endsWith('.export') ? receipt : a.endsWith('.transactions') ? { items: [] } : { phase: 'saved', contextId: 'cap:test', total: 7 })
    const wrapper = mount(Workbench, { props: { call } }); await flushPromises()
    const button = text => wrapper.findAll('button').find(b => b.text() === text)
    await button('导出会话').trigger('click'); await button('确认导出').trigger('click'); await flushPromises()
    const warning = () => wrapper.find('[data-testid="capture-export-result"]').text()
    expect(warning()).toContain('文件已保存（7 / 7 条）')
    expect(warning()).toContain('不是完整备份')
    expect(warning()).toContain(`缺失或无法读取 ${metadata.missingResources} 个资源`)
    expect(warning()).toContain(`省略 ${metadata.omittedResources} 个资源`)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    await button('事务 (7)').trigger('click'); await flushPromises()
    expect(warning()).toContain('不是完整备份')
    receipt = { saved: false, cancelled: true }
    await button('导出会话').trigger('click'); await button('确认导出').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('已取消导出')
    expect(warning()).toContain('不是完整备份')
    call.mockImplementation(async a => {
      if (a.endsWith('.export')) throw new Error('provider failed')
      if (a.endsWith('.transactions')) return { items: [] }
      return { phase: 'saved', contextId: 'cap:test', total: 7 }
    })
    await button('导出会话').trigger('click'); await button('确认导出').trigger('click'); await flushPromises()
    expect(warning()).toContain('不是完整备份')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('操作未完成')
    wrapper.unmount()
  })
  it.each([
    { saved: true },
    { saved: true, cancelled: true, count: 1, total: 1, partial: false, missingResources: 0, omittedResources: 0 },
    { saved: true, count: 1, total: 1, partial: false, missingResources: 1, omittedResources: 0 },
    { saved: true, count: '1', total: 1, partial: false, missingResources: 0, omittedResources: 0 },
    { saved: false, cancelled: false },
    null,
  ])('does not claim file saved for invalid receipt %j', async receipt => {
    const { default: Workbench } = await import('../src/components/InteractiveCaptureWorkbench.vue')
    const wrapper = mount(Workbench, { props: { call: async a => a.endsWith('.export') ? receipt : { phase: 'saved', contextId: 'cap:test', total: 1 } } })
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '导出会话').trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '确认导出').trigger('click'); await flushPromises()
    expect(wrapper.find('[data-testid="capture-export-result"]').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    wrapper.unmount()
  })
  it('renders a receipt produced by the actual JVM host/exporter', async () => {
    // Generate from current production Kotlin every run, not optional/stale JSON.
    const receipt = JSON.parse(execFileSync('python3', [
      resolve('tests/fixtures/capture-export-receipt.py'),
    ], { encoding: 'utf8', timeout: 120_000, stdio: ['ignore', 'pipe', 'inherit'] }))
    expect(receipt).toMatchObject({ saved: true, cancelled: false, count: 1, total: 1, partial: true, missingResources: 1, omittedResources: 0 })
    const { default: Workbench } = await import('../src/components/InteractiveCaptureWorkbench.vue')
    const wrapper = mount(Workbench, { props: { call: async a => a.endsWith('.export') ? receipt : { phase: 'saved', contextId: 'cap:export', total: receipt.total } } })
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '导出会话').trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '确认导出').trigger('click'); await flushPromises()
    expect(wrapper.find('[data-testid="capture-export-result"]').text()).toContain('缺失或无法读取 1 个资源')
    expect(wrapper.text()).toContain('不是完整备份')
    wrapper.unmount()
  }, 130_000)
  it('keeps all loaded pages after refresh, rather than losing rows after 50', async () => {
    const { default: Workbench } = await import('../src/components/InteractiveCaptureWorkbench.vue')
    const items = Array.from({ length: 75 }, (_, id) => ({ id, method: 'GET', statusCode: 200, url: `https://example.com/${id}` }))
    const call = vi.fn(async (a, args) => a.endsWith('.state') ? { phase: 'saved', contextId: 'cap:pages', total: 75 } : { items: items.slice(args.offset, args.offset + 50) })
    const wrapper = mount(Workbench, { props: { call } }); await flushPromises()
    await wrapper.findAll('button').find(b => b.text().startsWith('事务')).trigger('click'); await flushPromises()
    expect(wrapper.findAll('.transaction')).toHaveLength(50)
    await wrapper.findAll('button').find(b => b.text() === '加载更多').trigger('click'); await flushPromises()
    expect(wrapper.findAll('.transaction')).toHaveLength(75)
    wrapper.unmount()
  })
})
