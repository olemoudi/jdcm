import { hasResult, isBye, restingTeam, status, statusOf, hasPosition } from '../../core/model'
import { groupedRound, match, roundPage, UnexpectedResponse } from '../../core/parser'
import { fixture } from './fixtures'

// Mirrors core/src/test/kotlin/dev/jdcm/core/SiteParserTest.kt (the list parsers for the explorer's
// combos are not ported: the PWA's catalogue comes from the grouped calendar).
describe('SiteParser (port)', () => {
  it('a round page is a JSON string holding JSON, with the jornada list and its matches', () => {
    const page = roundPage(fixture('jornada_grupo.json'))
    expect(page.rounds).toHaveLength(9)
    expect(page.round?.number).toBe(1)
    expect(page.round?.date).toBe('2026-11-14')
    expect(page.previous).toBeNull() // jornada 0 on the site means none
    expect(page.next?.number).toBe(2)
    expect(page.group?.name).toBe('Grupo 1')
    expect(page.group?.competition).toBe('ALEVÍN MIXTO')

    const first = page.matches.find((m) => m.id === 10379)!
    expect(first.home?.name).toBe('FEC SANTA JOAQUINA DE VEDRUNA')
    expect(first.away?.name).toBe('NUESTRA SEÑORA DE LA CONSOLACIÓN "A"')
    expect(first.start).toBe('2026-11-13T18:00:00')
    expect(first.timeKnown).toBe(true)
    expect(first.venue?.name).toBe('CAMPO 1 IGLESIA - EXTERIOR')
    expect(first.venue?.postcode).toBe('28034')
    expect(hasPosition(first.venue!)).toBe(true)
    expect(status(first)).toBe('SCHEDULED')
    expect(hasResult(first)).toBe(false) // 0-0 pending is not a result
    expect(first.home?.crestPath).toBe('/documentos/wallimages/imagepath/Emblema_0061.jpg')
  })

  it('a team resting that matchday is a bye with the date only', () => {
    const page = roundPage(fixture('jornada_grupo_baloncesto.json'))
    const byes = page.matches.filter(isBye)
    expect(byes).toHaveLength(1)
    expect(restingTeam(byes[0])?.name).toBe('EL VALLE LAS TABLAS')
    expect(byes[0].timeKnown).toBe(false) // 00:00 means no time set
  })

  it('the grouped calendar lists every group of a category', () => {
    const grouped = groupedRound(fixture('calendario_agrupado.json'))
    expect(grouped).toHaveLength(1)
    expect(grouped[0].competitionId).toBe(113)
    expect(grouped[0].categories).toHaveLength(1)
    expect(grouped[0].categories[0].categoryId).toBe(306)
    const groups = grouped[0].categories[0].groups
    expect(groups).toHaveLength(8)
    expect(groups.every((g) => g.matches.length > 0)).toBe(true)
  })

  it('a login page or garbage is an unexpected response, not an empty list', () => {
    expect(() => roundPage('<!DOCTYPE html><html>marcaPaginaInicioSesion</html>')).toThrow(UnexpectedResponse)
    expect(() => roundPage('{"error":1}')).toThrow(UnexpectedResponse)
    expect(() => groupedRound('{"error":1}')).toThrow(UnexpectedResponse)
  })

  it('missing and null fields never throw', () => {
    const m = match(JSON.parse('{"Id":5,"IdEquipoLocal":1,"EquipoLocal":null,"IdEquipoVisitante":2,"FechaHora":null,"Campo":"Sin campo asignado","ResultadoEquipoLocal":"3"}'))
    expect(m).not.toBeNull()
    expect(m!.home?.name).toBe('Equipo 1')
    expect(m!.start).toBeNull()
    expect(m!.venue).toBeNull()
    expect(m!.homeScore).toBe(3)
  })

  it('status names map to what a family needs to tell apart', () => {
    expect(statusOf(1, 'Aplazado')).toBe('POSTPONED')
    expect(statusOf(12, 'Desprogramado')).toBe('UNSCHEDULED')
    expect(statusOf(6, 'No Celebrado/Incompleto')).toBe('NOT_HELD')
    expect(statusOf(3, 'Jugado')).toBe('PLAYED')
    expect(statusOf(9, 'Finalizado')).toBe('PLAYED')
    expect(statusOf(42, 'Algo nuevo')).toBe('UNKNOWN')
  })

  it('a desprogramado match is never marked modified', () => {
    expect(match({ Id: 5, IdEquipoLocal: 1, IdEquipoVisitante: 2, IdEstadoPartido: 12, EstadoPartido: 'Desprogramado', CambioAdministrador: true })!.modified).toBe(false)
    expect(match({ Id: 6, IdEquipoLocal: 1, IdEquipoVisitante: 2, SolicitudesModificaciones: [{ IdEstadoSolicitud: 6 }] })!.modified).toBe(true)
  })

  it('a score sent as an empty string is no score', () => {
    const raw = fixture('jornada_grupo.json')
    const blank = raw.replace('ResultadoEquipoLocal\\":0,', 'ResultadoEquipoLocal\\":\\"\\",')
    expect(blank).not.toBe(raw) // the fixture still has that shape
    const page = roundPage(blank)
    const all = [...page.matches, ...page.neighbours.flatMap((n) => n.matches)]
    expect(all.filter((m) => !m.scoreKnown)).toHaveLength(1)
  })

  it('one answer brings the next round with its matches (previous is the site’s Jornada 0 placeholder)', () => {
    const parsed = roundPage(fixture('jornada_grupo.json'))
    expect(parsed.neighbours.map((n) => n.round.number)).toEqual([2])
    expect(parsed.neighbours[0].matches).toHaveLength(5)
  })
})
