# JDCM

Android app to follow your children in the **Juegos Deportivos de Escuelas Católicas de Madrid**:
their matches, results and standings, at a glance, in the style of a sports-results app.

It reads the competition's public website
([juegosdeportivos.ecmadrid.org](https://juegosdeportivos.ecmadrid.org/Calendarios/CalendarioTemporada))
— the same data its calendar and results pages show — and presents it per child.

## What it does

- **Partidos**: one tap per child; the matchday strip (weekend by weekend), their match first with
  time, venue and directions, the rest of their group's round below.
- **Clasificación**: each child's group table (J G E P TF TC AVG Ptos), worked out by the app from the
  published results — the site does not publish standings publicly.
- **Explorar**: every sport, category and group the website has, including a whole round across groups.
- **Avisos**: a change of time or venue, a postponement, a result, and a reminder the evening before.
- Match sheet: directions, add to calendar, share.
- Works offline with what it last fetched; Spanish and English; dark (default) and light themes.

## Install

On the phone, open **https://github.com/olemoudi/jdcm/releases/latest/download/jdcm.apk** (or scan
the QR below), let the browser install it when Android asks (Android 10+), and open JDCM: a short
guide asks for alerts and for permission to install its own updates. From then on it updates
itself, silently.

<img src="docs/install-qr.png" width="220" alt="QR code of the install link">

Two channels: **beta** (stable; the link above, the default) and **alpha** (testing, every build;
Ajustes → Actualizaciones).

## Privacy

Children's names and teams are stored only on the phone. The app talks to two places: the
competition's website (to read calendars and results) and GitHub (to check for updates). No
accounts, no analytics. The diagnostic log (Settings → Diagnóstico) contains no names and is only
shared if you copy it yourself.

## Honest limitations

- Standings are computed: sanctions, forfeits handled outside the results, or tie-break rules the
  organisers apply differently can make them differ from the official table. Basketball and
  volleyball points (win 2, loss 1) are an assumption until checked against an official table.
- The season 2026-27 had no results yet when this was written; how the site marks a match as played
  is inferred and will be checked after the first weekend.
- Notifications depend on Android letting the app run in the background (battery savers may delay them).
- Everything depends on the website keeping its current public endpoints.

## Development

```sh
export JAVA_HOME=~/.jdks/jdk-17.0.19+10
./gradlew test              # core + app JVM tests
./gradlew :app:assembleDebug
```

See `CLAUDE.md` for architecture, conventions and the release procedure.
