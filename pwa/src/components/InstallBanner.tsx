import { useEffect, useState, useSyncExternalStore, type ReactNode } from 'react'
import { useFmt } from '../hooks/app-state'
import { canPromptInstall, promptInstall, subscribeInstallPrompt } from '../lib/install-prompt'
import { currentEnv, installMode, showInstallBanner, snoozeInstall, type InstallMode } from '../lib/platform'
import { AddSquare, Close, DotsVertical, Ellipsis, IosShare } from './Icons'
import { PrimaryButton, Sheet } from './ui'

/** How this page can be installed right now (re-evaluated when Chrome offers its prompt). */
export function useInstallMode(): InstallMode {
  const canPrompt = useSyncExternalStore(subscribeInstallPrompt, canPromptInstall, () => false)
  return installMode(currentEnv(canPrompt))
}

/** A symbol inside a sentence, drawn as the user will see it on screen. */
function Glyph({ children }: { children: ReactNode }) {
  return <span className="mx-0.5 inline-flex h-6 min-w-6 items-center justify-center rounded-md bg-raised-2 px-1 align-middle text-link">{children}</span>
}

/** A translated sentence with drawn symbols in place of {0}, {1}. */
function WithGlyphs({ text, glyphs }: { text: string; glyphs: ReactNode[] }) {
  const parts = text.split(/\{(\d)\}/)
  return <>{parts.map((p, i) => (i % 2 === 1 ? <Glyph key={i}>{glyphs[Number(p)]}</Glyph> : <span key={i}>{p}</span>))}</>
}

/** The three steps of iOS's "Add to Home Screen", drawn. */
export function InstallSteps({ mode }: { mode: InstallMode }) {
  const fmt = useFmt()
  if (mode === 'ios-inapp') {
    return <p className="text-sm leading-6"><WithGlyphs text={fmt.t('install_inapp')} glyphs={[<Ellipsis size={18} />]} /></p>
  }
  if (mode === 'android-manual') {
    return <p className="text-sm leading-6"><WithGlyphs text={fmt.t('install_android_manual')} glyphs={[<DotsVertical size={18} />]} /></p>
  }
  const first = mode === 'ios-other'
    ? <WithGlyphs text={fmt.t('install_step_share_other')} glyphs={[<IosShare size={18} />]} />
    : <WithGlyphs text={fmt.t('install_step_share')} glyphs={[<IosShare size={18} />, <Ellipsis size={18} />]} />
  const steps = [
    first,
    <WithGlyphs text={fmt.t('install_step_add')} glyphs={[<AddSquare size={18} />]} />,
    <span>{fmt.t('install_step_confirm')}</span>,
  ]
  return (
    <ol className="space-y-2.5">
      {steps.map((s, i) => (
        <li key={i} className="flex gap-3 text-sm leading-6">
          <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-bold text-on-primary">{i + 1}</span>
          <span>{s}</span>
        </li>
      ))}
    </ol>
  )
}

/**
 * The owner's first priority: on a visit (not yet installed), invite to install the app. On iOS
 * there is no prompt, so the steps are drawn; Chrome gets an "Install" button. "Later" hides it
 * for three days.
 */
export function InstallBanner() {
  const fmt = useFmt()
  const mode = useInstallMode()
  const [visible, setVisible] = useState(() => showInstallBanner(mode))
  useEffect(() => { setVisible(showInstallBanner(mode)) }, [mode])
  if (!visible) return null
  const ios = mode.startsWith('ios')
  const later = () => { snoozeInstall(); setVisible(false) }
  return (
    <section data-testid="install-banner" aria-label={fmt.t('install_title_generic')}
      className="relative mx-3 mt-2 rounded-2xl border border-primary/40 bg-primary-soft p-4">
      <button type="button" onClick={later} aria-label={fmt.t('later')} className="absolute right-1.5 top-1.5 flex h-10 w-10 items-center justify-center rounded-full text-text-2">
        <Close size={18} />
      </button>
      <div className="flex items-center gap-3 pr-8">
        <img src={`${import.meta.env.BASE_URL}apple-touch-icon.png`} alt="" width={44} height={44} className="rounded-[11px]" />
        <div>
          <h2 className="text-[17px] font-bold leading-tight">{fmt.t(ios ? 'install_title' : 'install_title_generic')}</h2>
          <p className="text-sm text-text-2">{fmt.t('install_text')}</p>
        </div>
      </div>
      <div className="mt-3">
        {mode === 'prompt'
          ? <PrimaryButton className="w-full" onClick={() => void promptInstall().then((ok) => ok && setVisible(false))}>{fmt.t('install_button')}</PrimaryButton>
          : <InstallSteps mode={mode} />}
      </div>
      <button type="button" onClick={later} className="mt-3 min-h-10 w-full rounded-xl text-sm font-semibold text-text-2 active:bg-raised">{fmt.t('later')}</button>
    </section>
  )
}

/** The same guide on demand (Settings → Install on the home screen). */
export function InstallSheet({ onClose }: { onClose: () => void }) {
  const fmt = useFmt()
  const mode = useInstallMode()
  return (
    <Sheet onClose={onClose} label={fmt.t('install_how')}>
      <div className="px-5 pb-6 pt-2">
        <h2 className="mb-3 text-lg font-bold">{fmt.t(mode.startsWith('ios') ? 'install_title' : 'install_title_generic')}</h2>
        {mode === 'prompt'
          ? <PrimaryButton className="w-full" onClick={() => void promptInstall().then(onClose)}>{fmt.t('install_button')}</PrimaryButton>
          : <InstallSteps mode={mode === 'none' || mode === 'standalone' ? 'ios-safari' : mode} />}
      </div>
    </Sheet>
  )
}
