import { afterEach } from 'vitest'

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
})

Object.defineProperty(window, 'getComputedStyle', {
  writable: true,
  value: () => ({
    width: '0px',
    height: '0px',
    getPropertyValue: () => '',
  }),
})

afterEach(() => {
  document.body.innerHTML = ''
})
