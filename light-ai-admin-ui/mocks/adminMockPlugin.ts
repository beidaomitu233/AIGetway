import fs from 'node:fs'
import path from 'node:path'
import type { ServerResponse } from 'node:http'
import type { Connect, Plugin } from 'vite'
import { bootstrapFixtures } from './fixtures/bootstrap'
import { handleProviderApi, handlePoolApi } from './entities'

/**
 * 契约 Mock：仅用于本地开发与深链验收（后端 BE-002 未交付）。
 * 不进入构建产物；后端完成后删除该插件。
 */

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  res.end(JSON.stringify(body))
}

function matchBootstrap(pathname: string): string | null {
  const match = pathname.match(/^\/(?:[^/]+\/)?admin\/bootstrap$/)
  return match ? match[0] : null
}

function handleAdminApi(req: Connect.IncomingMessage, res: ServerResponse): boolean {
  const url = new URL(req.url ?? '/', 'http://localhost')
  const bootstrapPath = matchBootstrap(url.pathname)
  if (bootstrapPath) {
    if (req.method !== 'GET') {
      sendJson(res, 405, {
        error: {
          code: 'UNSUPPORTED_CONTENT_TYPE',
          type: 'api',
          message: '仅支持 GET',
          retryable: false,
        },
      })
      return true
    }
    const roleParam = url.searchParams.get('role') ?? 'SYSTEM_ADMIN'
    const fixture = bootstrapFixtures[roleParam] ?? bootstrapFixtures.SYSTEM_ADMIN
    sendJson(res, 200, { data: fixture })
    return true
  }
  if (handleProviderApi(req, url, res)) return true
  if (handlePoolApi(req, url, res)) return true
  if (url.pathname.includes('/admin/')) {
    sendJson(res, 404, {
      error: {
        code: 'OBJECT_NOT_FOUND',
        type: 'api',
        message: '契约 Mock 未提供该接口',
        retryable: false,
      },
    })
    return true
  }
  return false
}

function serveIndex(root: string, res: ServerResponse): void {
  const html = fs.readFileSync(path.join(root, 'index.html'))
  res.statusCode = 200
  res.setHeader('Content-Type', 'text/html; charset=utf-8')
  res.end(html)
}

export function adminMockPlugin(): Plugin {
  return {
    name: 'light-ai-admin-mock',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (handleAdminApi(req, res)) return
        next()
      })
    },
    configurePreviewServer(server) {
      const distDir = path.join(server.config.root, 'dist')
      server.middlewares.use((req, res, next) => {
        const url = new URL(req.url ?? '/', 'http://localhost')
        const pathname = url.pathname
        if (handleAdminApi(req, res)) return
        // 嵌入根 /light-ai 下的静态资源改写到 dist 实际路径。
        if (pathname.startsWith('/light-ai/assets/')) {
          req.url = req.url?.replace('/light-ai/assets/', '/assets/')
          next()
          return
        }
        const isDeepUiPath =
          pathname === '/ui' ||
          pathname.startsWith('/ui/') ||
          pathname.startsWith('/light-ai/ui/')
        if (isDeepUiPath) {
          serveIndex(distDir, res)
          return
        }
        next()
      })
    },
  }
}
