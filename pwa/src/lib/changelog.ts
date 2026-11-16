// Pure helpers for the in-app update changelog (what a pending update contains), from GTD25
// (src/lib/changelog.ts): unit-testable and defensive against a malformed file from the network.

export interface VersionInfo {
  commit: string
  message: string
  builtAt?: string
  log?: Array<{ h: string; s: string }>
}

/** Commits in the incoming build newer than the running one: walk the log until the current commit. */
export function changelogFor(info: VersionInfo, current: string): Array<{ h: string; s: string }> {
  if (info.log && info.log.length) {
    const fresh: Array<{ h: string; s: string }> = []
    for (const c of info.log) {
      if (c.h === current) return fresh
      fresh.push(c)
    }
    if (fresh.length) return fresh // current not in the window: show what we have
  }
  return info.message ? [{ h: info.commit, s: info.message }] : []
}

async function fetchDeployed(file: 'version.json' | 'changes.json'): Promise<VersionInfo | null> {
  try {
    // BASE_URL-relative (the channel's own folder), cache-busted past the CDN and the browser.
    const response = await fetch(`${import.meta.env.BASE_URL}${file}?t=${Date.now()}`, {
      cache: 'no-store',
      signal: AbortSignal.timeout(10_000),
    })
    if (!response.ok) return null
    return parseVersionInfo(await response.json())
  } catch {
    return null
  }
}

/** The build the server is serving right now (version.json is never precached). Null when unreachable. */
export function fetchDeployedVersion(): Promise<VersionInfo | null> {
  return fetchDeployed('version.json')
}

/** What the deployed build contains: fetched only when an update is offered. */
export function fetchDeployedChanges(): Promise<VersionInfo | null> {
  return fetchDeployed('changes.json')
}

/** Validate/normalise an untrusted version.json payload; null if unusable. */
export function parseVersionInfo(j: unknown): VersionInfo | null {
  if (!j || typeof j !== 'object') return null
  const o = j as Record<string, unknown>
  if (typeof o.commit !== 'string' || !o.commit) return null
  const log = Array.isArray(o.log)
    ? (o.log as unknown[]).filter(
        (c): c is { h: string; s: string } =>
          !!c && typeof c === 'object'
          && typeof (c as Record<string, unknown>).h === 'string'
          && typeof (c as Record<string, unknown>).s === 'string',
      )
    : undefined
  return {
    commit: o.commit,
    message: typeof o.message === 'string' ? o.message : '',
    builtAt: typeof o.builtAt === 'string' ? o.builtAt : undefined,
    log,
  }
}
