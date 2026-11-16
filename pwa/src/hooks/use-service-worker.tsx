import { createContext, useCallback, useContext, useEffect, useRef, type ReactNode } from 'react'
import { useRegisterSW } from 'virtual:pwa-register/react'
import { GIT_COMMIT } from '../lib/env'
import { fetchDeployedVersion } from '../lib/changelog'

/*
 * Registration and update detection, GTD25's model (PWA_UPDATE_PROTOCOL.md, src/hooks/
 * use-service-worker.tsx): detect → inform → apply. Mounted above every screen and error boundary,
 * so a broken build can always pull its fix. The worker is this channel's own (scope = its folder).
 */

const UPDATE_INTERVAL_MS = 30 * 60 * 1000   // periodic check while open
const MIN_UPDATE_CHECK_MS = 10 * 60 * 1000  // debounce for automatic triggers
const RELOAD_FALLBACK_MS = 12_000            // only if controllerchange never fires (never shorter: §8.6)
const UPDATE_CHECK_TIMEOUT_MS = 15_000

export type UpdateCheckResult = 'update-found' | 'up-to-date' | 'stale-worker' | 'no-worker' | 'failed'

async function currentRegistration(cached: ServiceWorkerRegistration | undefined): Promise<ServiceWorkerRegistration | undefined> {
  if (cached) return cached
  if (typeof navigator === 'undefined' || !navigator.serviceWorker) return undefined
  try {
    return await navigator.serviceWorker.getRegistration(import.meta.env.BASE_URL)
  } catch {
    return undefined
  }
}

async function updateWithinTimeout(registration: ServiceWorkerRegistration): Promise<boolean> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      registration.update().then(() => true),
      new Promise<boolean>((resolve) => { timer = setTimeout(() => resolve(false), UPDATE_CHECK_TIMEOUT_MS) }),
    ])
  } finally {
    clearTimeout(timer)
  }
}

// Module level: the page reloads at most once per life, whichever path fires first (§7 rule 1).
let reloadArmed = false
function reloadOnce() {
  if (reloadArmed) return
  reloadArmed = true
  try { window.location.reload() } catch { /* no-op */ }
}

export interface ServiceWorkerApi {
  needRefresh: boolean
  applyUpdate: () => void
  checkForUpdate: () => void                    // debounced (focus, visibility, interval)
  forceCheck: () => Promise<UpdateCheckResult>  // immediate, user-initiated
}

function useServiceWorkerImpl(): ServiceWorkerApi {
  const registrationRef = useRef<ServiceWorkerRegistration | undefined>(undefined)
  const lastCheckRef = useRef(0)
  const { needRefresh: [needRefresh], updateServiceWorker } = useRegisterSW({
    onRegisteredSW(_url, registration) {
      registrationRef.current = registration
    },
  })

  const runCheck = useCallback(async (): Promise<UpdateCheckResult> => {
    lastCheckRef.current = Date.now()
    const registration = await currentRegistration(registrationRef.current)
    if (!registration) return 'no-worker'
    registrationRef.current = registration
    let completed: boolean
    try {
      completed = await updateWithinTimeout(registration)
    } catch {
      return 'failed'
    }
    if (registration.installing || registration.waiting) return 'update-found'
    if (!completed) return 'failed'
    // "Nothing new" is believed only if the server agrees (a stuck worker says the same).
    const deployed = await fetchDeployedVersion()
    return deployed && deployed.commit !== GIT_COMMIT ? 'stale-worker' : 'up-to-date'
  }, [])

  const checkForUpdate = useCallback(() => {
    if (document.visibilityState === 'hidden') return
    if (Date.now() - lastCheckRef.current < MIN_UPDATE_CHECK_MS) return
    void runCheck()
  }, [runCheck])

  const forceCheck = useCallback(() => runCheck(), [runCheck])

  // SKIP_WAITING, then reload once the new worker controls the page. The plugin reloads only when
  // Workbox calls it an "update", which it does not on the session that first installed the worker:
  // our own controllerchange listener (armed only now, after the tap) covers that, and the long
  // guarded fallback covers browsers where controllerchange never fires (§7).
  const applyUpdate = useCallback(() => {
    if (reloadArmed) return
    navigator.serviceWorker?.addEventListener('controllerchange', reloadOnce, { once: true })
    void updateServiceWorker(true)
    setTimeout(reloadOnce, RELOAD_FALLBACK_MS)
  }, [updateServiceWorker])

  useEffect(() => {
    const onVisibilityChange = () => { if (document.visibilityState === 'visible') checkForUpdate() }
    document.addEventListener('visibilitychange', onVisibilityChange)
    window.addEventListener('focus', checkForUpdate) // standalone restore
    const timer = setInterval(checkForUpdate, UPDATE_INTERVAL_MS)
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      window.removeEventListener('focus', checkForUpdate)
      clearInterval(timer)
    }
  }, [checkForUpdate])

  return { needRefresh, applyUpdate, checkForUpdate, forceCheck }
}

export const ServiceWorkerContext = createContext<ServiceWorkerApi | null>(null)

export function ServiceWorkerProvider({ children }: { children: ReactNode }) {
  const api = useServiceWorkerImpl()
  return <ServiceWorkerContext.Provider value={api}>{children}</ServiceWorkerContext.Provider>
}

const NOOP_SW: ServiceWorkerApi = {
  needRefresh: false,
  applyUpdate: () => {},
  checkForUpdate: () => {},
  forceCheck: () => Promise.resolve('no-worker'),
}

/** A no-op outside the provider (unit tests), so consumers never crash for lack of one (§8.12). */
export function useServiceWorker(): ServiceWorkerApi {
  return useContext(ServiceWorkerContext) ?? NOOP_SW
}
