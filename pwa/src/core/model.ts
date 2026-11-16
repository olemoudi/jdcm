/*
 * A port of what the PWA needs from the Kotlin `core/` (core/src/main/kotlin/dev/jdcm/core/model):
 * the match as the site describes it, and what it means for a family. Parsing and standings exist
 * twice — Kotlin for Android, TypeScript here — and are kept in step by testing both against the
 * same fixtures (core/src/test/resources/fixtures, read by src/core/__tests__ through their path).
 *
 * Dates are Madrid wall clock as the site writes them: "2026-11-14T10:30:00" and "2026-11-14".
 */

export type SportKind = 'BASKETBALL' | 'FUTSAL' | 'VOLLEYBALL' | 'OTHER'

/** The site's sport ids are stable (1, 2, 3); the name is a fallback (core's SportKind.of). */
export function sportKindOf(id: number, name?: string | null): SportKind {
  if (id === 1) return 'BASKETBALL'
  if (id === 2) return 'FUTSAL'
  if (id === 3) return 'VOLLEYBALL'
  const s = (name ?? '').toLowerCase()
  if (s.includes('balonc')) return 'BASKETBALL'
  if (s.includes('sala') || s.includes('fútbol') || s.includes('futbol')) return 'FUTSAL'
  if (s.includes('volei') || s.includes('vóley') || s.includes('voley')) return 'VOLLEYBALL'
  return 'OTHER'
}

export interface Team { id: number; name: string; crestPath: string; institutionId: number }

export interface Venue {
  name: string
  address: string
  postcode: string
  town: string
  lat: number | null
  lng: number | null
}

export const hasPosition = (v: Venue) => v.lat != null && v.lng != null && v.lat !== 0 && v.lng !== 0

export type MatchStatus = 'SCHEDULED' | 'PLAYED' | 'POSTPONED' | 'NOT_HELD' | 'UNSCHEDULED' | 'UNKNOWN'

/** Ids from the site's own catalogue (`estadosPartidosSolicitudes`, measured 2026-11-09). */
const BY_ID: Record<number, MatchStatus> = { 0: 'SCHEDULED', 1: 'POSTPONED', 6: 'NOT_HELD', 10: 'PLAYED', 11: 'PLAYED', 12: 'UNSCHEDULED' }

/** core's MatchStatus.of: the state's name first, then its id. */
export function statusOf(id: number, name: string): MatchStatus {
  const n = name.toLowerCase()
  if (n.includes('desprogram')) return 'UNSCHEDULED'
  if (n.includes('aplaz') || n.includes('suspend')) return 'POSTPONED'
  if (n.includes('no celebrado') || n.includes('incomplet')) return 'NOT_HELD'
  if (n.includes('pendiente')) return 'SCHEDULED'
  if (['jugado', 'finaliz', 'celebrado', 'disputado', 'acta', 'cerrado'].some((w) => n.includes(w))) return 'PLAYED'
  return BY_ID[id] ?? 'UNKNOWN'
}

export interface Match {
  id: number
  roundId: number
  roundNumber: number
  groupId: number
  /** Null on one side when the other team rests that matchday. */
  home: Team | null
  away: Team | null
  /** "2026-11-14T10:30:00"; with timeKnown false only its date counts (the site writes 00:00). */
  start: string | null
  timeKnown: boolean
  roundDate: string | null
  venue: Venue | null
  statusId: number
  statusName: string
  homeScore: number
  awayScore: number
  /** The site wrote both scores as numbers (null, "" or "-" is no score: no 0-0). */
  scoreKnown: boolean
  winnerId: number
  partials: string[]
  closed: boolean
  modified: boolean
  modifiedReason: string
  duration: string
}

export const isBye = (m: Match) => m.home == null || m.away == null
export const status = (m: Match) => statusOf(m.statusId, m.statusName)
export const restingTeam = (m: Match) => (isBye(m) ? m.home ?? m.away : null)

/** Postponed, taken off the calendar or not held: nobody turns up for it. */
export function isCancelled(m: Match): boolean {
  const s = status(m)
  return s === 'POSTPONED' || s === 'UNSCHEDULED' || s === 'NOT_HELD'
}

/**
 * Whether the score is a published result (core's Match.hasResult): never for a bye or a missing
 * score; a played state is one; a match not held only with a ruling (a winner, a forfeit score);
 * otherwise a closed flag, a winner or a score above 0-0.
 */
export function hasResult(m: Match): boolean {
  if (isBye(m) || !m.scoreKnown) return false
  switch (status(m)) {
    case 'POSTPONED':
    case 'UNSCHEDULED':
      return false
    case 'PLAYED':
      return true
    case 'NOT_HELD':
      return m.winnerId !== 0 || m.homeScore + m.awayScore > 0
    default:
      return m.closed || m.winnerId !== 0 || m.homeScore + m.awayScore > 0
  }
}

/** The day it is played on: its own date if set, else its round's. */
export const dayOf = (m: Match): string | null => m.start?.slice(0, 10) ?? m.roundDate

export interface Round { id: number; number: number; date: string | null }

export interface RoundPage {
  group: { id: number; name: string; competition: string; category: string; sport: string } | null
  round: Round | null
  previous: Round | null
  next: Round | null
  rounds: Round[]
  matches: Match[]
  /** The previous and next rounds come with their matches (only those the site sent matches for). */
  neighbours: Array<{ round: Round; matches: Match[] }>
}

export interface GroupedGroup { groupId: number; name: string; matches: Match[] }
export interface GroupedCategory { categoryId: number; name: string; groups: GroupedGroup[] }
export interface GroupedRound { competitionId: number; competition: string; categories: GroupedCategory[] }
