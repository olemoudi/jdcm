import { test, expect, addKid, controlled, dismissInstallBanner, LUCIA } from './fixtures'

// Beta at /jdcm/ and alpha at /jdcm/alpha/ share the origin, and the beta's scope contains the
// alpha's. Each must load its own shell, keep its own storage and caches, and update alone.
test('beta and alpha installed side by side stay apart', async ({ page, context, deploy }) => {
  // The beta first: its worker (scope /jdcm/) would catch /jdcm/alpha/ if it did not step aside.
  await page.goto('/jdcm/')
  expect(await controlled(page)).toMatch(/\/jdcm\/sw\.js$/)
  await expect(page.getByTestId('build-label')).toHaveText('beta · beta001')
  await dismissInstallBanner(page)
  await addKid(page, LUCIA)
  await expect(page.locator('section[data-testid^="kid-"]')).toContainText('Lucía')

  const alpha = await context.newPage()
  await alpha.goto('/jdcm/alpha/')
  await expect(alpha.getByTestId('build-label')).toHaveText('alpha · alph001')
  expect(await controlled(alpha)).toMatch(/\/jdcm\/alpha\/sw\.js$/)
  await expect(alpha).toHaveTitle('JDCM α')
  // Its own manifest identity.
  const manifest = await (await alpha.request.get('/jdcm/alpha/manifest.webmanifest')).json()
  expect(manifest).toMatchObject({ id: '/jdcm/alpha/', start_url: '/jdcm/alpha/', scope: '/jdcm/alpha/', short_name: 'JDCM α' })
  // The beta's child is not the alpha's: storage is namespaced by channel.
  await expect(alpha.getByTestId('empty-state')).toBeVisible()
  const keys = await alpha.evaluate(() => Object.keys(localStorage).sort())
  expect(keys.every((k) => k.startsWith('jdcm:beta:') || k.startsWith('jdcm:alpha:'))).toBe(true)
  expect(keys).toContain('jdcm:beta:kids')
  expect(keys).not.toContain('jdcm:alpha:kids')

  // Each channel's precache under its own name.
  const cachesBefore = await alpha.evaluate(() => caches.keys())
  expect(cachesBefore.some((n) => n.startsWith('jdcm-beta-precache'))).toBe(true)
  expect(cachesBefore.some((n) => n.startsWith('jdcm-alpha-precache'))).toBe(true)

  // Update the alpha: the beta neither sees an update nor loses anything.
  await deploy('alpha', 'b')
  await alpha.goto('/jdcm/alpha/#/ajustes')
  await alpha.getByTestId('check-updates').click()
  await alpha.getByTestId('update-dialog').getByRole('button', { name: 'Actualizar ahora' }).click()
  await expect(alpha.getByTestId('version')).toHaveText('alpha · alph002', { timeout: 20_000 })

  await page.goto('/jdcm/#/ajustes')
  await expect(page.getByTestId('version')).toHaveText('beta · beta001')
  await page.getByTestId('check-updates').click()
  await expect(page.getByText('Tienes la última versión.')).toBeVisible({ timeout: 20_000 })
  await expect(page.getByTestId('update-dialog')).toHaveCount(0)

  // Update the beta: its cleanup must not delete the alpha's precache (Workbox's would).
  await deploy('beta', 'b')
  await page.getByTestId('check-updates').click()
  await page.getByTestId('update-dialog').getByRole('button', { name: 'Actualizar ahora' }).click()
  await expect(page.getByTestId('version')).toHaveText('beta · beta002', { timeout: 20_000 })
  const cachesAfter = await page.evaluate(() => caches.keys())
  expect(cachesAfter.some((n) => n.startsWith('jdcm-alpha-precache'))).toBe(true)

  // With the host down, both shells still open from their own caches, each its own version.
  await deploy('alpha', 'down')
  await deploy('beta', 'down')
  await alpha.goto('/jdcm/alpha/')
  await expect(alpha.getByTestId('build-label')).toHaveText('alpha · alph002')
  await page.goto('/jdcm/')
  await expect(page.getByTestId('build-label')).toHaveText('beta · beta002')
  await expect(page.locator('section[data-testid^="kid-"]')).toContainText('Lucía')
})
