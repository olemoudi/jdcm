import type { GroupData, Match as ViewMatch, StandingRow as ViewRow } from '../lib/data'
import { dayOf, hasResult, sportKindOf, status, type Match, type Round } from './model'
import { match as parseMatch, round as parseRound } from './parser'
import { computeStandings, outcome, pointsRule, ruleMeasured } from './standings'
import { matchdayOf, weekendOf } from './schedule'

/*
 * One group as the PWA shows it, built from what the home fetcher publishes
 * (pwa/pi/jdcm-fetch.sh → jdcm-data v1/groups/<id>/all.json), from two sources:
 *  - the group's own round pages (`rounds`, with scores), each with when it was read;
 *  - the grouped calendars (`sched`: every match of the group, dates, venues, states, no scores),
 *    each match with when it last changed.
 * Schedule and state (date, time, venue, state, "modified") come from whichever source is newer;
 * score, winner and the closed flag only from the group's own pages (a grouped "played" state
 * without a score is «Resultado pendiente»). The rules of core's GroupSnapshot.merge that apply:
 *  - the group's id is ours: another group's matches are dropped;
 *  - a match id appears once (the newest page holding it; a moved match is listed once);
 *  - a missing date or venue keeps the one held;
 *  - a "result" dated after today is not believed: shown as pending, no score, no 0-0 in the table.
 * (Refusing an answer that shrank is the fetcher's job: it keeps the previous copy.)
 */

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => !!v && typeof v === 'object' && !Array.isArray(v)
const num = (v: unknown, d = 0) => (typeof v === 'number' && Number.isFinite(v) ? v : d)
const str = (v: unknown) => (typeof v === 'string' ? v : '')

export interface RoundFile { round: Obj; fetchedAt: number }
export interface SchedEntry { at: number; m: unknown }

/** The group's own pages: a match once, from the newest file holding it, with that file's time. */
export function combineRounds(groupId: number, files: RoundFile[], today: string): { rounds: Round[]; matches: Match[]; readAt: Map<number, number> } {
  const byId = new Map<number, Match>()
  const readAt = new Map<number, number>()
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
      readAt.set(m.id, f.fetchedAt)
    }
  }
  return { rounds, matches: [...byId.values()].sort(matchOrder), readAt }
}

/**
 * Both sources together (see the top of this file). A match only the grouped calendar knows has no
 * score; when the grouped state is newer than the page, the page's score counts only if the page
 * itself had a final result (the site writes 0-0 for a match still pending: no result).
 */
export function mergeSources(groupId: number, files: RoundFile[], sched: SchedEntry[], today: string): Match[] {
  const { matches, readAt } = combineRounds(groupId, files, today)
  const byId = new Map(matches.map((m) => [m.id, m]))
  for (const e of sched) {
    const s = parseMatch(e.m)
    if (!s || (s.groupId !== groupId && s.groupId !== 0)) continue
    const at = num(e.at)
    const p = byId.get(s.id)
    let out: Match
    if (!p) {
      out = { ...s, groupId, scoreKnown: false, homeScore: 0, awayScore: 0, winnerId: 0, closed: false }
    } else if (at > (readAt.get(s.id) ?? 0)) {
      const pageFinal = hasResult(p)
      out = {
        ...p,
        roundId: s.roundId || p.roundId,
        roundNumber: s.roundNumber || p.roundNumber,
        start: s.start ?? p.start,
        timeKnown: s.start ? s.timeKnown : p.timeKnown,
        venue: s.venue ?? (s.statusId === 12 ? null : p.venue),
        statusId: s.statusId,
        statusName: s.statusName,
        modified: s.modified,
        modifiedReason: s.modifiedReason || p.modifiedReason,
        scoreKnown: pageFinal && p.scoreKnown,
      }
    } else {
      continue // the page is newer: it stands
    }
    byId.set(s.id, unbelievedResult(out, today))
  }
  return [...byId.values()].sort(matchOrder)
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
  if (!isObj(v) || num(v.v) < 1 || !isObj(v.rounds)) return null
  const info = isObj(v.info) ? v.info : {}
  const meta = isObj(v.meta) ? v.meta : {}
  const ref = isObj(info.ref) ? info.ref : isObj(meta.ref) ? meta.ref : {}
  const groupId = num(ref.groupId, num(info.groupId, num(meta.groupId)))
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
  const sched: SchedEntry[] = (Array.isArray(v.sched) ? v.sched : []).filter(isObj).map((e) => ({ at: num(e.at), m: e.m }))
  const matches = mergeSources(groupId, files, sched, today)
  // The round list: the group's own (dated), or, before its page was ever read, the rounds its
  // grouped matches name (dated by their weekend).
  const rounds = listed.length ? listed : derivedRounds(matches)
  const names = isObj(meta.group) && str(meta.group.name) ? meta.group : isObj(info.group) ? info.group : {}
  const kind = sportKindOf(num(ref.sportId), str(names.sport))
  const rule = pointsRule(kind)
  const rows: ViewRow[] = computeStandings(matches, rule).map((r) => ({
    position: r.position,
    team: { id: r.team.id, name: r.team.name, crest: r.team.crestPath },
    played: r.played, won: r.won, drawn: r.drawn, lost: r.lost, scored: r.scored, conceded: r.conceded, points: r.points,
    form: r.form.map((o) => (o === 'WIN' ? 'W' : o === 'DRAW' ? 'D' : 'L')),
  }))
  const stamps = [...listed.map((r) => num(fetchedAt[String(r.id)])), ...sched.map((e) => e.at)].filter((x) => x > 0)
  return {
    generatedAt,
    ref: { groupId, sportId: num(ref.sportId), competitionId: num(ref.competitionId), categoryId: num(ref.categoryId) },
    group: { id: groupId, name: str(names.name), competition: str(names.competition), category: str(names.category), sport: str(names.sport) },
    sport: kind,
    rounds: rounds.map((r) => ({ ...r, fetchedAt: num(fetchedAt[String(r.id)]) })),
    currentRoundId: num(meta.currentRoundId),
    newestFetch: stamps.length ? Math.max(...stamps) : 0,
    matches: matches.map(toView),
    standings: { rule, measured: ruleMeasured(kind), rows },
  }
}

function derivedRounds(matches: Match[]): Round[] {
  const byId = new Map<number, Round>()
  for (const m of matches) {
    if (m.roundId <= 0) continue
    const day = dayOf(m)
    const held = byId.get(m.roundId)
    const date = day && (!held?.date || day < held.date) ? day : held?.date ?? null
    byId.set(m.roundId, { id: m.roundId, number: m.roundNumber, date })
  }
  return [...byId.values()].map((r) => ({ ...r, date: r.date ? weekendOf(r.date) : null })).sort((a, b) => a.number - b.number)
}
