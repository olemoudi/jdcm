import { groupedRoundFrom } from '../core/parser'

// What the screens read. The home fetcher (pwa/pi/jdcm-fetch.sh) publishes the site's own JSON to
// olemoudi/jdcm-data; src/core (a port of the Kotlin core) turns it into these view types. Everything
// from the network is read defensively: it may be half a file, an error page or a newer schema.

export type Status = 'SCHEDULED' | 'PLAYED' | 'POSTPONED' | 'NOT_HELD' | 'UNSCHEDULED' | 'UNKNOWN'
export type SportKind = 'FUTSAL' | 'BASKETBALL' | 'VOLLEYBALL' | 'OTHER'

export interface Team { id: number; name: string; crest: string }

export interface Venue {
  name: string
  address: string
  postcode: string
  town: string
  lat: number | null
  lng: number | null
}

export interface Match {
  id: number
  roundId: number
  roundNumber: number
  home: Team | null
  away: Team | null
  /** Madrid wall clock, "2026-11-14T10:30". */
  start: string | null
  timeKnown: boolean
  /** "2026-11-14". */
  day: string | null
  /** The Saturday of its weekend. */
  matchday: string | null
  status: Status
  statusName: string
  hasResult: boolean
  homeScore: number | null
  awayScore: number | null
  winner: 'HOME' | 'AWAY' | 'DRAW' | null
  partials: string[]
  modified: boolean
  modifiedReason: string
  duration: string
  venue: Venue | null
}

export interface Round { id: number; number: number; date: string | null; fetchedAt: number }

export interface StandingRow {
  position: number
  team: Team
  played: number
  won: number
  drawn: number
  lost: number
  scored: number
  conceded: number
  points: number
  form: Array<'W' | 'D' | 'L'>
}

export interface Standings {
  rule: { win: number; draw: number; loss: number; hasDraws: boolean }
  measured: boolean
  rows: StandingRow[]
}

export interface GroupRef { groupId: number; sportId: number; competitionId: number; categoryId: number }

export interface GroupData {
  generatedAt: string
  ref: GroupRef
  group: { id: number; name: string; competition: string; category: string; sport: string }
  sport: SportKind
  rounds: Round[]
  currentRoundId: number
  newestFetch: number
  matches: Match[]
  standings: Standings
}

export interface IndexGroup extends GroupRef {
  label: string
  newestFetch: number
  lastError: string | null
}

export interface DataIndex {
  generatedAt: string
  /** Epoch ms of the last publish (a run that reached the site). */
  generatedMs: number
  refreshHours: number
  refreshFromHour: number
  refreshToHour: number
  groups: IndexGroup[]
}

export interface CatalogGroup { id: number; name: string; followed: boolean; teams: Team[] }
export interface CatalogCategory { id: number; name: string; groups: CatalogGroup[] }
export interface CatalogCompetition { id: number; name: string; categories: CatalogCategory[] }
export interface CatalogSport { id: number; kind: SportKind; competitions: CatalogCompetition[] }

// --- the index and the catalogue ----------------------------------------------------------------

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => !!v && typeof v === 'object' && !Array.isArray(v)
const num = (v: unknown, d = 0): number => (typeof v === 'number' && Number.isFinite(v) ? v : d)
const str = (v: unknown, d = ''): string => (typeof v === 'string' ? v : d)

export function sportKindOfId(id: number): SportKind {
  return id === 1 ? 'BASKETBALL' : id === 2 ? 'FUTSAL' : id === 3 ? 'VOLLEYBALL' : 'OTHER'
}

/** `v1/index.json`: schema 1, or any later one that kept its fields (the fetcher only adds fields). */
export function parseIndex(v: unknown): DataIndex | null {
  if (!isObj(v) || num(v.v) < 1 || !Array.isArray(v.groups)) return null
  const generatedAt = str(v.generatedAt)
  const parsed = Date.parse(generatedAt)
  return {
    generatedAt,
    generatedMs: num(v.generatedMs, Number.isFinite(parsed) ? parsed : 0),
    refreshHours: num(v.refreshHours, 3),
    refreshFromHour: num(v.refreshFromHour, 7),
    refreshToHour: num(v.refreshToHour, 19),
    groups: v.groups.flatMap((g): IndexGroup[] => {
      if (!isObj(g) || num(g.groupId) <= 0) return []
      return [{
        groupId: num(g.groupId), sportId: num(g.sportId), competitionId: num(g.competitionId), categoryId: num(g.categoryId),
        label: str(g.label),
        newestFetch: num(g.newestFetch),
        lastError: typeof g.lastError === 'string' && g.lastError ? g.lastError : null,
      }]
    }),
  }
}

/**
 * `v1/catalog/<sportId>.json`: the site's grouped calendar of one round for the whole sport, trimmed
 * to the teams. Parsed with the core port (groupedRound), teams sorted by name, groups by number.
 */
export function buildCatalogSport(v: unknown, sportId: number, followed: Set<number>): CatalogSport | null {
  if (!isObj(v) || num(v.v) < 1) return null
  let rounds
  try {
    rounds = groupedRoundFrom(v.competitions)
  } catch {
    return null
  }
  const byName = (a: { name: string }, b: { name: string }) => collator.compare(a.name, b.name)
  const groupNo = (n: string) => Number(/\d+/.exec(n)?.[0] ?? Number.MAX_SAFE_INTEGER)
  return {
    id: sportId,
    kind: sportKindOfId(sportId),
    competitions: rounds.filter((c) => c.competitionId > 0).map((c) => ({
      id: c.competitionId,
      name: c.competition,
      categories: c.categories.filter((k) => k.categoryId > 0).map((k) => ({
        id: k.categoryId,
        name: k.name,
        groups: k.groups.filter((g) => g.groupId > 0).map((g) => {
          const seen = new Map<number, Team>()
          for (const m of g.matches) {
            for (const t of [m.home, m.away]) if (t && !seen.has(t.id)) seen.set(t.id, { id: t.id, name: t.name, crest: t.crestPath })
          }
          return { id: g.groupId, name: g.name, followed: followed.has(g.groupId), teams: [...seen.values()].sort(byName) }
        }).sort((a, b) => groupNo(a.name) - groupNo(b.name) || a.name.localeCompare(b.name)),
      })).sort(byName),
    })).sort(byName),
  }
}

const collator = new Intl.Collator('es-ES', { sensitivity: 'base' })
