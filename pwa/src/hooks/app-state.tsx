import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { buildGroup } from '../core/group'
import { buildCatalogSport, parseIndex, type CatalogSport, type DataIndex, type GroupData } from '../lib/data'
import { Format } from '../lib/format'
import { saveLang, savedLang, type Lang } from '../lib/i18n'
import { loadKids, saveKids, type Kid } from '../lib/kids'
import { FetchError, readCached, readFresh } from '../lib/remote'
import { madridToday } from '../lib/schedule'
import { readRaw, writeRaw } from '../lib/storage'

// --- settings: language and theme ---------------------------------------------------------------

export type Theme = 'dark' | 'light' | 'system'

function savedTheme(): Theme {
  const v = readRaw('theme')
  return v === 'light' || v === 'system' ? v : 'dark' // dark by default, like the app
}

export function applyTheme(theme: Theme): void {
  const dark = theme === 'dark' || (theme === 'system' && !!globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches)
  const root = document.documentElement
  root.dataset.theme = dark ? 'dark' : 'light'
  root.style.colorScheme = dark ? 'dark' : 'light'
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#111214' : '#F4F4F5')
}

interface SettingsApi {
  lang: Lang
  setLang: (l: Lang) => void
  theme: Theme
  setTheme: (t: Theme) => void
  fmt: Format
}

const SettingsContext = createContext<SettingsApi | null>(null)

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(savedLang)
  const [theme, setThemeState] = useState<Theme>(savedTheme)
  useEffect(() => {
    applyTheme(theme)
    if (theme !== 'system') return
    const mq = globalThis.matchMedia?.('(prefers-color-scheme: dark)')
    const on = () => applyTheme('system')
    mq?.addEventListener?.('change', on)
    return () => mq?.removeEventListener?.('change', on)
  }, [theme])
  useEffect(() => { document.documentElement.lang = lang }, [lang])
  const api = useMemo<SettingsApi>(() => ({
    lang,
    setLang: (l) => { saveLang(l); setLangState(l) },
    theme,
    setTheme: (t) => { writeRaw('theme', t); setThemeState(t) },
    fmt: new Format(lang),
  }), [lang, theme])
  return <SettingsContext.Provider value={api}>{children}</SettingsContext.Provider>
}

export function useSettings(): SettingsApi {
  const v = useContext(SettingsContext)
  if (!v) throw new Error('SettingsProvider missing')
  return v
}

/** The formatter of the current language (the shortcut every screen uses). */
export function useFmt(): Format {
  return useSettings().fmt
}

// --- kids -----------------------------------------------------------------------------------------

interface KidsApi {
  kids: Kid[]
  upsert: (k: Kid) => void
  remove: (id: string) => void
}

const KidsContext = createContext<KidsApi | null>(null)

export function KidsProvider({ children }: { children: ReactNode }) {
  const [kids, setKids] = useState<Kid[]>(loadKids)
  const api = useMemo<KidsApi>(() => ({
    kids,
    upsert: (k) => setKids((list) => {
      const next = list.some((x) => x.id === k.id) ? list.map((x) => (x.id === k.id ? k : x)) : [...list, k]
      saveKids(next)
      return next
    }),
    remove: (id) => setKids((list) => {
      const next = list.filter((x) => x.id !== id)
      saveKids(next)
      return next
    }),
  }), [kids])
  return <KidsContext.Provider value={api}>{children}</KidsContext.Provider>
}

export function useKids(): KidsApi {
  const v = useContext(KidsContext)
  if (!v) throw new Error('KidsProvider missing')
  return v
}

// --- data -----------------------------------------------------------------------------------------

export type LoadState = 'loading' | 'ok' | 'offline' | 'error'

interface DataApi {
  index: DataIndex | null
  groups: Record<number, GroupData>
  state: LoadState
  /** Epoch ms of the last answer from the network (0: only cached data so far). */
  checkedAt: number
  refreshing: boolean
  refresh: () => Promise<void>
  /** Ask for groups beyond the children's (a group or team screen opened from elsewhere). */
  want: (groupId: number) => void
  /** Per sport id, loaded when a child is being added (one file per sport). */
  catalog: Record<number, CatalogSport>
  catalogState: Record<number, LoadState>
  loadCatalog: (sportId: number) => void
}

const DataContext = createContext<DataApi | null>(null)

const INDEX = 'v1/index.json'
const groupPath = (id: number) => `v1/groups/${id}/all.json`

/** Coming back to the app re-checks the data at most this often (it changes every 3 h at most). */
const RECHECK_MS = 5 * 60_000

export function DataProvider({ children }: { children: ReactNode }) {
  const { kids } = useKids()
  const [index, setIndex] = useState<DataIndex | null>(null)
  const [groups, setGroups] = useState<Record<number, GroupData>>({})
  const [state, setState] = useState<LoadState>('loading')
  const [checkedAt, setCheckedAt] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [extra, setExtra] = useState<number[]>([])
  const [catalog, setCatalog] = useState<Record<number, CatalogSport>>({})
  const [catalogState, setCatalogState] = useState<Record<number, LoadState>>({})
  const indexRef = useRef<DataIndex | null>(null)
  indexRef.current = index
  const groupsRef = useRef(groups)
  groupsRef.current = groups
  const running = useRef<Promise<void> | null>(null)
  const lastCheck = useRef(0)

  const wanted = useMemo(() => [...new Set([...kids.map((k) => k.groupId), ...extra])].sort((a, b) => a - b), [kids, extra])
  const wantedRef = useRef(wanted)
  wantedRef.current = wanted

  const putGroup = (g: GroupData) => setGroups((all) => (all[g.ref.groupId]?.newestFetch === g.newestFetch && all[g.ref.groupId]?.generatedAt === g.generatedAt ? all : { ...all, [g.ref.groupId]: g }))

  // A load asked for while one runs (a child added mid-way) runs again right after it.
  const again = useRef(false)
  const loadRef = useRef<() => Promise<void>>(async () => {})
  const load = useCallback(async () => {
    if (running.current) {
      again.current = true
      return running.current
    }
    const run = (async () => {
      setRefreshing(true)
      lastCheck.current = Date.now()
      try {
        // 1. What was seen last time, at once.
        const cachedIndex = await readCached(INDEX, parseIndex)
        if (cachedIndex) setIndex((cur) => cur ?? cachedIndex)
        const today = madridToday()
        const group = (generatedAt: string) => (v: unknown) => buildGroup(v, today, generatedAt)
        for (const id of wantedRef.current) {
          if (groupsRef.current[id]) continue
          const g = await readCached(groupPath(id), group(cachedIndex?.generatedAt ?? ''))
          if (g) putGroup(g)
        }
        // 2. The network.
        const fresh = await readFresh(INDEX, parseIndex)
        setIndex(fresh)
        for (const id of wantedRef.current) {
          const entry = fresh.groups.find((g) => g.groupId === id)
          if (!entry) continue // not followed by the fetcher (yet)
          const held = groupsRef.current[id]
          if (held && held.newestFetch === entry.newestFetch) continue // unchanged since last seen
          putGroup(await readFresh(groupPath(id), group(fresh.generatedAt)))
        }
        setCheckedAt(Date.now())
        setState('ok')
      } catch (e) {
        setState(e instanceof FetchError && e.offline ? 'offline' : 'error')
      } finally {
        setRefreshing(false)
        running.current = null
        if (again.current) {
          again.current = false
          void loadRef.current()
        }
      }
    })()
    running.current = run
    return run
  }, [])
  loadRef.current = load

  // On start, whenever the set of wanted groups changes, and on coming back to the app.
  const wantedKey = wanted.join(',')
  useEffect(() => { void load() }, [load, wantedKey])
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible' && Date.now() - lastCheck.current > RECHECK_MS) void load()
    }
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('focus', onVisible)
    return () => {
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('focus', onVisible)
    }
  }, [load])

  const catalogStateRef = useRef(catalogState)
  catalogStateRef.current = catalogState
  const loadCatalog = useCallback((sportId: number) => {
    const st = catalogStateRef.current[sportId]
    if (st === 'loading' || st === 'ok') return
    setCatalogState((s) => ({ ...s, [sportId]: 'loading' }))
    const followed = () => new Set((indexRef.current?.groups ?? []).map((g) => g.groupId))
    const parse = (v: unknown) => buildCatalogSport(v, sportId, followed())
    void (async () => {
      const cached = await readCached(`v1/catalog/${sportId}.json`, parse)
      if (cached) setCatalog((c) => ({ ...c, [sportId]: cached }))
      try {
        const fresh = await readFresh(`v1/catalog/${sportId}.json`, parse)
        setCatalog((c) => ({ ...c, [sportId]: fresh }))
        setCatalogState((s) => ({ ...s, [sportId]: 'ok' }))
      } catch (e) {
        setCatalogState((s) => ({ ...s, [sportId]: cached ? 'ok' : e instanceof FetchError && e.offline ? 'offline' : 'error' }))
      }
    })()
  }, [])

  const api = useMemo<DataApi>(() => ({
    index, groups, state, checkedAt, refreshing,
    refresh: load,
    want: (id) => setExtra((x) => (x.includes(id) ? x : [...x, id])),
    catalog, catalogState, loadCatalog,
  }), [index, groups, state, checkedAt, refreshing, load, catalog, catalogState, loadCatalog])
  return <DataContext.Provider value={api}>{children}</DataContext.Provider>
}

export function useData(): DataApi {
  const v = useContext(DataContext)
  if (!v) throw new Error('DataProvider missing')
  return v
}
