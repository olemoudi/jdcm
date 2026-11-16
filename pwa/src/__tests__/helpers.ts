import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { parseGroup, type GroupData } from '../lib/data'

/** The real data the pump produced from the site on 2026-11-16 (pwa/e2e/data). */
export function realJson(path: string): unknown {
  return JSON.parse(readFileSync(fileURLToPath(new URL(`../../e2e/data/${path}`, import.meta.url)), 'utf8'))
}

export function realGroup(id: number): GroupData {
  const g = parseGroup(realJson(`groups/${id}.json`))
  if (!g) throw new Error(`group ${id} does not parse`)
  return g
}
