import { parseIndex } from '../lib/data'
import { __resetRemoteMemory, DATA_CACHE, FetchError, isStale, readCached, readFresh } from '../lib/remote'
import { realJson } from './helpers'

/** A tiny Cache Storage (node has none). */
function fakeCaches() {
  const stores = new Map<string, Map<string, string>>()
  return {
    stores,
    open: async (name: string) => {
      if (!stores.has(name)) stores.set(name, new Map())
      const s = stores.get(name)!
      return {
        match: async (url: string) => (s.has(url) ? new Response(s.get(url)) : undefined),
        put: async (url: string, res: Response) => { s.set(url, await res.text()) },
      }
    },
  }
}

describe('stale-while-revalidate for the data', () => {
  let caches: ReturnType<typeof fakeCaches>
  beforeEach(() => {
    __resetRemoteMemory()
    caches = fakeCaches()
    Object.defineProperty(globalThis, 'caches', { value: caches, configurable: true })
  })

  it('caches a good answer in this channel’s cache and serves it next time', async () => {
    const body = JSON.stringify(realJson('v1/index.json'))
    globalThis.fetch = vi.fn(async () => new Response(body)) as unknown as typeof fetch
    const fresh = await readFresh('v1/index.json', parseIndex)
    expect(fresh.groups).toHaveLength(2)
    expect([...caches.stores.keys()]).toEqual([DATA_CACHE])
    expect(DATA_CACHE).toBe('jdcm-beta-data')
    __resetRemoteMemory()
    expect((await readCached('v1/index.json', parseIndex))!.groups).toHaveLength(2)
  })

  it('never caches a broken answer over a good one', async () => {
    globalThis.fetch = vi.fn(async () => new Response('<html>rate limited</html>')) as unknown as typeof fetch
    await expect(readFresh('v1/index.json', parseIndex)).rejects.toBeInstanceOf(FetchError)
    expect(caches.stores.get(DATA_CACHE)?.size ?? 0).toBe(0)
  })

  it('tells offline from a server error', async () => {
    globalThis.fetch = vi.fn(async () => { throw new TypeError('Failed to fetch') }) as unknown as typeof fetch
    await expect(readFresh('v1/index.json', parseIndex)).rejects.toMatchObject({ offline: true })
    globalThis.fetch = vi.fn(async () => new Response('', { status: 404 })) as unknown as typeof fetch
    await expect(readFresh('v1/index.json', parseIndex)).rejects.toMatchObject({ offline: false })
  })
})

describe('stale data is flagged', () => {
  it('after 12.5 h: a missed run trips it, the overnight gap (19:07 → 07:07) does not', () => {
    const at = Date.UTC(2026, 10, 16, 18, 8) // 19:08 in Madrid
    expect(isStale(at, at + 12 * 3_600_000 + 5 * 60_000)).toBe(false) // 07:13, before the first run publishes
    expect(isStale(at, at + 15 * 3_600_000)).toBe(true) // 10:08 and still nothing
    expect(isStale(0, at)).toBe(false) // nothing loaded is not "old"
  })
})
