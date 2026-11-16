import { __resetMemoryStorage, readRaw, storageKey, writeRaw } from '../lib/storage'
import { loadKids, nextColor, parseKids, saveKids, type Kid } from '../lib/kids'

function fakeStorage() {
  const m = new Map<string, string>()
  return {
    map: m,
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => { m.set(k, v) },
    removeItem: (k: string) => { m.delete(k) },
  }
}

const kid: Kid = {
  id: 'a', name: 'Lucía', color: 1, sportId: 2, sportKind: 'FUTSAL', competitionId: 113, competitionName: 'ALEVÍN MIXTO',
  categoryId: 306, categoryName: 'ALEVÍN MIXTO', groupId: 177, groupName: 'Grupo 1', teamId: 1194, teamName: 'FEC', crest: '',
}

describe('storage, namespaced by channel', () => {
  beforeEach(() => __resetMemoryStorage())

  it('keys every value with the channel: the alpha never writes what the beta reads', () => {
    const s = fakeStorage()
    Object.defineProperty(globalThis, 'localStorage', { value: s, configurable: true })
    saveKids([kid])
    expect([...s.map.keys()]).toEqual(['jdcm:beta:kids'])
    expect(storageKey('kids', 'alpha')).toBe('jdcm:alpha:kids')
    expect(loadKids()).toEqual([kid])
    // What another channel stored is invisible here.
    s.setItem('jdcm:alpha:kids', JSON.stringify([{ ...kid, id: 'b' }]))
    expect(loadKids().map((k) => k.id)).toEqual(['a'])
  })

  it('keeps working in memory when storage throws (private mode, blocked site data)', () => {
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: { getItem: () => { throw new Error('denied') }, setItem: () => { throw new Error('denied') }, removeItem: () => { throw new Error('denied') } },
    })
    writeRaw('theme', 'light')
    expect(readRaw('theme')).toBe('light')
    saveKids([kid])
    expect(loadKids()).toEqual([kid])
  })

  it('drops a malformed child, never the list', () => {
    expect(parseKids([kid, { id: 'x' }, null, { ...kid, id: 'c', teamId: 0 }])).toEqual([kid])
    expect(parseKids('nope')).toBeNull()
  })

  it('gives each new child a colour nobody has', () => {
    expect(nextColor([])).toBe(0)
    expect(nextColor([kid])).toBe(0)
    expect(nextColor([{ ...kid, color: 0 }, kid])).toBe(2)
  })
})
