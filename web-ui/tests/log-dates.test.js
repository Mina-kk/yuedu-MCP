import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { localDateKey, shiftLocalDate } from '../src/lib/log-dates.js'

const modulePath = resolve('src/lib/log-dates.js')
// A child process gives each timezone a real independent local Date calendar.
// Vitest's jsdom worker does not reliably adopt a mutated process.env.TZ.
function inTimezone(tz, expression) {
  return JSON.parse(execFileSync(process.execPath, ['--input-type=module', '-e',
    `import { localDateKey, shiftLocalDate } from ${JSON.stringify(modulePath)}; console.log(JSON.stringify(${expression}))`
  ], { env: { ...process.env, TZ: tz }, encoding: 'utf8' }))
}

describe('HTTP 日志本地日历', () => {
  it.each([
    ['Asia/Shanghai', '2026-12-31T16:15:00Z', '2027-01-01'],
    ['America/Los_Angeles', '2027-01-01T00:15:00Z', '2026-12-31']
  ])('%s 日期遵循本地年月日而非 UTC', (tz, instant, expected) => {
    expect(inTimezone(tz, `localDateKey(new Date(${JSON.stringify(instant)}))`)).toBe(expected)
  })
  it.each([
    ['2024-03-01', -1, '2024-02-29'],
    ['2026-03-01', -1, '2026-02-28'],
    ['2024-02-29', 1, '2024-03-01'],
    ['2026-01-01', -1, '2025-12-31'],
    ['2026-12-31', 1, '2027-01-01']
  ])('%s 移动 %i 天 = %s', (value, days, expected) => {
    expect(shiftLocalDate(value, days)).toBe(expected)
  })
  it.each([
    ['America/New_York', '2026-03-08', 1, '2026-03-09'],
    ['America/New_York', '2026-11-01', 1, '2026-11-02'],
    ['America/New_York', '2026-03-09', -1, '2026-03-08'],
    ['America/New_York', '2026-11-02', -1, '2026-11-01'],
    ['Australia/Lord_Howe', '2026-04-05', 1, '2026-04-06']
  ])('%s 跨 DST %s 移动 %i 天 = %s', (tz, value, days, expected) => {
    expect(inTimezone(tz, `shiftLocalDate(${JSON.stringify(value)}, ${days})`)).toBe(expected)
  })
  it('年份补齐并保持本地日期组件', () => {
    const date = new Date(2026, 0, 2, 0, 15)
    expect(localDateKey(date)).toBe('2026-01-02')
  })
})
