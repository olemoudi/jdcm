import type { Match } from './model'

/*
 * A port of core's Schedule (core/src/main/kotlin/dev/jdcm/core/schedule/Schedule.kt): the weekend a
 * match belongs to. Dates are "YYYY-MM-DD"; arithmetic is done in UTC so the phone's zone never matters.
 */

const DAY_MS = 86_400_000

const utc = (day: string) => Date.UTC(Number(day.slice(0, 4)), Number(day.slice(5, 7)) - 1, Number(day.slice(8, 10)))

export function addDays(day: string, n: number): string {
  return new Date(utc(day) + n * DAY_MS).toISOString().slice(0, 10)
}

export function daysBetween(from: string, to: string): number {
  return Math.round((utc(to) - utc(from)) / DAY_MS)
}

/** 0 = Sunday … 6 = Saturday. */
export const weekday = (day: string) => new Date(utc(day)).getUTCDay()

/** The weekend a match belongs to, anchored on its Saturday: Friday-evening games count with it. */
export function weekendOf(day: string): string {
  switch (weekday(day)) {
    case 6: return day
    case 0: return addDays(day, -1)
    case 5: return addDays(day, 1)
    default: return addDays(day, 6 - weekday(day))
  }
}

/** A match moved further than this from its round is listed on its own weekend (a replay). */
const MOVED_DAYS = 3

/**
 * The matchday a match is listed under: its round's weekend, else its own — and its own when it
 * was moved more than three days away from its round (a rained-off match replayed weeks later
 * keeps its round but is played, and must be shown, on its new weekend).
 */
export function matchdayOf(m: Match): string | null {
  const own = m.start?.slice(0, 10) ?? null
  const round = m.roundDate
  const day = own != null && round != null && Math.abs(daysBetween(round, own)) > MOVED_DAYS ? own : round ?? (m.start?.slice(0, 10) ?? null)
  return day ? weekendOf(day) : null
}
