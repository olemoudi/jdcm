import { defineConfig } from 'vitest/config'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  resolve: {
    alias: {
      // A Vite virtual module that exists only when vite-plugin-pwa runs: stubbed for tests.
      'virtual:pwa-register/react': fileURLToPath(new URL('./src/__tests__/stubs/pwa-register.ts', import.meta.url)),
    },
  },
  define: {
    __CHANNEL__: JSON.stringify('beta'),
    __GIT_COMMIT__: JSON.stringify('test123'),
  },
  test: {
    globals: true,
    environment: 'node',
    include: ['src/__tests__/**/*.test.{ts,tsx}'],
  },
})
