# snail run

An Android running tracker that keeps everything on the phone.

The app **has no `INTERNET` permission at all**. That is not a setting or a promise in
the code — the permission is absent from the manifest, so Android refuses the process a
network socket. Nothing it records can leave the device.

## What it does today

- Records a run from the GPS chip: distance, moving time, pace, approximate elevation.
- Corrects the positions as it goes: jitter smoothed, reflections pulled back onto the
  track, dropouts ridden out.
- Optionally stops the clock when you stop, and starts it again when you move off.
- Speaks your pace aloud at every kilometre, using an on-device speech engine.
- Saves every finished run as a GPX file in a folder you pick once.
- Lists past runs as a list or a calendar, and shows one with its trace, its
  pace-and-elevation graph and any records. Drag across the graph for the average over
  any stretch.
- Draws the trace on an offline map, if you put one on the phone.
- Survives being killed mid-run: the track is in the database, and the app offers to
  finish or continue it on next launch.

Planned next: progress charts.

## Demo mode

Settings → **Demo mode** replaces the GPS chip with a synthetic trace, so the app can be
tried indoors: a loop, a pace that drifts, three traffic-light stops and a hill per lap.

Fix timestamps stay one second apart whatever speed you pick, because every metric is
derived from them. Only the wall clock between fixes is compressed: at 30x an hour of
running arrives in two minutes, and the numbers, the splits, the voice and the trace are
the ones an hour of real running would have produced.

A demo run is recorded like any other — it lands in the database and in your history —
so it is tagged `DEMO`, badged in the list, announced in red on the record screen while
it runs, and excluded from personal records. Delete it from its own screen when done.

The generator lives in `domain/demo/DemoRoute.kt` and is pure, so the trace the phone
replays is the one the tests assert on.

## The map

Settings → Map. There is no map tile in the app and no way to fetch one, so the map is a
file you put on the phone: an **MBTiles** raster extract of wherever you run. Pick it
once and it is copied into the app's own storage — SQLite needs a path, and a picked file
can be moved or deleted out from under a run.

- Zoom levels are read from the tiles themselves, not from what the file claims, because
  hand-cut extracts routinely claim wrong.
- A run outside what the file covers still draws, as a plain trace over blank.
- With a map present the trace is projected in Web Mercator rather than the app's own
  equirectangular projection. Tiles are cut to Mercator by definition, and mixing the two
  puts the trace visibly beside the road it was run on.

## Requirements

- Android 12 (API 31) or newer. Built and tested against API 35.
- No Google Play Services. The app uses the platform `LocationManager` directly, so it
  works on a de-Googled ROM.
- A text-to-speech engine for the voice feature. A LineageOS build without Google apps
  may have none; install **eSpeak NG** or **RHVoice** from F-Droid. Without one, runs
  still record normally and the voice simply stays off.

## Building

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK, ~2.6 MB
./gradlew :app:testDebugUnitTest  # the full test suite, JVM only
./gradlew :app:lintDebug
```

`local.properties` points at the Android SDK. JDK 17 or newer is required; the build
runs on JDK 21 and emits Java 17 bytecode.

## How it is put together

One module. The `domain` package imports nothing from `android.*` or `androidx.*`,
which is what lets the arithmetic — filtering, distance, pace, splits, records, GPX,
the announcement schedule — run under plain JUnit with no device and no emulator.

```
io.snailrun
├── domain/     geo, metrics, analysis, voice, gpx, demo  (pure Kotlin, tested)
├── data/       db, prefs, location, repo, export, voice
├── tracking/   RunRecorder, RunRecordingService
└── ui/         theme, components, record, history, detail, settings
```

A few decisions worth knowing before changing things:

- **Distance uses a local equirectangular projection**, not haversine. Haversine on a
  sphere is up to 0.5 % out, which is 50 m over 10 km. Verified against Vincenty.
- **The fix filter holds an anchor** rather than comparing each fix to its predecessor.
  At 5:30/km a runner covers about 3 m per second, right on the jitter floor, so the
  naive version would silently drop half the distance of a run.
- **Pace is smoothed over speed, never over pace.** Pace is 1/speed, and the mean of
  reciprocals is not the reciprocal of the mean.
- **Every pause increments a segment index.** Distance accumulates only within a
  segment, so no gap is ever counted or drawn as a straight line — and GPX gets one
  `<trkseg>` per segment.
- **Voice announcements use active duration, not wall clock**, so a pause can never
  produce a catch-up burst of announcements on resume.
- **Numbers spoken aloud are words, never "5:12".** Engines read the colon literally.
- **The database stores raw positions and nothing derived from them is trusted.** Every
  figure the app shows — distance, pace, splits, records, the drawn trace, the GPX — is
  re-derived from those positions through the current filter. See below.

## Correcting the positions

`domain/geo/TrackSmoother.kt` is a constant-velocity Kalman filter over local metres, one
independent filter per axis. It does three things a raw GPS track needs:

- **Jitter.** A receiver wanders a metre or two a second even standing still. Summed
  naively that wander becomes distance: measured against fixtures whose true length is
  known, a raw track reads about **65 % long**. Through the filter it lands within 2 % on
  a straight run and 3.5 % on a twisty one.
- **Reflections.** A fix hundreds of metres out is not dropped — leaving a tunnel, such a
  fix is the truth. Its measurement noise is inflated instead, so it nudges the estimate
  rather than snapping it, and three in a row are accepted as a real reposition.
- **Dropouts.** The prediction runs on the last known velocity and its uncertainty grows
  with the gap, so the fix that ends a dropout is adopted at once instead of being fought
  as an outlier. Nothing is invented across the hole: a straight line is the only
  reconstruction the data supports.

Two details worth knowing before touching it:

- **The velocity is set from the first two fixes, not converged towards.** Left to
  converge it spends ten seconds behind the runner and loses about ten metres off the
  front of every run.
- **Distance is gated on speed, not on a jitter floor.** The floor existed to stop raw
  noise being integrated; the filter removes the noise instead. What replaces it is a
  speed gate, read from the chip's Doppler where that is trustworthy — standing at a
  light, the filter is deliberately stiff and coasts ten metres before it believes you
  have stopped, while Doppler collapses to zero at once.

### Improving it later

Runs carry the filter version they were derived with. Raise `TrackSmoother.VERSION`, and
on next launch every run recorded under an older version is re-derived from its raw
positions: cumulative distances, totals, splits and records all rewritten. The latitudes
and longitudes are never touched — they are the record of what the chip said.

## Auto-pause

Off by default; Settings → While recording. Two thresholds with a dwell on each, because
one threshold flaps: a runner standing at a light would produce a burst of pause and
resume events, each splitting the track into another segment.

- Pauses about four seconds after you stop, resumes about two seconds after you move off.
- Walking does not pause the run; only standing still does. A walking break in the middle
  of a run is usually still the run.
- A pause you made by hand is never undone for you.
- Resuming fires at a lower bar and a shorter dwell than pausing, because the two
  mistakes do not cost the same: a late pause adds a few seconds of standing to the
  clock, a late resume silently drops real running out of it.

## Testing without a device

`./gradlew :app:testDebugUnitTest` is the whole feedback loop. Synthetic traces in
`domain/fixtures/Traces.kt` cover a steady run, a traffic-light stop, GPS outliers and
altitude noise; `RunRecorderTest` drives the full pipeline from fixes to a stored run
and a GPX file that parses.

The first real run on a phone should be used to check the accepted/rejected fix ratio,
so the filter thresholds get tuned from data rather than from the estimates in the code.

`TrackSmootherTest` scores the position filter against fixtures whose true length is
exact, so a change to it is a number that went up or down rather than a judgement about
whether the trace looks nicer. `MigrationTest` opens a real version 1 database and
upgrades it, because a migration that fails takes every run on the phone with it.

## Releases

CI runs the tests, lint and a debug build on every push and pull request. Tagging
builds the release APK and attaches it to a GitHub release:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The APK must be signed to be installable, and the signing key must stay the same
across versions — Android identifies an app by its signature, so a new key means the
update cannot install over the existing app, and the runs on the phone would be lost
with it. Set the key up once:

```bash
./tools/setup-signing.sh
```

That creates a keystore under `~/.snail-run/`, uploads it to the repository as
secrets, and leaves the file on your machine. **Back the keystore up.** Nothing secret
is committed.

A build without those secrets is **debug-signed** rather than left unsigned, so a
fork, or this repository before signing is set up, still produces an APK that
installs. The debug key differs per machine, so the first release-signed build cannot
replace a debug-signed one in place: uninstall the app once at that point.

For a signed build locally, copy the keystore to `release.keystore` in the repository
root — it is gitignored — and pass the passwords through `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD`.

## Licences

Inter is bundled under the SIL Open Font License. See `THIRD_PARTY_LICENSES.md`.
