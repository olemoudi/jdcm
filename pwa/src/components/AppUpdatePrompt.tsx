import { useEffect, useState } from 'react'
import { useServiceWorker } from '../hooks/use-service-worker'
import { useFmt } from '../hooks/app-state'
import { changelogFor, fetchDeployedChanges, type VersionInfo } from '../lib/changelog'
import { BUILD_LABEL, GIT_COMMIT } from '../lib/env'
import { readRaw, writeRaw } from '../lib/storage'

/*
 * App-wide update prompt (GTD25's AppUpdatePrompt, without its vault): a dialog with what the new
 * build contains, "Later" demotes it to a thin banner that stays. Mounted above every screen and
 * the error boundary. Same-commit signals are suppressed (§8.3); the dialog waits for the version
 * check, so it never flashes. After the reload, a short notice confirms the update — only if the
 * running commit really changed. Keys live in this channel's storage.
 */

const NOTICE_KEY = 'update-notice'
const NOTICE_TTL_MS = 10 * 60 * 1000

function readCompletedNotice(): string | null {
  const raw = readRaw(NOTICE_KEY)
  if (!raw) return null
  writeRaw(NOTICE_KEY, null)
  try {
    const n = JSON.parse(raw) as { from?: unknown; at?: unknown }
    if (typeof n.from !== 'string' || typeof n.at !== 'number') return null
    if (Date.now() - n.at > NOTICE_TTL_MS) return null
    if (n.from === GIT_COMMIT) return null // we did not actually move
    return GIT_COMMIT
  } catch {
    return null
  }
}

export function AppUpdatePrompt() {
  const fmt = useFmt()
  const { needRefresh, applyUpdate } = useServiceWorker()
  const [dismissed, setDismissed] = useState(false)
  const [updating, setUpdating] = useState(false)
  const [info, setInfo] = useState<VersionInfo | null>(null)
  const [versionChecked, setVersionChecked] = useState(false)
  const [done, setDone] = useState<string | null>(readCompletedNotice)

  useEffect(() => {
    setInfo(null)
    setVersionChecked(false)
    if (!needRefresh) return
    let active = true
    fetchDeployedChanges()
      .then((v) => { if (active && v) setInfo(v) })
      .finally(() => { if (active) setVersionChecked(true) })
    return () => { active = false }
  }, [needRefresh])

  useEffect(() => {
    if (!done) return
    const t = setTimeout(() => setDone(null), 6000)
    return () => clearTimeout(t)
  }, [done])

  const changes = info ? changelogFor(info, GIT_COMMIT) : []
  const sameCommit = info?.commit === GIT_COMMIT
  // A waiting worker for the build already running (a redeploy of the same commit) offers nothing.
  const stale = needRefresh && versionChecked && sameCommit
  const available = needRefresh && versionChecked && !stale

  if (done && !available) {
    return (
      <div role="status" data-testid="update-done" className="fixed inset-x-0 top-0 z-[70] bg-win px-4 pb-2 pt-safe text-sm font-semibold text-white">
        <div className="pt-2">{fmt.t('update_done', BUILD_LABEL)}</div>
      </div>
    )
  }
  if (!available) return null

  const doUpdate = () => {
    if (updating) return
    setUpdating(true)
    writeRaw(NOTICE_KEY, JSON.stringify({ from: GIT_COMMIT, to: info?.commit, at: Date.now() }))
    applyUpdate()
  }

  if (!dismissed) {
    return (
      <div className="fixed inset-0 z-[70] flex items-center justify-center bg-[var(--scrim)] p-4" role="dialog" aria-modal="true" aria-label={fmt.t('update_title')} data-testid="update-dialog">
        <div className="w-full max-w-sm rounded-2xl border border-line bg-card p-5 shadow-2xl">
          <h2 className="text-lg font-bold">{fmt.t('update_title')}</h2>
          <p className="mt-1 text-sm text-text-2">{fmt.t('update_text')}</p>
          {info && (
            <div className="mt-3 rounded-lg border border-line bg-raised p-2.5">
              <p className="font-mono text-[11px] text-text-2" data-testid="update-commits">{GIT_COMMIT} → {info.commit}</p>
              {changes.length > 0 && (
                <ul className="mt-1 max-h-40 space-y-0.5 overflow-auto">
                  {changes.map((c) => (
                    <li key={c.h} className="text-xs"><span className="font-mono text-text-2">{c.h}</span> {c.s}</li>
                  ))}
                </ul>
              )}
            </div>
          )}
          <div className="mt-4 flex justify-end gap-2">
            <button type="button" onClick={() => setDismissed(true)} className="min-h-11 rounded-xl px-4 text-sm font-semibold text-text-2 active:bg-raised">{fmt.t('later')}</button>
            <button type="button" onClick={doUpdate} disabled={updating} className="min-h-11 rounded-xl bg-primary px-4 text-sm font-semibold text-on-primary disabled:opacity-60">
              {updating ? fmt.t('updating') : fmt.t('update_now')}
            </button>
          </div>
        </div>
      </div>
    )
  }
  return (
    <div className="fixed inset-x-0 top-0 z-[70] bg-modified pt-safe text-sm font-semibold text-[#111214]" data-testid="update-banner">
      <div className="flex items-center justify-between gap-3 px-4 py-2">
        <span>{fmt.t('update_banner')}</span>
        <button type="button" onClick={doUpdate} disabled={updating} className="min-h-9 shrink-0 rounded-lg bg-black/15 px-3 text-xs font-bold">
          {updating ? fmt.t('updating') : fmt.t('update_now')}
        </button>
      </div>
    </div>
  )
}
