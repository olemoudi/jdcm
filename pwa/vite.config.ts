import { defineConfig, type Plugin } from 'vite'
import { execSync } from 'node:child_process'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { VitePWA } from 'vite-plugin-pwa'

// Two channels, chosen by path (owner, 2026-11-16): https://makensi.es/jdcm/ is BETA (what families
// install) and /jdcm/alpha/ is ALPHA (testing). Each build has its own base, manifest identity,
// service-worker scope, cache names and storage namespace; the beta's worker steps aside for
// /jdcm/alpha/ (src/sw.ts). JDCM_CHANNEL picks one; scripts/publish.sh sets it.
const CHANNEL = process.env.JDCM_CHANNEL === 'alpha' ? 'alpha' : 'beta'
const BASE = CHANNEL === 'alpha' ? '/jdcm/alpha/' : '/jdcm/'
// Paths inside this build's scope that belong to another channel's build.
const FOREIGN = CHANNEL === 'beta' ? ['/jdcm/alpha/'] : []

// The site sends no CORS headers (and answers only Spanish home connections): a fetcher at home
// (pwa/pi/jdcm-fetch.sh) publishes its JSON to olemoudi/jdcm-data, read through raw.githubusercontent.com.
// The e2e build points at a local copy.
const DATA_URL = process.env.VITE_DATA_URL ?? 'https://raw.githubusercontent.com/olemoudi/jdcm-data/main/'
// Crests are loaded from the site. The e2e build points elsewhere: tests never reach the site.
const SITE = process.env.JDCM_SITE_URL ?? 'https://juegosdeportivos.ecmadrid.org'

function git(cmd: string): string {
  try { return execSync(cmd, { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim() } catch { return '' }
}

// The build is named by the last commit that touched what goes into it, not the repo's HEAD: a
// release of the Android app, a group added to groups.json or a new e2e test must not look like a
// new PWA build to every phone (PWA_UPDATE_PROTOCOL §8.3). scripts/publish.sh checks the same paths.
// JDCM_COMMIT overrides it for the e2e update tests, which need two builds of one tree.
const BUILD_INPUTS = 'src public index.html vite.config.ts package.json package-lock.json tsconfig.app.json'
const gitCommit = process.env.JDCM_COMMIT || git(`git log -1 --format=%h -- ${BUILD_INPUTS}`) || 'dev'
const gitMessage = process.env.JDCM_COMMIT ? `e2e build ${gitCommit}` : git(`git log -1 --format=%s -- ${BUILD_INPUTS}`)
const gitLog = process.env.JDCM_COMMIT
  ? [{ h: gitCommit, s: `e2e build ${gitCommit}` }]
  : git(`git log -25 --format=%h%x09%s -- ${BUILD_INPUTS}`)
      .split('\n')
      .filter(Boolean)
      .map((line) => { const [h, ...rest] = line.split('\t'); return { h, s: rest.join('\t') } })
const builtAt = new Date().toISOString()

// GitHub Pages cannot set headers: a meta CSP is the deployable route (production build only; the
// dev server's HMR needs inline scripts). Data from raw.githubusercontent.com, crests from the site.
function cspPlugin(): Plugin {
  const dataOrigin = /^https?:\/\//.test(DATA_URL) ? new URL(DATA_URL).origin : ''
  const csp = [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    `img-src 'self' data: blob: ${new URL(SITE).origin}`,
    "font-src 'self' data:",
    `connect-src 'self' ${dataOrigin}`.trim(),
    "worker-src 'self'",
    "manifest-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; ')
  return {
    name: 'jdcm-csp',
    apply: 'build',
    transformIndexHtml(html) {
      return html.replace('</title>', `</title>\n    <meta http-equiv="Content-Security-Policy" content="${csp}" />`)
    },
  }
}

// version.json names the build (polled by the update check); changes.json carries the commit
// subjects, fetched only when an update is offered. Neither is precached (PWA_UPDATE_PROTOCOL §3.2).
// The page's own name (tab title, iOS home-screen label) says which channel it is.
function channelHtmlPlugin(): Plugin {
  const name = CHANNEL === 'alpha' ? 'JDCM α' : 'JDCM'
  return {
    name: 'jdcm-channel-html',
    transformIndexHtml(html) {
      return html.replaceAll('%JDCM_NAME%', name)
    },
  }
}

function versionJsonPlugin(): Plugin {
  return {
    name: 'jdcm-version-json',
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'version.json', source: JSON.stringify({ commit: gitCommit, channel: CHANNEL, builtAt }) })
      this.emitFile({ type: 'asset', fileName: 'changes.json', source: JSON.stringify({ commit: gitCommit, message: gitMessage, log: gitLog }) })
    },
  }
}

export default defineConfig({
  base: BASE,
  publicDir: `public/${CHANNEL}`,
  define: {
    __APP_VERSION__: JSON.stringify(process.env.npm_package_version ?? '0.0.0'),
    __GIT_COMMIT__: JSON.stringify(gitCommit),
    __BUILT_AT__: JSON.stringify(builtAt),
    __CHANNEL__: JSON.stringify(CHANNEL),
    __FOREIGN_PATHS__: JSON.stringify(FOREIGN),
    __DATA_URL__: JSON.stringify(DATA_URL),
    __SITE_URL__: JSON.stringify(SITE),
  },
  build: {
    rollupOptions: {
      output: {
        // GitHub Pages runs Jekyll on the website repo, which drops files whose names start with "_".
        sanitizeFileName: (name) => name.replace(/^_+/, 'x').replace(/[^\w.-]/g, '-'),
      },
    },
  },
  plugins: [
    react(),
    tailwindcss(),
    channelHtmlPlugin(),
    cspPlugin(),
    versionJsonPlugin(),
    VitePWA({
      strategies: 'injectManifest',
      srcDir: 'src',
      filename: 'sw.ts',
      registerType: 'prompt', // never autoUpdate: the user is asked (PWA_UPDATE_PROTOCOL §1)
      injectManifest: {
        // The worker is built with this config's `define`, so it knows its channel too.
        globIgnores: ['**/version.json', '**/changes.json'],
      },
      includeAssets: [],
      manifest: {
        id: BASE,
        name: CHANNEL === 'alpha' ? 'JDCM α (pruebas)' : 'JDCM',
        short_name: CHANNEL === 'alpha' ? 'JDCM α' : 'JDCM',
        description: 'Partidos, resultados y clasificación de los Juegos Deportivos de Escuelas Católicas de Madrid',
        lang: 'es',
        theme_color: '#111214',
        background_color: '#111214',
        display: 'standalone',
        orientation: 'portrait',
        start_url: BASE,
        scope: BASE,
        icons: [
          { src: 'pwa-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
    }),
  ],
})
