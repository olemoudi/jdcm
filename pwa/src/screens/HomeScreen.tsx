import { useMemo, useState } from 'react'
import { useData, useFmt, useKids } from '../hooks/app-state'
import type { GroupData, Match } from '../lib/data'
import { Format } from '../lib/format'
import { kidColor, type Kid } from '../lib/kids'
import { navigate } from '../lib/router'
import { focusMatchday, involves, isBye, lastResult, madridToday, matchdays, matchOrder, opponentOf, outcomeFor } from '../lib/schedule'
import { readRaw, writeRaw } from '../lib/storage'
import { BUILD_LABEL } from '../lib/env'
import { InstallBanner } from '../components/InstallBanner'
import { MatchCard } from '../components/MatchCard'
import { openDirections, useOpenMatch } from '../components/MatchSheet'
import { KidPills, MatchdayStrip } from '../components/Strips'
import { ChevronDown, ChevronUp, EventBusy, SportIcon } from '../components/Icons'
import { FlatCard, Hairline, PrimaryButton } from '../components/ui'
import { FreshnessLine, StaleBanner } from './shared'

/** Home: the children's matchday, Marca-style (the app's HomeScreen). */
export function HomeScreen() {
  const fmt = useFmt()
  const { kids } = useKids()
  const { groups } = useData()
  const [selectedKid, setSelectedKid] = useState<string | null>(null)
  const [pickedDay, setPickedDay] = useState<string | null>(null)

  const visible = selectedKid ? kids.filter((k) => k.id === selectedKid) : kids
  const shown = visible.length ? visible : kids
  const today = madridToday()
  const snaps = shown.map((k) => groups[k.groupId]).filter((g): g is GroupData => !!g)
  const snapsKey = snaps.map((g) => `${g.ref.groupId}:${g.newestFetch}`).join(',')
  const days = useMemo(() => matchdays(snaps), [snapsKey]) // snaps is rebuilt every render; the key says when it changed
  const focus = focusMatchday(days, today)
  const day = pickedDay && days.includes(pickedDay) ? pickedDay : focus

  return (
    <div className="pb-24">
      <header className="pt-safe">
        <h1 className="px-4 pb-1 pt-3 text-[28px] font-bold leading-tight">{fmt.t('tab_matches')}</h1>
      </header>
      <InstallBanner />
      {kids.length > 0 && <StaleBanner />}
      {kids.length === 0 ? (
        <EmptyState />
      ) : (
        <>
          <KidPills kids={kids} selectedId={selectedKid} onSelect={setSelectedKid} onAdd={() => navigate({ name: 'kid' })} />
          {days.length > 0 && (
            <>
              <MatchdayStrip
                days={days}
                selected={day}
                current={focus}
                today={today}
                dotsFor={(d) => shown
                  .filter((k) => groups[k.groupId]?.matches.some((m) => involves(m, k.teamId) && !isBye(m) && m.matchday === d))
                  .map((k) => kidColor(k.color))}
                onSelect={setPickedDay}
                onToday={() => setPickedDay(null)}
              />
              <StripLegend kids={shown} />
              <Hairline />
            </>
          )}
          {shown.map((k) => <KidSection key={k.id} kid={k} group={groups[k.groupId]} day={day} />)}
        </>
      )}
      <p className="mt-6 text-center font-mono text-[10px] text-text-2" data-testid="build-label">{BUILD_LABEL}</p>
    </div>
  )
}

function EmptyState() {
  const fmt = useFmt()
  return (
    <div className="flex flex-col items-center px-8 pt-10 text-center" data-testid="empty-state">
      <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-raised text-primary">
        <SportIcon kind="FUTSAL" size={34} />
      </div>
      <h2 className="text-xl font-bold">{fmt.t('empty_title')}</h2>
      <p className="mt-2 text-[15px] text-text-2">{fmt.t('empty_text')}</p>
      <PrimaryButton className="mt-6 w-full max-w-xs" onClick={() => navigate({ name: 'kid' })}>{fmt.t('add_kid')}</PrimaryButton>
    </div>
  )
}

/** What the dots mean, said the first three times (a dot was not self-explanatory: the owner asked). */
function StripLegend({ kids }: { kids: Kid[] }) {
  const fmt = useFmt()
  const [show] = useState(() => {
    const n = Number(readRaw('legend-shown')) || 0
    if (n >= 3) return false
    writeRaw('legend-shown', String(n + 1))
    return true
  })
  if (!show || !kids.length) return null
  return (
    <div className="flex flex-wrap items-center gap-1.5 px-4 pb-2.5 text-xs">
      {kids.map((k) => (
        <span key={k.id} className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-full" style={{ background: kidColor(k.color) }} />
          <b>{k.name}</b>
        </span>
      ))}
      <span className="text-text-2">{fmt.t(kids.length === 1 ? 'strip_legend_one' : 'strip_legend_many')}</span>
    </div>
  )
}

/** One child: their match of the matchday first, the rest of the group's after, the doors at the foot. */
function KidSection({ kid, group, day }: { kid: Kid; group: GroupData | undefined; day: string | null }) {
  const fmt = useFmt()
  const { state } = useData()
  const openMatch = useOpenMatch()
  const [expanded, setExpanded] = useState(false)
  const accent = kidColor(kid.color)
  const position = group?.standings.rows.find((r) => r.team.id === kid.teamId && r.played > 0)
  const title = `${Format.sentence(kid.competitionName)} · ${kid.groupName}`

  const matches = useMemo(() => (group && day ? group.matches.filter((m) => m.matchday === day).sort(matchOrder) : []), [group, day])
  const mine = matches.find((m) => involves(m, kid.teamId)) ?? null
  const others = matches.filter((m) => m !== mine)
  const last = group ? lastResult(group, kid.teamId) : null
  const open = (m: Match) => openMatch({ match: m, groupId: kid.groupId, title, highlightTeamId: kid.teamId, accent, who: kid.name, kidId: kid.id })

  return (
    <section className="mt-3" data-testid={`kid-${kid.id}`}>
      <button type="button" onClick={() => navigate({ name: 'group', groupId: kid.groupId, tab: 'standings', kidId: kid.id })}
        className="flex w-full items-center gap-3 px-4 py-2 text-left active:bg-raised">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full" style={{ background: accent, color: '#111214' }}>
          <SportIcon kind={kid.sportKind} size={20} />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[17px] font-bold">{kid.name}</span>
          <span className="block truncate text-xs text-text-2">{title} · {kid.teamName}</span>
        </span>
        {position && <span className="tnum shrink-0 text-sm font-semibold text-text-2">{fmt.t('position_points', position.position, position.points)}</span>}
      </button>

      {!group ? (
        <FlatCard><p className="p-4 text-sm text-text-2">{state === 'loading' ? fmt.t('loading') : fmt.t('not_loaded')}</p></FlatCard>
      ) : (
        <>
          <FlatCard accent={mine && !isBye(mine) ? accent : null}>
            {mine ? (
              <MatchCard match={mine} onClick={() => open(mine)} highlightTeamId={kid.teamId} accent={accent} highlightLabel={kid.name} onDirections={openDirections} />
            ) : (
              <NoMatchRow kid={kid} group={group} day={day} />
            )}
          </FlatCard>
          {last && (!mine || !mine.hasResult) && last.id !== mine?.id && <LastResultRow kid={kid} m={last} onClick={() => open(last)} />}
          {others.length > 0 ? (
            <>
              <button type="button" onClick={() => setExpanded(!expanded)} aria-expanded={expanded}
                className="flex min-h-12 w-full items-center px-5 text-[15px] text-link">
                <span className="flex-1 text-left">{fmt.t('rest_of_round', others.filter((m) => !isBye(m)).length)}</span>
                {expanded ? <ChevronUp size={20} /> : <ChevronDown size={20} />}
              </button>
              {expanded && (
                <FlatCard>
                  {others.map((m, i) => (
                    <div key={m.id}>
                      {i > 0 && <Hairline />}
                      <MatchCard match={m} compact onClick={() => open(m)} highlightTeamId={kid.teamId} accent={accent} />
                    </div>
                  ))}
                </FlatCard>
              )}
            </>
          ) : <div className="h-1" />}
          <div className="flex justify-evenly px-3">
            {([
              [fmt.t('link_calendar'), () => navigate({ name: 'group', groupId: kid.groupId, tab: 'calendar', kidId: kid.id })],
              [fmt.t('link_standings'), () => navigate({ name: 'group', groupId: kid.groupId, tab: 'standings', kidId: kid.id })],
              [fmt.t('link_team'), () => navigate({ name: 'team', groupId: kid.groupId, teamId: kid.teamId, kidId: kid.id })],
            ] as const).map(([label, go]) => (
              <button key={label} type="button" onClick={go} className="min-h-12 px-3 text-[15px] text-link">{label}</button>
            ))}
          </div>
          <FreshnessLine groupId={kid.groupId} />
        </>
      )}
    </section>
  )
}

function NoMatchRow({ kid, group, day }: { kid: Kid; group: GroupData; day: string | null }) {
  const fmt = useFmt()
  const next = day ? group.matches.filter((m) => involves(m, kid.teamId) && !isBye(m) && (m.matchday ?? '') > day).sort(matchOrder)[0] : null
  return (
    <div className="flex items-center gap-3 p-4">
      <EventBusy size={22} className="shrink-0 text-text-2" />
      <div>
        <p className="text-[15px]">{fmt.t('no_match_this_round', kid.name)}</p>
        {next && <p className="text-xs text-text-2">{fmt.t('next_on', fmt.whenLine(next))}</p>}
      </div>
    </div>
  )
}

function LastResultRow({ kid, m, onClick }: { kid: Kid; m: Match; onClick: () => void }) {
  const fmt = useFmt()
  const o = outcomeFor(m, kid.teamId)
  if (!o) return null
  const home = m.home?.id === kid.teamId
  const mine = home ? m.homeScore : m.awayScore
  const theirs = home ? m.awayScore : m.homeScore
  const color = o === 'W' ? 'var(--win)' : o === 'D' ? 'var(--draw)' : 'var(--loss)'
  return (
    <button type="button" onClick={onClick} className="flex min-h-13 w-full items-center gap-2.5 px-5 py-2 text-left active:bg-raised" data-testid="last-result">
      <span className="shrink-0 text-sm font-semibold text-text-2">{fmt.t('last_result')}</span>
      <span className="shrink-0 text-sm font-bold" style={{ color }}>{fmt.t(`res_${o}` as const, mine ?? 0, theirs ?? 0)}</span>
      <span className="min-w-0 flex-1 truncate text-xs text-text-2">
        {fmt.t('vs_team', opponentOf(m, kid.teamId)?.name ?? '')}{m.day ? ` · ${fmt.dayMonth(m.day)}` : ''}
      </span>
    </button>
  )
}
