import { useEffect, useState, type ReactNode } from 'react'
import type { Team } from '../lib/data'
import { SITE_URL } from '../lib/env'
import { useFmt } from '../hooks/app-state'
import { ChevronLeft, Close } from './Icons'

/** A school's crest from the site, or its initials when it has none (or it cannot load). */
export function Crest({ team, size = 28 }: { team: Team | null | undefined; size?: number }) {
  const [failed, setFailed] = useState(false)
  const src = team?.crest ? safeCrest(team.crest) : null
  const initials = (team?.name ?? '')
    .replace(/["“”'«»]/g, '')
    .split(/\s+/)
    .filter((w) => w.length > 2 || /^[A-Z]$/.test(w))
    .slice(0, 2)
    .map((w) => w[0])
    .join('')
  const style = { width: size, height: size, minWidth: size }
  if (src && !failed) {
    return (
      <img src={src} alt="" width={size} height={size} loading="lazy" decoding="async" onError={() => setFailed(true)}
        className="rounded-full bg-white object-contain" style={style} />
    )
  }
  return (
    <span aria-hidden="true" style={{ ...style, fontSize: Math.max(9, size * 0.36) }}
      className="inline-flex items-center justify-center rounded-full bg-raised-2 font-semibold text-text-2">
      {initials || '·'}
    </span>
  )
}

/** Only paths on the site itself: a crest path is data from the network. */
function safeCrest(path: string): string | null {
  try {
    const u = new URL(path, SITE_URL)
    return u.origin === new URL(SITE_URL).origin ? u.toString() : null
  } catch {
    return null
  }
}

/** The child's name on their team, in their colour, dark text (the palette is light enough). */
export function KidTag({ name, color }: { name: string; color: string }) {
  return (
    <span className="max-w-[7rem] shrink-0 truncate rounded-full px-2 py-0.5 text-xs font-bold text-[#111214]" style={{ background: color }}>
      {name}
    </span>
  )
}

export function Hairline({ inset = 0 }: { inset?: number }) {
  return <div className="h-px bg-line" style={{ marginLeft: inset }} />
}

/** Marca's flat card: no shadow, a thin line, an optional accent border for the child's match. */
export function FlatCard({ children, accent, className = '' }: { children: ReactNode; accent?: string | null; className?: string }) {
  return (
    <div className={`mx-3 overflow-hidden rounded-xl border bg-card ${className}`}
      style={{ borderColor: accent ?? 'var(--line)', borderWidth: accent ? 1.5 : 1 }}>
      {children}
    </div>
  )
}

/** A screen's top bar: back arrow (iOS has no system back in a standalone app), title, an action. */
export function ScreenHeader({ title, subtitle, onBack, action }: { title: string; subtitle?: string; onBack?: () => void; action?: ReactNode }) {
  const fmt = useFmt()
  return (
    <header className="sticky top-0 z-20 bg-bg pt-safe">
      <div className="flex min-h-12 items-center gap-1 px-1">
        {onBack ? (
          <button type="button" onClick={onBack} aria-label={fmt.t('back')} className="flex h-11 w-11 items-center justify-center rounded-full text-link active:bg-raised">
            <ChevronLeft size={26} />
          </button>
        ) : <span className="w-3" />}
        <div className="min-w-0 flex-1 py-1">
          <h1 className="truncate text-[17px] font-bold leading-tight">{title}</h1>
          {subtitle && <p className="truncate text-xs text-text-2">{subtitle}</p>}
        </div>
        {action}
      </div>
      <Hairline />
    </header>
  )
}

/** A bottom sheet over a scrim (the match sheet, the install guide). Escape and the scrim close it. */
export function Sheet({ onClose, children, label }: { onClose: () => void; children: ReactNode; label: string }) {
  const fmt = useFmt()
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = prev
    }
  }, [onClose])
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center" role="dialog" aria-modal="true" aria-label={label}>
      <button type="button" aria-label={fmt.t('close')} className="absolute inset-0 bg-[var(--scrim)]" onClick={onClose} />
      <div className="sheet-enter relative max-h-[92dvh] w-full max-w-lg overflow-y-auto rounded-t-2xl bg-card pb-safe shadow-2xl">
        <div className="sticky top-0 z-10 flex justify-between bg-card px-2 pt-2">
          <span className="mx-auto mt-1 h-1.5 w-10 rounded-full bg-raised-2" />
          <button type="button" onClick={onClose} aria-label={fmt.t('close')} className="absolute right-2 top-1 flex h-10 w-10 items-center justify-center rounded-full text-text-2 active:bg-raised">
            <Close size={20} />
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}

/** A short message at the bottom, gone after a few seconds. */
let toastListener: ((msg: string) => void) | null = null
export function toast(msg: string) {
  toastListener?.(msg)
}
export function ToastHost() {
  const [msg, setMsg] = useState<string | null>(null)
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined
    toastListener = (m) => {
      setMsg(m)
      clearTimeout(timer)
      timer = setTimeout(() => setMsg(null), 3500)
    }
    return () => { toastListener = null; clearTimeout(timer) }
  }, [])
  if (!msg) return null
  return (
    <div role="status" className="pointer-events-none fixed inset-x-0 z-[60] flex justify-center px-4" style={{ bottom: 'calc(env(safe-area-inset-bottom, 0px) + 76px)' }}>
      <div className="rounded-xl bg-text px-4 py-2.5 text-sm font-medium text-bg shadow-lg">{msg}</div>
    </div>
  )
}

/** Big tappable row (Home's doors are full-width rows or footer links, never tiny icons). */
export function RowButton({ children, onClick, className = '' }: { children: ReactNode; onClick: () => void; className?: string }) {
  return (
    <button type="button" onClick={onClick} className={`flex min-h-12 w-full items-center gap-3 px-4 py-2 text-left active:bg-raised ${className}`}>
      {children}
    </button>
  )
}

export function PrimaryButton({ children, onClick, disabled, className = '' }: { children: ReactNode; onClick?: () => void; disabled?: boolean; className?: string }) {
  return (
    <button type="button" onClick={onClick} disabled={disabled}
      className={`min-h-12 rounded-xl bg-primary px-5 text-[15px] font-semibold text-on-primary active:opacity-80 disabled:opacity-50 ${className}`}>
      {children}
    </button>
  )
}

export function SecondaryButton({ children, onClick, className = '' }: { children: ReactNode; onClick?: () => void; className?: string }) {
  return (
    <button type="button" onClick={onClick}
      className={`min-h-12 rounded-xl bg-raised px-5 text-[15px] font-semibold text-text active:bg-raised-2 ${className}`}>
      {children}
    </button>
  )
}
