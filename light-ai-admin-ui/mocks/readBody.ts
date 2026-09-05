// Mock 模块共享的请求体读取：同一请求只消费一次原始流，后续模块复用缓存，
// 避免多个 mock 处理器串行读取导致流耗尽后挂起。
import type { Connect } from 'vite'

type MockBody = Record<string, unknown>
type CachedReq = Connect.IncomingMessage & { _mockBody?: Promise<MockBody> }

export async function readMockBody(req: Connect.IncomingMessage): Promise<MockBody> {
  const cached = (req as CachedReq)._mockBody
  if (cached) return cached
  const promise = new Promise<MockBody>((resolve) => {
    let raw = ''
    req.on('data', (chunk: Buffer) => {
      raw += String(chunk)
    })
    req.on('end', () => {
      try {
        resolve(raw === '' ? {} : (JSON.parse(raw) as MockBody))
      } catch {
        resolve({})
      }
    })
    req.on('error', () => resolve({}))
  })
  ;(req as CachedReq)._mockBody = promise
  return promise
}
