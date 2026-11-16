// Builds the four bundles the e2e suite serves: each channel twice (A and B), so a test can
// "deploy" B over A and watch the update flow. Data and crests come from the local server.
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
export const PORT = 4318
export const BUILDS = {
  'beta-a': { channel: 'beta', commit: 'beta001' },
  'beta-b': { channel: 'beta', commit: 'beta002' },
  'alpha-a': { channel: 'alpha', commit: 'alph001' },
  'alpha-b': { channel: 'alpha', commit: 'alph002' },
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  execFileSync('npx', ['tsc', '-b'], { cwd: root, stdio: 'inherit' })
  for (const [name, b] of Object.entries(BUILDS)) {
    execFileSync('npx', ['vite', 'build', '--outDir', `e2e/.builds/${name}`, '--emptyOutDir', '--logLevel', 'warn'], {
      cwd: root,
      stdio: 'inherit',
      env: {
        ...process.env,
        JDCM_CHANNEL: b.channel,
        JDCM_COMMIT: b.commit,
        VITE_DATA_URL: '/data/',
        JDCM_SITE_URL: `http://localhost:${PORT}/site/`,
      },
    })
  }
}
