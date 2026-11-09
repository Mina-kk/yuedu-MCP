import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Android WebViewAssetLoader 以 https://appassets.androidplatform.net/assets/web/index.html
// 加载构建产物，所以必须用相对 base；所有资源本地打包，无 CDN。
export default defineConfig({
  base: './',
  plugins: [vue()],
  build: {
    target: 'es2020',
    assetsDir: 'assets',
    sourcemap: false,
    cssCodeSplit: false,
    // wasm 以 ?url 方式引入，不需要 Vite 处理
    assetsInlineLimit: 0
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.js'],
    restoreMocks: true
  }
})
