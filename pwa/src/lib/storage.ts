import { CHANNEL } from './env'

/*
 * localStorage, namespaced by channel and never throwing. Both channels live on one origin
 * (makensi.es), so they share localStorage: every key is "jdcm:<channel>:<name>", and the alpha
 * never writes anything the beta reads. Storage can throw (Safari private mode, blocked site data,
 * quota): reads then return null and writes are dropped, and the app keeps working in memory.
 */

const memory = new Map<string, string>()

export function storageKey(name: string, channel: string = CHANNEL): string {
  return `jdcm:${channel}:${name}`
}

export function readRaw(name: string): string | null {
  const key = storageKey(name)
  try {
    const v = globalThis.localStorage?.getItem(key)
    if (v != null) return v
  } catch { /* fall back to memory */ }
  return memory.get(key) ?? null
}

export function writeRaw(name: string, value: string | null): void {
  const key = storageKey(name)
  if (value == null) memory.delete(key)
  else memory.set(key, value)
  try {
    if (value == null) globalThis.localStorage?.removeItem(key)
    else globalThis.localStorage?.setItem(key, value)
  } catch { /* kept in memory for this session */ }
}

export function readJson<T>(name: string, validate: (v: unknown) => T | null): T | null {
  const raw = readRaw(name)
  if (raw == null) return null
  try {
    return validate(JSON.parse(raw))
  } catch {
    return null
  }
}

export function writeJson(name: string, value: unknown): void {
  writeRaw(name, JSON.stringify(value))
}

/** For tests. */
export function __resetMemoryStorage(): void {
  memory.clear()
}
