// The JSON the data workflow publishes (pump/src/main/kotlin/dev/jdcm/pump/Output.kt), read
// defensively: it comes from the network, may be from an older or newer pump, may be half a file
// or an error page. A malformed match is dropped, not the whole group.

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
  name: string
  competition: string
  category: string
  sport: string
  newestFetch: number
  lastError: string | null
}

export interface DataIndex {
  generatedAt: string
  /** Epoch ms of generatedAt. */
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
export interface Catalog { generatedAt: string; sports: CatalogSport[] }

// --- tolerant readers -------------------------------------------------------------------------

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => !!v && typeof v === 'object' && !Array.isArray(v)
const num = (v: unknown, d = 0): number => (typeof v === 'number' && Number.isFinite(v) ? v : d)
const numOrNull = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null)
const str = (v: unknown, d = ''): string => (typeof v === 'string' ? v : d)
const strOrNull = (v: unknown): string | null => (typeof v === 'string' && v ? v : null)
const bool = (v: unknown): boolean => v === true
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : [])

const STATUSES: Status[] = ['SCHEDULED', 'PLAYED', 'POSTPONED', 'NOT_HELD', 'UNSCHEDULED', 'UNKNOWN']
const SPORTS: SportKind[] = ['FUTSAL', 'BASKETBALL', 'VOLLEYBALL', 'OTHER']

export function sportKindOf(v: unknown): SportKind {
  return SPORTS.includes(v as SportKind) ? (v as SportKind) : 'OTHER'
}

export function sportKindOfId(id: number): SportKind {
  return id === 1 ? 'BASKETBALL' : id === 2 ? 'FUTSAL' : id === 3 ? 'VOLLEYBALL' : 'OTHER'
}

function team(v: unknown): Team | null {
  if (!isObj(v) || num(v.id) <= 0) return null
  return { id: num(v.id), name: str(v.name, `Equipo ${num(v.id)}`), crest: str(v.crest) }
}

function venue(v: unknown): Venue | null {
  if (!isObj(v)) return null
  return {
    name: str(v.name),
    address: str(v.address),
    postcode: str(v.postcode),
    town: str(v.town),
    lat: numOrNull(v.lat),
    lng: numOrNull(v.lng),
  }
}

export function parseMatch(v: unknown): Match | null {
  if (!isObj(v) || num(v.id) <= 0) return null
  const home = team(v.home)
  const away = team(v.away)
  if (!home && !away) return null
  const hasResult = bool(v.hasResult) && numOrNull(v.homeScore) != null && numOrNull(v.awayScore) != null
  const winner = v.winner === 'HOME' || v.winner === 'AWAY' || v.winner === 'DRAW' ? v.winner : null
  return {
    id: num(v.id),
    roundId: num(v.roundId),
    roundNumber: num(v.roundNumber),
    home,
    away,
    start: strOrNull(v.start),
    timeKnown: bool(v.timeKnown),
    day: strOrNull(v.day),
    matchday: strOrNull(v.matchday),
    status: STATUSES.includes(v.status as Status) ? (v.status as Status) : 'UNKNOWN',
    statusName: str(v.statusName),
    hasResult,
    homeScore: hasResult ? numOrNull(v.homeScore) : null,
    awayScore: hasResult ? numOrNull(v.awayScore) : null,
    winner: hasResult ? winner : null,
    partials: arr(v.partials).filter((p): p is string => typeof p === 'string'),
    modified: bool(v.modified),
    modifiedReason: str(v.modifiedReason),
    duration: str(v.duration),
    venue: venue(v.venue),
  }
}

function standingRow(v: unknown): StandingRow | null {
  if (!isObj(v)) return null
  const t = team(v.team)
  if (!t) return null
  return {
    position: num(v.position),
    team: t,
    played: num(v.played),
    won: num(v.won),
    drawn: num(v.drawn),
    lost: num(v.lost),
    scored: num(v.scored),
    conceded: num(v.conceded),
    points: num(v.points),
    form: arr(v.form).filter((f): f is 'W' | 'D' | 'L' => f === 'W' || f === 'D' || f === 'L'),
  }
}

function ref(v: unknown): GroupRef | null {
  if (!isObj(v)) return null
  const r = { groupId: num(v.groupId), sportId: num(v.sportId), competitionId: num(v.competitionId), categoryId: num(v.categoryId) }
  return r.groupId > 0 ? r : null
}

/** Schema 1 and anything later that kept its fields (the pump only adds fields). */
const knownSchema = (v: Obj) => num(v.v) >= 1

export function parseGroup(v: unknown): GroupData | null {
  if (!isObj(v) || !knownSchema(v) || !Array.isArray(v.matches)) return null
  const r = ref(v.ref)
  if (!r) return null
  const g = isObj(v.group) ? v.group : {}
  const st = isObj(v.standings) ? v.standings : {}
  const rule = isObj(st.rule) ? st.rule : {}
  return {
    generatedAt: str(v.generatedAt),
    ref: r,
    group: { id: num(g.id, r.groupId), name: str(g.name), competition: str(g.competition), category: str(g.category), sport: str(g.sport) },
    sport: sportKindOf(v.sport),
    rounds: arr(v.rounds)
      .filter(isObj)
      .filter((x) => num(x.id) > 0)
      .map((x) => ({ id: num(x.id), number: num(x.number), date: strOrNull(x.date), fetchedAt: num(x.fetchedAt) }))
      .sort((a, b) => a.number - b.number),
    currentRoundId: num(v.currentRoundId),
    newestFetch: num(v.newestFetch),
    matches: arr(v.matches).map(parseMatch).filter((m): m is Match => m !== null),
    standings: {
      rule: { win: num(rule.win, 2), draw: num(rule.draw, 1), loss: num(rule.loss), hasDraws: rule.hasDraws !== false },
      measured: bool(st.measured),
      rows: arr(st.rows).map(standingRow).filter((x): x is StandingRow => x !== null),
    },
  }
}

export function parseIndex(v: unknown): DataIndex | null {
  if (!isObj(v) || !knownSchema(v) || !Array.isArray(v.groups)) return null
  const generatedAt = str(v.generatedAt)
  const ms = Date.parse(generatedAt)
  return {
    generatedAt,
    generatedMs: Number.isFinite(ms) ? ms : 0,
    refreshHours: num(v.refreshHours, 3),
    refreshFromHour: num(v.refreshFromHour, 7),
    refreshToHour: num(v.refreshToHour, 22),
    groups: v.groups.flatMap((g): IndexGroup[] => {
      const r = ref(g)
      if (!r || !isObj(g)) return []
      return [{
        ...r,
        name: str(g.name),
        competition: str(g.competition),
        category: str(g.category),
        sport: str(g.sport),
        newestFetch: num(g.newestFetch),
        lastError: strOrNull(g.lastError),
      }]
    }),
  }
}

export function parseCatalog(v: unknown): Catalog | null {
  if (!isObj(v) || !knownSchema(v) || !Array.isArray(v.sports)) return null
  const named = <T>(x: unknown, f: (o: Obj) => T): T[] => arr(x).filter(isObj).filter((o) => num(o.id) > 0).map(f)
  return {
    generatedAt: str(v.generatedAt),
    sports: named(v.sports, (s) => ({
      id: num(s.id),
      kind: sportKindOf(s.kind),
      competitions: named(s.competitions, (c) => ({
        id: num(c.id),
        name: str(c.name),
        categories: named(c.categories, (cat) => ({
          id: num(cat.id),
          name: str(cat.name),
          groups: named(cat.groups, (g) => ({
            id: num(g.id),
            name: str(g.name),
            followed: bool(g.followed),
            teams: arr(g.teams).map(team).filter((t): t is Team => t !== null),
          })),
        })),
      })),
    })),
  }
}
