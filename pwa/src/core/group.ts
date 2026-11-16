import type { GroupData, Match as ViewMatch, StandingRow as ViewRow } from '../lib/data'
import { dayOf, hasResult, sportKindOf, status, type Match, type Round } from './model'
import { match as parseMatch, round as parseRound } from './parser'
import { computeStandings, outcome, pointsRule, ruleMeasured } from './standings'
import { matchdayOf } from './schedule'

/*
 * One group as the PWA shows it, built from what the home fetcher publishes
 * (pwa/pi/jdcm-fetch.sh → jdcm-data v1/groups/<id>/all.json): the site's own round objects, one per
 * round, as they were last accepted, plus the fetcher's meta (the round list, when each round was
 * read). The merge rules of core's GroupSnapshot that still apply here:
 *  - the group's id is ours: another group's matches are dropped;
 *  - a match id appears once, from the newest round file holding it (a moved match is listed once);
 *  - a "result" dated after today is not believed: shown as pending, no score, no 0-0 in the table.
 * (Refusing a round that shrank is the fetcher's job: it keeps the previous file.)
 */

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => !!v && typeof v === 'object' && !Array.isArray(v)
const num = (v: unknown, d = 0) => (typeof v === 'number' && Number.isFinite(v) ? v : d)
const str = (v: unknown) => (typeof v === 'string' ? v : '')

export interface RoundFile { round: Obj; fetchedAt: number }

/** core's GroupSnapshot.merge, the parts that apply to stored round files: see above. */
export function combineRounds(groupId: number, files: RoundFile[], today: string): { rounds: Round[]; matches: Match[] } {
  const byId = new Map<number, Match>()
  const rounds: Round[] = []
  const ordered = [...files].sort((a, b) => a.fetchedAt - b.fetchedAt)
  for (const f of ordered) {
    const r = parseRound(f.round)
    if (!r) continue
    rounds.push(r)
    const list = Array.isArray(f.round.Enfrentamientos) ? f.round.Enfrentamientos : []
    for (const raw of list) {
      const m = parseMatch(raw)
      if (!m || (m.groupId !== groupId && m.groupId !== 0)) continue
      byId.delete(m.id) // re-inserted: the newest file holding it decides
      byId.set(m.id, unbelievedResult({ ...m, roundId: r.id, roundNumber: r.number, groupId }, today))
    }
  }
  return { rounds, matches: [...byId.values()].sort(matchOrder) }
}

/**
 * A result for a match whose day has not come (an early closed flag, a stray score, a "played" state
 * typed into the wrong week) is shown as pending: no score, state 0.
 */
export function unbelievedResult(m: Match, today: string): Match {
  const day = dayOf(m)
  if (!day || day <= today || !hasResult(m)) return m
  return { ...m, closed: false, winnerId: 0, homeScore: 0, awayScore: 0, scoreKnown: false, statusId: 0, statusName: '' }
}

/** By day (none last), known times first, then time, byes last, then id (core's MatchOrder). */
export function matchOrder(a: Match, b: Match): number {
  const da = dayOf(a) ?? '9999-12-31'
  const db = dayOf(b) ?? '9999-12-31'
  if (da !== db) return da < db ? -1 : 1
  if (a.timeKnown !== b.timeKnown) return a.timeKnown ? -1 : 1
  const sa = a.start ?? ''
  const sb = b.start ?? ''
  if (sa !== sb) return sa < sb ? -1 : 1
  const ba = !a.home || !a.away
  const bb = !b.home || !b.away
  if (ba !== bb) return ba ? 1 : -1
  return a.id - b.id
}

const team = (t: Match['home']) => (t ? { id: t.id, name: t.name, crest: t.crestPath } : null)

/** What the screens read: the site's match with its meaning worked out (status, result, winner, matchday). */
export function toView(m: Match): ViewMatch {
  const result = hasResult(m)
  const o = result && m.home ? outcome(m, m.home.id) : null
  return {
    id: m.id,
    roundId: m.roundId,
    roundNumber: m.roundNumber,
    home: team(m.home),
    away: team(m.away),
    start: m.start ? m.start.slice(0, 16) : null,
    timeKnown: m.timeKnown,
    day: dayOf(m),
    matchday: matchdayOf(m),
    status: status(m),
    statusName: m.statusName,
    hasResult: result,
    homeScore: result ? m.homeScore : null,
    awayScore: result ? m.awayScore : null,
    winner: o === 'WIN' ? 'HOME' : o === 'LOSS' ? 'AWAY' : o === 'DRAW' ? 'DRAW' : null,
    partials: m.partials,
    modified: m.modified,
    modifiedReason: m.modifiedReason,
    duration: m.duration,
    venue: m.venue ? { ...m.venue } : null,
  }
}

/**
 * `v1/groups/<id>/all.json` → the group as the screens show it. Null when it is not that file at all
 * (an error page, another schema); a malformed round or match is skipped, not the group.
 */
export function buildGroup(v: unknown, today: string, generatedAt = ''): GroupData | null {
  if (!isObj(v) || num(v.v) < 1 || !isObj(v.meta) || !isObj(v.rounds)) return null
  const meta = v.meta
  const ref = isObj(meta.ref) ? meta.ref : {}
  const groupId = num(ref.groupId, num(meta.groupId))
  if (groupId <= 0) return null
  const fetchedAt = isObj(meta.fetchedAt) ? meta.fetchedAt : {}
  const listed = (Array.isArray(meta.rounds) ? meta.rounds : [])
    .filter(isObj)
    .map((r) => ({ id: num(r.id), number: num(r.number), date: typeof r.date === 'string' && r.date ? r.date : null }))
    .filter((r) => r.id > 0)
    .sort((a, b) => a.number - b.number)
  const files: RoundFile[] = listed.flatMap((r) => {
    const o = (v.rounds as Obj)[String(r.id)]
    return isObj(o) && num(o.Id) === r.id ? [{ round: o, fetchedAt: num(fetchedAt[String(r.id)]) }] : []
  })
  const { matches } = combineRounds(groupId, files, today)
  const info = isObj(meta.group) ? meta.group : {}
  const kind = sportKindOf(num(ref.sportId), str(info.sport))
  const rule = pointsRule(kind)
  const rows: ViewRow[] = computeStandings(matches, rule).map((r) => ({
    position: r.position,
    team: { id: r.team.id, name: r.team.name, crest: r.team.crestPath },
    played: r.played, won: r.won, drawn: r.drawn, lost: r.lost, scored: r.scored, conceded: r.conceded, points: r.points,
    form: r.form.map((o) => (o === 'WIN' ? 'W' : o === 'DRAW' ? 'D' : 'L')),
  }))
  const stamps = listed.map((r) => num(fetchedAt[String(r.id)])).filter((x) => x > 0)
  return {
    generatedAt,
    ref: { groupId, sportId: num(ref.sportId), competitionId: num(ref.competitionId), categoryId: num(ref.categoryId) },
    group: { id: groupId, name: str(info.name), competition: str(info.competition), category: str(info.category), sport: str(info.sport) },
    sport: kind,
    rounds: listed.map((r) => ({ ...r, fetchedAt: num(fetchedAt[String(r.id)]) })),
    currentRoundId: num(meta.currentRoundId),
    newestFetch: stamps.length ? Math.max(...stamps) : 0,
    matches: matches.map(toView),
    standings: { rule, measured: ruleMeasured(kind), rows },
  }
}
