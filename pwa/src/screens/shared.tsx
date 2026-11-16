import { useEffect, useState } from 'react'
import { useData, useFmt } from '../hooks/app-state'
import { SITE_URL } from '../lib/env'
import { isStale } from '../lib/remote'
import { External, Refresh } from '../components/Icons'

/** Re-renders every minute, so "hace 5 min" keeps counting. */
export function useMinuteTick(): number {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 60_000)
    return () => clearInterval(t)
  }, [])
  return now
}

/**
 * When the data was read from the site and how often it is (every ~3 h in the daytime: the data
 * workflow's pace), with a refresh. Always shown: the PWA has no live connection to the site.
 */
export function FreshnessLine({ groupId }: { groupId?: number }) {
  const fmt = useFmt()
  const now = useMinuteTick()
  const { index, groups, state, refreshing, refresh } = useData()
  const g = groupId ? groups[groupId] : undefined
  // The last publish: every run that reaches the site publishes, so this is when the data was checked.
  const at = index?.generatedMs || g?.newestFetch || 0
  const entry = groupId ? index?.groups.find((x) => x.groupId === groupId) : undefined
  const pace = fmt.t('freshness_pace', index?.refreshHours ?? 3, index?.refreshFromHour ?? 7, index?.refreshToHour ?? 19)
  const stale = isStale(at, now)
  return (
    <div className="px-5 pb-2 pt-1 text-xs text-text-2" data-testid="freshness">
      <p className={stale ? 'font-bold text-danger' : undefined}>
        {state === 'offline' ? fmt.t('offline_data', fmt.age(at, now)) : fmt.t('freshness', fmt.age(at, now))}
        {' · '}
        <button type="button" onClick={() => void refresh()} disabled={refreshing} className="inline-flex items-center gap-1 text-link disabled:opacity-60">
          <Refresh size={13} />{refreshing ? fmt.t('refreshing') : fmt.t('refresh')}
        </button>
      </p>
      <p>{pace}</p>
      {state === 'error' && <p className="text-danger">{fmt.t('data_error')}</p>}
      {entry?.lastError && <p className="text-modified">{fmt.t('group_error')}</p>}
    </div>
  )
}

/**
 * Said plainly at the top when the data is old (> 12.5 h): the fetcher may be off, and parents must
 * not take old data for current. Points to the official site.
 */
export function StaleBanner() {
  const fmt = useFmt()
  const now = useMinuteTick()
  const { index } = useData()
  const at = index?.generatedMs ?? 0
  if (!isStale(at, now)) return null
  return (
    <section role="alert" data-testid="stale-banner" className="mx-3 mt-2 rounded-2xl border border-danger/60 bg-danger/10 p-4">
      <h2 className="text-[15px] font-bold text-danger">{fmt.t('stale_title')}</h2>
      <p className="mt-1 text-sm">{fmt.t('stale_text', fmt.age(at, now))}</p>
      <a href={`${SITE_URL}/Calendarios/CalendarioTemporada`} target="_blank" rel="noopener" className="mt-2 inline-flex items-center gap-1 text-sm font-semibold text-link">
        {fmt.t('open_web')} <External size={14} />
      </a>
    </section>
  )
}
