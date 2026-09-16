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
geoimport import <card> --dest <dir> [--track T ...] [--tracks-dir D] [--dry-run] [--resume ...]
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

The phone records; the desktop consumes. `geoimport tracks pull` uses adb over
USB or `adb tcpip`. There is deliberately no cloud sync — a day of one-minute
fixes is under 100 KB, and you are at the machine with the card in hand
anyway.

## Tests

```
./gradlew :core:test :desktop:test
```

The byte-level writers (RAF, MP4) are tested against synthetic files, and the
desktop JPEG writer round-trips through metadata-extractor — an independent
parser — so agreement means the bytes are right, not merely self-consistent.
