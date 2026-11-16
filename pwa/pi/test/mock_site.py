"""A stand-in for juegosdeportivos.ecmadrid.org, for testing jdcm-fetch.sh without touching the site.

Built from the real futsal grouped calendar of round 1 (core/src/test/resources/fixtures/
calendario_agrupado.json: Alevín mixto, groups 177-184, five matches each). Round numbers 2-9 are
the same matches a week later each; group G's round N has id base(G) + N - 1, as on the site.
The grouped endpoint serves every group of a round number without scores; a group's own page
serves its round with the previous and next ones and, for played matches, their scores. Basketball
and volleyball are empty. A mode file (space-separated words) changes the behaviour:
  played   round 1 has been played: timed matches "Disputado sin Incidencias", untimed rained off
  late     the group pages have no scores yet (the results are not entered)
  incid    with played: timed matches "Disputado con Incidencias" (no score until the ruling)
  rain     group 177's first match of round 2 is rained off (in the grouped calendar only)
  gshrink  the grouped calendar lists only group 177
  wrong    a group's page answers another round than the one asked (JSON, but odd)
  down     every request answers 503;  html  every request answers a login page
Every request is logged with its time, to check the politeness of the fetcher.
"""
import copy
import datetime
import http.server
import json
import os
import sys
import time
import urllib.parse

FIX, MODE_FILE, LOG, PORT = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
ROUNDS = 9
GROUPED = json.loads(json.loads(open(os.path.join(FIX, "calendario_agrupado.json"), encoding="utf-8").read()))
COMP = GROUPED[0]
GROUPS = {g["IdGrupo"]: g for g in COMP["Categorias"][0]["Grupos"]}
BASE = {gid: g["Enfrentamientos"][0]["IdJornada"] for gid, g in GROUPS.items()}


def modes():
    return set(open(MODE_FILE).read().split()) if os.path.exists(MODE_FILE) else set()


def shift(fecha, weeks):
    d = datetime.date.fromisoformat(fecha[:10]) + datetime.timedelta(days=7 * weeks)
    return d.isoformat() + fecha[10:]


def matches(gid, n, m, with_scores):
    out = []
    for i, base in enumerate(GROUPS[gid]["Enfrentamientos"]):
        x = copy.deepcopy(base)
        x.update(Id=base["Id"] + (n - 1) * 1000, IdJornada=BASE[gid] + n - 1, NumeroJornada=n, FechaHora=shift(base["FechaHora"], n - 1))
        if n == 1 and "played" in m:
            if x["FechaHora"][11:16] == "00:00":
                x.update(IdEstadoPartido=6, EstadoPartido="No Celebrado/Incompleto")
            else:
                if "incid" in m:
                    x.update(IdEstadoPartido=10, EstadoPartido="Disputado con Incidencias")
                else:
                    x.update(IdEstadoPartido=11, EstadoPartido="Disputado sin Incidencias")
        if n == 2 and gid == 177 and i == 0 and "rain" in m and not with_scores:
            x.update(IdEstadoPartido=6, EstadoPartido="No Celebrado/Incompleto")
        if with_scores:
            played = x["IdEstadoPartido"] == 11 and "late" not in m
            h, a = (x["Id"] % 4, x["Id"] % 3) if played else (None, None)
            x.update(ResultadoEquipoLocal=h, ResultadoEquipoVisitante=a, Cerrado=False,
                     IdEquipoGanador=(x["IdEquipoLocal"] if h > a else x["IdEquipoVisitante"] if a > h else 0) if played else 0)
        out.append(x)
    return out


def round_obj(gid, n, m):
    return {"Id": BASE[gid] + n - 1, "NumeroJornada": n, "Fecha": shift("2026-11-14T00:00:00", n - 1),
            "IdGrupo": gid, "Enfrentamientos": matches(gid, n, m, True)}


def group_page(gid, round_id, m):
    n = 2 if round_id == 0 else round_id - BASE[gid] + 1
    jornadas = [{"Id": BASE[gid] + k - 1, "NumeroJornada": k, "Fecha": shift("2026-11-14T00:00:00", k - 1)} for k in range(1, ROUNDS + 1)]
    return {"previous": round_obj(gid, n - 1, m) if n > 1 else {"Id": 0}, "jornada": round_obj(gid, n, m),
            "next": round_obj(gid, n + 1, m) if n < ROUNDS else {"Id": 0}, "jornadas": jornadas}


def grouped(sport, n, m):
    if sport != 2 or not 1 <= n <= ROUNDS:
        return []
    c = copy.deepcopy(COMP)
    c["Categorias"][0]["Grupos"] = [
        {"IdGrupo": gid, "Grupo": g["Grupo"], "Enfrentamientos": matches(gid, n, m, False)}
        for gid, g in GROUPS.items() if "gshrink" not in m or gid == 177]
    return [c]


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(url.query).items()}
        with open(LOG, "a") as f:
            f.write(f"{time.time():.3f} {url.path} {json.dumps(q, sort_keys=True)} {self.headers.get('User-Agent')}\n")
        m = modes()
        if "down" in m:
            return self.send(503, "unavailable", "text/plain")
        if "html" in m:
            return self.send(200, "<!DOCTYPE html><html>marcaPaginaInicioSesion</html>", "text/html")
        if url.path.endswith("ObtenerDatosJornadaGrupo"):
            body = group_page(int(q["IdGrupo"]), int(q["IdJornada"]), m)
            if "wrong" in m:
                body["jornada"]["Id"] = 1
        elif url.path.endswith("ObtenerCalendarioAgrupado"):
            body = grouped(int(q["IdDeporte"]), int(q["NumeroJornada"]), m)
        else:
            return self.send(404, "not found", "text/plain")
        self.send(200, json.dumps(json.dumps(body, ensure_ascii=False)), "application/json")

    def send(self, code, body, ctype):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
