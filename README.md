# Kindlify

A small, private Android app: collect text and images on a **canvas**, turn it into an
**EPUB**, and send it to the **Kindle app** on the same phone.

- **New canvas**, then **Paste** whatever is on the clipboard (text or images; repeat as often as you like).
  The gallery icon adds a photo instead.
- **Share to Kindlify** from any app (selected text, a web page, one or more images): it opens a new canvas
  with that content, titled from the shared subject when there is one.
- Edit, reorder (⋮ menu) or remove pasted items, and set the book title.
- **Create EPUB** builds the book (no cover). Any later edit makes it stale until you create it again.
- **Export** hands the EPUB straight to the Kindle app, which uploads it with Send to Kindle so it shows up
  in your Kindle library. If the Kindle app isn't installed, the normal share sheet opens instead.
- **Cancel** abandons the canvas and deletes everything on it (after confirming).
  The back arrow keeps it as a draft on the home screen.

Everything is saved as you go, in the app's private storage. Nothing is sent anywhere until you tap Export.

## Install

Copy `Kindlify-1.0.apk` to the phone and open it. Android will ask you to allow installing apps from
that source (your browser or Files app) once. Requires Android 10 or newer.

## How it's built

| Part | What |
| --- | --- |
| `app/` | The Android app: Kotlin and Jetpack Compose (Material 3), a single activity. |
| `epub/` | Plain JVM library that builds the EPUB with [epub4j](https://github.com/documentnode/epub4j), the maintained fork of epublib. epub4j does the EPUB itself (zip layout, OPF, NCX, container); this module only produces one XHTML page plus the images. |

Notes:

- **Images** are re-encoded to JPEG (or PNG when transparent), at most 2000 px on the long side.
  This keeps books small and in formats every Kindle reads, whatever was copied (WebP, HEIC, huge photos).
- **Text**: blank lines start a new paragraph and single line breaks are kept. Characters that are invalid in XML
  are dropped so the book always validates.
- **kxml2 relocation**: epub4j builds kxml2's `KXmlSerializer` directly. Android has a hidden copy of that
  class that apps aren't allowed to use, so `epub/` bundles kxml2 under its own package name
  (the Shadow plugin's `shadowJar`). The app uses that jar.
- The output is checked with the W3C **epubcheck** validator in the tests: 0 errors, 0 warnings.

## Build it yourself

You need JDK 17+ and the Android SDK (Android Studio installs both).

```bash
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

Release builds are signed with the key named in `keystore.properties` (git-ignored; never commit it
or the `.jks`, as this repo is public):

```properties
storeFile=/absolute/path/to/kindlify-release.jks
storePassword=...
keyAlias=kindlify
keyPassword=...
```

Always sign with the **same key**. Android only installs an update over the existing app (keeping your
canvases) if the signature matches. Without `keystore.properties` the build falls back to the debug key.

## Tests

```bash
./gradlew :epub:test :app:testDebugUnitTest
```

- `epub/`: builds a book with text, PNG and JPEG and runs epubcheck on it.
- `app/`: end-to-end UI tests on the JVM with Robolectric (no emulator):
  - paste text and images, create the EPUB, export to the Kindle app, then Cancel;
  - export when no Kindle app is installed;
  - sharing text and images into the app, from cold start and while running.

  Screenshots are saved to `app/build/screenshots/`.

To check any EPUB:

```bash
./gradlew :epub:epubcheck -Pfile=/path/to/book.epub
```
