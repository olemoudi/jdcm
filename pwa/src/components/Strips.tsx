import { useEffect, useRef } from 'react'
import { useFmt } from '../hooks/app-state'
import { kidColor, type Kid } from '../lib/kids'
import { addDays } from '../lib/schedule'
import { Plus, SportIcon } from './Icons'

/** The children as big pills: one tap filters the whole screen to that child. */
export function KidPills({ kids, selectedId, onSelect, onAdd }: {
  kids: Kid[]; selectedId: string | null; onSelect: (id: string | null) => void; onAdd?: () => void
}) {
  const fmt = useFmt()
  return (
    <div className="no-scrollbar flex gap-2 overflow-x-auto px-3 py-2" role="tablist">
      {kids.length > 1 && <Pill text={fmt.t('all_kids')} selected={selectedId === null} onClick={() => onSelect(null)} />}
      {kids.map((k) => (
        <Pill key={k.id} text={k.name} color={kidColor(k.color)} sport={k.sportKind}
          selected={selectedId === k.id || kids.length === 1} onClick={() => onSelect(k.id)} />
      ))}
      {onAdd && (
        <button type="button" onClick={onAdd} aria-label={fmt.t('add_kid')}
          className="flex min-h-10 shrink-0 items-center justify-center rounded-full border border-line px-3 active:bg-raised">
          <Plus size={20} />
        </button>
      )}
    </div>
  )
}

function Pill({ text, color, sport, selected, onClick }: {
  text: string; color?: string; sport?: Kid['sportKind']; selected: boolean; onClick: () => void
}) {
  return (
    <button type="button" role="tab" aria-selected={selected} onClick={onClick}
      className={`flex min-h-10 shrink-0 items-center gap-2 rounded-full px-3.5 text-sm font-semibold ${selected ? 'bg-text text-bg' : 'bg-raised text-text'}`}>
      {color && <span className="h-2.5 w-2.5 rounded-full" style={{ background: color }} />}
      <span className="max-w-[10rem] truncate">{text}</span>
      {sport && <SportIcon kind={sport} size={16} className={selected ? 'text-bg' : 'text-text-2'} />}
    </button>
  )
}

/**
 * Marca's day strip, made for a weekend league: one cell per matchday (its Saturday), the month
 * above and "Today". Coloured dots under a day say which children play.
 */
export function MatchdayStrip({ days, selected, current, today, dotsFor, onSelect, onToday }: {
  days: string[]
  selected: string | null
  current: string | null
  today: string
  dotsFor: (day: string) => string[]
  onSelect: (day: string) => void
  onToday: () => void
}) {
  const fmt = useFmt()
  const scroller = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = scroller.current?.querySelector<HTMLElement>('[aria-selected="true"]')
    el?.scrollIntoView?.({ block: 'nearest', inline: 'center', behavior: 'smooth' })
  }, [selected, days.length])
  return (
    <div>
      <div className="flex items-center px-4 pt-1">
        <span className="flex-1 text-[17px] font-bold">{fmt.monthYear(selected ?? today)}</span>
        <button type="button" onClick={onToday} className="min-h-10 px-2 text-[15px] text-link">{fmt.t('see_today')}</button>
      </div>
      <div ref={scroller} className="no-scrollbar flex gap-0.5 overflow-x-auto px-2 pb-2" role="tablist">
        {days.map((d) => {
          const isSel = d === selected
          const isCurrent = d === current
          const thisWeekend = today >= addDays(d, -1) && today <= addDays(d, 1)
          const top = d === today ? fmt.t('today_short') : thisWeekend ? fmt.t('this_weekend_short') : fmt.monthShort(d)
          const accent = isCurrent || thisWeekend
          return (
            <button key={d} type="button" role="tab" aria-selected={isSel} aria-label={fmt.day(d)} onClick={() => onSelect(d)}
              className={`flex w-[60px] shrink-0 flex-col items-center rounded-lg py-1.5 ${isSel ? 'bg-primary' : ''}`}>
              <span className={`text-xs ${accent ? 'font-bold' : ''}`}
                style={{ color: isSel ? 'var(--on-primary)' : accent ? 'var(--primary)' : 'var(--text-2)' }}>{top}</span>
              <span className={`tnum text-2xl leading-8 ${isSel || isCurrent ? 'font-bold' : ''}`}
                style={{ color: isSel ? 'var(--on-primary)' : accent ? 'var(--primary)' : 'var(--text)' }}>
                {Number(d.slice(8, 10))}
              </span>
              <span className="flex h-2 items-center gap-[3px]">
                {dotsFor(d).slice(0, 4).map((c, i) => (
                  <span key={i} className="h-1.5 w-1.5 rounded-full" style={{ background: c, boxShadow: isSel ? '0 0 0 1px #fff' : undefined }} />
                ))}
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
