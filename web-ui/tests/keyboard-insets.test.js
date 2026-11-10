import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '../src/App.vue'

let wrapper
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
  document.documentElement.removeAttribute('data-keyboard')
})

it('uses raw Android IME only as a visibility signal, restores after close, never adds its pixels', async () => {
  vi.stubGlobal('innerHeight', 156)
  vi.stubGlobal('visualViewport', Object.assign(new EventTarget(), { height: 156 }))
  wrapper = mount(App)
  await flushPromises()
  const insets = rawIme => window.dispatchEvent(new CustomEvent('studio:insets', {
    detail: { top: 0, bottom: 0, ime: 0, rawTop: 63, rawBottom: 63, rawIme, density: 2.625 }
  }))
  insets(780)
  await flushPromises()
  expect(document.documentElement.dataset.keyboard).toBe('1')
  expect(document.documentElement.style.getPropertyValue('--native-inset-bottom')).toBe('0px')
  window.visualViewport.dispatchEvent(new Event('resize'))
  expect(document.documentElement.dataset.keyboard).toBe('1')
  insets(0)
  await flushPromises()
  expect(document.documentElement.dataset.keyboard).toBe('0')
})
