import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useData, useFmt, useKids } from '../hooks/app-state'
import { sportKindOfId, type CatalogCategory, type CatalogCompetition, type CatalogSport, type Team } from '../lib/data'
import { ageHint, Format } from '../lib/format'
import { KID_COLORS, newKidId, nextColor, type Kid } from '../lib/kids'
import { currentEnv, installMode } from '../lib/platform'
import { goBack, navigate } from '../lib/router'
import { Check, ChevronRight, Search, SportIcon } from '../components/Icons'
import { Crest, FlatCard, Hairline, PrimaryButton, ScreenHeader, SecondaryButton } from '../components/ui'
import { groupRequest, RequestButtons } from './shared'

type Step = 'name' | 'sport' | 'competition' | 'category' | 'team' | 'pending'

interface Pick {
  sportId: number
  competition?: CatalogCompetition
  category?: CatalogCategory
  team?: Team
  groupId?: number
  groupName?: string
}

const SPORT_ORDER = [2, 1, 3] // futsal, basketball, volleyball: the app's order

/**
 * Add or edit a child: name and colour, then sport → age group → (category) → team, searched by
 * school across every group of the category (the catalogue the data job publishes). A team whose
 * group the data job does not follow yet can still be saved, with a way to ask for it.
 */
export function KidEditor({ kidId, groupId, teamId }: { kidId?: string; groupId?: number; teamId?: number }) {
  const fmt = useFmt()
  const { kids, upsert, remove } = useKids()
  const { catalog, catalogState, loadCatalog, groups, index } = useData()
  const editing = kids.find((k) => k.id === kidId)
  const [step, setStep] = useState<Step>('name')
  const [name, setName] = useState(editing?.name ?? '')
  const [color, setColor] = useState(editing?.color ?? nextColor(kids))
  const [pick, setPick] = useState<Pick | null>(null)
  const [query, setQuery] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  useEffect(() => { loadCatalog() }, [loadCatalog])

  // Opened from a team ("This is my child's team"): the team is known already.
  const preset = useMemo((): Kid | null => {
    if (!groupId || !teamId) return null
    const g = groups[groupId]
    const team = g?.matches.flatMap((m) => [m.home, m.away]).find((t) => t?.id === teamId)
    if (!g || !team) return null
    return {
      id: '', name: '', color: 0, sportId: g.ref.sportId, sportKind: g.sport, competitionId: g.ref.competitionId,
      competitionName: g.group.competition, categoryId: g.ref.categoryId, categoryName: g.group.category,
      groupId, groupName: g.group.name, teamId, teamName: team.name, crest: team.crest,
    }
  }, [groupId, teamId, groups])

  const sport: CatalogSport | undefined = catalog?.sports.find((s) => s.id === pick?.sportId)
  const ios = installMode(currentEnv(false))
  const steps: Step[] = ['name', 'sport', 'competition', 'team']
  const stepNo = Math.min(steps.length, steps.indexOf(step === 'category' ? 'competition' : step === 'pending' ? 'team' : step) + 1)

  const followed = (gid: number) => !index || index.groups.some((g) => g.groupId === gid)

  const save = (base: Omit<Kid, 'id' | 'name' | 'color'>) => {
    const kid: Kid = { ...base, id: editing?.id ?? newKidId(), name: name.trim(), color }
    upsert(kid)
    navigate({ name: 'home' }, true)
  }

  const saveFromPick = () => {
    if (!pick?.team || !pick.competition || !pick.category || !pick.groupId) return
    save({
      sportId: pick.sportId, sportKind: sportKindOfId(pick.sportId),
      competitionId: pick.competition.id, competitionName: pick.competition.name,
      categoryId: pick.category.id, categoryName: pick.category.name,
      groupId: pick.groupId, groupName: pick.groupName ?? '', teamId: pick.team.id, teamName: pick.team.name, crest: pick.team.crest,
    })
  }

  const afterName = () => {
    if (!name.trim()) return
    if (preset) return save(preset)
    if (editing && step === 'name') return save(editing)
    setStep('sport')
  }

  const header = (
    <ScreenHeader
      title={editing ? fmt.t('edit_kid') : fmt.t('new_kid')}
      subtitle={preset || editing ? undefined : fmt.t('step_of', stepNo, steps.length)}
      onBack={() => {
        if (step === 'name') goBack()
        else if (step === 'sport') setStep('name')
        else if (step === 'competition') setStep('sport')
        else if (step === 'category') setStep('competition')
        else if (step === 'team') setStep(pick?.competition && pick.competition.categories.length > 1 ? 'category' : 'competition')
        else setStep('team')
      }}
    />
  )

  if (step === 'name') {
    return (
      <div className="pb-28">
        {header}
        {!editing && ios.startsWith('ios') && (
          <p className="mx-4 mt-3 rounded-xl bg-primary-soft p-3 text-sm" data-testid="ios-install-first">{fmt.t('ios_install_first')}</p>
        )}
        <div className="px-4 pt-4">
          <h2 className="text-xl font-bold">{fmt.t('kid_name_question')}</h2>
          <p className="mt-1 text-sm text-text-2">{fmt.t('kid_name_hint')}</p>
          <input value={name} onChange={(e) => setName(e.target.value.slice(0, 40))} placeholder={fmt.t('kid_name')} aria-label={fmt.t('kid_name')}
            autoComplete="off" autoCapitalize="words" enterKeyHint="next" onKeyDown={(e) => { if (e.key === 'Enter') afterName() }}
            className="mt-4 h-12 w-full rounded-xl border border-line bg-card px-4 text-base outline-none focus:border-primary" />
          <h3 className="mt-6 text-sm font-semibold text-text-2">{fmt.t('kid_color')}</h3>
          <div className="mt-2 flex gap-3" role="radiogroup" aria-label={fmt.t('kid_color')}>
            {KID_COLORS.map((c, i) => (
              <button key={c} type="button" role="radio" aria-checked={color === i} aria-label={c} onClick={() => setColor(i)}
                className="flex h-11 w-11 items-center justify-center rounded-full" style={{ background: c, boxShadow: color === i ? '0 0 0 3px var(--bg), 0 0 0 5px var(--text)' : undefined }}>
                {color === i && <Check size={20} className="text-[#111214]" />}
              </button>
            ))}
          </div>
          {preset && (
            <FlatCard className="mx-0 mt-6">
              <div className="flex items-center gap-3 p-3">
                <Crest team={{ id: preset.teamId, name: preset.teamName, crest: preset.crest }} size={36} />
                <div className="min-w-0">
                  <p className="truncate text-[15px] font-semibold">{preset.teamName}</p>
                  <p className="truncate text-xs text-text-2">{fmt.t(`sport_${preset.sportKind}` as const)} · {Format.sentence(preset.competitionName)} · {preset.groupName}</p>
                </div>
              </div>
            </FlatCard>
          )}
          {editing && (
            <>
              <FlatCard className="mx-0 mt-6">
                <div className="flex items-center gap-3 p-3">
                  <Crest team={{ id: editing.teamId, name: editing.teamName, crest: editing.crest }} size={36} />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[15px] font-semibold">{editing.teamName}</p>
                    <p className="truncate text-xs text-text-2">{fmt.t(`sport_${editing.sportKind}` as const)} · {Format.sentence(editing.competitionName)} · {editing.groupName}</p>
                  </div>
                </div>
                <Hairline />
                <button type="button" onClick={() => name.trim() && setStep('sport')} className="min-h-12 w-full px-3 text-left text-[15px] text-link">{fmt.t('change_team')}</button>
              </FlatCard>
              {!confirmDelete ? (
                <button type="button" onClick={() => setConfirmDelete(true)} className="mt-6 min-h-12 w-full rounded-xl text-[15px] font-semibold text-danger active:bg-raised">
                  {fmt.t('delete_kid', editing.name)}
                </button>
              ) : (
                <div className="mt-6 rounded-xl border border-danger/50 p-4">
                  <p className="text-sm">{fmt.t('delete_kid_text')}</p>
                  <div className="mt-3 flex gap-2">
                    <SecondaryButton className="flex-1" onClick={() => setConfirmDelete(false)}>{fmt.t('cancel')}</SecondaryButton>
                    <button type="button" onClick={() => { remove(editing.id); navigate({ name: 'home' }, true) }}
                      className="min-h-12 flex-1 rounded-xl bg-danger font-semibold text-white">{fmt.t('delete')}</button>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
        <BottomAction>
          <PrimaryButton className="w-full" disabled={!name.trim()} onClick={afterName}>
            {preset || editing ? fmt.t('save') : fmt.t('next')}
          </PrimaryButton>
        </BottomAction>
      </div>
    )
  }

  const catalogMissing = !catalog && (
    <p className="p-6 text-center text-sm text-text-2">{catalogState === 'loading' || catalogState === 'idle' ? fmt.t('catalog_loading') : fmt.t('catalog_error')}</p>
  )

  if (step === 'sport') {
    return (
      <div className="pb-24">
        {header}
        <h2 className="px-4 pt-4 text-xl font-bold">{fmt.t('kid_sport_question')}</h2>
        <div className="mt-4 space-y-2 px-4">
          {SPORT_ORDER.map((id) => (
            <button key={id} type="button" onClick={() => { setPick({ sportId: id }); setStep('competition') }}
              className="flex min-h-16 w-full items-center gap-4 rounded-xl border border-line bg-card px-4 text-left active:bg-raised">
              <SportIcon kind={sportKindOfId(id)} size={30} className="text-primary" />
              <span className="flex-1 text-[17px] font-semibold">{fmt.t(`sport_${sportKindOfId(id)}` as const)}</span>
              <ChevronRight size={20} className="text-text-2" />
            </button>
          ))}
        </div>
      </div>
    )
  }

  if (step === 'competition' || step === 'category') {
    const list = step === 'competition' ? sport?.competitions ?? [] : pick?.competition?.categories ?? []
    return (
      <div className="pb-24">
        {header}
        <h2 className="px-4 pt-4 text-xl font-bold">{fmt.t(step === 'competition' ? 'kid_competition_question' : 'kid_category_question')}</h2>
        <p className="px-4 pt-1 text-sm text-text-2">{fmt.t('kid_category_hint')}</p>
        {catalogMissing || (
          <FlatCard className="mt-4">
            {list.map((c, i) => {
              const hint = ageHint(c.name)
              return (
                <div key={c.id}>
                  {i > 0 && <Hairline />}
                  <button type="button" className="flex min-h-14 w-full items-center gap-3 px-4 py-2 text-left active:bg-raised"
                    onClick={() => {
                      if (step === 'competition') {
                        const comp = c as CatalogCompetition
                        const only = comp.categories.length === 1 ? comp.categories[0] : undefined
                        setPick({ sportId: pick!.sportId, competition: comp, category: only })
                        setStep(only ? 'team' : 'category')
                      } else {
                        setPick({ ...pick!, category: c as CatalogCategory })
                        setStep('team')
                      }
                      setQuery('')
                    }}>
                    <span className="min-w-0 flex-1">
                      <span className="block text-[15px] font-semibold">{Format.sentence(c.name)}</span>
                      {hint && step === 'competition' && <span className="block text-xs text-text-2">{fmt.t('age_hint', hint)}</span>}
                    </span>
                    <ChevronRight size={20} className="text-text-2" />
                  </button>
                </div>
              )
            })}
          </FlatCard>
        )}
      </div>
    )
  }

  if (step === 'team') {
    const all = (pick?.category?.groups ?? []).flatMap((g) => g.teams.map((t) => ({ team: t, group: g })))
    const q = fold(query.trim())
    const shown = q ? all.filter((x) => fold(x.team.name).includes(q)) : all
    return (
      <div className="pb-24">
        {header}
        <h2 className="px-4 pt-4 text-xl font-bold">{fmt.t('kid_team_question')}</h2>
        <p className="px-4 pt-1 text-sm text-text-2">{Format.sentence(pick?.competition?.name ?? '')}{pick?.category && pick.category.name !== pick.competition?.name ? ` · ${Format.sentence(pick.category.name)}` : ''}</p>
        <div className="sticky z-10 bg-bg px-4 py-3" style={{ top: 'calc(env(safe-area-inset-top, 0px) + 49px)' }}>
          <label className="flex h-12 items-center gap-2 rounded-xl border border-line bg-card px-3 focus-within:border-primary">
            <Search size={20} className="text-text-2" />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={fmt.t('search_school')} aria-label={fmt.t('search_school')}
              type="search" autoComplete="off" className="h-full min-w-0 flex-1 bg-transparent text-base outline-none" />
          </label>
        </div>
        {shown.length === 0 && <p className="px-4 py-6 text-center text-sm text-text-2">{fmt.t('no_team_found')}</p>}
        <FlatCard>
          {shown.map(({ team, group }, i) => (
            <div key={`${group.id}-${team.id}`}>
              {i > 0 && <Hairline inset={56} />}
              <button type="button" className="flex min-h-14 w-full items-center gap-3 px-4 py-2 text-left active:bg-raised"
                onClick={() => {
                  const next = { ...pick!, team, groupId: group.id, groupName: group.name }
                  setPick(next)
                  if (followed(group.id)) {
                    if (!next.competition || !next.category) return
                    save({
                      sportId: next.sportId, sportKind: sportKindOfId(next.sportId),
                      competitionId: next.competition.id, competitionName: next.competition.name,
                      categoryId: next.category.id, categoryName: next.category.name,
                      groupId: group.id, groupName: group.name, teamId: team.id, teamName: team.name, crest: team.crest,
                    })
                  } else setStep('pending')
                }}>
                <Crest team={team} size={30} />
                <span className="min-w-0 flex-1">
                  <span className="block text-[15px]">{team.name}</span>
                  <span className="block text-xs text-text-2">
                    {group.name}{followed(group.id) ? '' : ` · ${fmt.t('group_not_followed')}`}
                  </span>
                </span>
              </button>
            </div>
          ))}
        </FlatCard>
      </div>
    )
  }

  // step === 'pending': the group is not followed by the data job yet.
  const req = pick?.team && pick.competition && pick.category && pick.groupId
    ? groupRequest({
        sportId: pick.sportId, sportKind: sportKindOfId(pick.sportId), competitionId: pick.competition.id, competitionName: pick.competition.name,
        categoryId: pick.category.id, categoryName: pick.category.name, groupId: pick.groupId, groupName: pick.groupName ?? '', teamName: pick.team.name,
      }, fmt)
    : null
  return (
    <div className="pb-24">
      {header}
      <div className="px-4 pt-4" data-testid="not-followed">
        <h2 className="text-xl font-bold">{fmt.t('not_followed_title')}</h2>
        <p className="mt-2 text-[15px] text-text-2">{fmt.t('not_followed_text')}</p>
        {req && <p className="mt-3 text-sm font-semibold">{req.label} · {pick?.team?.name}</p>}
        {req && <RequestButtons req={req} />}
        <SecondaryButton className="mt-6 w-full" onClick={saveFromPick}>{fmt.t('save_anyway', name.trim())}</SecondaryButton>
      </div>
    </div>
  )
}

function BottomAction({ children }: { children: ReactNode }) {
  return (
    <div className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-bg px-4 pt-3" style={{ paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 12px)' }}>
      <div className="mx-auto max-w-lg">{children}</div>
    </div>
  )
}

/** Accent- and case-insensitive search ("pilar" finds «NUESTRA SEÑORA DEL PILAR»). */
export function fold(s: string): string {
  return s.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase()
}
