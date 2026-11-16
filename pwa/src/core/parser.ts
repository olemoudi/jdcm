import type { GroupedRound, Match, Round, RoundPage, Team, Venue } from './model'

/*
 * A port of core's SiteParser (core/src/main/kotlin/dev/jdcm/core/parse/SiteParser.kt), the parts
 * the PWA needs: a round page with its neighbours, the grouped calendar, and the tolerant reading of
 * every field. Every field may be null, missing, a number or a string holding one; only a body that
 * is not the endpoint's shape at all throws UnexpectedResponse.
 */

export class UnexpectedResponse extends Error {}

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => !!v && typeof v === 'object' && !Array.isArray(v)
const obj = (v: unknown): Obj | null => (isObj(v) ? v : null)
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : [])

/** A JSON primitive that is not null (kotlinx's JsonPrimitive minus JsonNull). */
function prim(o: Obj, k: string): string | number | boolean | null {
  const v = o[k]
  return typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean' ? v : null
}

/** kotlinx's `content`: a number's or boolean's text, a string as is. */
const content = (p: string | number | boolean | null): string | null => (p == null ? null : String(p))

function str(o: Obj, k: string): string {
  return content(prim(o, k)) ?? ''
}

function toNumber(s: string): number | null {
  const t = s.trim()
  if (t === '') return null
  const n = Number(t)
  return Number.isFinite(n) ? n : null
}

/** An integer, or a string holding a number (truncated), else 0. */
function int(o: Obj, k: string): number {
  const p = prim(o, k)
  if (p == null || typeof p === 'boolean') return 0
  const n = typeof p === 'number' ? p : toNumber(p)
  return n == null ? 0 : Math.trunc(n)
}

/** A number, or a string holding one: "" or "-" is no score, not a 0. */
function isNumber(o: Obj, k: string): boolean {
  const p = prim(o, k)
  if (p == null || typeof p === 'boolean') return false
  return typeof p === 'number' || toNumber(p) != null
}

function double(o: Obj, k: string): number | null {
  const p = prim(o, k)
  if (p == null || typeof p === 'boolean') return null
  return typeof p === 'number' ? p : toNumber(p)
}

function bool(o: Obj, k: string): boolean {
  const p = prim(o, k)
  return p === true || p === 'true'
}

/** Decodes, and decodes again when the body is a JSON string holding JSON (the MVC actions). */
export function unwrap(body: string): unknown {
  let first: unknown
  try {
    first = JSON.parse(body.trim())
  } catch {
    throw new UnexpectedResponse(`not JSON (${body.slice(0, 40).replace(/\n/g, ' ')})`)
  }
  if (typeof first !== 'string') return first
  try {
    return JSON.parse(first)
  } catch {
    throw new UnexpectedResponse('string is not JSON')
  }
}

const WALL = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})$/

/** "2026-11-13T18:00:00" (sometimes with fractions or a zone the site does not mean): wall-clock Madrid. */
export function dateTime(s: string): string | null {
  if (s.length < 16) return null
  let t = s.slice(0, 19)
  if (t.length === 16) t += ':00'
  const m = WALL.exec(t)
  if (!m) return null
  const [y, mo, d, h, mi, se] = m.slice(1).map(Number)
  const probe = new Date(Date.UTC(y, mo - 1, d, h, mi, se))
  if (probe.getUTCFullYear() !== y || probe.getUTCMonth() !== mo - 1 || probe.getUTCDate() !== d || h > 23 || mi > 59 || se > 59) return null
  return y > 2000 ? t : null
}

export function date(s: string): string | null {
  const dt = dateTime(s)
  if (dt) return dt.slice(0, 10)
  const t = s.slice(0, 10)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(t)) return null
  const [y, mo, d] = t.split('-').map(Number)
  const probe = new Date(Date.UTC(y, mo - 1, d))
  if (probe.getUTCFullYear() !== y || probe.getUTCMonth() !== mo - 1 || probe.getUTCDate() !== d) return null
  return y > 2000 ? t : null
}

export function round(o: Obj | null): Round | null {
  if (!o) return null
  const id = int(o, 'Id')
  if (id <= 0) return null
  return { id, number: int(o, 'NumeroJornada'), date: date(str(o, 'Fecha')) }
}

function team(id: number, name: string, crest: string, institution: number): Team | null {
  if (id <= 0) return null
  return { id, name: name.trim() || `Equipo ${id}`, crestPath: crest.trim(), institutionId: institution }
}

function venue(o: Obj): Venue | null {
  const field = str(o, 'Campo').trim()
  if (!field || field.toLowerCase() === 'sin campo asignado') return null
  const c = obj(o.CampoJuego)
  const lat = (c ? double(c, 'Latitud') : null) ?? double(o, 'Latitud')
  const lng = (c ? double(c, 'Longitud') : null) ?? double(o, 'Longitud')
  return {
    name: (c ? str(c, 'Nombre').trim() : '') || field,
    address: c ? str(c, 'Direccion').trim() : '',
    postcode: c ? str(c, 'CP').trim() : '',
    town: c ? str(c, 'Municipio').trim() : '',
    lat: lat != null && lat !== 0 ? lat : null,
    lng: lng != null && lng !== 0 ? lng : null,
  }
}

export function match(v: unknown): Match | null {
  const o = obj(v)
  if (!o) return null
  const id = int(o, 'Id')
  if (id <= 0) return null
  const home = team(int(o, 'IdEquipoLocal'), str(o, 'EquipoLocal'), str(o, 'URLEscudoEquipoLocal'), int(o, 'IdInstitucionLocal'))
  const away = team(int(o, 'IdEquipoVisitante'), str(o, 'EquipoVisitante'), str(o, 'URLEscudoEquipoVisitante'), int(o, 'IdInstitucionVisitante'))
  if (!home && !away) return null
  const start = dateTime(str(o, 'FechaHora'))
  const statusId = int(o, 'IdEstadoPartido')
  const statusName = str(o, 'EstadoPartido').trim()
  const unscheduled = statusId === 12 || statusName.toLowerCase().includes('desprogram')
  // The site's own rule (Calendarios.cshtml): an accepted request (state 6) or an organiser's
  // change marks the match as modified, and "desprogramado" outranks both.
  const acceptedRequest = arr(o.SolicitudesModificaciones).some((r) => isObj(r) && int(r, 'IdEstadoSolicitud') === 6)
  return {
    id,
    roundId: int(o, 'IdJornada'),
    roundNumber: int(o, 'NumeroJornada'),
    groupId: int(o, 'IdGrupo'),
    home,
    away,
    start,
    timeKnown: start != null && start.slice(11, 16) !== '00:00',
    roundDate: date(str(o, 'FechaJornada')),
    venue: venue(o),
    statusId,
    statusName,
    homeScore: int(o, 'ResultadoEquipoLocal'),
    awayScore: int(o, 'ResultadoEquipoVisitante'),
    scoreKnown: isNumber(o, 'ResultadoEquipoLocal') && isNumber(o, 'ResultadoEquipoVisitante'),
    winnerId: int(o, 'IdEquipoGanador'),
    partials: [1, 2, 3, 4, 5].map((i) => str(o, `ResultadoParcial${i}`).trim()).filter(Boolean),
    closed: bool(o, 'Cerrado'),
    modified: !unscheduled && (bool(o, 'CambioAdministrador') || acceptedRequest),
    modifiedReason: str(o, 'MotivoCambioAdministrador').trim(),
    duration: str(o, 'Duracion').trim(),
  }
}

function groupOf(list: unknown[], groupId: number): RoundPage['group'] {
  const o = list.map(obj).find((x) => x != null && int(x, 'IdGrupo') === groupId)
  if (!o) return null
  return { id: groupId, name: str(o, 'Grupo').trim(), competition: str(o, 'Competicion').trim(), category: str(o, 'Categoria').trim(), sport: str(o, 'Deporte').trim() }
}

/** `/Calendarios/ObtenerDatosJornadaGrupo` (and the results page's twin). */
export function roundPage(body: string): RoundPage {
  return roundPageFrom(unwrap(body))
}

export function roundPageFrom(root: unknown): RoundPage {
  if (!isObj(root)) throw new UnexpectedResponse('round page is not an object')
  if (!('jornada' in root) && !('jornadas' in root)) throw new UnexpectedResponse('round page without jornada')
  const jornada = obj(root.jornada)
  const list = arr(jornada?.Enfrentamientos)
  const matches = list.map(match).filter((m): m is Match => m !== null)
  const group = matches.map((m) => groupOf(list, m.groupId)).find((g) => g != null)
    ?? (jornada && int(jornada, 'IdGrupo') > 0 ? { id: int(jornada, 'IdGrupo'), name: '', competition: '', category: '', sport: '' } : null)
  const neighbours = (['previous', 'next'] as const).flatMap((k) => {
    const o = obj(root[k])
    const r = round(o)
    if (!o || !r || !Array.isArray(o.Enfrentamientos)) return []
    return [{ round: r, matches: o.Enfrentamientos.map(match).filter((m): m is Match => m !== null) }]
  })
  return {
    group,
    round: round(jornada),
    previous: round(obj(root.previous)),
    next: round(obj(root.next)),
    rounds: arr(root.jornadas).map((x) => round(obj(x))).filter((r): r is Round => r !== null).sort((a, b) => a.number - b.number),
    matches,
    neighbours,
  }
}

/** `/Calendarios/ObtenerCalendarioAgrupado`: every group of a category (or sport) for one round number. */
export function groupedRound(body: string): GroupedRound[] {
  return groupedRoundFrom(unwrap(body))
}

export function groupedRoundFrom(root: unknown): GroupedRound[] {
  if (!Array.isArray(root)) throw new UnexpectedResponse('grouped calendar is not an array')
  return root.map((c) => obj(c) ?? {}).map((c) => ({
    competitionId: int(c, 'IdCompeticion'),
    competition: str(c, 'Competicion').trim(),
    categories: arr(c.Categorias).map((k) => obj(k) ?? {}).map((k) => ({
      categoryId: int(k, 'IdCategoria'),
      name: str(k, 'Categoria').trim(),
      groups: arr(k.Grupos).map((g) => obj(g) ?? {}).map((g) => ({
        groupId: int(g, 'IdGrupo'),
        name: str(g, 'Grupo').trim(),
        matches: arr(g.Enfrentamientos).map(match).filter((m): m is Match => m !== null),
      })),
    })),
  }))
}
