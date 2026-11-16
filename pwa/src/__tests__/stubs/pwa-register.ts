// Test stub for `virtual:pwa-register/react` (only exists when vite-plugin-pwa runs).
export function useRegisterSW(_options?: unknown): {
  needRefresh: [boolean, (v: boolean) => void]
  offlineReady: [boolean, (v: boolean) => void]
  updateServiceWorker: (reloadPage?: boolean) => Promise<void>
} {
  return {
    needRefresh: [false, () => {}],
    offlineReady: [false, () => {}],
    updateServiceWorker: async () => {},
  }
}
