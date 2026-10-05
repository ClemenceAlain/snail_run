# snail run

An Android running tracker that keeps everything on the phone.

The app **has no `INTERNET` permission at all**. That is not a setting or a promise in
the code — the permission is absent from the manifest, so Android refuses the process a
network socket. Nothing it records can leave the device.

## What it does today

- Records a run from the GPS chip: distance, moving time, pace, approximate elevation.
- Corrects the positions as it goes: jitter smoothed, reflections pulled back onto the
  track, dropouts ridden out. Losing the signal never stops the recording: the clock
  keeps running and the stretch you ran through it is inferred when the fixes come back.
- Optionally stops the clock when you stop, and starts it again when you move off,
  saying so aloud both times.
- Speaks your pace aloud at every kilometre, in English, using an on-device speech
  engine.
- Exports a run as GPX when you ask for one, from the run's own screen.
- Shows past runs as a calendar with that month's runs listed under it, a progress chart
  or your records, and shows one with its trace, its pace-and-elevation graph and any
  records. Drag across the graph for the average over any stretch — it lights up on the
  trace as you drag.
- Draws the trace on an offline map, and opens it full screen to drag, pinch and
  double-tap around. A small demo map is in the APK, so that works before you supply
  one.
- Coaches you through a training plan for a 5 km, 10 km, half or marathon — or no race
  at all. It asks for your VMA first; if you do not know it, the plan opens with the
  six-minute test and reads your VMA off the run. Sessions follow Decathlon's 10 km
  plan: VMA reps, race-pace blocks and a long run, a lighter fourth week and a taper.
  Every pace is a percentage of your VMA, and every number says why it is what it is.
  Step back through the weeks you have done, kept as they were planned, and forward to
  race day. Hold a day to drag it elsewhere.
- Puts two bodyweight strength sessions a week beside the running, on the days that can
  take them, and counts you through one exercise at a time when you start it.
- Backs every run up to one file you choose — and your plan, your VMA, your past weeks
  and your rearranged ones with them — and puts one back.
- Survives being killed mid-run: the track is in the database, and the app offers to
  finish or continue it on next launch.

Nothing is planned next. The Runs tab's three modes — calendar, progress, records — the
Coach tab and the run's own screen cover what the app set out to do.

## Badges, and the size of things

Every screen labels itself with the same five-tone pill: quiet for a rest day or a
warm-up, pale green for easy running, filled green for anything that will hurt, coral for
a long run, amber for strength. A week of them reads in one pass without a word of it
being read, which is the point — the type under them can then be small, because it is
detail rather than headline.

The supporting line is 13sp, down from 15. Nothing primary moved: body text, session
names and every metric are where they were, so this is a denser screen rather than a
smaller one. `onSurfaceVariant`, which carries nearly all of that text, is documented safe
down to 12sp. Each tone pair clears 4.5:1 in both schemes; they are small text under WCAG
however emphatic they look.

**The app writes in English on every phone**, whatever the system language. Every string
in it is English, so following the locale meant a French phone rendering "mar. 23 sept."
under a card headed "Tempo", and "6,3 km" beside "Threshold". `UiLocale` pins the on-screen
half of that and `SPEECH_LOCALE` the spoken half; both move together on the day the app
ships a second language. The one thing still read from the phone is which day a calendar
week starts on, because that is a regional convention rather than a language.

## Demo mode

Settings → **Demo mode** replaces the GPS chip with a synthetic trace, so the app can be
tried indoors: a loop, a pace that drifts, three traffic-light stops and a hill per lap.

Fix timestamps stay one second apart whatever speed you pick, because every metric is
derived from them. Only the wall clock between fixes is compressed: at 30x an hour of
running arrives in two minutes, and the numbers, the splits, the voice and the trace are
the ones an hour of real running would have produced.

Demo runs are drawn on the map in the APK, which covers exactly the ground the synthetic
trace goes over. See **The map in the APK** below.

A demo run is recorded like any other, and counts like any other: it lands in the
database and in your history, it can hold a personal record, and the coach prices your
training paces off it. It is tagged `DEMO`, badged in the list and announced in red on
the record screen while it runs, so a record set on one is visibly a record set on one.
Delete it from its own screen when done — dropping it silently from the totals would
have told you something untrue about what the app keeps.

The generator lives in `domain/demo/DemoRoute.kt` and is pure, so the trace the phone
replays is the one the tests assert on.

## Your runs are only here

Every run lives in one place: the app's private database on the phone. `allowBackup` is
off and the data extraction rules exclude everything, so Android's own cloud backup and
device transfer skip it too. That is deliberate — nothing this app records goes anywhere
the user did not send it. It also means an uninstall, a factory reset or a lost phone
takes every run with it.

So Settings → **Your runs** has **Back up** and **Restore**.

A backup is the database itself, written with SQLite's `VACUUM INTO` so it is one
consistent, compacted file rather than a copy that might be torn mid-write or miss
everything still sitting in the write-ahead log. It is stamped with an `application_id`
of `SNLR`, which is how a restore tells it apart from any other SQLite file the picker
will happily hand over.

Restoring validates before it touches anything: the stamp, the table set, and the schema
version. A backup from an older version is fine — Room migrates it on open. One from a
newer version is refused outright, because an older app cannot know what a later one
added. A copy of the current database is taken first, so a restore that fails halfway
puts the old runs back rather than leaving you with neither set. It is refused outright
while a run is being recorded: that run is not in the backup, and restoring would throw
it away.

The app then restarts itself. The database instance is captured by the recorder, the
exporter and three view models; relaunching into a clean process is a far smaller thing
than making all of that swappable for an operation run perhaps once a year.

**The coach is in the backup**, though it does not live in the database: the race you
are training for, the weeks you rearranged around your job, and what you told the app
you had been running before you installed it. Those are statements about the runner,
exactly like the runs beside them, and a new phone that lost them would spend its first
month planning as though you had never run. They ride in a small key-value table written
into the backup file after the database is copied into it — outside the Room schema, so
it costs no migration, and a build that predates it simply does not look for it.

**Not in the backup:** settings, and the map file you picked. Android ties the picked map
file to the installation, so it would not survive a reinstall even if the backup carried
it — and it is hundreds of megabytes you still have the original of. A GPX export is not
a restore path either: it is a copy for other programs, and nothing reads it back.

There is no longer a switch to write every finished run out as GPX. It defaulted to on,
wrote into a folder chosen once and possibly months earlier, and its entire failure
surface was notifications apologising for a file nobody had asked for: no folder picked,
folder since deleted, grant lost to a reinstall. A GPX is a copy for another program,
which makes it an export, which makes it something you do on purpose — so it is now only
**Export** and **Share** on a run's own screen.

## The Runs tab

Three modes: **Calendar**, **Progress**, **Records**. There is no flat list of every run,
because the calendar already is one — the month's runs are listed under the grid, and
tapping a day narrows that list to the day. A separate list was the same rows with the one
piece of context that makes them worth reading, *when*, taken back out.

Above the grid the month comes to a figure: distance at hero size, then moving time and
average pace. Average pace was not shown anywhere before and is the thing a month of
running actually says about you; the count of active days it replaced is the figure nobody
opens the screen for, and it is still there in a sentence underneath.

## Records

Runs → **Records** is the best time over each standard distance — 1 km, 5 km, 10 km, half
marathon, marathon — across every run, with the run it was set on one tap away. A distance
nothing has covered yet is still listed, greyed: a list that simply stopped at 10 km would
read as the app having no opinion about a half marathon, rather than as a half marathon
not having been run.

It is a read over the best efforts already stored with each run, not a fresh pass over
every track. So it costs one indexed query per distance, and it is still right the moment
a run is deleted.

Demo runs are excluded, and so are runs still being recorded.

## The map

Settings → Map. There is no way to fetch a tile — no `INTERNET` permission — so the map
is a file you put on the phone: an **MBTiles** raster extract of wherever you run. Pick
it once and it is copied into the app's own storage, because SQLite needs a path and a
picked file can be moved or deleted out from under a run.

- Zoom levels are read from the tiles themselves, not from what the file claims, because
  hand-cut extracts routinely claim wrong. So is the ground it covers.
- A run outside what the file covers still draws, as a plain trace over blank.
- With a map present the trace is projected in Web Mercator rather than the app's own
  equirectangular projection. Tiles are cut to Mercator by definition, and mixing the two
  puts the trace visibly beside the road it was run on.

### Full screen

Tapping the trace on a run's screen opens it full screen, where it can be dragged,
pinched, double-tapped and zoomed with the buttons. It works with no map file at all:
the camera projects the trace whether or not there are tiles under it, so looking closely
at one corner of a run is not something you have to supply hundreds of megabytes to be
allowed to do.

The card on the run's own screen stays fixed and fitted to the run. The two have
different jobs — one answers *what shape was it* at a glance, the other is for looking
closely — and making the card movable would mean it jumped whenever a thumb brushed past.

A few things behind it:

- **The camera is a centre and a continuous zoom**, not a fitted viewport. A viewport is
  world pixels at one integer zoom, so a pinch would have to rewrite its origin and its
  scale together and keep them consistent; a centre and a zoom survive the gesture
  unchanged in meaning. `domain/geo/MapCamera.kt`, pure and tested.
- **Tiles exist only at integer zooms**, so the integer part of the zoom picks the level
  and the fraction becomes a scale factor. Past the deepest level the file holds, the
  level stops and the scale keeps going.
- **A missing tile is drawn from its nearest loaded ancestor**, enlarged, up to three
  levels up. A blurry map that pans is worth more than a sharp one that flashes white
  every time it is touched.
- **Tiles are keyed on the tile range, not the viewport.** A viewport changes on every
  frame of a drag and a range changes only at a tile boundary; keying the load on the
  viewport restarts it continuously and never finishes one.
- **The trace's world coordinates are computed once** at a reference zoom and scaled per
  frame. Projecting a latitude costs an `asinh` and a `tan`, and Mercator scales linearly
  with zoom, so a dragged map need not pay it for every point on every frame.

### Records

Runs → **Records** is the best time over each standard distance — 1 km, 5 km, 10 km, half
marathon, marathon — across every run, with the run it was set on one tap away. A distance
nothing has covered yet is still listed, greyed: a list that simply stopped at 10 km would
read as the app having no opinion about a half marathon, rather than as a half marathon
not having been run.

It is a read over the best efforts already stored with each run, not a fresh pass over
every track. So it costs one indexed query per distance, and it is still right the moment
a run is deleted.

Demo runs are excluded, and so are runs still being recorded.

## The map in the APK

`app/src/main/assets/demo.mbtiles` is a small raster map covering the ground the demo run
is run on, so demo mode shows a trace on a map before you have supplied one.

It is **drawn, not cut from anyone else's tiles**. A map we drew carries no licence, no
attribution requirement and no tile-server usage policy, and the demo run is synthetic
anyway. It is deliberately **not a map of Paris**: it is a plausible invented town over
the same ground — a rotated street grid, two avenues across it, a river, three parks and
buildings close in.

It is used only where it has tiles. A map you chose applies to every run you have, holes
and all, because you chose it; the bundled one is handed to the renderer with its
coverage attached, so a real run anywhere else is drawn exactly as it was before the
bundled map existed.

Regenerate it with:

```bash
python3 tools/make-demo-map.py     # needs Pillow
```

Deterministic from a fixed seed, so a regeneration that changed nothing produces no diff.
`DemoBasemapTest` opens the committed file and checks it still covers where `DemoRoute`
runs — it is generated and committed rather than built, so nothing else would notice if
the generator were changed and not re-run.

## Saying less

Screens carry one line and a **?**. The app has reasons for what it does and they are
worth reading once, but a paragraph the reader has already understood is noise the second
time — and four of them push the control they came for below the fold. So each section of
Settings, and the coach's week and paces, keep a sentence on screen and put the rest
behind a question mark.

## Requirements

- Android 12 (API 31) or newer. Built and tested against API 35.
- No Google Play Services. The app uses the platform `LocationManager` directly, so it
  works on a de-Googled ROM.
- A text-to-speech engine for the voice feature, with an **English** voice. A LineageOS
  build without Google apps may have none; install **eSpeak NG** or **RHVoice** from
  F-Droid. Without one, runs still record normally and the voice simply stays off.

  English specifically, whatever the phone's language is set to. Every announcement in
  this app exists only in English, so following the system locale meant a French phone
  picking a French voice and then being handed "average pace 5 minutes 12 seconds per
  kilometre" — an English sentence read with French phonemes, which is neither language
  and close to unintelligible at a run. `SPEECH_LOCALE` pins both ends of it: the strings
  are read out of English resources and the engine is asked for an English voice. The day
  the app ships a second language, both move together.

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
├── domain/     geo, metrics, analysis, coach, voice, gpx, demo  (pure Kotlin, tested)
├── data/       db, prefs, location, repo, export, voice
├── tracking/   RunRecorder, RunRecordingService
└── ui/         theme, components, record, history, coach, detail, settings
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

### Improving it later

Runs carry the filter version they were derived with. Raise `TrackSmoother.VERSION`, and
on next launch every run recorded under an older version is re-derived from its raw
positions: cumulative distances, totals, splits and records all rewritten. The latitudes
and longitudes are never touched — they are the record of what the chip said.

## Losing the signal

A tunnel, a station underpass, a street of tall buildings. The app used to treat that as
the runner stopping — no fix meant no clock — so the minutes spent under it vanished from
the moving time while the distance across it was still credited, which came out as a pace
nobody ran. Now the recording simply continues, and the hole is accounted for when the
fixes come back.

Two things happen, both in `domain/metrics`:

- **A degraded chip is still a chip.** A fix has to beat 25 m of accuracy to be believed,
  and a receiver losing the sky reports worse and worse accuracy before it goes quiet —
  so at that bar a run under a canopy records nothing at all. Once the fixes have already
  stopped for fifteen seconds the bar drops to 50 m. A poor position is still a position,
  the Kalman filter weighs it by exactly the accuracy it carries, and taking it beats
  inferring the whole stretch. The bar is never relaxed among good fixes, where a 50 m fix
  is a reflection, and never for the first fix of a segment, which anchors everything
  measured from it.
- **The gap itself is classified**, not ignored. `TrackGaps` looks at how long the fixes
  were away and how far apart the two ends are, and answers with one of three things:
  - under thirty seconds, nothing special: the fix stream breathing.
  - otherwise, if the speed the gap implies is between a brisk walk and 2:46/km, and the
    hole is under twenty minutes, it is **inferred**. The clock runs through it because
    the runner did, the straight line between the two fixes is counted as distance, and
    the pace the stretch gets is the one it implies rather than whatever the chip said at
    the moment it regained the sky.
  - anything else is **broken**: too slow means they were standing about with the phone
    on a table, too fast means they took the metro, too long means it was not a lost
    signal but a lost afternoon. Nothing crosses it — not the clock, not the distance, and
    not the drawn trace, which starts a new segment there exactly as a pause does.

The same rules run live and on every later re-derivation, which is what keeps the figure
on the record screen and the figure on the run's own screen the same figure. And because
a gap is a property of two stored fixes, none of it is written down: the run's own screen
says how long the signal was lost and how far was inferred by working it out again from
the track, so runs recorded before any of this existed are read under it too.

Two details worth knowing before touching it:

- **The velocity is set from the first two fixes, not converged towards.** Left to
  converge it spends ten seconds behind the runner and loses about ten metres off the
  front of every run.
- **Distance is gated on speed, not on a jitter floor.** The floor existed to stop raw
  noise being integrated; the filter removes the noise instead. What replaces it is a
  speed gate, read from the chip's Doppler where that is trustworthy — standing at a
  light, the filter is deliberately stiff and coasts ten metres before it believes you
  have stopped, while Doppler collapses to zero at once.

## Coaching

The Coach tab runs a training plan you start yourself. There is no model in it. Every
number is arithmetic with a reason attached, and the reason is one tap away.

**A plan is four answers.** Your VMA, your race (distance and date, or none), a target
time, and two, three or four sessions a week. They are stored as they were given, in one
preference. Everything else — which week this is, what the sessions are, what pace they
are written in — is worked out from them each time a week is shown, so a template change
never needs a migration.

**VMA first.** VMA, maximal aerobic speed, is the speed at which you reach your maximum
oxygen uptake, and every fast session is written as a percentage of it: 105 % of a
14 km/h VMA is 4:05/km. You can type it, enter the distance of a six-minute test you have
already run, or say you do not know. Then the plan's first quality session is the test
itself — 15 minutes easy, three strides, then six minutes as far as you can, the SAC
Athlétisme protocol: metres ÷ 100 is your VMA in km/h. When you save the run, the coach
replays the stored track through the same scheduler the run screen uses and reads the
VMA off the six-minute segment alone, so the warm-up cannot drag it down. A test stopped
before 5:30 is not read; the tab asks for the distance instead. Twelve weeks after the
last measurement the plan asks for a new test.

**The engine underneath is still VDOT.** A six-minute test is a six-minute race, so a VMA
goes through Daniels and Gilbert's equations like any other effort: `Vdot.fromEffort(VMA
× 100 m, 6 min)`. A 15 km/h VMA comes out near VDOT 44. That gives the easy range, the
race predictions and, without a VMA, an estimate worked back from your best recent effort
of 5 km or more — shown as an estimate, with the test offered to replace it. `VdotTest`
checks the equations against Daniels' published table at VDOT 50.

**The sessions follow Decathlon Coach's "10 km in 8 weeks".** Three a week: a VMA session
on Tuesday, a race-pace session on Thursday, an easy long run on Sunday. Every quality
session is framed the same way: 20 minutes easy, 5 minutes of drills, three 100 m strides,
the main set, 10 minutes easy. The 10 km plan is the published table with your race pace
in place of its 15.3 km/h. The VMA reps go 12 × 200 m at 105 %, 12 × 300 m, 10 × 400 m,
2 × (8 × 200 m) and 8 × 500 m at 95 %. The race-pace blocks grow from 5 × 1 km to 2 × 3 km
and a 3 + 2 + 1 km ladder. Week four is lighter. Week eight is the race. `PlanScheduleTest`
holds the template to the table line by line.

The 5 km (8 weeks), half (12) and marathon (16) plans use the same structure with the
app's own numbers. The 5 km has shorter reps. The half and marathon add threshold
sessions at 80–85 % of VMA, longer race-pace blocks and longer runs, and a lighter week
every fourth. Two sessions a week keeps the long run and alternates the other two. Four
adds a 40-minute easy Friday. Under a 12 km/h VMA, runs are about a fifth shorter and rep
counts a quarter lower: the templates are written for a runner who already runs 10 km
under 45 minutes.

**The race date fits the template, not the other way round.** With more time than the
template, four-week base cycles go in front of it, ending on a lighter week. With less,
the template's first weeks are skipped, but the taper and race week are always kept.
Race week is laid out from the race date: the last short VMA session five days before, a
20-minute shake-out two days before, the race on the day. With no race, the 10 km month
repeats, its second half the second time round, at your predicted 10 km pace.

**Race pace is your target, or your prediction.** The wizard fills in the time today's
fitness predicts. You can leave it, or type the time you want. A target more than 5 %
beyond your current fitness gets a warning, not a refusal.

**Every number explains itself.** Each step of a session carries its intensity ("105 %
VMA") beside its pace, and a "why" sentence on the session's sheet. The why says what the
pace is a percentage of, why the recovery is that short, what the session trains, and what
to do without a VMA yet. Each session also has a tip, the way a coach would say it at the
track. **About this plan** on the plan card explains the plan as a whole:

- how the weeks were fitted to your race;
- where the paces come from, with the 105/100/95/85/80 % ladder at your VMA;
- the four-fifths-easy rule;
- what each kind of session does;
- why weeks get lighter and why the plan tapers.

`PlanWeeksTest` fails the build if any session in any template lacks a reason, a tip or
an explained step.

**Every figure is one a runner can hit.** Rep distances are on a 50 m grid, durations in
whole minutes, and a session's total is the sum of the sequence the recorder will count
you through (`Workouts.metersOf`). `WorkoutRoundingTest` and `PlanWeeksTest` assert that
over every session.

**Two strength sessions a week.** Bodyweight, no equipment, fifteen or twenty minutes:
squats, split squats, calf raises, glute bridges and a plank one week; single-leg
deadlifts, side planks, step-ups and hip work the next. They carry no distance, so they
never reach a week's totals or the recorder. They go on rest days first, and never the day
before anything hard: loaded legs are slow legs for about a day. Race and taper weeks get
one.

**The paces are not on this tab.** They live under **Runs → Records**, closed until tapped.
They come from the same place as the plan's: your VMA if you gave one, your efforts if not.

**Weeks ahead are worked out; weeks behind are kept.** The arrows go forward to race week,
or half a year for a plan with no race. Each future week is planned when you look at it,
from the plan and today's VMA, so a new VMA reprices all of them at once. The current week
is saved each time it is planned, one JSON row per week in the `coach_weeks` table (schema
4), and the row stops changing when the week ends. So the back arrow shows the weeks you
were actually given, at the paces you had then, even after a new VMA or a new plan. Whether
a day was done is still read off the runs, so a run typed in later ticks the day it was run
on. A week the app was never opened in was never saved and is skipped. Starting a new plan
replaces this week and keeps every week before it.

**Finishing a run is held, not tapped.** It closes the run and drops the recorder's
state, there is no undo, and the control sits under the thumb of somebody out of breath
looking at the pavement. A bar fills under the label as you hold, so the gesture explains
itself the first time somebody brushes it and an abandoned hold visibly loses its
progress. A confirmation dialog is the usual answer and is worse: it puts a second target
on screen for a shaking hand, and trains people to dismiss it without reading.

**Hold a day to drag it somewhere else.** The days it passes shift along by one, because
that is what moving a session means: pushing Tuesday's VMA session to Wednesday slides
Wednesday back a day, rather than trading places with it. Only the rearrangement is stored,
keyed on the week, so a move survives a new run, a restart and a new VMA. If the move
stacks two hard days or leaves four days running without a rest, the week says so in red
and leaves it alone. A saved past week cannot be rearranged.

### The strength session, counted

**Start** on the strength card — on the record screen, or from a day in the Coach tab —
opens a screen that counts you through it. One exercise at hero size, the set you are on,
and a countdown or a rep target. The background changes colour between working and
resting, which is the one piece of state readable from a mat two feet away without
focusing on anything.

The design turns on one asymmetry. A running session is driven entirely by GPS fixes: the
runner covers ground whether or not they are paying attention, so every boundary can be
detected. A floor session has no such signal — nothing observable happens when somebody
finishes their tenth squat. So held sets and the forty-five-second rests end on a clock
and advance themselves, and counted sets end when you say they have, which is why **DONE**
is the big button during one rather than a small one beside a timer. `StrengthSession` is
pure and clock-free like the running scheduler, and `StrengthSessionTest` drives it with
numbers.

**Nothing is recorded.** No location is read, no run row is written, and nothing reaches
the history or the coach's volume arithmetic. There is no distance to measure and a 0 km
entry in the runs list would be a lie about a real piece of training. The consequence,
stated because it is the obvious next question: a completed strength session is not ticked
off anywhere, since the coach infers completion from runs and there is no run. Doing that
properly needs somewhere to store it, which is a schema change and has not been made.

### Running the session

The coach's prescription is only half of it. Load a session — **Run this** on a day in the
Coach tab, or **Load** on the record screen, which offers today's — and the app counts you
through it.

Each step is named when it starts, out loud and with a buzz: one long pulse into a hard
step, two short ones into a recovery, a pair at the end. Time steps get a spoken three,
two, one into the change. The screen shows the step, the rep number, the target pace and
what is left of it; the notification carries the same line, because that is what you pull
the phone out to read.

- **A step ends on active duration or on distance**, whichever it was prescribed in.
  Standing at a light does not burn a rep — the clock that counts it is the same one that
  stops when you stop.
- **Boundaries are tested on a fix**, about once a second. There is no timer anywhere in
  this app and this did not add one: a timer would end a rep you were not moving through.
  A step therefore changes on the first fix past its end, and a step whose end falls inside
  a dropout hands the overshoot to the next one, so five reps cannot quietly become five
  reps and five seconds.
- **NEXT** ends a step where you are standing — you crested the hill early — and **END
  SESSION** drops the guidance and keeps recording.
- **Off-pace warnings are off by default.** The pace they read is smoothed GPS, which
  wanders ten seconds a kilometre under trees, so they need twenty continuous seconds
  outside the band, a step longer than ninety seconds, and they speak at most once a
  minute. Without every one of those they are the setting that gets the whole voice turned
  off.

Afterwards the run's own screen shows the session against what you ran: every step, its
target, its actual pace and the difference. That table is worked out from the stored track
each time it is opened rather than written down during the run, so improving the position
filter improves every past session with it.

What *is* stored is the prescription — the weeks ahead are replanned whenever your VMA
moves, so the session this run was is not necessarily one the coach would still write —
and the handful of times you pressed NEXT, which is the one thing about a guided run that cannot
be worked out again. Where you had got to is not stored: after a crash it is rebuilt by
running the track back through the same scheduler, and `RunRecorderTest` checks the two
agree.

### Running it with someone

A partner is a name and a VMA under Settings → Coach — not a second user. They have no
runs and no plan here; the app only translates the days you share. Open a day, pick them,
and their version of every block appears under yours. Each runner is a snail in their own
colour: blue for you, then violet, magenta, teal and slate for partners. Your snails face
each other on a shared day.

- **Their paces are your session at their speed.** Each of your paces is read back to the
  fraction of your VDOT it was written at, then forward at theirs. VMA becomes VDOT through
  the same oxygen-cost curve as everything else (`Partners.vdotOf`), so there is no second
  model. Mirror mode keeps the same reps, distances and times.
- **Time it so we regroup** (`PartnerPlan.together`) runs a clock for each of you. Easy
  running and the jogs are shared at one pace where your bands overlap, or where the faster
  one only has to drop thirty seconds a kilometre below theirs. Reps stay as they are, at
  your own paces. On distance reps the faster one finishes early and jogs the gap, so you
  start the next rep together. On timed reps you start and stop together, and the sheet
  says how far ahead the faster one ends, so they know how far to come back. A tempo is
  run for your time, so you finish it together.
- **Only the shared day changes.** The pairing is stored against the day the session was
  planned for, so it follows a drag. Removing the partner forgets it. **Send … the
  session** shares their version as plain text.

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

Both are said aloud — "paused", and "running again" — if the voice is on. It rides on the
voice setting rather than one of its own: someone who turned the voice off wants the app
quiet, including about its own decisions. The resume is the more important of the two. A
runner who missed the pause is standing at a light believing they are still being timed;
a clock that restarted without saying so never corrects them.

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

**Back your runs up before that uninstall.** Settings → Your runs → Back up, to a file
somewhere other than the phone, then uninstall, install the signed APK, and restore.

For a signed build locally, copy the keystore to `release.keystore` in the repository
root — it is gitignored — and pass the passwords through `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD`.

## Licences

Inter is bundled under the SIL Open Font License. See `THIRD_PARTY_LICENSES.md`.
