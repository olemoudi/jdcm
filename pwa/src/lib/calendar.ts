import type { Match } from './data'
import { Format } from './format'

/*
 * An .ics for one match. iOS Safari opens a text/calendar download in its "Add to Calendar" sheet;
 * other browsers download it for the calendar app. Times are written as Madrid wall clock with a
 * TZID and a VTIMEZONE, so the phone's own zone never shifts them.
 */

const VTIMEZONE = [
  'BEGIN:VTIMEZONE',
  'TZID:Europe/Madrid',
  'BEGIN:DAYLIGHT',
  'TZOFFSETFROM:+0100',
  'TZOFFSETTO:+0200',
  'TZNAME:CEST',
  'DTSTART:19700329T020000',
  'RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU',
  'END:DAYLIGHT',
  'BEGIN:STANDARD',
  'TZOFFSETFROM:+0200',
  'TZOFFSETTO:+0100',
  'TZNAME:CET',
  'DTSTART:19701025T030000',
  'RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU',
  'END:STANDARD',
  'END:VTIMEZONE',
]

function esc(s: string): string {
  return s.replace(/\\/g, '\\\\').replace(/;/g, '\;').replace(/,/g, '\\,').replace(/\r?\n/g, '\\n')
}

/** RFC 5545 lines are folded at 75 octets (approximated by characters). */
function fold(line: string): string {
  const out: string[] = []
  let rest = line
  while (rest.length > 74) {
    out.push(rest.slice(0, 74))
    rest = ' ' + rest.slice(74)
  }
  out.push(rest)
  return out.join('\r\n')
}

const compact = (wall: string) => wall.replace(/[-:]/g, '').slice(0, 13) + '00'

function addMinutes(wall: string, minutes: number): string {
  const d = new Date(Date.UTC(+wall.slice(0, 4), +wall.slice(5, 7) - 1, +wall.slice(8, 10), +wall.slice(11, 13), +wall.slice(14, 16)) + minutes * 60_000)
  return d.toISOString().slice(0, 16)
}

export function matchIcs(m: Match, fmt: Format, who: string, nowMs: number = Date.now()): string | null {
  if (!m.day) return null
  const title = [who || null, `${m.home?.name ?? ''} - ${m.away?.name ?? ''}`].filter(Boolean).join(': ')
  const stamp = new Date(nowMs).toISOString().replace(/[-:]/g, '').slice(0, 15) + 'Z'
  const lines = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//JDCM//PWA//ES', 'CALSCALE:GREGORIAN', 'METHOD:PUBLISH', ...VTIMEZONE, 'BEGIN:VEVENT']
  lines.push(`UID:jdcm-match-${m.id}@makensi.es`, `DTSTAMP:${stamp}`)
  if (m.timeKnown && m.start) {
    const minutes = Math.min(180, Math.max(30, Format.durationMinutes(m) ?? 75))
    lines.push(`DTSTART;TZID=Europe/Madrid:${compact(m.start)}`, `DTEND;TZID=Europe/Madrid:${compact(addMinutes(m.start, minutes))}`)
  } else {
    const next = new Date(Date.UTC(+m.day.slice(0, 4), +m.day.slice(5, 7) - 1, +m.day.slice(8, 10)) + 86_400_000).toISOString().slice(0, 10)
    lines.push(`DTSTART;VALUE=DATE:${m.day.replace(/-/g, '')}`, `DTEND;VALUE=DATE:${next.replace(/-/g, '')}`)
  }
  lines.push(`SUMMARY:${esc(title)}`)
  if (m.venue) lines.push(`LOCATION:${esc(fmt.venueLine(m.venue))}`)
  if (m.venue?.lat != null && m.venue?.lng != null) lines.push(`GEO:${m.venue.lat};${m.venue.lng}`)
  lines.push(`DESCRIPTION:${esc(`${fmt.t('round_n', m.roundNumber)} · JDCM`)}`, 'END:VEVENT', 'END:VCALENDAR')
  return lines.map(fold).join('\r\n') + '\r\n'
}

/** Apple Maps on Apple devices (opens the Maps app), Google Maps elsewhere; the other as a fallback. */
export function directions(m: Match, ios: boolean): { primary: string; secondary: string; primaryName: string; secondaryName: string } | null {
  const v = m.venue
  if (!v) return null
  const hasPos = v.lat != null && v.lng != null && v.lat !== 0 && v.lng !== 0
  const query = [v.address, v.postcode, v.town].filter(Boolean).join(', ') || v.name
  const apple = hasPos
    ? `https://maps.apple.com/?daddr=${v.lat},${v.lng}&q=${encodeURIComponent(v.name || query)}`
    : `https://maps.apple.com/?daddr=${encodeURIComponent(query)}`
  const google = `https://www.google.com/maps/dir/?api=1&destination=${hasPos ? `${v.lat},${v.lng}` : encodeURIComponent(query)}`
  return ios
    ? { primary: apple, secondary: google, primaryName: 'Apple Maps', secondaryName: 'Google Maps' }
    : { primary: google, secondary: apple, primaryName: 'Google Maps', secondaryName: 'Apple Maps' }
}

/** The text a match is shared as (Web Share or the clipboard). */
export function shareText(m: Match, fmt: Format): string {
  const lines = [fmt.whenLine(m)]
  if (m.hasResult) lines.push(`${m.home?.name} ${m.homeScore} - ${m.awayScore} ${m.away?.name}`)
  else lines.push(`${m.home?.name ?? ''} - ${m.away?.name ?? ''}`)
  if (!m.hasResult && m.venue) {
    lines.push(fmt.venueLine(m.venue))
    if (m.venue.lat != null && m.venue.lng != null) lines.push(`https://www.google.com/maps/search/?api=1&query=${m.venue.lat},${m.venue.lng}`)
  }
  return lines.join('\n')
}
