import { buildGroup } from '../core/group'
import { buildCatalogSport, parseIndex } from '../lib/data'
import { realGroup, realJson } from './helpers'

describe('the published data (the home fetcher’s real output), read defensively', () => {
  it('builds a group from its round files: matches, rounds, standings and the rule', () => {
    const g = realGroup(177)
    expect(g.sport).toBe('FUTSAL')
    expect(g.group.name).toBe('Grupo 1')
    expect(g.rounds).toHaveLength(9)
    expect(g.currentRoundId).toBe(2597)
    expect(g.standings.measured).toBe(true)
    expect(g.standings.rule).toEqual({ win: 2, draw: 1, loss: 0, hasDraws: true })
    expect(g.standings.rows).toHaveLength(10)
    const played = g.matches.find((m) => m.id === 10379)!
    expect(played).toMatchObject({ status: 'PLAYED', hasResult: true, homeScore: 10, awayScore: 0, winner: 'HOME', matchday: '2026-11-14' })
    expect(new Set(g.matches.map((m) => m.id)).size).toBe(g.matches.length) // each match once
  })

  it('states the basketball rule as an assumption, without draws', () => {
    const g = realGroup(139)
    expect(g.standings.measured).toBe(false)
    expect(g.standings.rule.hasDraws).toBe(false)
  })

  it('shows the first matchday’s rain-offs as not held, without a score', () => {
    const notHeld = realGroup(177).matches.filter((m) => m.status === 'NOT_HELD')
    expect(notHeld.length).toBeGreaterThan(0)
    expect(notHeld.every((m) => !m.hasResult && m.homeScore === null)).toBe(true)
  })

  it('skips a malformed round, not the group', () => {
    const raw = realJson('v1/groups/177/all.json') as { rounds: Record<string, unknown> }
    const broken = { ...raw, rounds: { ...raw.rounds, 2604: '<html>', 2603: { Id: 9 } } }
    const g = buildGroup(broken, '2026-11-17')!
    expect(g.matches.length).toBeLessThan(realGroup(177).matches.length)
    expect(g.matches.find((m) => m.id === 10379)).toBeTruthy()
  })

  it('refuses what is not the data: an error page, an unknown schema, nothing', () => {
    expect(buildGroup('<html>', '2026-11-17')).toBeNull()
    expect(buildGroup({ v: 0, meta: {}, rounds: {} }, '2026-11-17')).toBeNull()
    expect(parseIndex(null)).toBeNull()
    expect(parseIndex({ v: 1 })).toBeNull()
    expect(buildCatalogSport({ v: 1, competitions: '<html>' }, 2, new Set())).toBeNull()
  })

  it('reads the index: the groups, when it was published, the refresh pace', () => {
    const index = parseIndex(realJson('v1/index.json'))!
    expect(index.groups.map((g) => g.groupId)).toEqual([177, 139])
    expect(index.refreshHours).toBe(3)
    expect(index.refreshToHour).toBe(19)
    expect(index.generatedMs).toBeGreaterThan(0)
    expect(index.groups[0].newestFetch).toBeGreaterThan(0)
  })

  it('builds the catalogue of a sport: every group with its teams, the followed ones marked', () => {
    const futsal = buildCatalogSport(realJson('v1/catalog/2.json'), 2, new Set([177]))!
    const groups = futsal.competitions.flatMap((c) => c.categories.flatMap((k) => k.groups))
    expect(groups).toHaveLength(42)
    expect(groups.filter((g) => g.followed).map((g) => g.id)).toEqual([177])
    expect(groups.find((g) => g.id === 177)!.teams.map((t) => t.name)).toContain('FEC SANTA JOAQUINA DE VEDRUNA')
    const alevin = futsal.competitions.find((c) => c.id === 113)!
    expect(alevin.categories[0].groups.map((g) => g.name).slice(0, 3)).toEqual(['Grupo 1', 'Grupo 2', 'Grupo 3'])
    const all = [1, 2, 3].map((s) => buildCatalogSport(realJson(`v1/catalog/${s}.json`), s, new Set())!)
    expect(all.reduce((n, s) => n + s.competitions.flatMap((c) => c.categories.flatMap((k) => k.groups)).length, 0)).toBe(109)
  })
})
