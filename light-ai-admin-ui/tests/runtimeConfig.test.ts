import { describe, expect, it } from 'vitest'
import { deriveUiRoot, initRuntimeConfig, routerBase, getRuntimeConfig } from '@/app/runtimeConfig'

describe('deriveUiRoot', () => {
  it('根部署（空根）返回空字符串', () => {
    expect(deriveUiRoot('/')).toBe('')
    expect(deriveUiRoot('/ui/overview')).toBe('')
  })

  it('嵌入部署返回 /ui 之前的挂载根', () => {
    expect(deriveUiRoot('/light-ai/ui/overview')).toBe('/light-ai')
    expect(deriveUiRoot('/light-ai/ui/')).toBe('/light-ai')
  })

  it('支持多级自定义挂载根', () => {
    expect(deriveUiRoot('/ops/light-ai/ui/traces/trace-1')).toBe('/ops/light-ai')
  })

  it('注入配置优先于地址推导并去除尾斜杠', () => {
    expect(deriveUiRoot('/light-ai/ui/overview', '/custom-root/')).toBe('/custom-root')
  })

  it('非应用路径返回空根', () => {
    expect(deriveUiRoot('/other/page')).toBe('')
  })
})

describe('initRuntimeConfig', () => {
  it('默认从地址推导 UI 根并生成 API 基路径', () => {
    window.history.replaceState(null, '', '/light-ai/ui/overview')
    const config = initRuntimeConfig()
    expect(config.uiRoot).toBe('/light-ai')
    expect(config.adminApiBase).toBe('/light-ai/admin')
    expect(routerBase()).toBe('/light-ai/')
    window.history.replaceState(null, '', '/')
  })

  it('服务端注入配置优先', () => {
    window.__LIGHT_AI_CONFIG__ = { ui_base_path: '/light-ai', admin_api_base_path: '/light-ai/admin' }
    const config = initRuntimeConfig()
    expect(config.adminApiBase).toBe('/light-ai/admin')
    expect(getRuntimeConfig().uiRoot).toBe('/light-ai')
    delete window.__LIGHT_AI_CONFIG__
    window.history.replaceState(null, '', '/')
  })

  it('空根部署使用 /admin', () => {
    window.history.replaceState(null, '', '/ui/overview')
    initRuntimeConfig()
    expect(getRuntimeConfig().adminApiBase).toBe('/admin')
    expect(routerBase()).toBe('/')
    window.history.replaceState(null, '', '/')
  })
})
