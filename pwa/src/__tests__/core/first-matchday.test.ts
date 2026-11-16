import { hasResult, isCancelled, status } from '../../core/model'
import { match, roundPage } from '../../core/parser'
import { computeStandings, pointsRule } from '../../core/standings'
import { fixture } from './fixtures'

// Mirrors core/src/test/kotlin/dev/jdcm/core/FirstMatchdayTest.kt: the real answers of the first
// matchday (2026-11-14, rain). The change-notification cases have no PWA counterpart.
describe('the first matchday (port)', () => {
  it('a match played with incidents and no score is not a 0-0', () => {
    const p = roundPage(fixture('j1_g136_incidencias.json'))
    const incidents = p.matches.find((m) => m.id === 8845)!
    expect(status(incidents)).toBe('PLAYED')
    expect(incidents.scoreKnown).toBe(false)
    expect(hasResult(incidents)).toBe(false)
    const playedOne = p.matches.find((m) => m.id === 8843)!
    expect(hasResult(playedOne)).toBe(true)
    expect([playedOne.homeScore, playedOne.awayScore]).toEqual([32, 11])
    // Only the played one counts: four teams with one game each would be wrong.
    const rows = computeStandings(p.matches, pointsRule('BASKETBALL'))
    expect(rows.filter((r) => r.played > 0)).toHaveLength(2)
  })

  it('rain-offs are not held, have no result, and are cancelled', () => {
    const off = roundPage(fixture('j1_g170_lluvia.json')).matches.filter((m) => status(m) === 'NOT_HELD')
    expect(off).toHaveLength(4)
    expect(off.every((m) => !hasResult(m) && isCancelled(m) && !m.scoreKnown)).toBe(true)
    const unscheduled = roundPage(fixture('j1_g220_desprogramado.json')).matches.find((m) => m.id === 11867)!
    expect(status(unscheduled)).toBe('UNSCHEDULED')
    expect(isCancelled(unscheduled)).toBe(true)
  })

  it('a played state without score fields (the grouped calendar) is no result', () => {
    const m = match({ Id: 1, IdEquipoLocal: 2, IdEquipoVisitante: 3, IdEstadoPartido: 11, EstadoPartido: 'Disputado sin Incidencias', FechaHora: '2026-11-13T18:00:00' })!
    expect(status(m)).toBe('PLAYED')
    expect(hasResult(m)).toBe(false)
  })

  it('the result of the first matchday is in today’s answer', () => {
    const m = roundPage(fixture('j1_g177_resultado.json')).matches.find((x) => x.id === 10379)!
    expect(hasResult(m)).toBe(true)
    expect([m.homeScore, m.awayScore]).toEqual([10, 0])
  })
})
