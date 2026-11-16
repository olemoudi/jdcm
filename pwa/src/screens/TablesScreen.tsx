import { useData, useFmt, useKids } from '../hooks/app-state'
import { Format } from '../lib/format'
import { kidColor } from '../lib/kids'
import { navigate } from '../lib/router'
import { StandingsTable } from '../components/StandingsTable'
import { SportIcon } from '../components/Icons'
import { FlatCard, PrimaryButton } from '../components/ui'
import { FreshnessLine, GroupPending } from './shared'

/** Every child's group table, one under the other (the app's Clasificación tab). */
export function TablesScreen() {
  const fmt = useFmt()
  const { kids } = useKids()
  const { groups, index, state } = useData()
  const seen = new Set<number>()
  const list = kids.filter((k) => (seen.has(k.groupId) ? false : (seen.add(k.groupId), true)))
  return (
    <div className="pb-24">
      <header className="pt-safe">
        <h1 className="px-4 pb-1 pt-3 text-[28px] font-bold leading-tight">{fmt.t('tab_tables')}</h1>
      </header>
      {list.length === 0 && (
        <div className="px-8 pt-10 text-center">
          <p className="text-[15px] text-text-2">{fmt.t('tables_empty')}</p>
          <PrimaryButton className="mt-5 w-full max-w-xs" onClick={() => navigate({ name: 'kid' })}>{fmt.t('add_kid')}</PrimaryButton>
        </div>
      )}
      {list.map((k) => {
        const g = groups[k.groupId]
        const followed = !index || index.groups.some((x) => x.groupId === k.groupId)
        const names = kids.filter((x) => x.groupId === k.groupId).map((x) => x.name).join(', ')
        return (
          <section key={k.groupId} className="mt-3">
            <button type="button" onClick={() => navigate({ name: 'group', groupId: k.groupId, tab: 'standings', kidId: k.id })}
              className="flex w-full items-center gap-3 px-4 py-2 text-left active:bg-raised">
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full" style={{ background: kidColor(k.color), color: '#111214' }}>
                <SportIcon kind={k.sportKind} size={20} />
              </span>
              <span className="min-w-0">
                <span className="block truncate text-[17px] font-bold">{names}</span>
                <span className="block truncate text-xs text-text-2">{Format.sentence(k.competitionName)} · {k.groupName}</span>
              </span>
            </button>
            {!followed ? <GroupPending kid={k} /> : g ? (
              <>
                <FlatCard>
                  <StandingsTable standings={g.standings} sport={g.sport} highlightTeamId={k.teamId} accent={kidColor(k.color)}
                    onTeam={(teamId) => navigate({ name: 'team', groupId: k.groupId, teamId, kidId: k.id })} />
                </FlatCard>
                <FreshnessLine groupId={k.groupId} />
              </>
            ) : <FlatCard><p className="p-4 text-sm text-text-2">{state === 'loading' ? fmt.t('loading') : fmt.t('not_loaded')}</p></FlatCard>}
          </section>
        )
      })}
    </div>
  )
}
