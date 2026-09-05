import { afterEach, describe, expect, it, vi } from 'vitest'
import { openTestStream, type TestStreamEvent } from '@/api/developerAccess'
import { initRuntimeConfig } from '@/app/runtimeConfig'
import type { UnifiedErrorPayload } from '@/api/errors'

function sseFrames(): string {
  const events = [
    { event: 'START', trace_id: 'tr-1', sequence: 0, model: 'chat-default', provider: 'OpenAI', provider_model: 'gpt-4o' },
    { event: 'DELTA', trace_id: 'tr-1', sequence: 1, model: '', provider: 'OpenAI', provider_model: 'gpt-4o', delta: '你好，' },
    { event: 'DELTA', trace_id: 'tr-1', sequence: 2, model: '', provider: 'OpenAI', provider_model: 'gpt-4o', delta: '这是流式回复，包含中文字符。' },
    {
      event: 'USAGE', trace_id: 'tr-1', sequence: 3, model: '', provider: 'OpenAI', provider_model: 'gpt-4o',
      usage: { prompt_tokens: 12, completion_tokens: 20, total_tokens: 32 },
      cost: { amount: '0.00001280', currency: 'USD', estimated: false },
    },
    { event: 'DONE', trace_id: 'tr-1', sequence: 4, model: '', provider: 'OpenAI', provider_model: 'gpt-4o', finish_reason: 'stop', total_ms: 1450 },
  ]
  return events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join('')
}

function streamResponse(chunks: Uint8Array[], status = 200): Response {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(chunk)
      }
      controller.close()
    },
  })
  void encoder
  return new Response(body, { status, headers: { 'Content-Type': 'text/event-stream' } })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('openTestStream（FE-052）', () => {
  it('跨块分帧与中文跨字节解析完整事件序列', async () => {
    initRuntimeConfig()
    const raw = sseFrames()
    // 整个 SSE 文本按 7 字节切片：帧边界与 UTF-8 中文字节都会被截断
    const encoded = new TextEncoder().encode(raw)
    const chunks: Uint8Array[] = []
    for (let offset = 0; offset < encoded.length; offset += 7) {
      chunks.push(encoded.subarray(offset, Math.min(offset + 7, encoded.length)))
    }
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(streamResponse(chunks))))

    const events: TestStreamEvent[] = []
    const errorBox: { value: UnifiedErrorPayload | null } = { value: null }
    await openTestStream(
      { model: 'chat-default', user_message: '你好', stream: true },
      {
        onEvent: (event) => {
          events.push(event)
        },
        onError: (payload) => {
          errorBox.value = payload
        },
      },
      new AbortController().signal,
    )
    expect(errorBox.value).toBeNull()
    expect(events.map((event) => event.event)).toEqual(['START', 'DELTA', 'DELTA', 'USAGE', 'DONE'])
    expect(events.map((event) => event.sequence)).toEqual([0, 1, 2, 3, 4])
    const fullText = events.filter((event) => event.event === 'DELTA').map((event) => event.delta).join('')
    expect(fullText).toBe('你好，这是流式回复，包含中文字符。')
    const usage = events.find((event) => event.event === 'USAGE')
    expect(usage?.usage?.total_tokens).toBe(32)
    const done = events.find((event) => event.event === 'DONE')
    expect(done?.finish_reason).toBe('stop')
  })

  it('流内 error envelope 触发 onError 且无事件回调', async () => {
    initRuntimeConfig()
    const raw = 'data: {"error":{"code":"CAPACITY_LIMITED","type":"capacity","message":"容量不足","retryable":true}}\n\n'
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(streamResponse([new TextEncoder().encode(raw)]))))
    const events: TestStreamEvent[] = []
    const errorBox: { value: UnifiedErrorPayload | null } = { value: null }
    await openTestStream(
      { model: 'chat-default', user_message: 'x', stream: true },
      { onEvent: (event) => events.push(event), onError: (payload) => (errorBox.value = payload) },
      new AbortController().signal,
    )
    expect(events).toEqual([])
    expect(errorBox.value?.code).toBe('CAPACITY_LIMITED')
  })

  it('建流失败（非 200）回传错误明细', async () => {
    initRuntimeConfig()
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ error: { code: 'MODEL_ALIAS_NOT_FOUND', type: 'api', message: 'Alias 不存在', retryable: false } }), { status: 404 }),
        ),
      ),
    )
    const errorBox: { value: UnifiedErrorPayload | null } = { value: null }
    await openTestStream(
      { model: 'no-such', user_message: 'x', stream: true },
      { onEvent: () => {}, onError: (payload) => (errorBox.value = payload) },
      new AbortController().signal,
    )
    expect(errorBox.value?.code).toBe('MODEL_ALIAS_NOT_FOUND')
  })

  it('取消后不再回调事件', async () => {
    initRuntimeConfig()
    const controller = new AbortController()
    const events: TestStreamEvent[] = []
    let released = false
    const body = new ReadableStream<Uint8Array>({
      start(control) {
        control.enqueue(new TextEncoder().encode('data: {"event":"START","trace_id":"tr-1","sequence":0,"model":"m","provider":"p","provider_model":"pm"}\n\n'))
      },
      pull(control) {
        if (released) return
        released = true
        // 取消后挂住，不再提供数据
        setTimeout(() => control.close(), 50)
        void control
      },
    })
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(body, { status: 200 }))))
    const pending = openTestStream(
      { model: 'chat-default', user_message: 'x', stream: true },
      { onEvent: (event) => events.push(event), onError: () => {} },
      controller.signal,
    )
    // 等待首块 START 消费完成后再取消
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(events.map((event) => event.event)).toEqual(['START'])
    controller.abort()
    await pending
    expect(events.map((event) => event.event)).toEqual(['START'])
  })
})
