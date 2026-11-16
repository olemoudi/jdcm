// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { AppUpdatePrompt } from '../components/AppUpdatePrompt'
import { SettingsProvider } from '../hooks/app-state'
import { ServiceWorkerContext, type ServiceWorkerApi } from '../hooks/use-service-worker'
import { storageKey } from '../lib/storage'

function renderPrompt(api: Partial<ServiceWorkerApi>) {
  const value: ServiceWorkerApi = { needRefresh: false, applyUpdate: () => {}, checkForUpdate: () => {}, forceCheck: async () => 'up-to-date', ...api }
  return render(
    <ServiceWorkerContext.Provider value={value}>
      <SettingsProvider><AppUpdatePrompt /></SettingsProvider>
    </ServiceWorkerContext.Provider>,
  )
}

function serve(changes: unknown) {
  globalThis.fetch = vi.fn(async () => new Response(JSON.stringify(changes))) as unknown as typeof fetch
}

describe('the update prompt (GTD25 protocol)', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem(storageKey('lang'), 'es')
  })
  afterEach(cleanup)

  it('shows nothing without a waiting build', () => {
    renderPrompt({ needRefresh: false })
    expect(screen.queryByTestId('update-dialog')).toBeNull()
  })

  it('shows the running → new commit and what changed, and applies on "Update now"', async () => {
    serve({ commit: 'new4567', message: 'x', log: [{ h: 'new4567', s: 'Arreglo del calendario' }, { h: 'test123', s: 'old' }] })
    const applyUpdate = vi.fn()
    renderPrompt({ needRefresh: true, applyUpdate })
    expect(await screen.findByTestId('update-commits')).toHaveProperty('textContent', 'test123 → new4567')
    expect(screen.getByText('Arreglo del calendario')).toBeTruthy()
    fireEvent.click(screen.getByText('Actualizar ahora'))
    expect(applyUpdate).toHaveBeenCalledTimes(1)
    // The notice is armed in this channel's storage, to confirm the update after the reload.
    expect(JSON.parse(localStorage.getItem(storageKey('update-notice'))!).from).toBe('test123')
  })

  it('"Later" demotes the dialog to a thin banner that stays', async () => {
    serve({ commit: 'new4567', message: 'x' })
    renderPrompt({ needRefresh: true })
    fireEvent.click(await screen.findByText('Más tarde'))
    expect(screen.queryByTestId('update-dialog')).toBeNull()
    expect(screen.getByTestId('update-banner')).toBeTruthy()
  })

  it('suppresses a waiting build of the same commit (the refresh loop, §8.3)', async () => {
    serve({ commit: 'test123', message: 'same' })
    renderPrompt({ needRefresh: true })
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
    expect(screen.queryByTestId('update-dialog')).toBeNull()
    expect(screen.queryByTestId('update-banner')).toBeNull()
  })

  it('confirms an update after the reload only if the commit really changed', () => {
    localStorage.setItem(storageKey('update-notice'), JSON.stringify({ from: 'old0000', at: Date.now() }))
    renderPrompt({})
    expect(screen.getByTestId('update-done').textContent).toContain('beta · test123')
    cleanup()
    localStorage.setItem(storageKey('update-notice'), JSON.stringify({ from: 'test123', at: Date.now() }))
    renderPrompt({})
    expect(screen.queryByTestId('update-done')).toBeNull()
  })
})
