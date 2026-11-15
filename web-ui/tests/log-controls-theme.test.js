import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import { baseParse } from '@vue/compiler-dom'
import { expect, it } from 'vitest'

const files = ['pages/LogsPage.vue', 'components/LogActions.vue', 'components/LogExportPanel.vue', 'components/InteractiveCaptureWorkbench.vue']
const source = file => readFileSync(`src/${file}`, 'utf8')
function elements(node, result = []) {
  if (node.type === 1) result.push(node)
  for (const child of node.children || []) elements(child, result)
  return result
}

it('log and capture controls all use MCP theme classes and explicit button types', () => {
  const defects = []
  for (const file of files) {
    const { descriptor } = parse(source(file))
    for (const node of elements(baseParse(descriptor.template.content))) {
      const attr = name => node.props.find(p => p.type === 6 && p.name === name)?.value?.content
      const classes = (attr('class') || '').split(/\s+/)
      if (node.tag === 'button') {
        if (!['button', 'submit'].includes(attr('type'))) defects.push(`${file}:${node.loc.start.line}: missing explicit type`)
        if (!classes.some(c => ['btn', 'seg', 'log-row'].includes(c))) defects.push(`${file}:${node.loc.start.line}: unthemed button`)
      }
      if (['input', 'textarea'].includes(node.tag) && attr('type') !== 'checkbox' && !classes.includes('input')) defects.push(`${file}: unthemed field`)
      if (node.tag === 'textarea' && !classes.includes('textarea')) defects.push(`${file}: missing textarea sizing`)
      if (node.tag === 'select' || (node.tag === 'input' && ['date', 'color'].includes(attr('type')))) defects.push(`${file}: native business picker`)
    }
  }
  expect(defects).toEqual([])
})

it('diagnostics disclosure has a themed chevron and suppresses native fieldset/marker paint', () => {
  const text = source('components/LogActions.vue')
  expect(text).toContain('name="chevron-right"')
  expect(text).toMatch(/summary::\-webkit-details-marker\s*\{\s*display:\s*none/)
  expect(text).toMatch(/list-style:\s*none/)
  expect(text).toMatch(/fieldset\s*\{[^}]*border:\s*0/s)
})

it('operation category has its own row and complete dates never split', () => {
  const text = source('pages/LogsPage.vue')
  expect(text).toContain('class="filter-row operation-filters"')
  expect(text).toMatch(/\.operation-filters\s*\{[^}]*display:\s*grid/s)
  expect(text).toMatch(/\.log-date-value time\s*\{[^}]*white-space:\s*nowrap/s)
  expect(text).not.toContain('日期操作目标：{{ date }}')
})
