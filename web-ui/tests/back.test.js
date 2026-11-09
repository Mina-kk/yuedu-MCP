import { describe, it, expect } from 'vitest'
import { createBackHandler } from '../src/lib/back.js'

// 返回 / Escape 决策顺序：dialog → sheet → dirty 确认 → 根页退出确认。
// 顺序改变会违背用户预期，因此每个分支都锁在测试里。

function makeState(patch = {}) {
  return {
    dialog: null,
    sheet: null,
    sheetDirty: false,
    page: 'mcp',
    ...patch
  }
}

function makeActions() {
  const calls = []
  const record = (name) => (...args) => {
    calls.push([name, ...args])
    return name
  }
  return {
    calls,
    closeDialog: record('closeDialog'),
    closeSheet: record('closeSheet'),
    requestDiscard: record('requestDiscard'),
    requestExit: record('requestExit'),
    goRoot: record('goRoot')
  }
}

describe('back handler', () => {
  it('有确认框时先关确认框', () => {
    const actions = makeActions()
    const handle = createBackHandler({ state: makeState({ dialog: { kind: 'confirm' } }), actions })
    expect(handle()).toBe('dialog')
    expect(actions.calls[0][0]).toBe('closeDialog')
  })

  it('有 sheet 且未修改时直接关闭', () => {
    const actions = makeActions()
    const handle = createBackHandler({ state: makeState({ sheet: 'settings' }), actions })
    expect(handle()).toBe('sheet')
    expect(actions.calls[0][0]).toBe('closeSheet')
  })

  it('sheet 有未保存修改时先请求放弃确认', () => {
    const actions = makeActions()
    const handle = createBackHandler({ state: makeState({ sheet: 'source-editor', sheetDirty: true }), actions })
    expect(handle()).toBe('discard')
    expect(actions.calls[0]).toEqual(['requestDiscard', 'source-editor'])
  })

  it('非根页面先回根页', () => {
    const actions = makeActions()
    const handle = createBackHandler({ state: makeState({ page: 'sources' }), actions })
    expect(handle()).toBe('root')
    expect(actions.calls[0][0]).toBe('goRoot')
  })

  it('根页面请求退出确认（自有 Vue 对话框，不用原生）', () => {
    const actions = makeActions()
    const handle = createBackHandler({ state: makeState({ page: 'mcp' }), actions })
    expect(handle()).toBe('exit')
    expect(actions.calls[0][0]).toBe('requestExit')
  })
})
