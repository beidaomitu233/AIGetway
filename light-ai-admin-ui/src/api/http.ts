import { ApiError, TimeoutError, type UnifiedErrorPayload } from './errors'
import { getRuntimeConfig } from '@/app/runtimeConfig'

export type QueryValue = string | number | boolean | null | undefined | ReadonlyArray<string | number>

export interface RequestOptions {
  path: string
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  query?: Record<string, QueryValue>
  body?: unknown
  signal?: AbortSignal | undefined
  /** 默认 30000；超时产生 TimeoutError，不自动重发。 */
  timeoutMs?: number
}

let csrfToken: string | undefined

/** bootstrap 返回会话 CSRF Token 后注册；写请求自动携带。 */
export function registerCsrfToken(token: string | undefined): void {
  csrfToken = token
}

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function serializeQuery(query: Record<string, QueryValue> | undefined): string {
  if (!query) return ''
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue
    if (Array.isArray(value)) {
      for (const item of value) params.append(key, String(item))
    } else {
      params.append(key, String(value))
    }
  }
  const encoded = params.toString()
  return encoded === '' ? '' : `?${encoded}`
}

/** 合并外部 signal 与超时 signal；任一触发即中止。 */
function combineSignals(external: AbortSignal | undefined, timeoutMs: number): {
  signal: AbortSignal
  cancelTimeout: () => void
} {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(new TimeoutError()), timeoutMs)
  const onExternalAbort = () => {
    clearTimeout(timer)
    controller.abort(external?.reason)
  }
  if (external) {
    if (external.aborted) {
      clearTimeout(timer)
      controller.abort(external.reason)
    } else {
      external.addEventListener('abort', onExternalAbort, { once: true })
    }
  }
  return {
    signal: controller.signal,
    cancelTimeout: () => {
      clearTimeout(timer)
      external?.removeEventListener('abort', onExternalAbort)
    },
  }
}

/**
 * 管理 API 统一请求入口：data 解包、UnifiedError 转换、取消与关联 ID。
 * 只允许读取类请求由调用方手动重试；写入失败不自动重发。
 */
export async function request<T>(options: RequestOptions): Promise<T> {
  const method = options.method ?? 'GET'
  const requestId = newRequestId()
  const { signal, cancelTimeout } = combineSignals(options.signal, options.timeoutMs ?? 30000)
  const url = `${getRuntimeConfig().adminApiBase}${options.path}${serializeQuery(options.query)}`
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Request-Id': requestId,
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (method !== 'GET' && csrfToken) {
    headers['X-CSRF-Token'] = csrfToken
  }

  // 中止原因经 controller.abort 透传：外部取消得到 AbortError，超时得到 TimeoutError。
  const init: RequestInit = {
    method,
    headers,
    signal,
    credentials: 'same-origin',
    cache: 'no-store',
  }
  if (options.body !== undefined) {
    init.body = JSON.stringify(options.body)
  }
  const response = await fetch(url, init)
  cancelTimeout()

  let payload: unknown = undefined
  const text = await response.text()
  if (text !== '') {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = undefined
    }
  }

  const envelope = payload as { data?: T; error?: UnifiedErrorPayload } | undefined
  if (response.ok && envelope && envelope.data !== undefined) {
    return envelope.data
  }

  const errorPayload: UnifiedErrorPayload =
    envelope && envelope.error
      ? envelope.error
      : {
          code: `HTTP_${response.status}`,
          type: 'protocol',
          message: '服务响应格式异常',
        }
  throw new ApiError(response.status, errorPayload, requestId)
}
