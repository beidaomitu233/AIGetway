// 开发接入契约（FE-049—FE-053，附录 4.6.1 / 4.6.5）。
// StreamEvent SSE：每个 data 为一个对象（START/DELTA/USAGE/DONE），错误为
// {error: UnifiedError}；不使用 /v1 的 choices 解析器，无 [DONE] 字面串。
// 测试正文只保存在当前页内存；Token 位置固定占位符 lai_your_token。

import { request } from './http'
import { getRuntimeConfig } from '../app/runtimeConfig'
import type { RuntimeMode } from './bootstrap'
import type { UnifiedErrorPayload } from './errors'

export type AuthenticationType = 'NONE' | 'HOST_CONTEXT' | 'BEARER_TOKEN'
export type SampleType = 'DEPENDENCY' | 'CONFIG' | 'SYNC' | 'ASYNC' | 'STREAM' | 'HTTP'
export type BuildTool = 'MAVEN' | 'GRADLE'
export type AccessMode = 'LOCAL_RUNTIME' | 'EMBEDDED' | 'STANDALONE_CLIENT'

export interface DeveloperAliasSummary {
  alias_id: string
  alias: string
  display_name: string
  support_stream: boolean
  support_system_message: boolean
  context_window: number | null
  max_output_tokens: number | null
}

export interface DeveloperAccessContext {
  runtime_mode: RuntimeMode
  api_base_url: string | null
  authentication_type: AuthenticationType
  sdk_version: string
  server_version: string
  current_snapshot_no: number | null
  selected_alias_id: string | null
  available_models: DeveloperAliasSummary[]
}

export interface CodeSampleQuery {
  alias_id: string
  mode: AccessMode
  sample_type: SampleType
  build_tool?: BuildTool
}

export interface CodeSampleResult {
  language: string
  filename: string | null
  content: string
  alias_id: string
  mode: AccessMode
  sample_type: SampleType
}

export interface ApiTestCommand {
  model: string
  system_message?: string | undefined
  user_message: string
  stream: boolean
  temperature?: string | undefined
  top_p?: string | undefined
  max_tokens?: number | undefined
}

export interface ApiTestUsage {
  prompt_tokens: number
  completion_tokens: number
  total_tokens: number
}

export interface ApiTestCost {
  amount: string
  currency: string
  estimated: boolean
}

export interface ApiTestResponse {
  id: string
  model: string
  choices: { index: number; message: { role: string; content: string }; finish_reason: string }[]
  usage: ApiTestUsage
  light_ai: {
    trace_id: string
    provider: string
    provider_model: string
    cost: ApiTestCost | null
    snapshot_no: number
  }
}

export interface ApiTestResult {
  response: ApiTestResponse
  trace_id: string
  total_ms: number
}

export type StreamEventType = 'START' | 'DELTA' | 'USAGE' | 'DONE'

export interface TestStreamEvent {
  event: StreamEventType
  trace_id: string
  sequence: number
  model: string
  provider: string
  provider_model: string
  delta?: string
  usage?: ApiTestUsage
  cost?: ApiTestCost | null
  finish_reason?: string
  total_ms?: number
}

export function fetchDeveloperContext(aliasId?: string, signal?: AbortSignal): Promise<DeveloperAccessContext> {
  return request<DeveloperAccessContext>({
    path: '/developer-access/context',
    query: aliasId ? { alias_id: aliasId } : undefined,
    signal,
  })
}

export function fetchCodeSample(query: CodeSampleQuery, signal?: AbortSignal): Promise<CodeSampleResult> {
  return request<CodeSampleResult>({ path: '/developer-access/code-sample', query: query as never, signal })
}

export function testChat(command: ApiTestCommand, signal?: AbortSignal): Promise<ApiTestResult> {
  return request<ApiTestResult>({ path: '/developer-access/test/chat', method: 'POST', body: command, signal })
}

export interface StreamCallbacks {
  onEvent: (event: TestStreamEvent) => void
  /** 流内错误（UnifiedErrorEnvelope）：保留已收文本，不视为成功。 */
  onError: (error: UnifiedErrorPayload) => void
}

/**
 * 发起流式测试并解析 StreamEvent SSE（FE-052）：
 * - POST fetch + ReadableStream；TextDecoder stream 模式处理中文跨块字节；
 * - 按 SSE 规范以空行分帧，帧内取 data: 行；跨块缓冲；
 * - data 为 {error} 时回调 onError 并结束；正常结束以 DONE 或连接关闭为准；
 * - 取消经外部 AbortSignal 传播，取消后不再回调。
 */
export async function openTestStream(
  command: ApiTestCommand,
  callbacks: StreamCallbacks,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(`${getRuntimeConfig().adminApiBase}/developer-access/test/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ ...command, stream: true }),
    signal,
    credentials: 'same-origin',
    cache: 'no-store',
  })

  if (!response.ok || !response.body) {
    let payload: { error?: UnifiedErrorPayload } | null = null
    try {
      payload = (await response.json()) as { error?: UnifiedErrorPayload } | null
    } catch {
      // 无 JSON 体时按无明细错误处理
    }
    callbacks.onError(
      payload?.error ?? {
        code: `HTTP_${response.status}`,
        type: 'protocol',
        message: '流式连接建立失败',
        retryable: false,
      },
    )
    return
  }

  const decoder = new TextDecoder('utf-8')
  const reader = response.body.getReader()
  let buffer = ''

  const dispatchFrame = (frame: string): void => {
    const dataLines = frame
      .split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
    if (dataLines.length === 0) return
    let payload: unknown
    try {
      payload = JSON.parse(dataLines.join('\n'))
    } catch {
      return
    }
    if (payload !== null && typeof payload === 'object' && 'error' in (payload as Record<string, unknown>)) {
      callbacks.onError((payload as { error: UnifiedErrorPayload }).error)
      return
    }
    const event = payload as TestStreamEvent
    if (typeof event?.event === 'string' && typeof event?.sequence === 'number') {
      callbacks.onEvent(event)
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (signal.aborted) return
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let separator = buffer.indexOf('\n\n')
      while (separator !== -1) {
        const frame = buffer.slice(0, separator)
        buffer = buffer.slice(separator + 2)
        dispatchFrame(frame)
        if (signal.aborted) return
        separator = buffer.indexOf('\n\n')
      }
    }
    // 流关闭后处理残余帧（无结尾空行的最后一个事件）
    buffer += decoder.decode()
    if (buffer.trim() !== '' && !signal.aborted) {
      dispatchFrame(buffer)
    }
  } catch (e) {
    if (signal.aborted) return
    callbacks.onError({
      code: 'NETWORK_ERROR',
      type: 'network',
      message: '流式连接中断',
      retryable: false,
    })
    throw e
  }
}
