import type { Match, Venue } from './data'
import { translate, type Key, type Lang } from './i18n'

/** Dates and times the way a family reads them: «Sáb 14 nov · 10:30», «hora por confirmar». */
export class Format {
  readonly lang: Lang
  private readonly locale: string

  constructor(lang: Lang) {
    this.lang = lang
    this.locale = lang === 'en' ? 'en-GB' : 'es-ES'
  }

  t(key: Key, ...args: Array<string | number>): string {
    return translate(this.lang, key, ...args)
  }

  private date(day: string): Date {
    return new Date(Date.UTC(Number(day.slice(0, 4)), Number(day.slice(5, 7)) - 1, Number(day.slice(8, 10)), 12))
  }

  /** «Sáb». */
  weekdayShort(day: string): string {
    const s = new Intl.DateTimeFormat(this.locale, { weekday: 'short', timeZone: 'UTC' }).format(this.date(day)).replace('.', '')
    return s.charAt(0).toUpperCase() + s.slice(1)
  }

  /** «nov». */
  monthShort(day: string): string {
    return new Intl.DateTimeFormat(this.locale, { month: 'short', timeZone: 'UTC' }).format(this.date(day)).replace('.', '').toLowerCase()
  }

  /** «Noviembre 2026». */
  monthYear(day: string): string {
    const m = new Intl.DateTimeFormat(this.locale, { month: 'long', timeZone: 'UTC' }).format(this.date(day))
    return `${m.charAt(0).toUpperCase()}${m.slice(1)} ${day.slice(0, 4)}`
  }

  /** «Sáb 14 nov». */
  day(day: string): string {
    return `${this.weekdayShort(day)} ${Number(day.slice(8, 10))} ${this.monthShort(day)}`
  }

  /** «14 nov». */
  dayMonth(day: string): string {
    return `${Number(day.slice(8, 10))} ${this.monthShort(day)}`
  }

  time(m: Match): string {
    return m.timeKnown && m.start ? m.start.slice(11, 16) : '--:--'
  }

  /** «Sáb 14 nov · 10:30», «Sáb 14 nov · hora por confirmar», «Sin fecha». */
  whenLine(m: Match): string {
    if (!m.day) return this.t('no_date')
    return `${this.day(m.day)} · ${m.timeKnown && m.start ? m.start.slice(11, 16) : this.t('time_tbc')}`
  }

  statusLabel(m: Match): string | null {
    switch (m.status) {
      case 'POSTPONED': return this.t('status_postponed')
      case 'UNSCHEDULED': return this.t('status_unscheduled')
      case 'NOT_HELD': return this.t('status_not_held')
      case 'PLAYED': return this.t(m.hasResult ? 'status_final' : 'status_no_score')
      default: return m.hasResult ? this.t('status_final') : null
    }
  }

  venueLine(v: Venue | null): string {
    if (!v) return this.t('no_venue')
    const place = [v.address, v.town].filter(Boolean).join(', ')
    return [v.name, place].filter(Boolean).join(' · ')
  }

  /** «hace 5 min», «hace 2 h». */
  age(ms: number, nowMs: number = Date.now()): string {
    if (ms <= 0) return this.t('age_never')
    const mins = Math.max(0, Math.floor((nowMs - ms) / 60_000))
    if (mins < 1) return this.t('age_now')
    if (mins < 60) return this.t('age_minutes', mins)
    if (mins < 24 * 60) return this.t('age_hours', Math.floor(mins / 60))
    return this.t('age_days', Math.floor(mins / (24 * 60)))
  }

  /** "01:10:00" → 70; null when absent or zero. */
  static durationMinutes(m: Match): number | null {
    const p = m.duration.split(':')
    const v = Number(p[0]) * 60 + Number(p[1])
    return Number.isFinite(v) && v > 0 ? v : null
  }

  /** Competition names come in capitals («ALEVÍN MIXTO»): «Alevín mixto». */
  static sentence(s: string): string {
    const l = s.toLocaleLowerCase('es')
    return l.charAt(0).toLocaleUpperCase('es') + l.slice(1)
  }
}

/** Orientative ages of the brackets (the app's explorer). */
export function ageHint(name: string): string | null {
  const n = name.toLowerCase()
  if (n.includes('prebenj')) return '6-7'
  if (n.includes('benjam')) return '8-9'
  if (n.includes('alev')) return '10-11'
  if (n.includes('infantil')) return '12-13'
  if (n.includes('cadete')) return '14-15'
  if (n.includes('juvenil')) return '16-17'
  return null
}
