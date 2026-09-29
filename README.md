# geoimport

Imports photos off a camera card and geotags them from a recorded GPS track.
Runs on Android, and on Windows, Linux and macOS as a CLI.

## Modules

| | |
|---|---|
| `core/` | All the logic: card scanning, resume, track parsing, time matching, GPS writing. Plain Kotlin/JVM, no Android dependencies. |
| `app/` | Android app (Compose): records tracks, imports, geotags. |
| `desktop/` | Cross-platform CLI on the same core. |

`core` is shared verbatim, so the phone and the desktop agree by construction
rather than by discipline. Only two things differ per platform, both behind
interfaces in `core/spi`:

- `JpegGpsWriter` — androidx.exifinterface on Android, Apache Commons Imaging
  on desktop.
- `ExifDateReader` — androidx.exifinterface on Android, metadata-extractor on
  desktop.

Everything else, including the Fuji RAF header surgery and the MP4 atom
rewriting, is the same code on both.

## Geotagging

Positions are linearly interpolated between the two track points bracketing a
photo's capture time. Outside a recorded stretch of track the nearest end of
one is used, but only within `--tolerance`.

A track keeps the segments it was recorded in — a GPX `<trkseg>`, a KML
`gx:Track`, and each file when several are merged. Inside a segment the device
was recording throughout, so a gap is a dropout and its two ends really do
bracket where the device went; `ImportPlan.acrossWideGap` reports how far, so
an inferred position can be told from a measured one. Between segments nothing
was recorded, and the straight line across is not a route anybody took, so
photos there need the tolerance to reach an end.

## Choosing tracks

An outing is usually several recordings, and geotagging one import should not
mean picking the right files out of a folder by hand. Both front ends take as
many tracks as you like and work out which of them apply:

- Desktop: repeat `--track`, or point `--tracks-dir` at a folder.
- Android: the recording folder is used by default, and the picker takes
  several files at once.

The photos decide. `PhotoImporter.plan` resolves capture times first, then asks
for a geotagger, and `TrackSelection` keeps the tracks whose recorded span
reaches the photo range padded by `--tolerance`. Errors fail safe in one
direction: the range is a union, so an odd capture time can only pull in more
tracks, never drop one that would have matched. When nothing overlaps, both
front ends say so and name the nearest track — that is nearly always a wrong
camera clock or `--photo-tz`, and silence there is expensive.

Writers are tried in order, and a failure falls through rather than losing the
position:

| Writer | Handles |
|---|---|
| `exiftool` (desktop, optional) | CR3, NEF, ARW, DNG, HEIC, TIFF … |
| built-in (both platforms) | RAF, JPEG, MOV/MP4 |
| XMP sidecar | anything else |

exiftool is only consulted for formats the built-in writer does *not* handle,
so RAF, JPEG and MOV always take the shared path. `--builtin` drops it
entirely and reproduces the phone's behaviour exactly.

There is no library alternative: exiv2 cannot write RAF, ARW, CR3 or HEIF;
Commons Imaging covers JPEG and TIFF; metadata-extractor is read-only. Each
vendor's container needs its own offset-correct rewrite.

## Capture time

Two corrections, both explicit because both are easy to get silently wrong:

- **`--photo-tz`** — cameras usually record `DateTimeOriginal` with no
  `OffsetTimeOriginal`, so the reading alone does not identify an instant.
  Defaults to the machine's zone, which is wrong for a trip imported after
  flying home.
- **`--clock-offset`** — camera clocks drift. At walking pace two minutes is a
  couple of streets.

## Desktop

```
geoimport import <card> --dest <dir> [--track T ...] [--tracks-dir D] [--exclude jpg,...] [--dry-run] [--resume ...]
geoimport tag <folder> (--track T ... | --tracks-dir D) [--recursive] [--dry-run]
geoimport tracks pull|list
```

`--tracks-dir` pairs with `tracks pull`: pull the phone's recordings once, then
point every import at the folder and let it pick.

`--dry-run` prints the same `ImportPlan` the import consumes, so the preview
cannot disagree with what happens. The Android preview screen renders that
same object.

Build a self-contained image for the current OS (no Java needed on the target
machine):

```
./gradlew :desktop:jpackageImage
```

jpackage only targets the OS it runs on; `.github/workflows/build.yml` runs
the three platforms as a matrix. Run `./gradlew :desktop:fetchExifTool` for
where to drop exiftool so it gets bundled.

## Tracks

Tracks live in `Documents/Geoimport` on Android and in `~/Geoimport` on the
desktop. Folders from before the rename (`GPS-Tracks`) are moved on the phone
the first time the app starts. On the desktop, the old folder is still read
until the new one exists.

### Sync (opt-in)

Recording on the phone and importing on a tablet means the tracks have to get
from one to the other. The app syncs the track folder with a `Geoimport` folder
in the user's own Google Drive. It starts after a recording stops, when the
app opens, and hourly in the background. Each Google account has its own
tracks, and nothing is synced until someone signs in (⋮ → Sync tracks).

The rules live in `core/sync` (`SyncPlanner`), which is unit-tested with no
network:

- **Tracks are only ever added.** Deleting a track on one device removes it
  from that device only. It is not downloaded there again, and it stays in
  Drive and on the other devices.
- **A track that grew is replaced everywhere.** Resuming a recording under the
  same name appends to the file, and the longer file replaces the older copy.
- **A conflict keeps both versions.** If a file changed on both sides, Drive's
  version keeps the name and the local one becomes `name (2)`.
- **A track is uploaded once it's finished.** The one still being recorded is
  left out of every sync.

Where the last import stopped is synced too, in `State/last-import.properties`
inside the tracks folder. It holds the last photo copied, its `Import NN`
number, and which device ran the import. An import on the tablet then
continues after what the PC already copied, and the next folder is numbered
above both.
- **Auto resume point:** the shared one is used only if that photo is on the
  card and came later than this device's own last import.
- **Both devices imported:** the later photo and the higher number win.
- **An explicit start filename or time** still overrides both.

The camera settings sync the same way, in `State/import-settings.properties`:
- which file types to leave on the card
- the camera clock offset
- the photo time zone
- the tolerance

Each setting keeps its own time of change, so edits on two devices merge
setting by setting. The record interval stays on each device.

File types are chosen in the import preview. The card is read for every
common photo and video format, including RAW formats beyond RAF, and each type
found is shown as a chip ("RAF 120 · JPG 120"). Unticking a type deselects its
files and is remembered for the next import. A type never seen before starts
ticked, so nothing new is skipped silently. On the desktop, `--exclude jpg,mov`
does the same, and it defaults to the synced choice.

The app uses the `drive.file` scope, so it sees only files it created. On a
PC, Google Drive for Desktop mirrors the folder, and
`--tracks-dir "G:\My Drive\Geoimport"` reads it like any other folder,
state included. Files created on the PC stay invisible to the app, though.
So a GPX dropped into that folder does not reach the phone, and the CLI only
updates the state file once the app has created it. Without Drive,
`geoimport tracks pull` still copies tracks over adb.

One-time Google Cloud setup, done by whoever builds the APK:

1. Create a Cloud project and enable the **Google Drive API**.
2. Create an OAuth consent screen: type *External*, with the scope
   `.../auth/drive.file`. Then **publish it to production**. In *Testing*
   mode, access expires after 7 days, and every user has to be added as a
   test user. `drive.file` is a non-sensitive scope, so publishing needs no
   verification. It does need the Branding page filled in:
   - home page `https://pbungert.github.io/geoimport/`
   - privacy policy `https://pbungert.github.io/geoimport/privacy.html`
   - authorized domain `pbungert.github.io`
3. Create an OAuth client of type **Android** for each key that signs an APK
   you install, with the package `com.pbungert.geoimport` and that key's
   SHA-1:
   - **Release builds** are signed with the key named in `keystore.properties`
     in the project root. That file is not in git; it holds `storeFile`,
     `storePassword`, `keyAlias` and `keyPassword`. Without it, for example on
     CI, release builds fall back to the debug key.
   - **Debug builds** use the debug key, which is different on every machine:
     `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`.

No client ID goes into the code. Play services matches the app to the client
by its package and signature. Devices need Google Play services.

## Website

`docs/` is served by GitHub Pages at <https://pbungert.github.io/geoimport/>:
- the home page;
- the privacy policy the Google sign-in links to;
- the page for the GPX extension namespace that recorded tracks declare.

Keep the privacy policy in step with the app. If the app starts sending
anything anywhere new, the policy has to say so.

## Tests

```
./gradlew :core:test :desktop:test
```

The byte-level writers (RAF, MP4) are tested against synthetic files, and the
desktop JPEG writer round-trips through metadata-extractor — an independent
parser — so agreement means the bytes are right, not merely self-consistent.

## License

MIT, see [LICENSE](LICENSE).
