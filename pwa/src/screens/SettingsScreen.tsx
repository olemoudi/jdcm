import { useRef, useState, type ReactNode } from 'react'
import { useData, useFmt, useKids, useSettings, type Theme } from '../hooks/app-state'
import { useServiceWorker } from '../hooks/use-service-worker'
import { APP_VERSION, BUILD_LABEL, BUILT_AT, CHANNEL, GIT_COMMIT, REPO_URL, SITE_URL } from '../lib/env'
import { Format } from '../lib/format'
import { kidColor } from '../lib/kids'
import { navigate } from '../lib/router'
import { InstallSheet, useInstallMode } from '../components/InstallBanner'
import { AddSquare, ChevronRight, External, Plus } from '../components/Icons'
import { FlatCard, Hairline, toast } from '../components/ui'
import { useMinuteTick } from './shared'

/** Settings: children, appearance, data freshness, install, version (≤ 1 tap from Home). */
export function SettingsScreen() {
  const fmt = useFmt()
  const { lang, setLang, theme, setTheme } = useSettings()
  const { kids } = useKids()
  const { index } = useData()
  const { forceCheck, needRefresh } = useServiceWorker()
  const mode = useInstallMode()
  const now = useMinuteTick()
  const [installOpen, setInstallOpen] = useState(false)
  const [checking, setChecking] = useState(false)
  const needRefreshRef = useRef(needRefresh)
  needRefreshRef.current = needRefresh

  const check = async () => {
    setChecking(true)
    const result = await forceCheck()
    setChecking(false)
    // An update found is shown by the always-mounted prompt; say something in every other case (§8.14).
    if (result === 'update-found' || needRefreshRef.current) return
    toast(result === 'up-to-date' || result === 'no-worker' ? fmt.t('up_to_date') : result === 'stale-worker' ? fmt.t('stale_worker') : fmt.t('check_failed'))
  }

  return (
    <div className="pb-24">
      <header className="pt-safe">
        <h1 className="px-4 pb-1 pt-3 text-[28px] font-bold leading-tight">{fmt.t('tab_settings')}</h1>
      </header>

      <Section title={fmt.t('kids')}>
        {kids.map((k, i) => (
          <div key={k.id}>
            {i > 0 && <Hairline inset={52} />}
            <button type="button" onClick={() => navigate({ name: 'kid', kidId: k.id })} className="flex min-h-14 w-full items-center gap-3 px-4 py-2 text-left active:bg-raised">
              <span className="h-5 w-5 shrink-0 rounded-full" style={{ background: kidColor(k.color) }} />
              <span className="min-w-0 flex-1">
                <span className="block text-[15px] font-semibold">{k.name}</span>
                <span className="block truncate text-xs text-text-2">{k.teamName} · {Format.sentence(k.competitionName)} · {k.groupName}</span>
              </span>
              <ChevronRight size={18} className="text-text-2" />
            </button>
          </div>
        ))}
        {kids.length > 0 && <Hairline inset={52} />}
        <button type="button" onClick={() => navigate({ name: 'kid' })} className="flex min-h-12 w-full items-center gap-3 px-4 text-left text-[15px] text-link active:bg-raised">
          <Plus size={20} /> {fmt.t('add_kid')}
        </button>
      </Section>

      {mode !== 'none' && (
        <Section>
          {mode === 'standalone' ? (
            <p className="flex min-h-12 items-center gap-3 px-4 text-[15px] text-text-2"><AddSquare size={20} />{fmt.t('installed')}</p>
          ) : (
            <button type="button" onClick={() => setInstallOpen(true)} className="flex min-h-12 w-full items-center gap-3 px-4 text-left text-[15px] active:bg-raised">
              <AddSquare size={20} className="text-primary" /><span className="flex-1">{fmt.t('install_app')}</span><ChevronRight size={18} className="text-text-2" />
            </button>
          )}
        </Section>
      )}

      <Section title={fmt.t('appearance')}>
        <Segmented<Theme> value={theme} onChange={setTheme} options={[['dark', fmt.t('theme_dark')], ['light', fmt.t('theme_light')], ['system', fmt.t('theme_system')]]} />
        <Hairline />
        <div className="flex items-center px-4 py-2">
          <span className="flex-1 text-[15px]">{fmt.t('language')}</span>
          <Segmented value={lang} onChange={setLang} options={[['es', 'Español'], ['en', 'English']]} compact />
        </div>
      </Section>

      <Section title={fmt.t('data')}>
        <div className="px-4 py-3 text-sm">
          <p data-testid="data-generated">{fmt.t('data_generated', fmt.age(index?.generatedMs ?? 0, now))}</p>
          <p className="mt-1 text-text-2">{fmt.t('freshness_pace', index?.refreshHours ?? 3, index?.refreshFromHour ?? 7, index?.refreshToHour ?? 22)}</p>
        </div>
      </Section>

      <Section title={fmt.t('about')}>
        <p className="px-4 py-3 text-sm text-text-2">{fmt.t('about_text')}</p>
        <Hairline />
        <LinkRow href={`${SITE_URL}/Calendarios/CalendarioTemporada`} label={fmt.t('open_web')} />
        <Hairline />
        <LinkRow href={`${REPO_URL}/releases/latest`} label={fmt.t('android_app')} />
        <Hairline />
        <LinkRow href={REPO_URL} label={fmt.t('source_code')} />
      </Section>

      <Section title={fmt.t('version')}>
        <div className="px-4 py-3">
          <p className="font-mono text-sm" data-testid="version">{BUILD_LABEL}</p>
          <p className="mt-0.5 text-xs text-text-2">{APP_VERSION}{BUILT_AT ? ` · ${BUILT_AT.slice(0, 16).replace('T', ' ')} UTC` : ''}</p>
        </div>
        <Hairline />
        <button type="button" onClick={() => void check()} disabled={checking} data-testid="check-updates"
          className="min-h-12 w-full px-4 text-left text-[15px] text-link active:bg-raised disabled:opacity-60">
          {checking ? fmt.t('checking') : fmt.t('check_updates')}
        </button>
      </Section>
      <p className="mt-2 px-4 text-center font-mono text-[10px] text-text-2">{CHANNEL} · {GIT_COMMIT}</p>
      {installOpen && <InstallSheet onClose={() => setInstallOpen(false)} />}
    </div>
  )
}

function Section({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="mt-5">
      {title && <h2 className="px-5 pb-1.5 text-xs font-semibold uppercase tracking-wide text-text-2">{title}</h2>}
      <FlatCard>{children}</FlatCard>
    </section>
  )
}

function LinkRow({ href, label }: { href: string; label: string }) {
  return (
    <a href={href} target="_blank" rel="noopener" className="flex min-h-12 items-center gap-3 px-4 text-[15px] active:bg-raised">
      <span className="flex-1">{label}</span><External size={16} className="text-text-2" />
    </a>
  )
}

function Segmented<T extends string>({ value, onChange, options, compact = false }: {
  value: T; onChange: (v: T) => void; options: Array<[T, string]>; compact?: boolean
}) {
  return (
    <div className={`flex rounded-xl bg-raised p-1 ${compact ? '' : 'm-3'}`} role="radiogroup">
      {options.map(([v, label]) => (
        <button key={v} type="button" role="radio" aria-checked={value === v} onClick={() => onChange(v)}
          className={`min-h-9 flex-1 rounded-lg px-3 text-sm font-semibold ${value === v ? 'bg-card shadow-sm' : 'text-text-2'}`}>
          {label}
        </button>
      ))}
    </div>
  )
}
