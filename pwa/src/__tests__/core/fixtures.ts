import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { Match, Team } from '../../core/model'

/**
 * The real answers of the site that the Kotlin core is tested against, read from where they live
 * (core/src/test/resources/fixtures): one set of fixtures keeps both implementations in step.
 */
export function fixture(name: string): string {
  return readFileSync(fileURLToPath(new URL(`../../../../core/src/test/resources/fixtures/${name}`, import.meta.url)), 'utf8')
}

/** core's TestData.team / match / played. */
export const team = (id: number, name = `T${id}`): Team => ({ id, name, crestPath: '', institutionId: 0 })

export function match(id: number, home: Team | null, away: Team | null, o: Partial<{
  homeScore: number; awayScore: number; status: string; statusId: number; start: string | null; round: number; winnerId: number; closed: boolean; scoreKnown: boolean
}> = {}): Match {
  const start = o.start === undefined ? '2026-11-14T10:30:00' : o.start
  const round = o.round ?? 1
  return {
    id, roundId: 100 + round, roundNumber: round, groupId: 7, home, away,
    start, timeKnown: start != null && start.slice(11, 16) !== '00:00', roundDate: start?.slice(0, 10) ?? null,
    venue: { name: 'Campo 1', address: 'Calle Mayor 1', postcode: '', town: '', lat: null, lng: null },
    statusId: o.statusId ?? 0, statusName: o.status ?? 'Pendiente de Jugar',
    homeScore: o.homeScore ?? 0, awayScore: o.awayScore ?? 0, scoreKnown: o.scoreKnown ?? true,
    winnerId: o.winnerId ?? 0, partials: [], closed: o.closed ?? false, modified: false, modifiedReason: '', duration: '',
  }
}

const plusWeeks = (round: number) => new Date(Date.UTC(2026, 10, 14 + 7 * (round - 1), 10, 30)).toISOString().slice(0, 19)

export const played = (id: number, home: Team, away: Team, h: number, a: number, round = 1): Match =>
  match(id, home, away, { homeScore: h, awayScore: a, status: 'Jugado', statusId: 2, round, closed: true, start: plusWeeks(round) })
