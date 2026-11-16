// The e2e web server: makensi.es in miniature. /jdcm/ serves the beta build, /jdcm/alpha/ the
// alpha one (nested, as on the real site), /data/ the data branch, /site/ stands in for the
// competition's site (404: crests fall back to initials, and no test ever reaches the real site).
// POST /__deploy?channel=beta&build=b swaps what a channel serves, like a publish would; build=down
// makes the channel's host fail (503), to test that the installed shell opens without it.
import { createServer } from 'node:http'
import { readFile, stat } from 'node:fs/promises'
import { extname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PORT } from './builds.mjs'

const here = fileURLToPath(new URL('.', import.meta.url))
const live = { beta: 'beta-a', alpha: 'alpha-a' }
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.png': 'image/png', '.svg': 'image/svg+xml',
}

async function send(res, file) {
  try {
    const s = await stat(file)
    if (s.isDirectory()) return send(res, join(file, 'index.html'))
    const body = await readFile(file)
    // GitHub Pages sends max-age=600; here no-cache, so a "deploy" is visible at once.
    res.writeHead(200, { 'content-type': TYPES[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-cache' })
    res.end(body)
  } catch {
    res.writeHead(404, { 'content-type': 'text/plain' })
    res.end('not found')
  }
}

createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)
  const path = normalize(decodeURIComponent(url.pathname))
  if (req.method === 'POST' && path === '/__deploy') {
    const channel = url.searchParams.get('channel')
    const build = url.searchParams.get('build')
    if (!(channel in live) || !['a', 'b', 'down'].includes(build)) { res.writeHead(400); return res.end() }
    live[channel] = build === 'down' ? 'down' : `${channel}-${build}`
    res.writeHead(200)
    return res.end(live[channel])
  }
  if (path === '/jdcm' || path === '/jdcm/alpha') { res.writeHead(301, { location: `${path}/` }); return res.end() }
  const channel = path.startsWith('/jdcm/alpha/') ? 'alpha' : path.startsWith('/jdcm/') ? 'beta' : null
  if (channel && live[channel] === 'down') { res.writeHead(503); return res.end('down') }
  if (channel === 'alpha') return send(res, join(here, '.builds', live.alpha, path.slice('/jdcm/alpha/'.length)))
  if (channel === 'beta') return send(res, join(here, '.builds', live.beta, path.slice('/jdcm/'.length)))
  if (path.startsWith('/data/')) return send(res, join(here, 'data', path.slice('/data/'.length)))
  if (path === '/') { res.writeHead(200, { 'content-type': 'text/html' }); return res.end('<p>makensi.es (rest of the site)</p>') }
  res.writeHead(404)
  res.end('not found')
}).listen(PORT, () => console.log(`e2e server on http://localhost:${PORT}`))
