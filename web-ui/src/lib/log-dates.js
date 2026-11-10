// HTTP log dates follow the device's local calendar, not UTC or 24-hour spans.
export function localDateKey(date = new Date()) {
  return [String(date.getFullYear()).padStart(4, '0'), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-')
}

export function shiftLocalDate(value, days) {
  const [year, month, day] = value.split('-').map(Number)
  // Local noon avoids midnight clock changes; setDate crosses DST/calendar edges.
  const date = new Date(0)
  date.setHours(12, 0, 0, 0)
  date.setFullYear(year, month - 1, day)
  date.setDate(date.getDate() + days)
  return localDateKey(date)
}
