import type { GroupData, Match } from './data'
import { addDays, weekendOf } from '../core/schedule'

/*
 * What a team has coming and what it has done, as a family reads it: a port of :core's
 * schedule/Schedule.kt, on the view matches (src/core/group.ts worked out status, result and matchday).
 * Dates are "YYYY-MM-DD" strings and times "YYYY-MM-DDTHH:mm" in Madrid wall-clock time, as the
 * site writes them; date arithmetic is done in UTC so the phone's own time zone never matters.
 */

const fmtMadrid = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Europe/Madrid',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

/** Madrid wall clock now, "2026-11-14T10:30". */
export function madridNow(ms: number = Date.now()): string {
  const p = Object.fromEntries(fmtMadrid.formatToParts(new Date(ms)).map((x) => [x.type, x.value]))
  return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`
}

export function madridToday(ms: number = Date.now()): string {
  return madridNow(ms).slice(0, 10)
}

// The weekend arithmetic is core's (one copy: src/core/schedule.ts).
export { addDays, weekday, weekendOf } from '../core/schedule'

const toUtc = (day: string) => Date.UTC(Number(day.slice(0, 4)), Number(day.slice(5, 7)) - 1, Number(day.slice(8, 10)))

/** Minutes since the epoch of a Madrid wall-clock "YYYY-MM-DDTHH:mm" (comparable, not an instant). */
function wallMinutes(t: string): number {
  return Math.floor(toUtc(t.slice(0, 10)) / 60_000) + Number(t.slice(11, 13)) * 60 + Number(t.slice(14, 16))
}

export const isBye = (m: Match) => !m.home || !m.away
export const involves = (m: Match, teamId: number) => m.home?.id === teamId || m.away?.id === teamId
export const opponentOf = (m: Match, teamId: number) => (m.home?.id === teamId ? m.away : m.away?.id === teamId ? m.home : null)
export const isCancelled = (m: Match) => m.status === 'POSTPONED' || m.status === 'UNSCHEDULED' || m.status === 'NOT_HELD'

/** By day (none last), known times first, then time, byes last, then id: the order the site lists a round in. */
export function matchOrder(a: Match, b: Match): number {
  const da = a.day ?? '9999-12-31'
  const db = b.day ?? '9999-12-31'
  if (da !== db) return da < db ? -1 : 1
  if (a.timeKnown !== b.timeKnown) return a.timeKnown ? -1 : 1
  const sa = a.start ?? ''
  const sb = b.start ?? ''
  if (sa !== sb) return sa < sb ? -1 : 1
  if (isBye(a) !== isBye(b)) return isBye(a) ? 1 : -1
  return a.id - b.id
}

/** Distinct matchdays (Saturdays) of these groups, ascending. */
export function matchdays(groups: GroupData[]): string[] {
  const set = new Set<string>()
  for (const g of groups) {
    for (const r of g.rounds) if (r.date) set.add(weekendOf(r.date))
    for (const m of g.matches) if (m.matchday) set.add(m.matchday)
  }
  return [...set].sort()
}

/** The matchday to show on opening: this weekend until Monday night, else the next one, else the last. */
export function focusMatchday(days: string[], today: string): string | null {
  return days.find((d) => addDays(d, 2) >= today) ?? days[days.length - 1] ?? null
}

/** Still to come, or being played (a match with a time stays "ahead" until 3 h after its start). */
export function isAhead(m: Match, now: string): boolean {
  const today = now.slice(0, 10)
  if (!m.start) return m.day ? m.day >= today : true
  if (m.timeKnown) return wallMinutes(m.start) + 180 > wallMinutes(now)
  return m.start.slice(0, 10) >= today
}

/** Kick-off has passed and it is not over yet (100 min): "Ahora". */
export function isLive(m: Match, now: string): boolean {
  if (!m.timeKnown || !m.start || m.hasResult) return false
  const s = wallMinutes(m.start)
  const n = wallMinutes(now)
  return n >= s && n < s + 100
}

export function teamMatches(g: GroupData, teamId: number): Match[] {
  return g.matches.filter((m) => involves(m, teamId)).sort(matchOrder)
}

export function upcoming(g: GroupData, teamId: number, now: string): Match[] {
  return teamMatches(g, teamId).filter((m) => !isBye(m) && !m.hasResult && isAhead(m, now))
}

/** The next match the team has to turn up to (a postponed or unscheduled one only if there is nothing else). */
export function nextMatch(g: GroupData, teamId: number, now: string): Match | null {
  const list = upcoming(g, teamId, now)
  return list.find((m) => m.status !== 'UNSCHEDULED' && m.status !== 'POSTPONED') ?? list[0] ?? null
}

export function lastResult(g: GroupData, teamId: number): Match | null {
  const played = teamMatches(g, teamId).filter((m) => m.hasResult)
  return played[played.length - 1] ?? null
}

/** W, D or L for this team in a match with a result. */
export function outcomeFor(m: Match, teamId: number): 'W' | 'D' | 'L' | null {
  if (!m.hasResult || !m.winner) return null
  if (m.winner === 'DRAW') return 'D'
  const home = m.home?.id === teamId
  return (m.winner === 'HOME') === home ? 'W' : 'L'
}

/** The round a group is on for the calendar tab: this weekend's, else the next, else the last. */
export function focusRound(g: GroupData, today: string) {
  return g.rounds.find((r) => r.date != null && addDays(r.date, 2) >= today) ?? g.rounds[g.rounds.length - 1] ?? null
}
