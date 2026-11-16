import type { SportKind, Standings } from '../lib/data'
import { useFmt } from '../hooks/app-state'
import { Crest, Hairline } from './ui'

/**
 * The table from the friend's screenshot (the app's StandingsTable): the school's name on its own
 * line (long names whole), the numbers under it in fixed columns J G E P TF TC AVG Ptos. The
 * followed team's row is tinted in its child's colour. The points rule is stated under every
 * table, and said to be an assumption where it is one (basketball, volleyball).
 */
export function StandingsTable({ standings, sport, highlightTeamId = 0, accent, onTeam }: {
  standings: Standings
  sport: SportKind
  highlightTeamId?: number
  accent?: string | null
  onTeam?: (teamId: number) => void
}) {
  const fmt = useFmt()
  const { rule, rows } = standings
  const cols = [fmt.t('col_played'), fmt.t('col_won'), ...(rule.hasDraws ? [fmt.t('col_drawn')] : []), fmt.t('col_lost'),
    fmt.t('col_for'), fmt.t('col_against'), fmt.t('col_diff'), fmt.t('col_points')]
  const ruleText = rule.hasDraws ? fmt.t('standings_rule_draws', rule.win, rule.draw, rule.loss) : fmt.t('standings_rule', rule.win, rule.loss)
  return (
    <div data-testid="standings">
      {rows.length > 0 && rows.every((r) => r.played === 0) && (
        <p className="px-4 py-2.5 text-xs text-text-2">{fmt.t('standings_not_started')}</p>
      )}
      <div className="flex pl-14 pr-3 py-1.5 text-xs font-semibold text-text-2">
        {cols.map((c, i) => <span key={c} className="text-center" style={{ flex: i === cols.length - 1 ? 1.2 : 1 }}>{c}</span>)}
      </div>
      <Hairline />
      {rows.map((r) => {
        const mine = r.team.id === highlightTeamId
        const nums = [r.played, r.won, ...(rule.hasDraws ? [r.drawn] : []), r.lost, r.scored, r.conceded, r.scored - r.conceded, r.points]
        return (
          <div key={r.team.id}>
            <button type="button" onClick={() => onTeam?.(r.team.id)}
              className="flex w-full items-center py-2.5 pl-2 pr-3 text-left active:bg-raised"
              style={mine ? { background: `color-mix(in srgb, ${accent ?? 'var(--primary)'} 16%, transparent)` } : undefined}
              aria-label={`${r.position}º ${r.team.name}: ${r.points} pts`}>
              <span className="flex w-10 shrink-0 flex-col items-center gap-1">
                <span className={`tnum text-sm font-semibold ${mine ? '' : 'text-text-2'}`}>{r.position}</span>
                <Crest team={r.team} size={28} />
              </span>
              <span className="ml-2 min-w-0 flex-1">
                <span className="flex items-center gap-2">
                  <span className={`line-clamp-2 text-sm ${mine ? 'font-bold' : 'text-text-2'}`}>{r.team.name}</span>
                  {r.form.length > 0 && (
                    <span className="flex shrink-0 gap-[3px]">
                      {r.form.map((f, i) => (
                        <span key={i} className="h-2 w-2 rounded-full" style={{ background: f === 'W' ? 'var(--win)' : f === 'D' ? 'var(--draw)' : 'var(--loss)' }} />
                      ))}
                    </span>
                  )}
                </span>
                <span className="mt-0.5 flex">
                  {nums.map((n, i) => (
                    <span key={i} className={`tnum text-center text-base ${i === nums.length - 1 ? 'font-bold' : ''}`} style={{ flex: i === nums.length - 1 ? 1.2 : 1 }}>{n}</span>
                  ))}
                </span>
              </span>
            </button>
            <Hairline inset={56} />
          </div>
        )
      })}
      <p className="px-4 pt-2.5 text-xs text-text-2">{fmt.t('standings_footnote', ruleText)}</p>
      {!standings.measured && (
        <p className="px-4 pb-2.5 pt-1 text-xs text-text-2" data-testid="standings-assumed">{fmt.t('standings_assumed', fmt.t(`sport_${sport}` as const))}</p>
      )}
    </div>
  )
}
