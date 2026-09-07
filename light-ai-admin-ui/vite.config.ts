import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { adminMockPlugin } from './mocks/adminMockPlugin'

// base 保持相对路径：静态包在任意挂载根（空根或 /light-ai）下，
// 由 index.html 内联脚本计算运行根并写入 <base>，资源按该根解析。
export default defineConfig(({ mode }) => {
  const useMock = mode === 'mock' || process.env.VITE_USE_MOCK === 'true'
  const backendTarget = process.env.VITE_BACKEND_TARGET || 'http://127.0.0.1:8080'

  return {
    base: './',
    plugins: [vue(), ...(useMock ? [adminMockPlugin()] : [])],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/admin': {
          target: backendTarget,
          changeOrigin: true,
        },
        '/v1': {
          target: backendTarget,
          changeOrigin: true,
        },
        '/internal': {
          target: backendTarget,
          changeOrigin: true,
        },
      },
    },
    preview: {
      port: 4173,
      proxy: {
        '/admin': {
          target: backendTarget,
          changeOrigin: true,
        },
        '/v1': {
          target: backendTarget,
          changeOrigin: true,
        },
        '/internal': {
          target: backendTarget,
          changeOrigin: true,
        },
      },
    },
  }
})
