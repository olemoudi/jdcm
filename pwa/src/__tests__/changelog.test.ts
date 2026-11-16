import { changelogFor, parseVersionInfo } from '../lib/changelog'

describe('the update changelog (GTD25)', () => {
  const info = { commit: 'c', message: 'third', log: [{ h: 'c', s: 'third' }, { h: 'b', s: 'second' }, { h: 'a', s: 'first' }] }

  it('lists the commits newer than the running one', () => {
    expect(changelogFor(info, 'a').map((c) => c.h)).toEqual(['c', 'b'])
    expect(changelogFor(info, 'c')).toEqual([])
  })

  it('falls back to what it has, then to the headline', () => {
    expect(changelogFor(info, 'zzz')).toHaveLength(3)
    expect(changelogFor({ commit: 'c', message: 'only' }, 'a')).toEqual([{ h: 'c', s: 'only' }])
  })

  it('rejects what is not a version file and drops bad log entries', () => {
    expect(parseVersionInfo('<html>')).toBeNull()
    expect(parseVersionInfo({ message: 'x' })).toBeNull()
    expect(parseVersionInfo({ commit: 'c', log: [{ h: 'a', s: 'ok' }, { h: 1 }, null] })!.log).toEqual([{ h: 'a', s: 'ok' }])
  })
})
