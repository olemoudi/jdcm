import { readRaw, writeRaw } from './storage'

/*
 * How this page can be installed, which decides what the install banner says. Safari on iOS has no
 * install prompt: the user adds the page to the home screen from the share sheet (Chrome, Edge and
 * Firefox on iOS can too since iOS 16.4, from their own share button). In-app browsers (Instagram,
 * Facebook, Google app…) cannot at all. Chrome on Android and desktop fire `beforeinstallprompt`.
 */
export type InstallMode =
  | 'standalone'      // already running as the installed app
  | 'ios-safari'
  | 'ios-other'       // Chrome / Edge / Firefox on iOS
  | 'ios-inapp'       // a webview inside another app
  | 'prompt'          // beforeinstallprompt is available
  | 'android-manual'  // Android without a prompt (Firefox, Samsung Internet…)
  | 'none'            // desktop without a prompt: no banner

export interface Env {
  ua: string
  platform?: string
  maxTouchPoints?: number
  standalone: boolean
  canPrompt: boolean
}

export function isIos(env: Pick<Env, 'ua' | 'platform' | 'maxTouchPoints'>): boolean {
  if (/iPhone|iPad|iPod/.test(env.ua)) return true
  // iPadOS asks for desktop sites: a "Mac" with a touch screen.
  return env.platform === 'MacIntel' && (env.maxTouchPoints ?? 0) > 1
}

export function installMode(env: Env): InstallMode {
  if (env.standalone) return 'standalone'
  if (isIos(env)) {
    if (/FBAN|FBAV|Instagram|Line\/|GSA\/|MicroMessenger|Snapchat|TikTok|LinkedInApp|Twitter/i.test(env.ua)) return 'ios-inapp'
    if (/CriOS|EdgiOS|FxiOS|OPiOS/.test(env.ua)) return 'ios-other'
    return 'ios-safari'
  }
  if (env.canPrompt) return 'prompt'
  if (/Android/i.test(env.ua)) return 'android-manual'
  return 'none'
}

export function isStandalone(): boolean {
  try {
    if (globalThis.matchMedia?.('(display-mode: standalone)').matches) return true
  } catch { /* old browsers */ }
  return (globalThis.navigator as Navigator & { standalone?: boolean } | undefined)?.standalone === true
}

export function currentEnv(canPrompt: boolean): Env {
  const nav = globalThis.navigator
  return {
    ua: nav?.userAgent ?? '',
    platform: nav?.platform,
    maxTouchPoints: nav?.maxTouchPoints,
    standalone: isStandalone(),
    canPrompt,
  }
}

/** "Later" hides the banner for this long; then it comes back (the owner's rule for the Android banner). */
export const INSTALL_SNOOZE_MS = 3 * 24 * 60 * 60 * 1000

export function installSnoozedUntil(): number {
  const v = Number(readRaw('install-snoozed-until'))
  return Number.isFinite(v) ? v : 0
}

export function snoozeInstall(nowMs: number = Date.now()): void {
  writeRaw('install-snoozed-until', String(nowMs + INSTALL_SNOOZE_MS))
}

export function showInstallBanner(mode: InstallMode, nowMs: number = Date.now(), snoozedUntil = installSnoozedUntil()): boolean {
  if (mode === 'standalone' || mode === 'none') return false
  return nowMs >= snoozedUntil
}
