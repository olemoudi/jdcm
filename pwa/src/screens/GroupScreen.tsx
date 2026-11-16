import { useEffect, useMemo, useRef, useState } from 'react'
import { useData, useFmt, useKids } from '../hooks/app-state'
import { Format } from '../lib/format'
import { kidColor } from '../lib/kids'
import { goBack, navigate } from '../lib/router'
import { focusRound, madridToday, matchOrder } from '../lib/schedule'
import { MatchCard } from '../components/MatchCard'
import { openDirections, useOpenMatch } from '../components/MatchSheet'
import { StandingsTable } from '../components/StandingsTable'
import { ChevronLeft, ChevronRight } from '../components/Icons'
import { FlatCard, Hairline, ScreenHeader } from '../components/ui'
import { FreshnessLine } from './shared'

/** A group: its calendar round by round, and the computed standings (the app's GroupScreen). */
export function GroupScreen({ groupId, tab, kidId }: { groupId: number; tab: 'calendar' | 'standings'; kidId?: string }) {
  const fmt = useFmt()
  const { groups, want, state } = useData()
  const { kids } = useKids()
  useEffect(() => want(groupId), [groupId, want])
  const g = groups[groupId]
  const kid = kids.find((k) => k.id === kidId) ?? kids.find((k) => k.groupId === groupId)
  const accent = kid ? kidColor(kid.color) : null
  const title = g?.group.name || kid?.groupName || ''
  const subtitle = Format.sentence(g?.group.competition || kid?.competitionName || '')

  const setTab = (t: 'calendar' | 'standings') => navigate({ name: 'group', groupId, tab: t, kidId }, true)

  return (
    <div className="pb-24">
      <ScreenHeader title={title} subtitle={subtitle} onBack={goBack} />
      <div className="sticky z-10 bg-bg px-3 py-2" style={{ top: 'calc(env(safe-area-inset-top, 0px) + 49px)' }}>
        <div className="flex rounded-xl bg-raised p-1" role="tablist">
          {(['calendar', 'standings'] as const).map((t) => (
            <button key={t} type="button" role="tab" aria-selected={tab === t} onClick={() => setTab(t)}
              className={`min-h-9 flex-1 rounded-lg text-sm font-semibold ${tab === t ? 'bg-card shadow-sm' : 'text-text-2'}`}>
              {t === 'calendar' ? fmt.t('link_calendar') : fmt.t('link_standings')}
            </button>
          ))}
        </div>
      </div>
      {!g ? (
        <p className="p-6 text-center text-sm text-text-2">{state === 'loading' ? '…' : fmt.t('not_loaded')}</p>
      ) : tab === 'standings' ? (
        <>
          <FlatCard className="mt-1">
            <StandingsTable standings={g.standings} sport={g.sport} highlightTeamId={kid?.teamId} accent={accent}
              onTeam={(teamId) => navigate({ name: 'team', groupId, teamId, kidId })} />
          </FlatCard>
          <FreshnessLine groupId={groupId} />
        </>
      ) : (
        <Calendar groupId={groupId} kidId={kidId} accent={accent} highlightTeamId={kid?.teamId ?? 0} who={kid?.name} title={`${subtitle} · ${title}`} />
      )}
    </div>
  )
}

function Calendar({ groupId, kidId, accent, highlightTeamId, who, title }: {
  groupId: number; kidId?: string; accent: string | null; highlightTeamId: number; who?: string; title: string
}) {
  const fmt = useFmt()
  const g = useData().groups[groupId]
  const openMatch = useOpenMatch()
  const initial = useMemo(() => focusRound(g, madridToday())?.id ?? 0, [g])
  const [roundId, setRoundId] = useState(initial)
  const i = Math.max(0, g.rounds.findIndex((r) => r.id === roundId))
  const round = g.rounds[i]
  const matches = round ? g.matches.filter((m) => m.roundId === round.id).sort(matchOrder) : []
  const chips = useRef<HTMLDivElement>(null)
  useEffect(() => {
    chips.current?.querySelector<HTMLElement>('[aria-selected="true"]')?.scrollIntoView?.({ inline: 'center', block: 'nearest' })
  }, [roundId])
  return (
    <>
      <div ref={chips} className="no-scrollbar flex gap-1.5 overflow-x-auto px-3 pb-2" role="tablist">
        {g.rounds.map((r) => (
          <button key={r.id} type="button" role="tab" aria-selected={r.id === round?.id} onClick={() => setRoundId(r.id)}
            className={`flex min-h-12 shrink-0 flex-col items-center justify-center rounded-lg px-3 ${r.id === round?.id ? 'bg-primary text-on-primary' : 'bg-raised'}`}>
            <span className="text-sm font-bold">{fmt.t('round_short', r.number)}</span>
            {r.date && <span className="text-[11px] opacity-80">{fmt.dayMonth(r.date)}</span>}
          </button>
        ))}
      </div>
      {round && (
        <div className="flex items-center px-1">
          <button type="button" disabled={i === 0} onClick={() => setRoundId(g.rounds[i - 1].id)} aria-label={fmt.t('previous_round')}
            className="flex h-11 w-11 items-center justify-center text-link disabled:opacity-30"><ChevronLeft size={22} /></button>
          <p className="flex-1 text-center text-[15px] font-bold">
            {fmt.t('round_n', round.number)}{round.date ? ` · ${fmt.day(round.date)}` : ''}
          </p>
          <button type="button" disabled={i >= g.rounds.length - 1} onClick={() => setRoundId(g.rounds[i + 1].id)} aria-label={fmt.t('next_round')}
            className="flex h-11 w-11 items-center justify-center text-link disabled:opacity-30"><ChevronRight size={22} /></button>
        </div>
      )}
      <FlatCard>
        {matches.length === 0 && <p className="p-4 text-sm text-text-2">{fmt.t('no_matches_round')}</p>}
        {matches.map((m, k) => (
          <div key={m.id}>
            {k > 0 && <Hairline />}
            <MatchCard match={m} highlightTeamId={highlightTeamId} accent={accent} highlightLabel={who} onDirections={openDirections}
              onClick={() => openMatch({ match: m, groupId, title, highlightTeamId, accent, who, kidId })} />
          </div>
        ))}
      </FlatCard>
      <FreshnessLine groupId={groupId} />
    </>
  )
}
