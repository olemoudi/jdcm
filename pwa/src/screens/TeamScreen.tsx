import { useEffect } from 'react'
import { useData, useFmt, useKids } from '../hooks/app-state'
import { Format } from '../lib/format'
import { kidColor } from '../lib/kids'
import { goBack, navigate } from '../lib/router'
import { madridNow, nextMatch, outcomeFor, teamMatches } from '../lib/schedule'
import { MatchCard } from '../components/MatchCard'
import { openDirections, useOpenMatch } from '../components/MatchSheet'
import { Crest, FlatCard, Hairline, ScreenHeader, SecondaryButton } from '../components/ui'
import { FreshnessLine } from './shared'

/** A team: its next match, its season, its place in the table (the app's TeamScreen). */
export function TeamScreen({ groupId, teamId, kidId }: { groupId: number; teamId: number; kidId?: string }) {
  const fmt = useFmt()
  const { groups, want } = useData()
  const { kids } = useKids()
  useEffect(() => want(groupId), [groupId, want])
  const g = groups[groupId]
  const kid = kids.find((k) => k.id === kidId && k.teamId === teamId) ?? kids.find((k) => k.groupId === groupId && k.teamId === teamId)
  const accent = kid ? kidColor(kid.color) : null
  const team = g?.standings.rows.find((r) => r.team.id === teamId)?.team
    ?? g?.matches.flatMap((m) => [m.home, m.away]).find((t) => t?.id === teamId) ?? null
  const row = g?.standings.rows.find((r) => r.team.id === teamId)
  const title = g ? `${Format.sentence(g.group.competition)} · ${g.group.name}` : ''
  const openMatch = useOpenMatch()

  if (!g || !team) {
    return (
      <div className="pb-24">
        <ScreenHeader title={kid?.teamName ?? ''} onBack={goBack} />
        <p className="p-6 text-center text-sm text-text-2">{fmt.t('not_loaded')}</p>
      </div>
    )
  }
  const next = nextMatch(g, teamId, madridNow())
  const season = teamMatches(g, teamId)
  const open = (m: (typeof season)[number]) => openMatch({ match: m, groupId, title, highlightTeamId: teamId, accent, who: kid?.name, kidId: kid?.id })

  return (
    <div className="pb-24">
      <ScreenHeader title={g.group.name} subtitle={Format.sentence(g.group.competition)} onBack={goBack} />
      <div className="flex flex-col items-center px-6 pt-4 text-center">
        <Crest team={team} size={72} />
        <h2 className="mt-3 text-xl font-bold">{team.name}</h2>
        {row && (
          <p className="tnum mt-1 text-sm text-text-2">
            {fmt.t('position_points', row.position, row.points)} · {row.won}G {g.standings.rule.hasDraws ? `${row.drawn}E ` : ''}{row.lost}P
          </p>
        )}
        {kid ? (
          <span className="mt-2 rounded-full px-3 py-1 text-xs font-bold text-[#111214]" style={{ background: accent ?? undefined }}>{kid.name}</span>
        ) : (
          <SecondaryButton className="mt-3" onClick={() => navigate({ name: 'kid', groupId, teamId })}>{fmt.t('follow_as_kid')}</SecondaryButton>
        )}
      </div>
      {next && (
        <>
          <h3 className="px-4 pb-2 pt-5 text-[17px] font-bold">{fmt.t('next_match')}</h3>
          <FlatCard accent={accent}>
            <MatchCard match={next} onClick={() => open(next)} highlightTeamId={teamId} accent={accent} highlightLabel={kid?.name} onDirections={openDirections} />
          </FlatCard>
        </>
      )}
      <h3 className="px-4 pb-2 pt-5 text-[17px] font-bold">{fmt.t('season')}</h3>
      <FlatCard>
        {season.map((m, i) => {
          const o = outcomeFor(m, teamId)
          return (
            <div key={m.id}>
              {i > 0 && <Hairline />}
              <div className="flex items-center">
                <span className="w-9 shrink-0 pl-3 text-xs font-bold" style={{ color: o === 'W' ? 'var(--win)' : o === 'L' ? 'var(--loss)' : 'var(--draw)' }}>
                  {o ? fmt.t(o === 'W' ? 'col_won' : o === 'D' ? 'col_drawn' : 'col_lost') : fmt.t('round_short', m.roundNumber)}
                </span>
                <div className="min-w-0 flex-1">
                  <MatchCard match={m} compact onClick={() => open(m)} highlightTeamId={teamId} />
                </div>
              </div>
            </div>
          )
        })}
      </FlatCard>
      <FreshnessLine groupId={groupId} />
    </div>
  )
}
