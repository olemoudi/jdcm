import { hasResult, type Match, type SportKind, type Team } from './model'

/*
 * A port of core's Standings and PointsRule (core/src/main/kotlin/dev/jdcm/core/standings): the
 * public site has no standings (its Clasificaciones page needs a login), so they are computed from
 * the published results. Futsal is measured (an official table: W 2, D 1, L 0, then goal
 * difference); basketball and volleyball (W 2, L 1) are an assumption stated under the table.
 */

export interface PointsRule { win: number; draw: number; loss: number; hasDraws: boolean }

export function pointsRule(kind: SportKind): PointsRule {
  switch (kind) {
    case 'FUTSAL': return { win: 2, draw: 1, loss: 0, hasDraws: true }
    case 'BASKETBALL':
    case 'VOLLEYBALL': return { win: 2, draw: 1, loss: 1, hasDraws: false }
    default: return { win: 2, draw: 1, loss: 0, hasDraws: true }
  }
}

/** Measured from an official table (futsal only); otherwise the rule is an assumption. */
export const ruleMeasured = (kind: SportKind) => kind === 'FUTSAL'

export type Outcome = 'WIN' | 'DRAW' | 'LOSS'

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
  /** Oldest first, at most the last five played. */
  form: Outcome[]
}

/** The winner the site names wins (a forfeit, a committee decision); without one, the score decides. */
export function outcome(m: Match, teamId: number): Outcome {
  const home = m.home?.id === teamId
  const mine = home ? m.homeScore : m.awayScore
  const theirs = home ? m.awayScore : m.homeScore
  if (m.winnerId === teamId) return 'WIN'
  if (m.winnerId !== 0 && (m.winnerId === m.home?.id || m.winnerId === m.away?.id)) return 'LOSS'
  if (mine > theirs) return 'WIN'
  if (mine < theirs) return 'LOSS'
  return 'DRAW'
}

const collator = new Intl.Collator('es-ES', { sensitivity: 'base' })

/**
 * Every team that appears in [matches] gets a row (a table exists from matchday one), counted only
 * from matches with a published result, each match once. Order: points, goal difference, goals
 * scored, then name. Teams level on every criterion share a position ("3, 3, 5").
 */
export function computeStandings(matches: Match[], rule: PointsRule): StandingRow[] {
  const teams = new Map<number, Team>()
  for (const m of matches) {
    if (m.home && !teams.has(m.home.id)) teams.set(m.home.id, m.home)
    if (m.away && !teams.has(m.away.id)) teams.set(m.away.id, m.away)
  }
  type Tally = { won: number; drawn: number; lost: number; scored: number; conceded: number; results: Array<[Match, Outcome]> }
  const tallies = new Map<number, Tally>()
  for (const id of teams.keys()) tallies.set(id, { won: 0, drawn: 0, lost: 0, scored: 0, conceded: 0, results: [] })
  const seen = new Set<number>()
  for (const m of matches) {
    if (!hasResult(m) || seen.has(m.id)) continue
    seen.add(m.id)
    const h = m.home
    const a = m.away
    if (!h || !a) continue
    const add = (t: Tally, o: Outcome, mine: number, theirs: number) => {
      if (o === 'WIN') t.won++
      else if (o === 'DRAW') t.drawn++
      else t.lost++
      t.scored += mine
      t.conceded += theirs
      t.results.push([m, o])
    }
    add(tallies.get(h.id)!, outcome(m, h.id), m.homeScore, m.awayScore)
    add(tallies.get(a.id)!, outcome(m, a.id), m.awayScore, m.homeScore)
  }
  const byStart = (x: [Match, Outcome], y: [Match, Outcome]) => {
    const sx = x[0].start
    const sy = y[0].start
    if (sx !== sy) {
      if (sx == null) return -1 // Kotlin's compareBy puts nulls first
      if (sy == null) return 1
      return sx < sy ? -1 : 1
    }
    return x[0].roundNumber - y[0].roundNumber
  }
  const rows: StandingRow[] = [...teams.values()].map((t) => {
    const x = tallies.get(t.id)!
    return {
      position: 0,
      team: t,
      played: x.won + x.drawn + x.lost,
      won: x.won,
      drawn: x.drawn,
      lost: x.lost,
      scored: x.scored,
      conceded: x.conceded,
      points: x.won * rule.win + x.drawn * rule.draw + x.lost * rule.loss,
      form: [...x.results].sort(byStart).slice(-5).map((r) => r[1]),
    }
  })
  const diff = (r: StandingRow) => r.scored - r.conceded
  rows.sort((a, b) => b.points - a.points || diff(b) - diff(a) || b.scored - a.scored || collator.compare(a.team.name, b.team.name))
  rows.forEach((r, i) => {
    const prev = rows[i - 1]
    const tied = prev && prev.points === r.points && diff(prev) === diff(r) && prev.scored === r.scored
    r.position = tied ? prev.position : i + 1
  })
  return rows
}
