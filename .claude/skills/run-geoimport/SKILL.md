---
name: run-geoimport
description: Build, run, screenshot and drive Geoimport - the Android app on an emulator (boot, install, seed tracks and photos, tap by on-screen text) and the desktop CLI. Use when asked to run, start, launch, screenshot or test the app, or to confirm a track-cleanup, geotagging or import change works in the real app rather than in tests.
---

# Running Geoimport

Two deployables share `core/`: an Android app (`app/`) and a CLI (`desktop/`).
Paths below are relative to the repo root.

The Android app is driven by `.claude/skills/run-geoimport/driver.mjs`, a Node
wrapper around `adb` that boots the emulator, installs, seeds tracks and photos,
and taps by on-screen text. **Changes to `core/` are far quicker to check
through the desktop CLI** - see [Fast path](#fast-path-core-changes) - so reach
for the emulator when the change is in `app/` or when you need to see the map.

## Prerequisites

Windows, with the Android SDK at `%LOCALAPPDATA%\Android\Sdk` (override with
`ANDROID_SDK_ROOT`), at least one AVD, and Node on `PATH`. Gradle needs a JDK:

```bash
export JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr"
```

Nothing else installs - no emulator packages to fetch, no `apt-get`.
The driver sets `JAVA_HOME` itself; the export is for calling `gradlew`
directly.

## Run: Android app (agent path)

```bash
node .claude/skills/run-geoimport/driver.mjs boot      # starts the AVD if none is attached
node .claude/skills/run-geoimport/driver.mjs install   # assembleDebug + install + all-files access
node .claude/skills/run-geoimport/driver.mjs start
node .claude/skills/run-geoimport/driver.mjs shot home # -> build/run-shots/home.png, prints the path
```

`boot` takes about a minute from cold; `install` about 40 s. Read the
screenshot with the Read tool - a blank or splash frame means you screenshotted
too early, so `wait-text` first.

| Command | What it does |
| --- | --- |
| `ui` | every on-screen text with the coordinates to tap it |
| `find <text>` | matching nodes, with bounds |
| `tap-text <text> [--x N]` | tap a node by its text; `--x` taps that column on the node's row |
| `wait-text <text> [secs]` | poll until the text appears (use instead of sleeping) |
| `tap <x> <y>`, `swipe <x1> <y1> <x2> <y2>` | raw input |
| `sheet-up`, `sheet-down` | expand / collapse the bottom sheet |
| `menu <item>` | open the overflow menu and tap an item |
| `settle` | wait until the screen stops changing, before a `shot` |
| `push-track <file.gpx>` | copy a track to `/sdcard/Documents/Geoimport` |
| `seed-photos <folder> <YYYYMMDDhhmm...>` | dummy RAFs with those **UTC** capture times |
| `stop`, `grant` | force-stop; re-grant all-files access |

Real tracks to push live in `C:\Users\pbung\Downloads` and
`C:\Users\pbung\Downloads\GPS-Tracks`.

### Seeing the track cleanup on the map

```bash
D=".claude/skills/run-geoimport/driver.mjs"
node $D push-track "C:/Users/pbung/Downloads/2026-09-18_01.gpx"
node $D stop && node $D start
node $D sheet-up && node $D wait-text Tracks 20
node $D tap-text "2026-09-18_01" --x 94     # the checkbox column
node $D sheet-down
node $D menu "Show raw track"
node $D shot raw-on
```

The cleaned track draws red, the raw one grey beneath it, and each removed fix
gets a red ring. Tapping a ring (`tap <x> <y>` on the ring) replaces the card at
the top with that fix's reasoning.

### Placing photos end to end

Seed **before** launching (see Gotchas), then:

```bash
D=".claude/skills/run-geoimport/driver.mjs"
node $D seed-photos "Import 05" 202609181000 202609181200
node $D stop && node $D start
node $D menu "Place photos already here"
node $D wait-text "Import 05" 20 && node $D tap-text "Import 05"
node $D wait-text "ready to place" 30 && node $D tap-text "Place "
node $D wait-text "placed" 60 && node $D tap-text "Done"
node $D menu "Import log" && node $D wait-text "Matching against" 20
node $D ui | grep -iE "matching|leaving|tagged|no track"
```

which prints what the run decided:

```
Matching against 1 of 14 tracks: 2026-09-18_01 (1920 points) (13 outside the photos' time range).
Leaving out 2 misleading fixes and 1 altitude.
No track point within tolerance for DSCF0001.RAF - not geotagged.
Tagged 1 of 2 files.
```

## Fast path: `core/` changes

The CLI runs the same selection, cleanup and geotagging as the app, in seconds,
against the real tracks on this machine:

```bash
export JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr"
./gradlew :desktop:run --console=plain -q --args="tracks list --tracks-dir=C:/Users/pbung/Downloads/GPS-Tracks"
./gradlew :desktop:run --console=plain -q --args="tag <photo-dir> --track C:/Users/pbung/Downloads/2026-09-18_01.gpx --dry-run"
```

`tag --dry-run` writes nothing and prints the same two lines the app logs, plus
a row per photo:

```
Matching against 2026-09-18_01 (1920 points).
Leaving out 2 misleading fixes and 1 altitude.
  DSCF0001.JPG  2026-09-18T10:00:00Z   46.95582,   7.44654 [built-in]
```

Capture times come from EXIF, falling back to file modification time - so a
couple of dummy files with `touch -d "2026-09-18 10:00:00Z"` are enough to
exercise it.

## Run: human path

Android Studio, or `./gradlew :app:installDebug` and launch from the launcher.
Useless for checking anything without eyes on the screen.

## Test

```bash
export JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr"
./gradlew :core:test :desktop:test :app:compileDebugKotlin --console=plain
```

`:app` has no unit tests worth running; compiling it is the check.

## Gotchas

- **`JAVA_HOME` must point at Android Studio's JBR.** Without it `gradlew`
  fails outright; there is no other JDK on this machine.
- **Node cannot spawn `gradlew.bat` directly.** Since the CVE-2024-27980 fix it
  throws `EINVAL` unless `shell: true` is passed. The driver does.
- **`adb push` from Git Bash needs a Windows host path.** `/c/Users/...` fails
  with `cannot stat`; use `C:/Users/...` (and `MSYS_NO_PATHCONV=1` so the
  *device* path survives). The driver resolves paths itself, so `push-track`
  takes either.
- **`touch -t` on the device reads UTC but `ls` prints local time.** Seeding
  `202609181000` gives a file the app sees at 10:00Z, listed as 11:00 in
  Europe/Berlin. `seed-photos` takes UTC for this reason.
- **The import-folder list is read once at launch.** A folder seeded while the
  app is running does not appear in "Place photos already here" - `stop` and
  `start` after seeding.
- **Track checkboxes are separate nodes from their labels**, at x≈94. Tap them
  with `tap-text "<track name>" --x 94`; tapping the label's own centre hits the
  row, not the box.
- **`KEYCODE_BACK` from the Import log leaves the app entirely** and lands on
  the launcher. Navigate with `tap-text "Done"` / `menu` instead.
- **Dummy `.RAF` files always fall back to XMP sidecars** - "not a RAF file
  (magic mismatch)" in the log is expected, not a regression. Only a real card
  dump exercises the embedded-EXIF writer.
- **Tracks are only found in `/sdcard/Documents/Geoimport`**, and the app needs
  `MANAGE_EXTERNAL_STORAGE`, which `adb install -g` does *not* grant - it takes
  an `appops` call, which `install` and `grant` do.
- **A `shot` straight after a tap catches the animation** - the menu fading
  out, the sheet still sliding. `menu` settles by itself; after your own taps,
  `settle` first.
- **`gradlew -q` output mangles non-ASCII** in this console ("Grütschalp" prints
  as "Gr?tschalp"). Cosmetic; the files are fine.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `timed out waiting for text "X"` | `ui` to see what is actually on screen - usually a dialog you didn't expect, or a list that needs `sheet-up` first |
| `ERROR: null root node returned by UiTestAutomationBridge` | The app is still drawing. The driver retries; if you call `adb shell uiautomator dump` by hand, check for the `dumped to` line or you will parse the *previous* screen |
| `no such file` from `push-track` | Host path, not device path, and Windows-style |
| `adb devices` shows `offline` | Still booting - `boot` waits for `sys.boot_completed` |
| The map shows the wrong track | Nothing selected means "newest recording"; choose explicitly via `sheet-up` + `tap-text ... --x 94` |
