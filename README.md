# snail run

An Android running tracker that keeps everything on the phone.

The app **has no `INTERNET` permission at all**. That is not a setting or a promise in
the code — the permission is absent from the manifest, so Android refuses the process a
network socket. Nothing it records can leave the device.

## What it does today

- Records a run from the GPS chip: distance, moving time, pace, approximate elevation.
- Speaks your pace aloud at every kilometre, using an on-device speech engine.
- Saves every finished run as a GPX file in a folder you pick once.
- Lists past runs, and shows one with its trace, per-kilometre splits and any records.
- Survives being killed mid-run: the track is in the database, and the app offers to
  finish or continue it on next launch.

Planned next: auto-pause, a calendar view, an offline basemap behind the trace, then
progress charts and GPX import.

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
├── domain/     geo, metrics, analysis, voice, gpx   (pure Kotlin, heavily tested)
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

## Testing without a device

`./gradlew :app:testDebugUnitTest` is the whole feedback loop. Synthetic traces in
`domain/fixtures/Traces.kt` cover a steady run, a traffic-light stop, GPS outliers and
altitude noise; `RunRecorderTest` drives the full pipeline from fixes to a stored run
and a GPX file that parses.

The first real run on a phone should be used to check the accepted/rejected fix ratio,
so the filter thresholds get tuned from data rather than from the estimates in the code.

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
secrets, and leaves the file on your machine. **Back the keystore up.** Nothing
secret is committed, and a clone without the secrets still builds — it just produces
an unsigned APK.

## Licences

Inter is bundled under the SIL Open Font License. See `THIRD_PARTY_LICENSES.md`.
