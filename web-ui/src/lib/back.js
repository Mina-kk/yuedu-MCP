// 统一的返回键 / Escape 决策。顺序改变会改变用户预期，测试覆盖每个分支。

export function createBackHandler({ state, actions }) {
  return function handleBack() {
    if (state.dialog) {
      actions.closeDialog()
      return 'dialog'
    }
    if (state.sheet) {
      if (state.sheetDirty) {
        actions.requestDiscard(state.sheet)
        return 'discard'
      }
      actions.closeSheet()
      return 'sheet'
    }
    if (state.page && state.page !== 'mcp') {
      actions.goRoot()
      return 'root'
    }
    actions.requestExit()
    return 'exit'
  }
}
