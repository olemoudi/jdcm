import { useEffect, useState } from 'react'
import { useData, useFmt } from '../hooks/app-state'
import type { Kid } from '../lib/kids'
import { REPO_URL } from '../lib/env'
import { Format } from '../lib/format'
import { External, Refresh, Share } from '../components/Icons'
import { FlatCard, toast } from '../components/ui'

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
  const at = g?.newestFetch || index?.generatedMs || 0
  const entry = groupId ? index?.groups.find((x) => x.groupId === groupId) : undefined
  const pace = fmt.t('freshness_pace', index?.refreshHours ?? 3, index?.refreshFromHour ?? 7, index?.refreshToHour ?? 22)
  return (
    <div className="px-5 pb-2 pt-1 text-xs text-text-2" data-testid="freshness">
      <p>
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

/** The prefilled request to follow a group: for GitHub, and as plain text for a message. */
export function groupRequest(kid: Pick<Kid, 'sportId' | 'competitionId' | 'competitionName' | 'categoryId' | 'categoryName' | 'groupId' | 'groupName' | 'teamName' | 'sportKind'>, fmt: Format) {
  const label = [fmt.t(`sport_${kid.sportKind}` as const), Format.sentence(kid.competitionName),
    kid.categoryName && kid.categoryName !== kid.competitionName ? Format.sentence(kid.categoryName) : null, kid.groupName]
    .filter(Boolean).join(' · ')
  const line = JSON.stringify({ groupId: kid.groupId, sportId: kid.sportId, competitionId: kid.competitionId, categoryId: kid.categoryId, label })
  const title = `PWA: añadir ${label}`
  const body = `Grupo para pwa/groups.json:\n\n\`\`\`json\n${line}\n\`\`\`\n\nEquipo: ${kid.teamName}\n`
  const url = `${REPO_URL}/issues/new?title=${encodeURIComponent(title)}&body=${encodeURIComponent(body)}`
  return { label, line, url, text: fmt.t('request_text', label, line) }
}

export async function shareRequest(text: string, fmt: Format): Promise<void> {
  try {
    if (navigator.share) {
      await navigator.share({ text })
      return
    }
  } catch (e) {
    if ((e as Error)?.name === 'AbortError') return
  }
  try {
    await navigator.clipboard.writeText(text)
    toast(fmt.t('copied'))
  } catch { /* nothing else to try */ }
}

/** A child whose group the data job does not follow yet: how to ask for it. */
export function GroupPending({ kid }: { kid: Kid }) {
  const fmt = useFmt()
  const req = groupRequest(kid, fmt)
  return (
    <FlatCard className="p-4" >
      <div data-testid="group-pending">
        <h3 className="text-[15px] font-bold">{fmt.t('group_pending_title')}</h3>
        <p className="mt-1 text-sm text-text-2">{fmt.t('group_pending_text', kid.name)}</p>
        <RequestButtons req={req} />
      </div>
    </FlatCard>
  )
}

export function RequestButtons({ req }: { req: ReturnType<typeof groupRequest> }) {
  const fmt = useFmt()
  return (
    <div className="mt-3 flex flex-col gap-2">
      <button type="button" onClick={() => void shareRequest(req.text, fmt)}
        className="flex min-h-12 items-center justify-center gap-2 rounded-xl bg-primary px-4 text-[15px] font-semibold text-on-primary">
        <Share size={18} />{fmt.t('ask_by_message')}
      </button>
      <a href={req.url} target="_blank" rel="noopener" className="flex min-h-11 items-center justify-center gap-1.5 rounded-xl bg-raised px-4 text-sm font-semibold">
        {fmt.t('ask_on_github')} <External size={15} />
      </a>
    </div>
  )
}
