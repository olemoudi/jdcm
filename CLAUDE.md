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
  Saturday), `changes/MatchChanges` (what to notify), `policy/RefreshPolicy` (when and which rounds),
  `policy/PreMatchPolicy` (the last hours before a child's match).
  Real responses from the site are fixtures in `core/src/test/resources/fixtures/`.
- `:app` — `data/` (`EcmApi`: one request at a time, 350 ms apart, honest User-Agent, retries;
  `GroupCache`/`KidStore`/`JsonFile`: atomic JSON files; `Settings`: SharedPreferences;
  `Repository`: sync by rounds with progress, explorer lists, team discovery), `notify/` (channels,
  texts, `Reminders` = one inexact `AlarmManager` alarm, re-armed after every sync/boot/fire),
  `work/SyncWorker` (hourly; the policy decides if anything is fetched; also the one-off pre-match
  check), `work/PreMatch` (the pre-match alarm), `ui/` (Compose screens),
  `update/` + `diag/` (from the oleskills `android-update-channels` and `android-diagnostic-log` skills).
  Manual DI in `AppContainer` (`JdcmApp.kt`).

## The source site (measured 2026-11-09)

- Endpoints: see `EcmApi`. One call per jornada (`ObtenerDatosJornadaGrupo`, `IdJornada=0` = current);
  no whole-season call. `ObtenerCalendarioAgrupado` = every group of a category (or, with
  `IdCompeticion=-1`, of a sport) for one round number.
- **Standings are not public** (`/Clasificaciones` needs a login): computed. Futsal W2 D1 L0 and
  goal-difference tie-break are measured from an official table the friend shared; basketball and
  volleyball W2 L1 are an assumption, stated under every table.
- Match states measured on the first matchday (2026-11-14, rain; fixtures `j1_*.json`): 0 «Pendiente de
  Jugar»; 6 «No Celebrado/Incompleto» = rain-offs, score **null**; 10 «Disputado con Incidencias», score
  null until ruled; 11 «Disputado sin Incidencias», score set, winner set (0 on a draw), `Cerrado` still
  false; 12 «Desprogramado» (time back to 00:00). Rescheduled matches keep their jornada, with a new
  `FechaHora` and `CambioAdministrador=true` (or an accepted request, state 6). A null score is no result
  (`Match.scoreKnown`); the grouped calendar sends no score fields at all.
- **Politeness**: never ask faster than the data changes (`RefreshPolicy`: 10 min with the app open
  on match weekends, 2 h otherwise; background 50 min / 5 h, nothing at night; a full pass per group
  once a day). Never parallelise requests to the site.
- **Pre-match checks** (owner, 2026-11-14: rain-offs are announced at the last minute): from 4 h before a
  followed child's match (known time, still on) until kick-off, that child's group only, every ~15 min
  (`PreMatchPolicy`). `work/PreMatch`: one **exact** `setExactAndAllowWhileIdle` alarm (`USE_EXACT_ALARM`;
  an inexact one is held to 2/hour in the "frequent" standby bucket, measured) → expedited one-off
  `SyncWorker` (Android 12+) in `SyncMode.PRE_MATCH`; re-armed with the reminder. Off when change
  notifications are off (in the app or in Android). Diagnostics → «Pre-match checks». Verified on the AVD in deep Doze.
- **Replays**: a rescheduled match keeps its round, so `RefreshPolicy.distanceDays` measures a round by
  its date *or* any of its matches' own dates (the old round is hot while its replay is near), and
  `Schedule.matchdayOf` lists a match moved > 3 days from its round on its own weekend.

## Reliability rules (reviews 2026-11-09 and 2026-11-16)

- `GroupSnapshot.merge` is defensive: the group id is ours; other groups' matches dropped; a round
  that shrinks below half (or to nothing, even from one match) is refused unless the same answer
  repeats; a missing date/venue keeps the held one; a "result" dated in the future (any state, a
  "played" one included) is stored as pending with no score; the site's round list is ignored if
  it lacks the round just fetched and never drops rounds holding results. A score is known only if
  it is a number; «No Celebrado» counts as a result only with a winner or a score (never a bare 0-0).
- `Repository.sync` gates on the last *attempt* (`lastAttemptMs`; 10 min for a group never loaded;
  pull to refresh 30 s, 5 s after a failure); rounds closest to today first, neighbours from the same
  answer (`previous`/`next` carry matches); a site-wide failure (5xx, 429, timeout, offline) ends the
  sync and the group loop, an odd answer about one round does not — but two odd answers in a row do
  (a login wall); odd bodies are retried once, 429 never; 3-minute budget; save/report/status in
  `NonCancellable`; reminder re-armed after any fetch. Screens start syncs with `syncInBackground`
  (app scope), never in their own scope. The team search writes groups under the group's lock.
- Notifications: ≤ 3 per child per sync (else one summary), `setOnlyAlertOnce`, ids per kind; changes
  only against data < 3 days old (a group cached long ago by the explorer has old results, not news).
- Eve reminder: each child's first match that is on, not started, not reminded and whose eve is not
  long gone (`Reminders.candidates`); the alarm posts only what is due (`dueNow`). Boot re-arm holds
  `goAsync`. Known: siblings in the same match get one notification (ids per match).
- `KidStore`: read-modify-write under a mutex, disk before memory, write not cancellable, a failed
  write logged (never a crash). The kid editor's new id is fixed per editor (a double tap can't
  duplicate a child); forward navigation only from a RESUMED entry (no double push).
- `JsonFile`: fsync + `.bak`; a corrupt file reads the backup (the children list can't vanish).
- Crests (Coil) use their own OkHttp dispatcher, one request at a time to the site.
- Device-only state (`device` prefs: guide done, worker scheduled, reminded, seen) is not backed up.
- Known limitation: `EcmApi.SEASON_ID` is hard-coded (12 = 2026-27); the explorer and team search need
  a release at the season rollover (July 2027). Followed groups keep working until then.

## First run

`ui/onboarding/`: welcome → notifications → install permission (drawn guide; without it no updates;
on Android 10–11 the text says each update takes one tap — `Perms.silentUpdates`) → battery (optional,
plus «Pausar actividad si no se usa» off while it applies: a summer unopened would stop updates and
remove the alerts permission) → add the first child. Home's `InstallPermissionBanner` reminds while the
install permission is missing ("Más tarde" = 3 days). Navigation after the guide waits for the
NavHost (a crash in 0.2.0's first build). Launcher icon source: `tools/icon/jdcm_icon.svg`
(render with `rsvg-convert` to preview; the vectors in `res/drawable` share its geometry).

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
  install follows **beta whatever APK it came from** (owner, 2026-11-16: nobody should have to touch it);
  the channel is settled once at the first start (`initialChannel`, stored in `updates` prefs). Installs
  that ran a build before 0.2.3 keep the channel they were on (their build's). To test alphas on a new
  phone, switch to alpha in Settings → Updates.
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

## PWA for iPhone families (`pwa/`)

- **The site filters by IP** (measured 2026-11-16): GitHub's runners and 16 of 17 test servers in
  13 countries time out (a Madrid datacenter answered, a Barcelona one did not); Spanish home
  connections answer in 0.3 s. It also sends no CORS headers. So the data comes from home:
  `pwa/pi/jdcm-fetch.sh`, a plain bash script (curl, jq, git) on the owner's Raspberry Pi 3 B+
  (1 GB, Raspberry Pi OS 32/64-bit), run by a systemd user timer at 07:07, 10:07, 13:07, 16:07, 19:07
  Madrid (linger on, night guard 07–21 in the script too). Install: `pwa/pi/install.sh`; owner's guide
  in Spanish: `pwa/pi/INSTALL.md`.
- **No self-hosted runner**: on a public repo a fork's PR could run code on the owner's home machine,
  and a job token for jdcm can push tags, and tags release signed APKs.
- **All groups** (owner, 2026-11-16): every group of the season (~109 in 3 sports) is covered; there
  is no list to configure. The group list and the team catalogue come from the grouped calendars.
- **Fetch policy** (tiers, logged per run as `requests=N (boot, now, results, backfill, daily,
  weekly)`): the grouped calendar `ObtenerCalendarioAgrupado` (`IdCompeticion=-1`) gives every
  group's schedule and states for one sport and round number, but NO scores; a group's own page
  `ObtenerDatosJornadaGrupo` gives scores (one group, one round, plus its neighbours). Every run:
  grouped calendars of the round numbers with matches from −3 to +6 days (and the previous one while
  open), then a group's page only where the grouped calendar shows a match played (or is stale) that
  started over an hour ago, is not cancelled, is from the last 14 days and has no final result;
  oldest first; a group whose page brings nothing new is not asked again in that run. First run of the
  day: grouped calendars of the next 3 weeks; weekly: every other round number; groups never read on
  their own: round 1 (backfill). A sport never read: its round 1 first (bootstrap).
- **Politeness**: one request at a time ≥ 1.5 s apart, UA `JDCM-PWA-fetch/2`, ≤ 150 requests and
  ≤ 8 min a run; non-JSON is asked once more; 5xx/429/timeouts and two odd answers in a row (a JSON
  answer for the wrong round or group counts) stop the run. Defensive merges: a group's round page is
  stored only if it is the round and group asked and holds ≥ half the held matches; a grouped answer
  is taken per group with the same rule; either way the same smaller count is believed only when an
  earlier run saw it too. Measured on a mock with the real grouped answers: a first run ~141 requests,
  ~20 s of CPU here (est. a few minutes on a Pi 3, mostly the 1.5 s gaps), 35 MB RSS.
- **jdcm-data** (`main`, one parentless commit per run built inside the shallow clone, so the push
  carries only changed files; deploy key that can write there only, `~/.config/jdcm-fetch/deploy_key`;
  `JDCM_PUMP_SSH=default` uses your own ssh, e.g. `ssk run olemoudi -- env JDCM_PUMP_SSH=default
  pwa/pi/jdcm-fetch.sh --now` from this PC). Layout `v1/`: `state/sched-<sport>.json` (every grouped
  match by id with `at` = when it last CHANGED, round numbers' fetch times, group names, refusals),
  `groups/<id>/r<round>.json` (the group's page as sent, with scores), `groups/<id>/meta.json` (round
  list, fetchedAt, counts, finals, refusals), `groups/<id>/all.json` (info + meta + rounds + sched:
  what the PWA downloads for a child's group), `catalog/<sport>.json`, `index.json` (every group with
  ids and newestFetch, ~10 KB). Tests: `pwa/pi/test/test.sh` (a mock of both endpoints built from the
  fixtures, a local bare repo).
- **Merging in the PWA** (`pwa/src/core/group.ts`): schedule and state (date, time, venue, state,
  modified) from whichever source is newer; score, winner and closed only from the group's own pages
  (a grouped "played" without a score is «Resultado pendiente»; a page's pending 0-0 never becomes a
  result).
- **Parsing and standings exist twice**: Kotlin `core/` for Android, `pwa/src/core/` (TypeScript) for
  the PWA: SiteParser, match semantics, the merge rules that apply to stored rounds, Standings +
  PointsRule, Schedule.matchdayOf. They are kept in step by the shared fixtures:
  `pwa/src/__tests__/core/` reads `core/src/test/resources/fixtures/*.json` and mirrors SiteParserTest,
  StandingsTest, FirstMatchdayTest and the applicable merge/schedule cases. Change one, change both.
- **PWA**: Vite + React + Tailwind + vite-plugin-pwa (GTD25's stack and update model, see
  `~/vibedev/gtd25/PWA_UPDATE_PROTOCOL.md`), reads `https://raw.githubusercontent.com/olemoudi/jdcm-data/main/v1/`
  and flags data older than 12.5 h (the Pi may be off). Two channels by path on makensi.es (website repo
  `olemoudi/olemoudi.github.io`): `/jdcm/` = beta, `/jdcm/alpha/` = alpha. Each build has its own
  manifest id/scope, worker, cache prefix (`jdcm-<channel>-`) and storage keys (`jdcm:<channel>:`); the
  beta worker ignores `/jdcm/alpha/`. Never use Workbox's `cleanupOutdatedCaches()` (it would delete the
  alpha's precache). Publish: `pwa/scripts/publish.sh ~/vibedev/olemoudi.github.io beta|alpha`, then
  commit and `ssk run olemoudi -- git push` in the website checkout.
- **Tests**: `cd pwa && npx vitest run`; `npx playwright test` (iPhone WebKit + Android Chromium, builds
  both channels twice and serves them nested like the real site); `pwa/pi/test/test.sh`.

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
  `App`, `Source`, `Repo`, `Store`, `SyncWorker`, `Notify`, `Reminder`, `PreMatch`, `Kids`, `UI`, `Update`.
- **Never log** children's names, school/team names, venues, URLs with query strings.

### Reading a pasted diagnostics report

1. Check it arrived whole (`== end ==` last) and which build it is.
2. `now` + zone and `process up`: look for `Exit:`, crashes and ANRs.
3. `## Phone` first: notifications or a channel off, battery restrictions, bucket, data saver, work
   not scheduled explain most "it did not notify" reports.
4. `## Groups`: `LAST SYNC FAILED` / `MISSING n ROUNDS`; `## Match states seen` for parser questions.
5. Don't ask for `adb logcat`; ask for a fresh report after reproducing the problem.
