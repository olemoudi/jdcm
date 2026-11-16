import { test, expect, addKid, dismissInstallBanner, LUCIA, PABLO } from './fixtures'

test.describe('children and their matches', () => {
  test('add a child and see their matchday on Home, from the published data', async ({ page }) => {
    await page.goto('/jdcm/')
    await dismissInstallBanner(page)
    await page.getByTestId('empty-state').getByRole('button', { name: 'Añadir hijo' }).click()
    await expect(page).toHaveURL(/#\/hijo\/nuevo/)
    await addKid(page, LUCIA)

    const section = page.locator('section[data-testid^="kid-"]')
    await expect(section).toContainText('Lucía')
    await expect(section).toContainText('FEC SANTA JOAQUINA DE VEDRUNA')
    // Her match of round 2 (Saturday 21 November, 10:00, away at El Valle Sanchinarro "D").
    const card = section.locator('[data-match-id]').first()
    await expect(card).toContainText('EL VALLE SANCHINARRO "D"')
    await expect(card).toContainText('10:00')
    await expect(card).toContainText('Sáb 21 nov')
    // The last result stays in sight: she won 10-0 on the first matchday.
    await expect(section.getByTestId('last-result')).toContainText('Ganó 10-0')
    await expect(section).toContainText('1º · 2 pts')
    await expect(section.getByTestId('freshness')).toContainText('Se actualizan cada ~3 h durante el día (7–22 h)')

    // The match sheet: directions, calendar, share.
    await card.getByRole('button').first().click()
    const sheet = page.getByTestId('match-sheet')
    await expect(sheet).toContainText('Jornada 2')
    await expect(sheet.getByRole('link', { name: 'Cómo llegar' })).toHaveAttribute('href', /maps\.apple\.com|google\.com\/maps/)
    await expect(sheet.getByRole('button', { name: 'Al calendario' })).toBeVisible()
    const download = page.waitForEvent('download')
    await sheet.getByRole('button', { name: 'Al calendario' }).click()
    expect((await download).suggestedFilename()).toMatch(/^jdcm-\d+\.ics$/)
    await page.keyboard.press('Escape')

    // Survives a reload (stored on the device).
    await page.reload()
    await expect(page.locator('section[data-testid^="kid-"]')).toContainText('Lucía')
  })

  test('group calendar and standings; basketball says its points rule is assumed', async ({ page }) => {
    await page.goto('/jdcm/')
    await dismissInstallBanner(page)
    await addKid(page, LUCIA)
    await addKid(page, PABLO)
    await page.getByRole('navigation').getByRole('button', { name: 'Clasificación' }).click()
    const futsal = page.getByTestId('standings').first()
    await expect(futsal.getByRole('button', { name: /^1º FEC SANTA JOAQUINA DE VEDRUNA/ })).toBeVisible()
    await expect(futsal).toContainText('victoria 2, empate 1, derrota 0')
    await expect(page.getByTestId('standings-assumed')).toContainText('Los puntos de Baloncesto son una suposición')

    // The group's calendar, round by round.
    await page.goto('/jdcm/#/grupo/177/calendario')
    await expect(page.getByRole('tab', { name: /J2/ })).toHaveAttribute('aria-selected', 'true')
    await page.getByRole('tab', { name: /J1/ }).click()
    await expect(page.getByText('No celebrado').first()).toBeVisible() // the rain-offs of 14 November
  })

  test('a team whose group is not followed yet can be saved, with a way to ask for it', async ({ page }) => {
    await page.goto('/jdcm/')
    await dismissInstallBanner(page)
    await addKid(page, { ...LUCIA, name: 'Marta', search: 'pilar', team: /NUESTRA SEÑORA DEL PILAR "A"/ })
    const pending = page.getByTestId('not-followed')
    await expect(pending).toContainText('Este grupo aún no está en la web app')
    await expect(pending.getByRole('link', { name: /Pedirlo en GitHub/ })).toHaveAttribute('href', /github\.com\/olemoudi\/jdcm\/issues\/new\?title=.*groupId%22%3A180/)
    await pending.getByRole('button', { name: 'Guardar a Marta' }).click()
    await expect(page.getByTestId('group-pending')).toContainText('Pide que añadan el de Marta')
  })
})
