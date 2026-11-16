import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { buildGroup } from '../core/group'
import type { GroupData } from '../lib/data'

/** The real data the home fetcher published from the site on 2026-11-16 (pwa/e2e/data/v1). */
export function realJson(path: string): unknown {
  return JSON.parse(readFileSync(fileURLToPath(new URL(`../../e2e/data/${path}`, import.meta.url)), 'utf8'))
}

/** As the app builds it on Tuesday 17 November (the day the e2e tests are set on). */
export function realGroup(id: number, today = '2026-11-17'): GroupData {
  const g = buildGroup(realJson(`v1/groups/${id}/all.json`), today)
  if (!g) throw new Error(`group ${id} does not parse`)
  return g
}
