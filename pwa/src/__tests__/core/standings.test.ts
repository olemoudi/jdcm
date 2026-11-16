import { computeStandings, outcome, pointsRule } from '../../core/standings'
import { match, played, team } from './fixtures'

// Mirrors core/src/test/kotlin/dev/jdcm/core/StandingsTest.kt.
describe('Standings (port)', () => {
  const futsal = pointsRule('FUTSAL')
  const a = team(1, 'CALASANCIO "C"')
  const b = team(2, 'SAGRADOS CORAZONES "A"')
  const c = team(3, 'CLARET "B"')
  const d = team(4, 'BVM IRLANDESAS')

  it('futsal counts 2 for a win, 1 for a draw, 0 for a loss, as the friend’s official table does', () => {
    const rows: Array<[[number, number, number], number]> = [[[6, 0, 2], 12], [[5, 1, 2], 11], [[4, 3, 1], 11], [[3, 1, 4], 7], [[2, 2, 4], 6]]
    for (const [[w, dr, l], pts] of rows) expect(w * futsal.win + dr * futsal.draw + l * futsal.loss).toBe(pts)
  })

  it('table orders by points, then goal difference, then goals scored', () => {
    const t = computeStandings([
      played(1, a, b, 3, 1, 1),
      played(2, c, d, 2, 2, 1),
      played(3, a, c, 1, 1, 2),
      played(4, b, d, 5, 0, 2),
    ], futsal)
    expect(t.map((r) => r.team.id)).toEqual([a, b, c, d].map((x) => x.id))
    expect(t[0]).toMatchObject({ played: 2, won: 1, drawn: 1, lost: 0, scored: 4, conceded: 2, points: 3 })
    expect(t[1].points).toBe(2)
    expect(t[2].points).toBe(2)
    expect(t[0].form).toEqual(['WIN', 'DRAW'])
  })

  it('teams with no result yet still get a row, and a fully level table shares positions', () => {
    const t = computeStandings([match(1, a, b), match(2, c, d)], futsal)
    expect(t).toHaveLength(4)
    expect(t.map((r) => r.position)).toEqual([1, 1, 1, 1])
    expect(t.reduce((s, r) => s + r.played, 0)).toBe(0)
  })

  it('postponed, unscheduled and pending matches do not count', () => {
    const t = computeStandings([
      match(1, a, b, { homeScore: 3, awayScore: 0, status: 'Aplazado', statusId: 1 }),
      match(2, c, d),
      match(3, a, c, { homeScore: 2, awayScore: 0, status: 'Desprogramado', statusId: 12 }),
    ], futsal)
    expect(t.reduce((s, r) => s + r.played, 0)).toBe(0)
  })

  it('the site’s named winner outranks the score (forfeits, committee decisions)', () => {
    const m = match(1, a, b, { winnerId: b.id, closed: true })
    expect(computeStandings([m], futsal)[0].team.id).toBe(b.id)
    expect(outcome(m, a.id)).toBe('LOSS')
  })

  it('basketball gives a point for a loss', () => {
    const t = computeStandings([played(1, a, b, 40, 38)], pointsRule('BASKETBALL'))
    expect(t[0].points).toBe(2)
    expect(t[1].points).toBe(1)
  })

  it('a result counted twice (same match in two fetches) counts once', () => {
    const m = played(1, a, b, 3, 1)
    expect(computeStandings([m, m], futsal)[0].played).toBe(1)
  })

  it('form keeps the last five, oldest first', () => {
    const matches = [1, 2, 3, 4, 5, 6, 7].map((r) => played(r, a, b, r % 2 === 0 ? 1 : 3, 2, r))
    const row = computeStandings(matches, futsal).find((r) => r.team.id === a.id)!
    expect(row.form).toEqual(['WIN', 'LOSS', 'WIN', 'LOSS', 'WIN'])
  })
})
