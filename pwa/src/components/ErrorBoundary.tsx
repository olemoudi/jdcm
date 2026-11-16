import { Component, type ReactNode } from 'react'
import { BUILD_LABEL } from '../lib/env'

/**
 * Last resort: a crashed screen shows a reload button and the build (the version indicator must be
 * visible in degraded states too: PWA_UPDATE_PROTOCOL §4). It sits INSIDE the service-worker
 * provider and the update prompt stays mounted beside it, so a broken build can still be updated.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  componentDidCatch(error: Error) {
    console.error('[JDCM] render error', error)
  }

  render() {
    if (!this.state.error) return this.props.children
    const es = !navigator.language?.toLowerCase().startsWith('en')
    return (
      <div className="flex min-h-dvh flex-col items-center justify-center gap-3 p-6 text-center pt-safe">
        <h1 className="text-xl font-bold">{es ? 'Algo ha fallado' : 'Something went wrong'}</h1>
        <p className="text-sm text-text-2">{es ? 'Recarga la app. Si se repite, avisa indicando esta versión:' : 'Reload the app. If it happens again, report it with this version:'}</p>
        <p className="font-mono text-xs text-text-2" data-testid="build-label">{BUILD_LABEL}</p>
        <button type="button" onClick={() => location.reload()} className="min-h-12 rounded-xl bg-primary px-6 font-semibold text-on-primary">
          {es ? 'Recargar' : 'Reload'}
        </button>
      </div>
    )
  }
}
