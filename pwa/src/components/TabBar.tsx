import { useFmt } from '../hooks/app-state'
import { navigate, type Route } from '../lib/router'
import { TabMatches, TabSettings, TabTables } from './Icons'

/** iPhone-style bottom tabs, within thumb reach, above the home indicator. */
export function TabBar({ route }: { route: Route }) {
  const fmt = useFmt()
  const tabs = [
    { name: 'home' as const, label: fmt.t('tab_matches'), icon: <TabMatches size={26} /> },
    { name: 'tables' as const, label: fmt.t('tab_tables'), icon: <TabTables size={26} /> },
    { name: 'settings' as const, label: fmt.t('tab_settings'), icon: <TabSettings size={26} /> },
  ]
  const active = route.name === 'tables' || route.name === 'settings' ? route.name : route.name === 'home' ? 'home' : null
  return (
    <nav className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-bg pb-safe px-safe" aria-label="JDCM">
      <div className="mx-auto flex max-w-lg">
        {tabs.map((t) => (
          <button key={t.name} type="button" onClick={() => navigate({ name: t.name }, active !== null)}
            aria-current={active === t.name ? 'page' : undefined}
            className={`flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 text-[11px] font-semibold ${active === t.name ? 'text-primary' : 'text-text-2'}`}>
            {t.icon}
            <span>{t.label}</span>
          </button>
        ))}
      </div>
    </nav>
  )
}
