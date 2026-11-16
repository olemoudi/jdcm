import type { ReactNode } from 'react'
import type { Match, Team } from '../lib/data'
import { madridNow, isBye, isLive } from '../lib/schedule'
import { useFmt } from '../hooks/app-state'
import { Crest, KidTag } from './ui'
import { Directions, Place } from './Icons'

/**
 * One match, the way Marca draws it (the app's MatchCard): a status line, the two teams on their
 * own rows with the score at the right, and a side button for the one action that matters (how to
 * get there). The followed team is bold with its child's colour; the winner is bold once there is a result.
 */
export function MatchCard({
  match,
  onClick,
  highlightTeamId = 0,
  accent,
  highlightLabel,
  compact = false,
  showDate = true,
  onDirections,
}: {
  match: Match
  onClick: () => void
  highlightTeamId?: number
  accent?: string | null
  highlightLabel?: string
  compact?: boolean
  showDate?: boolean
  onDirections?: (m: Match) => void
}) {
  const fmt = useFmt()
  if (isBye(match)) {
    const team = match.home ?? match.away
    const mine = team?.id === highlightTeamId
    return (
      <div className="flex items-center gap-2.5 px-3.5 py-2.5">
        <Crest team={team} size={22} />
        <span className={`min-w-0 flex-1 truncate text-sm ${mine ? 'font-bold' : ''}`}>{team?.name}</span>
        {mine && accent && highlightLabel && <KidTag name={highlightLabel} color={accent} />}
        <span className="text-sm font-semibold text-text-2">{fmt.t('rests')}</span>
      </div>
    )
  }
  const result = match.hasResult
  const canGo = !!onDirections && !!match.venue && !result && match.status !== 'UNSCHEDULED'
  const description = [
    `${match.home?.name}${result ? ` ${match.homeScore}` : ''}`,
    `${match.away?.name}${result ? ` ${match.awayScore}` : ''}`,
    fmt.whenLine(match),
    fmt.statusLabel(match),
    compact ? null : fmt.venueLine(match.venue),
  ].filter(Boolean).join('. ')
  return (
    <div className="flex items-stretch" data-match-id={match.id}>
      {accent && highlightTeamId !== 0 && (match.home?.id === highlightTeamId || match.away?.id === highlightTeamId) && (
        <div className="w-1 shrink-0" style={{ background: accent }} />
      )}
      <button type="button" onClick={onClick} aria-label={description}
        className={`min-w-0 flex-1 text-left active:bg-raised ${compact ? 'py-2 pl-3.5 pr-2' : 'py-3 pl-3.5 pr-2'}`}>
        <StatusLine match={match} showDate={showDate} />
        <div className={`flex items-center ${compact ? 'mt-1' : 'mt-2'}`}>
          <div className={`min-w-0 flex-1 ${compact ? 'space-y-1' : 'space-y-2'}`}>
            <TeamLine team={match.home} match={match} highlightTeamId={highlightTeamId} accent={accent} label={highlightLabel} compact={compact} />
            <TeamLine team={match.away} match={match} highlightTeamId={highlightTeamId} accent={accent} label={highlightLabel} compact={compact} />
          </div>
          <RightColumn match={match} compact={compact} />
        </div>
        {!compact && match.venue && !result && match.status !== 'UNSCHEDULED' && (
          <div className="mt-2.5 flex items-start gap-1 text-xs text-text-2">
            <Place size={15} className="mt-px shrink-0" />
            <span className="line-clamp-2">{fmt.venueLine(match.venue)}</span>
          </div>
        )}
      </button>
      {canGo && !compact && (
        <div className="flex items-center border-l border-line my-2.5">
          <button type="button" onClick={() => onDirections?.(match)} aria-label={fmt.t('action_directions')}
            className="flex h-full min-w-14 items-center justify-center px-3 text-text-2 active:bg-raised">
            <Directions size={24} />
          </button>
        </div>
      )}
    </div>
  )
}

function Label({ text, color }: { text: string; color: string }) {
  return <span className="text-[11px] font-bold uppercase tracking-wide" style={{ color }}>{text}</span>
}

function StatusLine({ match, showDate }: { match: Match; showDate: boolean }) {
  const fmt = useFmt()
  const live = isLive(match, madridNow())
  let label: ReactNode = null
  if (match.status === 'UNSCHEDULED') label = <Label text={fmt.t('status_unscheduled')} color="var(--danger)" />
  else if (match.status === 'POSTPONED') label = <Label text={fmt.t('status_postponed')} color="var(--danger)" />
  else if (match.status === 'NOT_HELD') label = <Label text={fmt.t('status_not_held')} color="var(--danger)" />
  else if (match.status === 'PLAYED' && !match.hasResult) label = <Label text={fmt.t('status_no_score')} color="var(--text-2)" />
  else if (match.hasResult) label = <span className="text-sm text-text-2 underline">{fmt.t('status_final')}</span>
  else if (live) label = <Label text={fmt.t('status_live')} color="var(--primary)" />
  return (
    <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
      {label}
      {match.modified && match.status !== 'UNSCHEDULED' && !match.hasResult && <Label text={fmt.t('status_modified')} color="var(--modified)" />}
      {(showDate || !match.timeKnown) && (
        <span className="text-sm text-text-2">{match.day ? fmt.day(match.day) : fmt.t('no_date')}</span>
      )}
    </div>
  )
}

function TeamLine({ team, match, highlightTeamId, accent, label, compact }: {
  team: Team | null; match: Match; highlightTeamId: number; accent?: string | null; label?: string; compact: boolean
}) {
  const winner = match.hasResult && team && ((match.winner === 'HOME' && match.home?.id === team.id) || (match.winner === 'AWAY' && match.away?.id === team.id))
  const mine = !!team && team.id === highlightTeamId
  return (
    <div className="flex items-center gap-2.5">
      <Crest team={team} size={compact ? 22 : 28} />
      <span className={`line-clamp-2 min-w-0 ${compact ? 'text-sm' : 'text-[15px]'} ${winner || mine ? 'font-bold' : ''}`}>{team?.name}</span>
      {mine && accent && label && <KidTag name={label} color={accent} />}
    </div>
  )
}

function RightColumn({ match, compact }: { match: Match; compact: boolean }) {
  const fmt = useFmt()
  if (match.hasResult) {
    const size = compact ? 'text-base' : 'text-2xl'
    return (
      <div className={`tnum ml-2 flex flex-col items-end ${compact ? 'gap-1' : 'gap-2'}`}>
        <span className={`${size} leading-7 ${match.winner === 'HOME' ? 'font-bold' : ''}`}>{match.homeScore}</span>
        <span className={`${size} leading-7 ${match.winner === 'AWAY' ? 'font-bold' : ''}`}>{match.awayScore}</span>
      </div>
    )
  }
  const tbc = !match.timeKnown
  const color = match.status === 'UNSCHEDULED' || match.status === 'POSTPONED' || match.status === 'NOT_HELD'
    ? 'var(--text-2)'
    : match.modified ? 'var(--modified)' : 'var(--text)'
  return (
    <div className="ml-2 flex flex-col items-end">
      <span className={`tnum ${compact ? 'text-base' : 'text-xl'} font-semibold ${match.status === 'UNSCHEDULED' ? 'line-through' : ''}`} style={{ color }}>
        {tbc ? '--:--' : fmt.time(match)}
      </span>
      {tbc && !compact && <span className="text-[11px] text-text-2">{fmt.t('time_tbc_short')}</span>}
    </div>
  )
}
