import { CHANNEL, DATA_URL } from './env'

/*
 * Stale-while-revalidate for the data files, in the page (not the service worker): what was last
 * seen is shown at once from the Cache API, the network answer replaces it when it arrives. Only a
 * file that passed validation is cached, so a broken answer never replaces a good one. The cache
 * is this channel's own (both channels share the origin's Cache Storage).
 */

export const DATA_CACHE = `jdcm-${CHANNEL}-data`

const memory = new Map<string, unknown>()

export function dataUrl(path: string): string {
  return new URL(path, new URL(DATA_URL, globalThis.location?.href ?? 'https://makensi.es/')).toString()
}

async function openCache(): Promise<Cache | null> {
  try {
    return typeof caches !== 'undefined' ? await caches.open(DATA_CACHE) : null
  } catch {
    return null // private mode, blocked storage: memory only
  }
}

export async function readCached<T>(path: string, parse: (v: unknown) => T | null): Promise<T | null> {
  const url = dataUrl(path)
  if (memory.has(url)) return parse(memory.get(url))
  try {
    const cache = await openCache()
    const hit = await cache?.match(url)
    if (!hit) return null
    const v = await hit.json()
    const parsed = parse(v)
    if (parsed) memory.set(url, v)
    return parsed
  } catch {
    return null
  }
}

export class FetchError extends Error {
  readonly offline: boolean
  constructor(message: string, offline: boolean) {
    super(message)
    this.offline = offline
  }
}

/** The network's answer, validated, then cached. Throws FetchError (offline or not). */
export async function readFresh<T>(path: string, parse: (v: unknown) => T | null): Promise<T> {
  const url = dataUrl(path)
  let text: string
  try {
    // no-cache: revalidate with the CDN (ETag) instead of trusting the browser's copy.
    const res = await fetch(url, { cache: 'no-cache', signal: AbortSignal.timeout(20_000) })
    if (!res.ok) throw new FetchError(`HTTP ${res.status}`, false)
    text = await res.text()
  } catch (e) {
    if (e instanceof FetchError) throw e
    throw new FetchError(e instanceof Error ? e.message : String(e), true)
  }
  let v: unknown
  try {
    v = JSON.parse(text)
  } catch {
    throw new FetchError('not JSON', false)
  }
  const parsed = parse(v)
  if (!parsed) throw new FetchError('unexpected data', false)
  memory.set(url, v)
  try {
    const cache = await openCache()
    await cache?.put(url, new Response(text, { headers: { 'content-type': 'application/json' } }))
  } catch { /* shown anyway; cached next time */ }
  return parsed
}

/** For tests. */
export function __resetRemoteMemory(): void {
  memory.clear()
}

/**
 * Data older than this is flagged plainly: the fetcher is a device at someone's home and can be off.
 * Its runs are 3 h apart in the daytime and 12 h apart overnight (19:07 → 07:07), so 12.5 h only
 * trips when a run was missed, not every morning before the first one.
 */
export const STALE_MS = 12.5 * 3_600_000

export function isStale(generatedMs: number, nowMs: number = Date.now()): boolean {
  return generatedMs > 0 && nowMs - generatedMs > STALE_MS
}
