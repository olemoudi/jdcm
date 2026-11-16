import { test as base, expect, type Page } from '@playwright/test'

/** Tuesday after the first matchday, 09:00 in Madrid: Home shows round 2, still to be played. */
export const NOW = new Date('2026-11-17T09:00:00+01:00')

type Fixtures = {
  /** "Publishes" build a or b of a channel on the e2e server, or takes its host down. */
  deploy: (channel: 'beta' | 'alpha', build: 'a' | 'b' | 'down') => Promise<void>
}

export const test = base.extend<Fixtures>({
  deploy: async ({ request }, use) => {
    await use(async (channel, build) => {
      const r = await request.post(`/__deploy?channel=${channel}&build=${build}`)
      expect(r.ok()).toBe(true)
    })
  },
  page: async ({ page, request }, use) => {
    for (const c of ['beta', 'alpha']) await request.post(`/__deploy?channel=${c}&build=a`)
    await page.clock.setFixedTime(NOW)
    await use(page)
  },
})

export { expect }

/**
 * Waits until the page's own channel worker controls it (clientsClaim on the first visit). A first
 * visit to /jdcm/alpha/ with the beta installed starts under the beta's worker (its scope contains
 * the alpha's), which steps aside; the alpha's worker then claims the page.
 */
export async function controlled(page: Page): Promise<string> {
  const own = new URL('sw.js', page.url().replace(/#.*$/, '')).pathname
  await page.waitForFunction((path) => navigator.serviceWorker?.controller?.scriptURL.endsWith(path), own, { timeout: 20_000 })
  return page.evaluate(() => navigator.serviceWorker.controller!.scriptURL)
}

export async function dismissInstallBanner(page: Page) {
  const banner = page.getByTestId('install-banner')
  if (await banner.isVisible()) await banner.getByRole('button', { name: /^(Más tarde|Later)$/ }).last().click()
}

export async function addKid(page: Page, kid: { name: string; sport: string; competition: RegExp; search: string; team: RegExp }) {
  await page.goto(page.url().includes('/jdcm/alpha/') ? '/jdcm/alpha/#/hijo/nuevo' : '/jdcm/#/hijo/nuevo')
  await page.getByLabel('Nombre').fill(kid.name)
  await page.getByRole('button', { name: 'Siguiente' }).click()
  await page.getByRole('button', { name: kid.sport }).click()
  await page.getByRole('button', { name: kid.competition }).click()
  await page.getByLabel('Busca el colegio').fill(kid.search)
  await page.getByRole('button', { name: kid.team }).first().click()
}

export const LUCIA = { name: 'Lucía', sport: 'Fútbol sala', competition: /^Alevín mixto/, search: 'vedruna', team: /FEC SANTA JOAQUINA DE VEDRUNA/ }
export const PABLO = { name: 'Pablo', sport: 'Baloncesto', competition: /^Alevín masculino/, search: 'vedruna', team: /FEC SANTA JOAQUINA DE VEDRUNA/ }
