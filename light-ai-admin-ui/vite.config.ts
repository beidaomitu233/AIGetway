import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { adminMockPlugin } from './mocks/adminMockPlugin'

// base 保持相对路径：静态包在任意挂载根（空根或 /light-ai）下，
// 由 index.html 内联脚本计算运行根并写入 <base>，资源按该根解析。
export default defineConfig({
  base: './',
  plugins: [vue(), adminMockPlugin()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
  },
  preview: {
    port: 4173,
  },
})
