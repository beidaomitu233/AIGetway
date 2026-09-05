/// <reference types="vite/client" />

declare global {
  interface Window {
    /** 服务端注入的运行配置（可选）；ui_base_path 为应用挂载根，不带尾斜杠。 */
    __LIGHT_AI_CONFIG__?: {
      ui_base_path?: string
      admin_api_base_path?: string
      runtime_mode?: string
    }
    /** index.html 内联脚本计算的挂载根，空字符串表示根部署。 */
    __LIGHT_AI_UI_ROOT__?: string
  }
}

export interface RuntimeConfig {
  /** 应用挂载根：'' 或 '/light-ai' 等自定义根。 */
  uiRoot: string
  /** 管理 API 根：uiRoot + '/admin'。 */
  adminApiBase: string
}

/** 从注入配置或当前地址推导挂载根；页面 URL 固定以 /ui/ 为应用段。 */
export function deriveUiRoot(pathname: string, injected?: string): string {
  if (typeof injected === 'string' && injected !== '') {
    return injected.replace(/\/+$/, '')
  }
  const match = pathname.match(/^(.*\/)ui(\/|$)/)
  if (!match) return ''
  return match[1].replace(/\/+$/, '')
}

let config: RuntimeConfig = {
  uiRoot: '',
  adminApiBase: '/admin',
}

/** 应用启动时初始化一次；此后 http 层读取 adminApiBase。 */
export function initRuntimeConfig(): RuntimeConfig {
  const injected = window.__LIGHT_AI_CONFIG__
  const uiRoot = deriveUiRoot(window.location.pathname, injected?.ui_base_path) ||
    window.__LIGHT_AI_UI_ROOT__ ||
    ''
  config = {
    uiRoot,
    adminApiBase: normalizeApiBase(injected?.admin_api_base_path, uiRoot),
  }
  return config
}

export function getRuntimeConfig(): RuntimeConfig {
  return config
}

/** bootstrap 返回服务端权威基路径后更新后续请求目标。 */
export function applyServerBasePaths(uiBasePath?: string, adminApiBasePath?: string): void {
  const uiRoot = deriveUiRoot(window.location.pathname, uiBasePath)
  config = {
    uiRoot,
    adminApiBase: normalizeApiBase(adminApiBasePath, uiRoot),
  }
}

function normalizeApiBase(injected: string | undefined, uiRoot: string): string {
  if (typeof injected === 'string' && injected !== '') {
    return injected.replace(/\/+$/, '')
  }
  return (uiRoot || '') + '/admin'
}

/** vue-router history 基座：挂载根加尾斜杠，根部署为 '/'。 */
export function routerBase(): string {
  return (config.uiRoot || '') + '/'
}
