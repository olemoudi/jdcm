import { useEffect, useState } from 'react'

/*
 * Hash routes (#/grupo/177): GitHub Pages has no fallback for deep paths, and a hash keeps the
 * installed app's start URL plain. The service worker's navigation fallback never sees them.
 */
export type Route =
  | { name: 'home' }
  | { name: 'tables' }
  | { name: 'settings' }
  | { name: 'group'; groupId: number; tab: 'calendar' | 'standings'; kidId?: string }
  | { name: 'team'; groupId: number; teamId: number; kidId?: string }
  | { name: 'kid'; kidId?: string; groupId?: number; teamId?: number }

export function parseRoute(hash: string): Route {
  const [path, query = ''] = hash.replace(/^#\/?/, '').split('?')
  const q = new URLSearchParams(query)
  const parts = path.split('/').filter(Boolean)
  const n = (s: string | undefined) => (s && /^\d+$/.test(s) ? Number(s) : NaN)
  const kidId = q.get('hijo') ?? undefined
  switch (parts[0]) {
    case 'tablas': return { name: 'tables' }
    case 'ajustes': return { name: 'settings' }
    case 'grupo': {
      const groupId = n(parts[1])
      if (!Number.isFinite(groupId)) break
      return { name: 'group', groupId, tab: parts[2] === 'clasificacion' ? 'standings' : 'calendar', kidId }
    }
    case 'equipo': {
      const groupId = n(parts[1])
      const teamId = n(parts[2])
      if (!Number.isFinite(groupId) || !Number.isFinite(teamId)) break
      return { name: 'team', groupId, teamId, kidId }
    }
    case 'hijo': {
      const groupId = n(q.get('grupo') ?? undefined)
      const teamId = n(q.get('equipo') ?? undefined)
      return {
        name: 'kid',
        kidId: parts[1] && parts[1] !== 'nuevo' ? parts[1] : undefined,
        groupId: Number.isFinite(groupId) ? groupId : undefined,
        teamId: Number.isFinite(teamId) ? teamId : undefined,
      }
    }
  }
  return { name: 'home' }
}

export function routeHash(r: Route): string {
  const kid = (id?: string) => (id ? `?hijo=${encodeURIComponent(id)}` : '')
  switch (r.name) {
    case 'home': return '#/'
    case 'tables': return '#/tablas'
    case 'settings': return '#/ajustes'
    case 'group': return `#/grupo/${r.groupId}/${r.tab === 'standings' ? 'clasificacion' : 'calendario'}${kid(r.kidId)}`
    case 'team': return `#/equipo/${r.groupId}/${r.teamId}${kid(r.kidId)}`
    case 'kid': {
      if (r.kidId) return `#/hijo/${encodeURIComponent(r.kidId)}`
      const q = r.groupId && r.teamId ? `?grupo=${r.groupId}&equipo=${r.teamId}` : ''
      return `#/hijo/nuevo${q}`
    }
  }
}

/** How many in-app steps lie behind this history entry (0: the app was opened here). */
const depth = (): number => {
  const d = (history.state as { jdcm?: unknown } | null)?.jdcm
  return typeof d === 'number' ? d : 0
}

export function navigate(r: Route, replace = false): void {
  const hash = routeHash(r)
  if (replace) history.replaceState({ jdcm: depth() }, '', hash)
  else history.pushState({ jdcm: depth() + 1 }, '', hash)
  window.dispatchEvent(new HashChangeEvent('hashchange'))
  window.scrollTo(0, 0)
}

/** Back within the app when there is history (an installed iOS app has no back button), else home. */
export function goBack(): void {
  if (depth() > 0) history.back()
  else navigate({ name: 'home' }, true)
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(location.hash))
  useEffect(() => {
    const on = () => setRoute(parseRoute(location.hash))
    window.addEventListener('hashchange', on)
    window.addEventListener('popstate', on)
    return () => {
      window.removeEventListener('hashchange', on)
      window.removeEventListener('popstate', on)
    }
  }, [])
  return route
}
