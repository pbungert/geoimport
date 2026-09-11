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
photo's capture time. Outside the track's range the nearest endpoint is used,
but only within `--tolerance`.

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
geoimport import <card> --dest <dir> [--track T] [--dry-run] [--resume ...]
geoimport tag <folder> --track T [--recursive] [--dry-run]
geoimport tracks pull|list
```

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
