import { parseRoute, routeHash, type Route } from '../lib/router'

describe('hash routes', () => {
  it('round-trip', () => {
    const routes: Route[] = [
      { name: 'home' }, { name: 'tables' }, { name: 'settings' },
      { name: 'group', groupId: 177, tab: 'standings', kidId: 'k1' },
      { name: 'group', groupId: 139, tab: 'calendar' },
      { name: 'team', groupId: 177, teamId: 1194 },
      { name: 'kid' }, { name: 'kid', kidId: 'abc' }, { name: 'kid', groupId: 177, teamId: 1194 },
    ]
    for (const r of routes) expect(parseRoute(routeHash(r))).toEqual({ kidId: undefined, ...r, ...(r.name === 'kid' ? { groupId: r.groupId, teamId: r.teamId } : {}) })
  })

  it('sends anything unknown home', () => {
    expect(parseRoute('#/grupo/abc')).toEqual({ name: 'home' })
    expect(parseRoute('')).toEqual({ name: 'home' })
    expect(parseRoute('#/nada')).toEqual({ name: 'home' })
  })
})
