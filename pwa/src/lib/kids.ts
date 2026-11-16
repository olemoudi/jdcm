import { readJson, writeJson } from './storage'
import type { SportKind } from './data'

/** A child, stored only on this device (and only in this channel's storage). */
export interface Kid {
  id: string
  name: string
  /** Index into KID_COLORS. */
  color: number
  sportId: number
  sportKind: SportKind
  competitionId: number
  competitionName: string
  categoryId: number
  categoryName: string
  groupId: number
  groupName: string
  teamId: number
  teamName: string
  crest: string
}

/** The Android app's palette (Notifier.KID_COLORS): light enough for dark text on it in both themes. */
export const KID_COLORS = ['#4FC3F7', '#F06292', '#81C784', '#FFD54F', '#BA68C8', '#FF8A65']

export function kidColor(index: number): string {
  return KID_COLORS[((index % KID_COLORS.length) + KID_COLORS.length) % KID_COLORS.length]
}

const posInt = (v: unknown) => typeof v === 'number' && Number.isInteger(v) && v > 0
const s = (v: unknown) => (typeof v === 'string' ? v : '')

function parseKid(v: unknown): Kid | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  if (typeof o.id !== 'string' || !o.id || typeof o.name !== 'string' || !o.name.trim()) return null
  if (![o.sportId, o.competitionId, o.categoryId, o.groupId, o.teamId].every(posInt)) return null
  const kind = o.sportKind
  return {
    id: o.id,
    name: o.name.trim().slice(0, 40),
    color: typeof o.color === 'number' && Number.isInteger(o.color) ? o.color : 0,
    sportId: o.sportId as number,
    sportKind: kind === 'FUTSAL' || kind === 'BASKETBALL' || kind === 'VOLLEYBALL' ? kind : 'OTHER',
    competitionId: o.competitionId as number,
    competitionName: s(o.competitionName),
    categoryId: o.categoryId as number,
    categoryName: s(o.categoryName),
    groupId: o.groupId as number,
    groupName: s(o.groupName),
    teamId: o.teamId as number,
    teamName: s(o.teamName),
    crest: s(o.crest),
  }
}

/** A malformed entry is dropped, never the list: a bad write must not lose every child. */
export function parseKids(v: unknown): Kid[] | null {
  if (!Array.isArray(v)) return null
  return v.map(parseKid).filter((k): k is Kid => k !== null)
}

const KEY = 'kids'

export function loadKids(): Kid[] {
  return readJson(KEY, parseKids) ?? []
}

export function saveKids(kids: Kid[]): void {
  writeJson(KEY, kids)
}

export function newKidId(): string {
  try {
    return crypto.randomUUID()
  } catch {
    return `k${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`
  }
}

/** The first colour no other child has (cycling when all are taken). */
export function nextColor(kids: Kid[]): number {
  const used = new Set(kids.map((k) => k.color))
  for (let i = 0; i < KID_COLORS.length; i++) if (!used.has(i)) return i
  return kids.length % KID_COLORS.length
}
