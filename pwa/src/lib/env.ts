// Build-time constants, behind guards so tests and odd builds never crash on a missing define.

export type Channel = 'beta' | 'alpha'

export const CHANNEL: Channel =
  typeof __CHANNEL__ !== 'undefined' && __CHANNEL__ === 'alpha' ? 'alpha' : 'beta'

/** The running build: the last commit that touched pwa/ (vite.config.ts). Shown in the UI. */
export const GIT_COMMIT: string = typeof __GIT_COMMIT__ !== 'undefined' ? __GIT_COMMIT__ : 'dev'

export const APP_VERSION: string = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : '0.0.0'

export const BUILT_AT: string = typeof __BUILT_AT__ !== 'undefined' ? __BUILT_AT__ : ''

/** Where the home fetcher publishes (olemoudi/jdcm-data, via raw.githubusercontent.com). */
export const DATA_URL: string =
  typeof __DATA_URL__ !== 'undefined' ? __DATA_URL__ : 'https://raw.githubusercontent.com/olemoudi/jdcm-data/main/'

export const SITE_URL: string = typeof __SITE_URL__ !== 'undefined' ? __SITE_URL__ : 'https://juegosdeportivos.ecmadrid.org'

export const REPO_URL = 'https://github.com/olemoudi/jdcm'

/** "beta · 1a2b3c4": the version indicator (PWA_UPDATE_PROTOCOL §4), channel included. */
export const BUILD_LABEL = `${CHANNEL} · ${GIT_COMMIT}`
