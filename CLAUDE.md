# CLAUDE.md — JDCM

Android app (Kotlin + Compose) that fronts the **public** pages of
https://juegosdeportivos.ecmadrid.org (Juegos Deportivos de Escuelas Católicas de Madrid) for a
family: each child is a local profile (name, colour, sport, category, group, team); Home shows
their matchday Marca-style; standings are computed by the app; notifications for changes, results
and an eve-of-match reminder. Built by the owner (olemoudi) for a friend. Public repo
`olemoudi/jdcm`, distributed only as GitHub releases with a self-updater (alpha/beta channels).
The owner writes in Spanish; code, comments and commits are English; UI is es + en.

No backend, no accounts: the site's login area is out of scope (owner, 2026-11-09).

## Layout

- `:core` — pure JVM, everything with logic, all unit-tested:
  `model/` (Match, Team, Round, `GroupSnapshot` = what is cached per group, `MatchStatus`),
  `parse/SiteParser` (tolerant JSON reader; the MVC actions return a JSON *string* holding JSON),
  `standings/Standings` + `PointsRule`, `schedule/Schedule` (next/last match, matchdays anchored on
  Saturday), `changes/MatchChanges` (what to notify), `policy/RefreshPolicy` (when and which rounds).
  Real responses from the site are fixtures in `core/src/test/resources/fixtures/`.
- `:app` — `data/` (`EcmApi`: one request at a time, 350 ms apart, honest User-Agent, retries;
  `GroupCache`/`KidStore`/`JsonFile`: atomic JSON files; `Settings`: SharedPreferences;
  `Repository`: sync by rounds with progress, explorer lists, team discovery), `notify/` (channels,
  texts, `Reminders` = one inexact `AlarmManager` alarm, re-armed after every sync/boot/fire),
  `work/SyncWorker` (hourly; the policy decides if anything is fetched), `ui/` (Compose screens),
  `update/` + `diag/` (from the oleskills `android-update-channels` and `android-diagnostic-log` skills).
  Manual DI in `AppContainer` (`JdcmApp.kt`).

## The source site (measured 2026-11-09)

- Endpoints: see `EcmApi`. One call per jornada (`ObtenerDatosJornadaGrupo`, `IdJornada=0` = current);
  no whole-season call. `ObtenerCalendarioAgrupado` = every group of a category (or, with
  `IdCompeticion=-1`, of a sport) for one round number.
- **Standings are not public** (`/Clasificaciones` needs a login): computed. Futsal W2 D1 L0 and
  goal-difference tie-break are measured from an official table the friend shared; basketball and
  volleyball W2 L1 are an assumption, stated under every table.
- Season 2026-27 starts 2026-11-14: the parser's notion of a *played* match (state names, `Cerrado`,
  `IdEquipoGanador`, a non-zero score) was written before any real result existed. After the first
  weekend, check Diagnostics → «Match states seen» and adjust `MatchStatus.of` / `Match.hasResult`.
- **Politeness**: never ask faster than the data changes (`RefreshPolicy`: 10 min with the app open
  on match weekends, 2 h otherwise; background 50 min / 5 h, nothing at night; a full pass per group
  once a day). Never parallelise requests to the site.

## Conventions

- Strings: `res/values/strings.xml` (en) and `res/values-es/strings.xml` (es) hold the same keys and
  placeholders (`StringsParityTest`). Write both in the same change.
- «Novedades»: `release-notes/<versionName>.json` (`{"es","en"}`), **at most 3 bullets of 140 chars
  per language** — the owner wants them read in seconds. `ChangelogTest` enforces it and refuses a
  build without its note. A beta's note summarises its alphas in the same 3 bullets.
- Home doors are full-width rows or footer links, never tiny icons (rwilco lesson).
- Long loads show a determinate % (team discovery, first sync of a group).
- Emulator: own AVD `jdcm` on port 5584, headless (`-no-window -memory 2048`), dark mode only, one
  pass per change set, never while building (shared low-memory machine; never touch other projects'
  emulators or Gradle daemons). Shut it down when done (`adb -s emulator-5584 emu kill`).
- Build: `export JAVA_HOME=~/.jdks/jdk-17.0.19+10`. Toolchain: AGP 9.2.1 (built-in Kotlin), Gradle
  9.4.1, compileSdk 37, Compose BOM 2026.08.00, material3 1.4.0 (no Expressive APIs).
- Git: outside the identity folders, push with `ssk run olemoudi -- git push …`.

## Versions, channels and releases

- Two channels, chosen by the tag: `vX.Y.Z-alpha` → **alpha** (GitHub pre-release, testing) and
  `vX.Y.Z-beta` → **beta** (normal release, production; `releases/latest` = newest beta = the install
  link). Any other tag fails the Release workflow. Phones follow one channel (Settings → Updates) and
  update themselves from `channels/<channel>.json`, which CI commits after each release. A fresh
  install follows its own build's channel.
- **Every release is an alpha.** Cut a beta only when the owner says so in words ("promote the alpha
  to beta"). Never offer beta as an option when asking what to cut.
- To cut an alpha: raise `versionCode` by 1 and set `versionName = "X.Y.Z-alpha"` in
  `app/build.gradle.kts` (exactly one line each — CI greps them; never write the pattern in a comment);
  write `release-notes/X.Y.Z-alpha.json` (short, see above); run `./gradlew test`; commit on `main` as
  `X.Y.Z-alpha: <what changed>`; `git tag vX.Y.Z-alpha`; `ssk run olemoudi -- git push origin main vX.Y.Z-alpha`.
- A promotion is a rebuild, not a re-tag: `versionCode + 1`, `versionName = "X.Y.Z-beta"`, notes that
  summarise every alpha since the last beta for end users, commit `X.Y.Z-beta: the alpha channel,
  promoted`, tag `vX.Y.Z-beta`. The next alpha continues above it (alpha ≥ beta, always).
- Never hand-edit `channels/*.json`. Never mark a beta as pre-release. Never put `[skip ci]` in a commit
  you write, and never tag a commit made by a bot (channel pointer): the tag would not start the
  Release workflow.
- Verify a release with `gh run list --workflow release.yml`, `gh release view vX.Y.Z-alpha` and
  `git fetch && git log origin/main -1` (expect `chore: point the alpha channel at X.Y.Z-alpha [skip ci]`)
  — not by reading the raw URL (5-minute CDN cache). Then `git pull` before the next change (the bot
  committed to main).
- No rollback on Android: a bad release is fixed only by a newer versionCode.
- The release key lives in `~/.jdcm-signing/` and in CI secrets. Never commit it, print it, or re-sign
  with a different key.
- The updater is the one part that cannot be fixed remotely: any change to `update/` needs its JVM tests
  green and a real-phone check that the NEXT release still installs.

## Diagnostics (always on)

- `diag/DiagLog` keeps an always-on log: two rotating 256 KB files in `filesDir/diag`, levels
  I/W/E/F, per-minute caps, repeat folding, a crash handler, and Android's record of how earlier
  processes ended (`ApplicationExitInfo`) logged at start.
- Settings → Diagnóstico copies `diag/DiagReport`: header + `## Phone` + app sections
  (`ui/settings/AppDiag.kt`: settings, kids by id, groups with fetch times and failures, match states
  seen, next reminder) + the log tail, ending `== end ==`.
- **When adding code**: every background entry point logs start and outcome; every branch that decides
  *not* to do something the user expects logs why, with its inputs (`key=value`, ids as numbers or
  8-char prefixes); every swallowed exception is `DiagLog.w(tag, "what failed", e)`. Tags in use:
  `App`, `Source`, `Repo`, `Store`, `SyncWorker`, `Notify`, `Reminder`, `Kids`, `UI`, `Update`.
- **Never log** children's names, school/team names, venues, URLs with query strings.

### Reading a pasted diagnostics report

1. Check it arrived whole (`== end ==` last) and which build it is.
2. `now` + zone and `process up`: look for `Exit:`, crashes and ANRs.
3. `## Phone` first: notifications or a channel off, battery restrictions, bucket, data saver, work
   not scheduled explain most "it did not notify" reports.
4. `## Groups`: `LAST SYNC FAILED` / `MISSING n ROUNDS`; `## Match states seen` for parser questions.
5. Don't ask for `adb logcat`; ask for a fresh report after reproducing the problem.
