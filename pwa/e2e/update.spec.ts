import { test, expect, controlled, dismissInstallBanner } from './fixtures'

// GTD25's manual script (PWA_UPDATE_PROTOCOL §10), automated: note the hash, deploy a new build,
// check, see the prompt with the change, update, reload ONCE, and the hash is the new one.
test('a new build is offered, applied with one reload, and the version shows it', async ({ page, deploy }) => {
  await page.goto('/jdcm/')
  expect(await controlled(page)).toMatch(/\/jdcm\/sw\.js$/)
  // A returning visit (the app opened again): the worker is in control from the start.
  await page.reload()
  await expect(page.getByTestId('build-label')).toHaveText('beta · beta001')
  await dismissInstallBanner(page)

  await deploy('beta', 'b')
  await page.getByRole('button', { name: 'Ajustes' }).click()
  await expect(page.getByTestId('version')).toHaveText('beta · beta001')
  let loads = 0
  page.on('load', () => loads++)
  await page.getByTestId('check-updates').click()

  const dialog = page.getByTestId('update-dialog')
  await expect(dialog).toBeVisible({ timeout: 20_000 })
  await expect(dialog.getByTestId('update-commits')).toHaveText('beta001 → beta002')
  const clicked = Date.now()
  await dialog.getByRole('button', { name: 'Actualizar ahora' }).click()

  await expect(page.getByTestId('update-done')).toContainText('beta · beta002', { timeout: 20_000 })
  // Reloaded by the new worker taking control, not by the 12 s fallback (§7).
  expect(Date.now() - clicked).toBeLessThan(8_000)
  await expect(page.getByTestId('version')).toHaveText('beta · beta002')
  expect(loads).toBe(1)
  // No prompt comes back, and the new worker is the one in control.
  await page.getByTestId('check-updates').click()
  await expect(page.getByText('Tienes la última versión.')).toBeVisible({ timeout: 20_000 })
  await expect(dialog).toHaveCount(0)
})

test('"Later" leaves a banner that still updates, also in the session that installed the app', async ({ page, deploy }) => {
  await page.goto('/jdcm/#/ajustes')
  await controlled(page)
  await deploy('beta', 'b')
  await page.getByTestId('check-updates').click()
  await page.getByTestId('update-dialog').getByRole('button', { name: 'Más tarde' }).click()
  const banner = page.getByTestId('update-banner')
  await expect(banner).toBeVisible()
  const clicked = Date.now()
  await banner.getByRole('button', { name: 'Actualizar ahora' }).click()
  await expect(page.getByTestId('version')).toHaveText('beta · beta002', { timeout: 20_000 })
  expect(Date.now() - clicked).toBeLessThan(8_000)
})

test('with the host unreachable, the installed shell still opens, with the data last seen', async ({ page, deploy }) => {
  await page.goto('/jdcm/')
  await controlled(page)
  await expect(page.getByTestId('empty-state')).toBeVisible()
  // The host answers 503 for everything under /jdcm/ (index.html, sw.js, assets): only the precache can help.
  await deploy('beta', 'down')
  await page.reload()
  await expect(page.getByTestId('build-label')).toHaveText('beta · beta001')
  await expect(page.getByTestId('empty-state')).toBeVisible()
})
