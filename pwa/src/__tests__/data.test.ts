import { parseCatalog, parseGroup, parseIndex, parseMatch } from '../lib/data'
import { realGroup, realJson } from './helpers'

describe('the published data, read defensively', () => {
  it('reads a real group: matches, rounds, standings and the rule', () => {
    const g = realGroup(177)
    expect(g.sport).toBe('FUTSAL')
    expect(g.rounds).toHaveLength(9)
    expect(g.standings.measured).toBe(true)
    expect(g.standings.rule).toEqual({ win: 2, draw: 1, loss: 0, hasDraws: true })
    expect(g.standings.rows).toHaveLength(10)
    const played = g.matches.find((m) => m.id === 10379)!
    expect(played).toMatchObject({ status: 'PLAYED', hasResult: true, homeScore: 10, awayScore: 0, winner: 'HOME', matchday: '2026-11-14' })
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

  it('drops a malformed match, not the group', () => {
    const raw = realJson('groups/177.json') as { matches: unknown[] }
    const broken = { ...raw, matches: [null, 42, { id: 'x' }, { id: 5 }, ...raw.matches] }
    expect(parseGroup(broken)!.matches).toHaveLength(raw.matches.length)
  })

  it('never shows a score that is not a result', () => {
    expect(parseMatch({ id: 1, home: { id: 1, name: 'A' }, away: { id: 2, name: 'B' }, hasResult: true, homeScore: null, awayScore: 3 })!.hasResult).toBe(false)
    expect(parseMatch({ id: 1, home: { id: 1, name: 'A' }, away: { id: 2, name: 'B' }, hasResult: false, homeScore: 0, awayScore: 0 })!.homeScore).toBeNull()
    expect(parseMatch({ id: 1, status: 'WHATEVER', home: { id: 1, name: 'A' } })!.status).toBe('UNKNOWN')
  })

  it('refuses what is not the data: an error page, an unknown schema, nothing', () => {
    expect(parseGroup('<html>')).toBeNull()
    expect(parseGroup({ v: 0, matches: [] })).toBeNull()
    expect(parseGroup({ v: 1, matches: [], ref: {} })).toBeNull()
    expect(parseIndex(null)).toBeNull()
    expect(parseCatalog({ v: 1 })).toBeNull()
  })

  it('reads the index and the catalogue of every team', () => {
    const index = parseIndex(realJson('index.json'))!
    expect(index.groups.map((g) => g.groupId)).toEqual([177, 139])
    expect(index.refreshHours).toBe(3)
    expect(index.generatedMs).toBeGreaterThan(0)
    const catalog = parseCatalog(realJson('catalog.json'))!
    expect(catalog.sports.map((s) => s.kind).sort()).toEqual(['BASKETBALL', 'FUTSAL', 'VOLLEYBALL'])
    const groups = catalog.sports.flatMap((s) => s.competitions.flatMap((c) => c.categories.flatMap((k) => k.groups)))
    expect(groups).toHaveLength(109)
    expect(groups.filter((g) => g.followed).map((g) => g.id).sort()).toEqual([139, 177])
  })
})
