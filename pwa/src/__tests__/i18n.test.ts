import { dictionaries, defaultLang, translate } from '../lib/i18n'

describe('i18n', () => {
  it('Spanish and English hold the same keys and the same placeholders', () => {
    const es = dictionaries.es as Record<string, string>
    const en = dictionaries.en as Record<string, string>
    expect(Object.keys(en).sort()).toEqual(Object.keys(es).sort())
    const ph = (s: string) => (s.match(/\{\d\}/g) ?? []).sort().join(',')
    for (const k of Object.keys(es)) expect(`${k}: ${ph(en[k])}`).toBe(`${k}: ${ph(es[k])}`)
  })

  it('is Spanish unless the phone speaks English', () => {
    expect(defaultLang('es-ES')).toBe('es')
    expect(defaultLang('ca-ES')).toBe('es')
    expect(defaultLang('en-GB')).toBe('en')
    expect(defaultLang(null)).toBe('es')
  })

  it('fills placeholders in order', () => {
    expect(translate('es', 'position_points', 3, 6)).toBe('3º · 6 pts')
    expect(translate('en', 'freshness_pace', 3, 7, 22)).toBe('Refreshed about every 3 h in the daytime (7–22 h).')
  })
})
