// 开发接入契约 Mock（FE-049—FE-053）：仅用于本地开发（后端 BE-046/047 未交付）。
// context/code-sample/chat 同步结果与 StreamEvent SSE 流；后端完成后整文件删除。
import type { ServerResponse } from 'node:http'
import { readMockBody } from './readBody'

const availableModels = [
  {
    alias_id: 'alias-1', alias: 'chat-default', display_name: '默认对话',
    support_stream: true, support_system_message: true, context_window: 128000, max_output_tokens: 16384,
  },
  {
    alias_id: 'alias-2', alias: 'summary', display_name: '摘要生成',
    support_stream: false, support_system_message: false, context_window: 64000, max_output_tokens: 8192,
  },
]

function sampleContent(alias: string, mode: string, sampleType: string, buildTool: string): { language: string; filename: string | null; content: string } {
  if (sampleType === 'DEPENDENCY') {
    if (buildTool === 'GRADLE') {
      return {
        language: 'groovy',
        filename: 'build.gradle',
        content: `dependencies {\n    implementation 'com.lightai:light-ai-client:1.0.0'\n}`,
      }
    }
    return {
      language: 'xml',
      filename: 'pom.xml',
      content: `<dependency>\n    <groupId>com.lightai</groupId>\n    <artifactId>light-ai-client</artifactId>\n    <version>1.0.0</version>\n</dependency>`,
    }
  }
  if (sampleType === 'CONFIG') {
    return {
      language: 'yaml',
      filename: 'application.yml',
      content: `light-ai:\n  mode: ${mode === 'EMBEDDED' ? 'embedded' : 'standalone-client'}\n  base-url: https://your-deployment.example.com\n  access-token: lai_your_token`,
    }
  }
  if (sampleType === 'HTTP') {
    return {
      language: 'bash',
      filename: null,
      content: `curl -X POST https://your-deployment.example.com/v1/chat/completions \\\n  -H "Authorization: Bearer lai_your_token" \\\n  -H "Content-Type: application/json" \\\n  -d '{"model":"${alias}","messages":[{"role":"user","content":"你好"}]}'`,
    }
  }
  const callKind = sampleType === 'STREAM' ? 'streamChat' : sampleType === 'ASYNC' ? 'chatAsync' : 'chat'
  return {
    language: 'java',
    filename: 'Example.java',
    content: `LightAiClient client = LightAiClient.builder()\n    .baseUrl("https://your-deployment.example.com")\n    .accessToken("lai_your_token")\n    .build();\n\nUnifiedChatResponse response = client.${callKind}(request -> request\n    .model("${alias}")\n    .addUserMessage("你好"));`,
  }
}

async function handle(req: { method?: string | undefined; url?: string | undefined; on?: ((event: string, cb: (chunk?: Buffer) => void) => void) | undefined }, res: ServerResponse): Promise<boolean> {
  const url = new URL(req.url ?? '/', 'http://localhost')
  const method = req.method ?? 'GET'
  const path = url.pathname.replace(/^\/(?:[^/]+\/)?admin/, '')

  if (method === 'GET' && path === '/developer-access/context') {
    const requested = url.searchParams.get('alias_id')
    const alias = availableModels.find((item) => item.alias_id === requested) ?? availableModels[0]!
    res.statusCode = 200
    res.setHeader('Content-Type', 'application/json; charset=utf-8')
    res.end(JSON.stringify({
      data: {
        runtime_mode: 'STANDALONE_SERVER',
        api_base_url: 'https://your-deployment.example.com',
        authentication_type: 'BEARER_TOKEN',
        sdk_version: '1.0.0',
        server_version: '1.0.0',
        current_snapshot_no: 12,
        selected_alias_id: alias.alias_id,
        available_models: availableModels.map((item) => ({ ...item })),
      },
    }))
    return true
  }

  if (method === 'GET' && path === '/developer-access/code-sample') {
    const aliasId = url.searchParams.get('alias_id') ?? 'alias-1'
    const alias = availableModels.find((item) => item.alias_id === aliasId)?.alias ?? 'chat-default'
    const mode = url.searchParams.get('mode') ?? 'STANDALONE_CLIENT'
    const sampleType = url.searchParams.get('sample_type') ?? 'SYNC'
    const buildTool = url.searchParams.get('build_tool') ?? 'MAVEN'
    const content = sampleContent(alias, mode, sampleType, buildTool)
    res.statusCode = 200
    res.setHeader('Content-Type', 'application/json; charset=utf-8')
    res.end(JSON.stringify({ data: { ...content, alias_id: aliasId, mode, sample_type: sampleType } }))
    return true
  }

  if (method === 'POST' && path === '/developer-access/test/chat') {
    const body = await readMockBody(req as import('vite').Connect.IncomingMessage)
    const userMessage = String(body.user_message ?? '')
    if (userMessage.trim() === '') {
      res.statusCode = 400
      res.setHeader('Content-Type', 'application/json; charset=utf-8')
      res.end(JSON.stringify({
        error: {
          code: 'FIELD_VALIDATION_FAILED',
          type: 'validation',
          message: '字段校验失败',
          retryable: false,
          errors: [{ field: 'user_message', code: 'REQUIRED', message: 'user_message 必填' }],
        },
      }))
      return true
    }
    if (url.searchParams.get('__fail') === '1') {
      res.statusCode = 429
      res.setHeader('Content-Type', 'application/json; charset=utf-8')
      res.end(JSON.stringify({
        error: { code: 'CAPACITY_LIMITED', type: 'capacity', message: '容量不足', retryable: true, retry_after_ms: 2000 },
      }))
      return true
    }
    res.statusCode = 200
    res.setHeader('Content-Type', 'application/json; charset=utf-8')
    res.end(JSON.stringify({
      data: {
        response: {
          id: 'tr-sync-1', model: String(body.model ?? 'chat-default'),
          choices: [{ index: 0, message: { role: 'assistant', content: `你好：${userMessage}` }, finish_reason: 'stop' }],
          usage: { prompt_tokens: 12, completion_tokens: 20, total_tokens: 32 },
          light_ai: { trace_id: 'tr-sync-1', provider: 'OpenAI', provider_model: 'gpt-4o', cost: { amount: '0.00001280', currency: 'USD', estimated: false }, snapshot_no: 12 },
        },
        trace_id: 'tr-sync-1',
        total_ms: 1230,
      },
    }))
    return true
  }

  if (method === 'POST' && path === '/developer-access/test/chat/stream') {
    const body = await readMockBody(req as import('vite').Connect.IncomingMessage)
    const userMessage = String(body.user_message ?? '')
    if (userMessage.trim() === '') {
      res.statusCode = 400
      res.setHeader('Content-Type', 'application/json; charset=utf-8')
      res.end(JSON.stringify({
        error: {
          code: 'FIELD_VALIDATION_FAILED',
          type: 'validation',
          message: '字段校验失败',
          retryable: false,
          errors: [{ field: 'user_message', code: 'REQUIRED', message: 'user_message 必填' }],
        },
      }))
      return true
    }
    res.statusCode = 200
    res.setHeader('Content-Type', 'text/event-stream; charset=utf-8')
    res.setHeader('Cache-Control', 'no-store')
    let sequence = 0
    const traceId = 'tr-stream-1'
    const base = { model: String(body.model ?? 'chat-default'), provider: 'OpenAI', provider_model: 'gpt-4o' }
    // 每帧整体按 5 字节切片写入：帧是合法 SSE，但分块点会落在
    // UTF-8 中文字节中间，验证客户端 TextDecoder(stream) 的跨块解码。
    const writeFrameInChunks = (payload: Record<string, unknown>): void => {
      const frame = `data: ${JSON.stringify(payload)}

`
      const encoded = Buffer.from(frame, 'utf-8')
      for (let offset = 0; offset < encoded.length; offset += 5) {
        res.write(encoded.subarray(offset, Math.min(offset + 5, encoded.length)))
      }
    }
    writeFrameInChunks({ event: 'START', trace_id: traceId, sequence: sequence++, ...base })
    const deltas = ['你好：', '这是流式', '回复，包含中文字符。']
    for (const delta of deltas) {
      writeFrameInChunks({ event: 'DELTA', trace_id: traceId, sequence: sequence++, ...base, delta })
    }
    writeFrameInChunks({
      event: 'USAGE', trace_id: traceId, sequence: sequence++, ...base,
      usage: { prompt_tokens: 12, completion_tokens: 20, total_tokens: 32 },
      cost: { amount: '0.00001280', currency: 'USD', estimated: false },
    })
    if (url.searchParams.get('__interrupt') === '1') {
      res.end()
      return true
    }
    writeFrameInChunks({ event: 'DONE', trace_id: traceId, sequence: sequence++, ...base, finish_reason: 'stop', total_ms: 1450 })
    res.end()
    return true
  }

  return false
}


export function handleDeveloperApi(
  req: { method?: string | undefined; url?: string | undefined; on?: ((event: string, cb: (chunk?: Buffer) => void) => void) | undefined },
  res: ServerResponse,
): Promise<boolean> {
  return handle(req, res)
}
