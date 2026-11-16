import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import type { Match, Team } from '../lib/data'
import { directions, matchIcs, shareText } from '../lib/calendar'
import { Format } from '../lib/format'
import { isIos } from '../lib/platform'
import { navigate } from '../lib/router'
import { useFmt } from '../hooks/app-state'
import { CalendarAdd, ChevronRight, Clock, Directions, External, Info, Place, Share } from './Icons'
import { Crest, Hairline, Sheet, toast } from './ui'

export interface MatchArgs {
  match: Match
  groupId: number
  /** Group and competition, for the title line. */
  title: string
  highlightTeamId?: number
  accent?: string | null
  /** The child's name: the calendar event is titled with it. */
  who?: string
  kidId?: string
}

const MatchSheetContext = createContext<(a: MatchArgs) => void>(() => {})

export function useOpenMatch() {
  return useContext(MatchSheetContext)
}

const ios = () => isIos({ ua: navigator.userAgent, platform: navigator.platform, maxTouchPoints: navigator.maxTouchPoints })

/** Opens directions straight away (the side button of a match card). */
export function openDirections(m: Match) {
  const d = directions(m, ios())
  if (d) window.open(d.primary, '_blank', 'noopener')
}

export function MatchSheetHost({ children }: { children: ReactNode }) {
  const [args, setArgs] = useState<MatchArgs | null>(null)
  const open = useMemo(() => (a: MatchArgs) => setArgs(a), [])
  return (
    <MatchSheetContext.Provider value={open}>
      {children}
      {args && <MatchSheet args={args} onClose={() => setArgs(null)} />}
    </MatchSheetContext.Provider>
  )
}

function MatchSheet({ args, onClose }: { args: MatchArgs; onClose: () => void }) {
  const fmt = useFmt()
  const m = args.match
  const playable = !m.hasResult && m.status !== 'UNSCHEDULED'
  const dir = directions(m, ios())
  const minutes = Format.durationMinutes(m)

  const addToCalendar = () => {
    const ics = matchIcs(m, fmt, args.who ?? '')
    if (!ics) return
    const url = URL.createObjectURL(new Blob([ics], { type: 'text/calendar;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `jdcm-${m.id}.ics`
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 60_000)
  }

  const share = async () => {
    const text = shareText(m, fmt)
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
    } catch { /* nothing more to try */ }
  }

  const goTeam = (t: Team) => {
    onClose()
    navigate({ name: 'team', groupId: args.groupId, teamId: t.id, kidId: args.kidId })
  }

  return (
    <Sheet onClose={onClose} label={`${m.home?.name ?? ''} - ${m.away?.name ?? ''}`}>
      <div data-testid="match-sheet" className="pb-4">
        <p className="px-12 text-center text-sm font-semibold text-text-2">
          {[fmt.t('round_n', m.roundNumber), args.title || null].filter(Boolean).join(' · ')}
        </p>
        <div className="mt-4 flex items-start px-3">
          <BigTeam team={m.home} won={m.winner === 'HOME'} highlight={args.highlightTeamId} onClick={goTeam} />
          <div className="flex w-28 shrink-0 flex-col items-center pt-3">
            {m.hasResult ? (
              <>
                <span className="tnum text-4xl font-bold">{m.homeScore} - {m.awayScore}</span>
                <span className="text-sm font-semibold text-text-2">{fmt.t('status_final')}</span>
              </>
            ) : (
              <>
                <span className="tnum text-4xl font-bold" style={{ color: m.modified ? 'var(--modified)' : undefined }}>{fmt.time(m)}</span>
                {m.day && <span className="text-sm font-semibold text-text-2">{fmt.day(m.day)}</span>}
                {fmt.statusLabel(m) && <span className="mt-1 text-sm font-bold uppercase text-danger">{fmt.statusLabel(m)}</span>}
              </>
            )}
          </div>
          <BigTeam team={m.away} won={m.winner === 'AWAY'} highlight={args.highlightTeamId} onClick={goTeam} />
        </div>
        {m.partials.length > 0 && <p className="tnum mt-2 text-center text-sm text-text-2">{m.partials.join('   ')}</p>}

        <div className="mt-5 flex gap-2 px-3">
          {playable && dir && (
            <Action icon={<Directions size={22} />} label={fmt.t('action_directions')} href={dir.primary} />
          )}
          {playable && m.day && <Action icon={<CalendarAdd size={22} />} label={fmt.t('action_calendar')} onClick={addToCalendar} />}
          <Action icon={<Share size={22} />} label={fmt.t('action_share')} onClick={() => void share()} />
        </div>
        {playable && dir && (
          <a href={dir.secondary} target="_blank" rel="noopener" className="mt-2 flex items-center justify-center gap-1 text-sm text-link">
            {fmt.t('other_maps', dir.secondaryName)} <External size={14} />
          </a>
        )}

        <div className="mt-3"><Hairline /></div>
        <InfoRow icon={<Clock size={20} />} label={fmt.t('detail_when')} value={fmt.whenLine(m)} />
        <InfoRow icon={<Place size={20} />} label={fmt.t('detail_where')} value={fmt.venueLine(m.venue)} />
        {minutes && <InfoRow icon={<Clock size={20} />} label={fmt.t('detail_duration')} value={`${minutes} min`} />}
        {m.modified && (
          <InfoRow icon={<Info size={20} />} label={fmt.t('status_modified')} value={m.modifiedReason || fmt.t('modified_explained')} color="var(--modified)" />
        )}
        {(m.status === 'UNSCHEDULED' || m.status === 'POSTPONED') && (
          <InfoRow icon={<Info size={20} />} label={fmt.statusLabel(m) ?? ''} value={fmt.t('unscheduled_explained')} color="var(--danger)" />
        )}
        <Hairline />
        {[m.home, m.away].filter((t): t is Team => !!t).map((t) => (
          <button key={t.id} type="button" onClick={() => goTeam(t)} className="flex min-h-13 w-full items-center gap-3 px-4 py-2 text-left active:bg-raised">
            <Crest team={t} size={24} />
            <span className="min-w-0 flex-1 truncate text-sm">{fmt.t('see_team', t.name)}</span>
            <ChevronRight size={18} className="text-text-2" />
          </button>
        ))}
      </div>
    </Sheet>
  )
}

function BigTeam({ team, won, highlight, onClick }: { team: Team | null; won: boolean; highlight?: number; onClick: (t: Team) => void }) {
  const fmt = useFmt()
  return (
    <button type="button" disabled={!team} onClick={() => team && onClick(team)} className="flex min-w-0 flex-1 flex-col items-center gap-2 rounded-xl p-1 active:bg-raised">
      <Crest team={team} size={60} />
      <span className={`line-clamp-3 text-center text-sm ${won || (team && team.id === highlight) ? 'font-bold' : ''}`}>{team?.name ?? fmt.t('rests')}</span>
    </button>
  )
}

function Action({ icon, label, onClick, href }: { icon: ReactNode; label: string; onClick?: () => void; href?: string }) {
  const cls = 'flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 rounded-xl bg-raised px-2 text-xs font-semibold active:bg-raised-2'
  return href
    ? <a href={href} target="_blank" rel="noopener" className={cls}>{icon}<span className="truncate">{label}</span></a>
    : <button type="button" onClick={onClick} className={cls}>{icon}<span className="truncate">{label}</span></button>
}

function InfoRow({ icon, label, value, color }: { icon: ReactNode; label: string; value: string; color?: string }) {
  return (
    <div className="flex items-start gap-3.5 px-4 py-2.5">
      <span className="mt-0.5 text-text-2" style={color ? { color } : undefined}>{icon}</span>
      <span className="min-w-0">
        <span className="block text-xs font-semibold text-text-2" style={color ? { color } : undefined}>{label}</span>
        <span className="block text-sm">{value}</span>
      </span>
    </div>
  )
}
