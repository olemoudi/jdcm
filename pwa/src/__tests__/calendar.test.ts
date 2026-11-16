import { directions, matchIcs, shareText } from '../lib/calendar'
import { Format } from '../lib/format'
import { realGroup } from './helpers'

const g = realGroup(177)
const fmt = new Format('es')
const timed = g.matches.find((m) => m.timeKnown && !m.hasResult && m.venue?.lat != null)!

describe('add to calendar, directions, share', () => {
  it('writes the match in Madrid time, whatever the phone’s zone', () => {
    const ics = matchIcs(timed, fmt, 'Lucía', Date.UTC(2026, 10, 16))!
    const start = timed.start!.replace(/[-:]/g, '')
    expect(ics).toContain(`DTSTART;TZID=Europe/Madrid:${start}00`)
    expect(ics).toContain('BEGIN:VTIMEZONE')
    expect(ics).toContain(`UID:jdcm-match-${timed.id}@makensi.es`)
    expect(ics).toMatch(/SUMMARY:Lucía: /)
    expect(ics.split('\r\n').every((l) => l.length <= 75)).toBe(true)
  })

  it('makes an all-day event when the time is not set yet', () => {
    const ics = matchIcs({ ...timed, timeKnown: false, start: `${timed.day}T00:00` }, fmt, '')!
    expect(ics).toContain(`DTSTART;VALUE=DATE:${timed.day!.replace(/-/g, '')}`)
  })

  it('opens Apple Maps on an iPhone and Google Maps elsewhere', () => {
    const ios = directions(timed, true)!
    expect(ios.primary).toMatch(/^https:\/\/maps\.apple\.com\/\?daddr=40\./)
    expect(ios.secondary).toMatch(/^https:\/\/www\.google\.com\/maps\/dir\//)
    expect(directions(timed, false)!.primary).toMatch(/google/)
  })

  it('shares when, who and where', () => {
    const text = shareText(timed, fmt)
    expect(text).toContain(timed.home!.name)
    expect(text).toContain('google.com/maps')
  })
})
