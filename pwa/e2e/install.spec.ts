import { test, expect, NOW } from './fixtures'

test.describe('the install banner (the owner’s first priority)', () => {
  test('a first visit on an iPhone invites to install, with the three steps drawn', async ({ page }, info) => {
    test.skip(info.project.name !== 'iphone-webkit', 'iOS Safari only')
    await page.goto('/jdcm/')
    const banner = page.getByTestId('install-banner')
    await expect(banner).toBeVisible()
    await expect(banner).toContainText('Instala JDCM en tu iPhone')
    await expect(banner.locator('ol > li')).toHaveCount(3)
    await expect(banner).toContainText('Añadir a pantalla de inicio')
    await expect(banner).toContainText('Toca «Añadir»')
    // It comes before everything else on Home.
    const box = await banner.boundingBox()
    expect(box!.y).toBeLessThan(200)

    await banner.getByRole('button', { name: 'Más tarde' }).last().click()
    await expect(banner).toBeHidden()
    await page.reload()
    await expect(page.getByTestId('empty-state')).toBeVisible()
    await expect(banner).toBeHidden()

    // "Later" lasts three days.
    await page.clock.setFixedTime(new Date(NOW.getTime() + 3 * 24 * 3600_000 + 60_000))
    await page.reload()
    await expect(banner).toBeVisible()
  })

  test('the alpha path invites to install too', async ({ page }, info) => {
    test.skip(info.project.name !== 'iphone-webkit', 'iOS Safari only')
    await page.goto('/jdcm/alpha/')
    await expect(page.getByTestId('install-banner')).toContainText('Instala JDCM en tu iPhone')
  })

  test('the installed app shows no banner', async ({ page }) => {
    await page.addInitScript(() => Object.defineProperty(navigator, 'standalone', { get: () => true }))
    await page.goto('/jdcm/')
    await expect(page.getByTestId('empty-state')).toBeVisible()
    await expect(page.getByTestId('install-banner')).toHaveCount(0)
  })

  test('Chrome on Android: manual steps, then an Install button once Chrome offers its prompt', async ({ page }, info) => {
    test.skip(info.project.name !== 'android-chromium', 'Chrome only')
    await page.goto('/jdcm/')
    const banner = page.getByTestId('install-banner')
    await expect(banner).toContainText('Instalar app')
    await page.evaluate(() => {
      const e = new Event('beforeinstallprompt') as Event & { prompt: () => Promise<void>; userChoice: Promise<unknown> }
      e.prompt = async () => { (window as unknown as { prompted: boolean }).prompted = true }
      e.userChoice = Promise.resolve({ outcome: 'accepted' })
      window.dispatchEvent(e)
    })
    await banner.getByRole('button', { name: 'Instalar' }).click()
    expect(await page.evaluate(() => (window as unknown as { prompted?: boolean }).prompted)).toBe(true)
    await expect(banner).toBeHidden()
  })
})
