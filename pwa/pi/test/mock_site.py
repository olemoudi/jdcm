"""A stand-in for juegosdeportivos.ecmadrid.org, for testing jdcm-fetch.sh without touching the site.

Serves the real answers in core/src/test/resources/fixtures: group 177's first matchday as its
current round, group 139's basketball page, the futsal grouped calendar. Other rounds are made up
from the group's round list (the base matches, moved to that round's date, still pending). A mode
file switches the behaviour: ok, html (a login page), down (503), shrink (one match per round).
Every request is logged with its time, to check the politeness of the fetcher.
"""
import copy
import http.server
import json
import os
import sys
import time
import urllib.parse

FIX, MODE_FILE, LOG, PORT = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])


def inner(name):
    return json.loads(json.loads(open(os.path.join(FIX, name), encoding="utf-8").read()))


BASES = {177: inner("j1_g177_resultado.json"), 139: inner("jornada_grupo_baloncesto.json")}
GROUPED = {2: open(os.path.join(FIX, "calendario_agrupado.json"), encoding="utf-8").read()}


def made_up(base, entry, shrink):
    """Round `entry` of the base page's list, with the base matches moved to its date."""
    if entry["Id"] == base["jornada"]["Id"] and not shrink:
        return base["jornada"]
    j = copy.deepcopy(base["jornada"])
    j.update(Id=entry["Id"], NumeroJornada=entry["NumeroJornada"], Fecha=entry["Fecha"])
    day = entry["Fecha"][:10]
    matches = []
    for i, m in enumerate(base["jornada"]["Enfrentamientos"]):
        m = copy.deepcopy(m)
        m.update(Id=100000 + entry["Id"] * 10 + i, IdJornada=entry["Id"], NumeroJornada=entry["NumeroJornada"],
                 FechaHora=day + (m.get("FechaHora") or "T00:00:00")[10:], IdEstadoPartido=0, EstadoPartido="Pendiente de Jugar",
                 ResultadoEquipoLocal=None, ResultadoEquipoVisitante=None, IdEquipoGanador=0)
        matches.append(m)
    j["Enfrentamientos"] = matches[:1] if shrink else matches
    return j


def round_page(group, round_id, shrink):
    base = BASES[group]
    rounds = sorted(base["jornadas"], key=lambda r: r["NumeroJornada"])
    if round_id == 0 and not shrink:
        return base
    current = round_id or base["jornada"]["Id"]
    idx = next(i for i, r in enumerate(rounds) if r["Id"] == current)
    page = {"jornada": made_up(base, rounds[idx], shrink), "jornadas": base["jornadas"]}
    page["previous"] = made_up(base, rounds[idx - 1], shrink) if idx > 0 else {"Id": 0}
    page["next"] = made_up(base, rounds[idx + 1], shrink) if idx + 1 < len(rounds) else {"Id": 0}
    return page


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(url.query).items()}
        with open(LOG, "a") as f:
            f.write(f"{time.time():.3f} {url.path} {json.dumps(q, sort_keys=True)} {self.headers.get('User-Agent')}\n")
        mode = open(MODE_FILE).read().strip() if os.path.exists(MODE_FILE) else "ok"
        if mode == "down":
            return self.send(503, "unavailable", "text/plain")
        if mode == "html":
            return self.send(200, "<!DOCTYPE html><html>marcaPaginaInicioSesion</html>", "text/html")
        if url.path.endswith("ObtenerDatosJornadaGrupo"):
            page = round_page(int(q["IdGrupo"]), int(q["IdJornada"]), mode == "shrink")
            return self.send(200, json.dumps(json.dumps(page, ensure_ascii=False)), "application/json")
        if url.path.endswith("ObtenerCalendarioAgrupado"):
            return self.send(200, GROUPED.get(int(q["IdDeporte"]), json.dumps("[]")), "application/json")
        self.send(404, "not found", "text/plain")

    def send(self, code, body, ctype):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
