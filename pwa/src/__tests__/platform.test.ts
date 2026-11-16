import { INSTALL_SNOOZE_MS, installMode, isIos, showInstallBanner } from '../lib/platform'

const IPHONE_SAFARI = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1'
const IPHONE_CHROME = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/140.0.0.0 Mobile/15E148 Safari/604.1'
const IPHONE_INSTAGRAM = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Instagram 350.0'
const IPAD_DESKTOP = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15'
const ANDROID_CHROME = 'Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36'
const DESKTOP_CHROME = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36'

const env = (ua: string, over: Partial<{ standalone: boolean; canPrompt: boolean; platform: string; maxTouchPoints: number }> = {}) =>
  ({ ua, standalone: false, canPrompt: false, ...over })

describe('how the page can be installed', () => {
  it('tells Safari, the other iOS browsers and in-app browsers apart', () => {
    expect(installMode(env(IPHONE_SAFARI))).toBe('ios-safari')
    expect(installMode(env(IPHONE_CHROME))).toBe('ios-other')
    expect(installMode(env(IPHONE_INSTAGRAM))).toBe('ios-inapp')
  })

  it('knows an iPad asking for the desktop site', () => {
    expect(isIos({ ua: IPAD_DESKTOP, platform: 'MacIntel', maxTouchPoints: 5 })).toBe(true)
    expect(isIos({ ua: IPAD_DESKTOP, platform: 'MacIntel', maxTouchPoints: 0 })).toBe(false)
  })

  it('uses Chrome’s prompt when there is one, and says nothing on a desktop without it', () => {
    expect(installMode(env(ANDROID_CHROME, { canPrompt: true }))).toBe('prompt')
    expect(installMode(env(ANDROID_CHROME))).toBe('android-manual')
    expect(installMode(env(DESKTOP_CHROME))).toBe('none')
  })

  it('never shows the banner inside the installed app', () => {
    expect(installMode(env(IPHONE_SAFARI, { standalone: true }))).toBe('standalone')
    expect(showInstallBanner('standalone', 0, 0)).toBe(false)
    expect(showInstallBanner('none', 0, 0)).toBe(false)
  })

  it('comes back three days after "Later"', () => {
    const now = 1_000_000
    expect(showInstallBanner('ios-safari', now, 0)).toBe(true)
    expect(showInstallBanner('ios-safari', now, now + INSTALL_SNOOZE_MS)).toBe(false)
    expect(showInstallBanner('ios-safari', now + INSTALL_SNOOZE_MS, now + INSTALL_SNOOZE_MS)).toBe(true)
  })
})
