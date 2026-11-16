/*
 * Chrome's install prompt (Android, desktop). The event can fire before React mounts, so it is
 * caught from main.tsx at start and kept here; the banner subscribes.
 */
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

let deferred: BeforeInstallPromptEvent | null = null
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((l) => l())

export function listenForInstallPrompt(): void {
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault() // our banner asks, not Chrome's mini-infobar
    deferred = e as BeforeInstallPromptEvent
    emit()
  })
  window.addEventListener('appinstalled', () => {
    deferred = null
    emit()
  })
}

export function canPromptInstall(): boolean {
  return deferred !== null
}

export function subscribeInstallPrompt(l: () => void): () => void {
  listeners.add(l)
  return () => { listeners.delete(l) }
}

/** Shows Chrome's install dialog. True when the user accepted. */
export async function promptInstall(): Promise<boolean> {
  const e = deferred
  if (!e) return false
  deferred = null
  emit()
  await e.prompt()
  const choice = await e.userChoice.catch(() => ({ outcome: 'dismissed' as const }))
  return choice.outcome === 'accepted'
}
