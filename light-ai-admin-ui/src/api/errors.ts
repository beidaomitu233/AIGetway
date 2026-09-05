export interface FieldErrorIssue {
  field: string
  code: string
  message: string
}

/** 管理失败响应的 error 对象（BACKEND_PLAN 4.7.3）。 */
export interface UnifiedErrorPayload {
  code: string
  type: string
  message: string
  retryable?: boolean
  param?: string
  errors?: FieldErrorIssue[]
  trace_id?: string
  retry_after_ms?: number
  request_id?: string
  current_version?: number
  current_state_version?: number
}

const VERSION_CONFLICT_CODE = 'CONFIG_VERSION_CONFLICT'
const ACCESS_DENIED_CODE = 'ACCESS_DENIED'

export class ApiError extends Error {
  readonly status: number
  readonly payload: UnifiedErrorPayload
  /** 字段路径 → 首条错误信息，供表单定位。 */
  readonly fieldMessages: Map<string, string>
  /** 服务端 request_id；缺失时保留客户端关联 ID。 */
  readonly requestId: string

  constructor(status: number, payload: UnifiedErrorPayload, requestId: string) {
    super(payload.message)
    this.name = 'ApiError'
    this.status = status
    this.payload = payload
    this.requestId = payload.request_id ?? requestId
    this.fieldMessages = new Map()
    for (const issue of payload.errors ?? []) {
      if (!this.fieldMessages.has(issue.field)) {
        this.fieldMessages.set(issue.field, issue.message)
      }
    }
  }

  get code(): string {
    return this.payload.code
  }

  get retryable(): boolean {
    return this.payload.retryable === true
  }

  get isVersionConflict(): boolean {
    return this.payload.code === VERSION_CONFLICT_CODE
  }

  get isAccessDenied(): boolean {
    return this.payload.code === ACCESS_DENIED_CODE
  }

  /** 版本冲突时服务端回传的最新可编辑对象版本。 */
  get serverVersion(): number | undefined {
    return this.payload.current_version ?? this.payload.current_state_version
  }
}

/** 调用方主动取消（AbortController 或页面离开）；不应展示为错误。 */
export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException
    ? error.name === 'AbortError'
    : error instanceof Error && error.name === 'AbortError'
}

/** 请求超时；与用户取消区分，可提示重试。 */
export class TimeoutError extends Error {
  constructor() {
    super('请求超时')
    this.name = 'TimeoutError'
  }
}

export function toErrorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof TimeoutError) return error.message
  if (error instanceof Error) return '网络请求失败'
  return '未知错误'
}
