import { buildGroup, combineRounds, mergeSources, toView, unbelievedResult } from '../../core/group'
import { hasResult, status } from '../../core/model'
import { roundPageFrom, unwrap } from '../../core/parser'
import { matchdayOf, weekendOf } from '../../core/schedule'
import { fixture, match, team } from './fixtures'

/** A round object as the fetcher stores it (v1/groups/<id>/r<round>.json). */
const roundObj = (id: number, number: number, fecha: string, matches: object[]) => ({ Id: id, NumeroJornada: number, Fecha: fecha, Enfrentamientos: matches })
const raw = (id: number, groupId: number, extra: object = {}) => ({
  Id: id, IdGrupo: groupId, IdEquipoLocal: id * 10, EquipoLocal: `L${id}`, IdEquipoVisitante: id * 10 + 1, EquipoVisitante: `V${id}`,
  FechaHora: '2026-11-21T10:00:00', IdEstadoPartido: 0, EstadoPartido: 'Pendiente de Jugar', ResultadoEquipoLocal: 0, ResultadoEquipoVisitante: 0, ...extra,
})

// Mirrors the merge cases of core/src/test/kotlin/dev/jdcm/core/MergeAndPolicyTest.kt that apply to
// stored round files (refusing a shrunk round is the fetcher's job), and the schedule cases.
describe('combining round files (port of GroupSnapshot.merge)', () => {
  it('the group keeps its own id, and other groups’ matches are dropped', () => {
    const { matches } = combineRounds(7, [{ round: roundObj(101, 1, '2026-11-21T00:00:00', [raw(1, 7), raw(9, 8)]), fetchedAt: 1 }], '2026-11-17')
    expect(matches.map((m) => m.id)).toEqual([1])
    expect(matches[0].groupId).toBe(7)
  })

  it('a match that moved to another round is listed once, from the newest file holding it', () => {
    const { matches } = combineRounds(7, [
      { round: roundObj(101, 1, '2026-11-14T00:00:00', [raw(1, 7, { FechaHora: '2026-11-14T10:00:00' })]), fetchedAt: 1 },
      { round: roundObj(102, 2, '2026-11-21T00:00:00', [raw(1, 7), raw(2, 7)]), fetchedAt: 2 },
    ], '2026-11-17')
    expect(matches.filter((m) => m.id === 1)).toHaveLength(1)
    expect(matches.find((m) => m.id === 1)!.roundId).toBe(102)
  })

  it('a result dated in the future is stored as pending, and believed once its day has come', () => {
    const early = match(1, team(1), team(2), { homeScore: 3, awayScore: 1, closed: true, start: '2026-11-21T10:30:00' })
    const odd = unbelievedResult(early, '2026-11-17')
    expect(hasResult(odd)).toBe(false)
    expect(hasResult(unbelievedResult(early, '2026-11-21'))).toBe(true)
  })

  it('a played state typed into next week’s match is stored as pending, never a 0-0', () => {
    const typo = match(1, team(1), team(2), { status: 'Disputado sin Incidencias', statusId: 11, start: '2026-11-28T10:30:00' })
    const m = unbelievedResult(typo, '2026-11-17')
    expect(hasResult(m)).toBe(false)
    expect(m.scoreKnown).toBe(false)
    expect(status(m)).toBe('SCHEDULED')
  })

  it('a rained-off match closed at 0-0 is no result, one ruled with a score is', () => {
    expect(hasResult(match(1, team(1), team(2), { status: 'No Celebrado/Incompleto', statusId: 6, closed: true }))).toBe(false)
    expect(hasResult(match(1, team(1), team(2), { homeScore: 3, status: 'No Celebrado/Incompleto', statusId: 6, closed: true }))).toBe(true)
  })

  it('builds the group the screens show from the fetcher’s files (the real first matchday)', () => {
    const page = unwrap(fixture('j1_g177_resultado.json')) as Record<string, Record<string, unknown>>
    const parsed = roundPageFrom(page)
    const all = {
      v: 1,
      meta: {
        groupId: 177, ref: { groupId: 177, sportId: 2, competitionId: 113, categoryId: 306 },
        group: { name: 'Grupo 1', competition: 'ALEVÍN MIXTO', category: 'ALEVÍN MIXTO', sport: 'Fútbol sala' },
        rounds: parsed.rounds.map((r) => ({ id: r.id, number: r.number, date: r.date })),
        fetchedAt: { 2596: 10, 2597: 10 },
        currentRoundId: 2596,
      },
      rounds: { 2596: page.jornada, 2597: page.next },
    }
    const g = buildGroup(all, '2026-11-17')!
    expect(g.sport).toBe('FUTSAL')
    expect(g.rounds).toHaveLength(9)
    expect(g.matches).toHaveLength(10)
    const won = g.matches.find((m) => m.id === 10379)!
    expect(won).toMatchObject({ status: 'PLAYED', hasResult: true, homeScore: 10, awayScore: 0, winner: 'HOME', matchday: '2026-11-14', start: '2026-11-13T18:00' })
    expect(g.standings.measured).toBe(true)
    expect(g.standings.rows).toHaveLength(10)
    expect(g.standings.rows[0]).toMatchObject({ position: 1, points: 2, form: ['W'] })
    expect(g.newestFetch).toBe(10)
    expect(buildGroup({ v: 1, meta: {}, rounds: {} }, '2026-11-17')).toBeNull()
    expect(buildGroup('<html>', '2026-11-17')).toBeNull()
  })
})

// Mirrors the matchday cases of core's ChangesAndScheduleTest.
describe('Schedule (port)', () => {
  it('anchors a weekend on its Saturday', () => {
    expect(weekendOf('2026-11-13')).toBe('2026-11-14')
    expect(weekendOf('2026-11-15')).toBe('2026-11-14')
  })

  it('a match replayed weeks later is listed on its own weekend, a sunday one with its round', () => {
    const replay = { ...match(1, team(1), team(2)), start: '2026-12-13T11:00:00' }
    expect(matchdayOf(replay)).toBe('2026-12-12')
    const sunday = { ...match(2, team(1), team(2)), start: '2026-11-15T11:00:00' }
    expect(matchdayOf(sunday)).toBe('2026-11-14')
  })
})

describe('the grouped calendar and the group’s own pages together', () => {
  const page = (state: object, at: number) => ({ round: roundObj(102, 2, '2026-11-21T00:00:00', [raw(1, 7, state)]), fetchedAt: at })
  const sched = (state: object, at: number) => ({ at, m: raw(1, 7, state) })
  const PENDING = { IdEstadoPartido: 0, EstadoPartido: 'Pendiente de Jugar', ResultadoEquipoLocal: 0, ResultadoEquipoVisitante: 0 }
  const PLAYED = { IdEstadoPartido: 11, EstadoPartido: 'Disputado sin Incidencias' }

  it('takes the state from the grouped calendar when it is newer, the score only from the page', () => {
    const [m] = mergeSources(7, [page({ ...PLAYED, ResultadoEquipoLocal: 3, ResultadoEquipoVisitante: 1, IdEquipoGanador: 10 }, 1)],
      [sched({ ...PLAYED, FechaHora: '2026-11-21T11:00:00' }, 2)], '2026-11-22')
    expect(m.start).toBe('2026-11-21T11:00:00')
    expect(hasResult(m)).toBe(true)
    expect([m.homeScore, m.awayScore]).toEqual([3, 1])
  })

  it('a grouped "played" without a score is «Resultado pendiente», not the page’s placeholder 0-0', () => {
    const [m] = mergeSources(7, [page(PENDING, 1)], [sched(PLAYED, 2)], '2026-11-22')
    expect(status(m)).toBe('PLAYED')
    expect(hasResult(m)).toBe(false)
    const v = toView(m)
    expect(v).toMatchObject({ status: 'PLAYED', hasResult: false, homeScore: null })
  })

  it('a rain-off the grouped calendar saw after the page was read wins', () => {
    const [m] = mergeSources(7, [page(PENDING, 1)], [sched({ IdEstadoPartido: 6, EstadoPartido: 'No Celebrado/Incompleto' }, 2)], '2026-11-22')
    expect(status(m)).toBe('NOT_HELD')
  })

  it('an older grouped state does not undo a newer page', () => {
    const [m] = mergeSources(7, [page({ ...PLAYED, ResultadoEquipoLocal: 2, ResultadoEquipoVisitante: 2 }, 5)], [sched(PENDING, 2)], '2026-11-22')
    expect(status(m)).toBe('PLAYED')
    expect(hasResult(m)).toBe(true)
  })

  it('a match only the grouped calendar knows has no score, and another group’s match is dropped', () => {
    const ms = mergeSources(7, [], [sched(PLAYED, 1), { at: 1, m: raw(9, 8) }], '2026-11-22')
    expect(ms.map((m) => m.id)).toEqual([1])
    expect(ms[0].scoreKnown).toBe(false)
  })

  it('a group read only through the grouped calendar still has its rounds, matches and table', () => {
    const g = buildGroup({ v: 1, info: { ref: { groupId: 7, sportId: 2, competitionId: 1, categoryId: 1 }, group: { name: 'Grupo 1' } },
      meta: {}, rounds: {}, sched: [sched({ ...PENDING, IdJornada: 101, NumeroJornada: 1 }, 1)] }, '2026-11-17')!
    expect(g.rounds).toEqual([{ id: 101, number: 1, date: '2026-11-21', fetchedAt: 0 }])
    expect(g.matches).toHaveLength(1)
    expect(g.standings.rows).toHaveLength(2)
  })
})
