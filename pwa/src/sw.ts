/// <reference lib="webworker" />
import { precacheAndRoute, createHandlerBoundToURL } from 'workbox-precaching'
import { NavigationRoute, registerRoute } from 'workbox-routing'
import { cacheNames, clientsClaim, setCacheNameDetails } from 'workbox-core'

declare let self: ServiceWorkerGlobalScope

/*
 * The app shell, GTD25's model (PWA_UPDATE_PROTOCOL.md §3.4): precached, served offline, and
 * replaced only when the user taps "Update now" (SKIP_WAITING), never on its own.
 *
 * Two channels share the origin, and their scopes nest: the beta's (/jdcm/) contains the alpha's
 * (/jdcm/alpha/). So this worker
 *  - never answers a request under another channel's folder (__FOREIGN_PATHS__): not the
 *    navigation fallback, not the precache, nothing — a phone with the beta installed must still
 *    load the alpha's own shell, and the alpha's own worker takes over from there;
 *  - names its caches with its channel ("jdcm-beta-…"), and cleans up only those. Workbox's
 *    cleanupOutdatedCaches() is NOT used: it deletes every precache whose name contains this
 *    worker's scope, and "https://…/jdcm/" is contained in the alpha's "https://…/jdcm/alpha/".
 * The data files are not handled here (the page caches them itself, src/lib/remote.ts).
 */

const CHANNEL: string = typeof __CHANNEL__ !== 'undefined' ? __CHANNEL__ : 'beta'
const FOREIGN: string[] = typeof __FOREIGN_PATHS__ !== 'undefined' ? __FOREIGN_PATHS__ : []

const isForeign = (url: URL) => url.origin === self.location.origin && FOREIGN.some((p) => url.pathname.startsWith(p))

// Lifecycle diagnostics: the only window into a phone without a debugger.
console.debug(`[SW ${CHANNEL}] script evaluated at`, new Date().toISOString())
self.addEventListener('install', () => console.debug(`[SW ${CHANNEL}] install`))

// First listener: another channel's requests go straight to the network, and no later listener
// (Workbox's router included) ever sees them.
self.addEventListener('fetch', (event) => {
  if (isForeign(new URL(event.request.url))) event.stopImmediatePropagation()
})

// Activation only when the user asks (§8.1: never skipWaiting() at module scope).
self.addEventListener('message', (event) => {
  if (event.data?.type === 'SKIP_WAITING') event.waitUntil(self.skipWaiting())
})

setCacheNameDetails({ prefix: `jdcm-${CHANNEL}` })

// Drop this channel's older precaches (never another channel's): §8.10.
self.addEventListener('activate', (event) => {
  console.debug(`[SW ${CHANNEL}] activate`)
  event.waitUntil((async () => {
    const current = cacheNames.precache
    for (const name of await caches.keys()) {
      if (name.startsWith(`jdcm-${CHANNEL}-precache`) && !name.startsWith(current)) await caches.delete(name)
    }
  })())
})

clientsClaim()
precacheAndRoute(self.__WB_MANIFEST)
registerRoute(new NavigationRoute(createHandlerBoundToURL('index.html'), {
  allowlist: [new RegExp(`^${import.meta.env.BASE_URL.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)],
  denylist: FOREIGN.map((p) => new RegExp(`^${p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)),
}))
