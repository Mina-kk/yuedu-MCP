import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { parse } from '@vue/compiler-sfc'
import { parse as parseTemplate } from '@vue/compiler-dom'

const vueFiles = dir => readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? vueFiles(join(dir, e.name)) : e.name.endsWith('.vue') ? [join(dir, e.name)] : [])
const all = vueFiles('src')
const elements = root => [root, ...(root.children || []).flatMap(elements)]
describe('production business control skin contract', () => {
  it('every Vue business button has an explicit skin; no native business pickers', () => {
    const unstyled = [], pickers = []
    for (const file of all) {
      const { descriptor } = parse(readFileSync(file, 'utf8'))
      if (!descriptor.template) continue
      for (const n of elements(parseTemplate(descriptor.template.content))) {
        if (n.type !== 1) continue
        const hasClass = n.props.some(p => p.type === 6 && p.name === 'class' && p.value?.content?.trim())
        if (n.tag === 'button' && !hasClass) unstyled.push(`${file}:${n.loc.start.line}`)
        if (n.tag === 'select' || (n.tag === 'input' && n.props.some(p => p.name === 'type' && ['date', 'time', 'datetime-local', 'color'].includes(p.value?.content)))) pickers.push(`${file}:${n.loc.start.line}`)
      }
    }
    expect(unstyled).toEqual([])
    expect(pickers).toEqual([])
  })
})
