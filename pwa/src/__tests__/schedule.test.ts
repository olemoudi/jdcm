import type { Match } from '../lib/data'
import {
  addDays, focusMatchday, focusRound, isAhead, isLive, lastResult, madridNow, madridToday, matchdays, matchOrder, nextMatch, outcomeFor, weekendOf,
} from '../lib/schedule'
import { realGroup } from './helpers'

const futsal = realGroup(177)
const basket = realGroup(139)

function m(over: Partial<Match>): Match {
  return {
    id: 1, roundId: 1, roundNumber: 1, home: { id: 1, name: 'A', crest: '' }, away: { id: 2, name: 'B', crest: '' },
    start: '2026-11-14T10:30', timeKnown: true, day: '2026-11-14', matchday: '2026-11-14', status: 'SCHEDULED', statusName: '',
    hasResult: false, homeScore: null, awayScore: null, winner: null, partials: [], modified: false, modifiedReason: '', duration: '', venue: null,
    ...over,
  }
}

describe('Madrid time', () => {
  it('is Madrid wall clock whatever the phone zone, across the DST change', () => {
    expect(madridNow(Date.UTC(2026, 10, 14, 9, 30))).toBe('2026-11-14T10:30') // CET, UTC+1
    expect(madridNow(Date.UTC(2026, 6, 4, 9, 30))).toBe('2026-07-04T11:30')   // CEST, UTC+2
    expect(madridToday(Date.UTC(2026, 10, 14, 23, 30))).toBe('2026-11-15')
  })
})

describe('matchdays (core Schedule)', () => {
  it('anchors a weekend on its Saturday: Friday evening counts with it, Sunday too', () => {
    expect(weekendOf('2026-11-13')).toBe('2026-11-14') // Friday
    expect(weekendOf('2026-11-14')).toBe('2026-11-14')
    expect(weekendOf('2026-11-15')).toBe('2026-11-14') // Sunday
    expect(weekendOf('2026-11-11')).toBe('2026-11-14') // Wednesday: the coming weekend
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01')
  })

  it('lists the weekends of every child’s group, once each, in order', () => {
    const days = matchdays([futsal, basket])
    expect(days[0]).toBe('2026-11-14')
    expect(days).toEqual([...new Set(days)].sort())
    expect(days.length).toBeGreaterThanOrEqual(14)
  })

  it('shows this weekend until Monday, then the next one', () => {
    const days = ['2026-11-14', '2026-11-21', '2026-11-28']
    expect(focusMatchday(days, '2026-11-12')).toBe('2026-11-14')
    expect(focusMatchday(days, '2026-11-16')).toBe('2026-11-14') // Monday
    expect(focusMatchday(days, '2026-11-17')).toBe('2026-11-21')
    expect(focusMatchday(days, '2027-03-01')).toBe('2026-11-28') // season over: the last
    expect(focusMatchday([], '2026-11-17')).toBeNull()
    expect(focusRound(futsal, '2026-11-17')?.number).toBe(2)
  })
})

describe('a team’s matches', () => {
  it('keeps a match "ahead" until 3 h after kick-off, and a time-less one all its day', () => {
    expect(isAhead(m({}), '2026-11-14T13:29')).toBe(true)
    expect(isAhead(m({}), '2026-11-14T13:31')).toBe(false)
    expect(isAhead(m({ timeKnown: false, start: '2026-11-14T00:00' }), '2026-11-14T23:00')).toBe(true)
    expect(isAhead(m({ start: null, day: null }), '2026-11-14T23:00')).toBe(true)
  })

  it('is live from kick-off for 100 minutes, unless it has a result', () => {
    expect(isLive(m({}), '2026-11-14T10:29')).toBe(false)
    expect(isLive(m({}), '2026-11-14T11:00')).toBe(true)
    expect(isLive(m({}), '2026-11-14T12:10')).toBe(false)
    expect(isLive(m({ hasResult: true, homeScore: 1, awayScore: 0, winner: 'HOME' }), '2026-11-14T11:00')).toBe(false)
  })

  it('finds the next match and the last result in real data', () => {
    // FEC Santa Joaquina de Vedruna won 10-0 on Friday 13 November (the first matchday, measured).
    const team = 1194
    const last = lastResult(futsal, team)!
    expect(last.id).toBe(10379)
    expect(outcomeFor(last, team)).toBe('W')
    expect(outcomeFor(last, 611)).toBe('L')
    const next = nextMatch(futsal, team, '2026-11-16T10:00')!
    expect(next.roundNumber).toBe(2)
  })

  it('skips a postponed or unscheduled match when another is coming', () => {
    const g = { ...futsal, matches: [
      m({ id: 1, status: 'UNSCHEDULED', day: '2026-11-21', start: '2026-11-21T00:00', timeKnown: false }),
      m({ id: 2, day: '2026-11-28', start: '2026-11-28T10:00' }),
    ] }
    expect(nextMatch(g, 1, '2026-11-16T10:00')?.id).toBe(2)
  })

  it('orders a round like the site: by day, known times first, byes last', () => {
    const list = [
      m({ id: 3, away: null }),
      m({ id: 2, timeKnown: false, start: '2026-11-14T00:00' }),
      m({ id: 1, start: '2026-11-14T09:00' }),
      m({ id: 4, day: '2026-11-13', start: '2026-11-13T18:00' }),
    ].sort(matchOrder)
    expect(list.map((x) => x.id)).toEqual([4, 1, 3, 2])
  })
})
