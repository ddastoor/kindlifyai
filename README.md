# Kindlify

A small, private Android app: collect text and images on a **canvas**, turn it into an
**EPUB**, and send it to the **Kindle app** on the same phone.

- **New canvas**, then **Paste** whatever is on the clipboard (text or images; repeat as often as you like).
  The gallery icon adds a photo instead.
- **Share to Kindlify** from any app (selected text, a web page, one or more images):
  - no canvas open: starts a new canvas with it, titled from the shared subject when there is one;
  - a canvas open: appends it to that canvas, after a separator line (which becomes a horizontal rule in the book).
- **Fetch contents**: when a canvas holds nothing but a link, this button pulls in the page in
  "reader mode": the article's text and pictures, without ads, menus, side panels, comments or footers.
  It's added below the link (which is kept) after a separator, and a canvas still called "Untitled …"
  takes the article's title. The button is greyed out whenever there's anything besides the link.
  Pages that build their text with JavaScript, or sit behind a paywall or login, can't be fetched.
- Edit, reorder (⋮ menu) or remove items, separators included.
- **Create EPUB** asks for the book title (pre-filled with the canvas title, "Untitled <date>" by default),
  then builds the book (no cover). Any later edit makes it stale until you create it again.
- **Export** hands the EPUB straight to the Kindle app, which uploads it with Send to Kindle so it shows up
  in your Kindle library. If the Kindle app isn't installed, the normal share sheet opens instead.
- **Cancel** abandons the canvas and deletes everything on it (after confirming).
  The back arrow keeps it on the home screen, where you can reopen it to keep working.
- **History**: the 20 most recently edited canvases are kept on the home screen. Starting a 21st deletes
  the one at the bottom of the list (the least recently edited), along with its pictures and EPUB.

Everything is saved as you go, in the app's private storage. The app only goes online for Fetch contents
(to download that page and its pictures); nothing leaves the phone until you tap Export.

## Install

The latest build is in the repo: [`apk/Kindlify.apk`](apk/Kindlify.apk) (version 1.3).
On the phone, download it from
<https://github.com/ddastoor/kindlifyai/raw/main/apk/Kindlify.apk>, open it, and allow installing apps
from that source (your browser or Files app) when Android asks. Requires Android 10 or newer.

Over USB, with USB debugging on:

```bash
adb install -r apk/Kindlify.apk
```

Updates install over the existing app and keep your canvases, because every build is signed with the same key.

## How it's built

| Part | What |
| --- | --- |
| `app/` | The Android app: Kotlin and Jetpack Compose (Material 3), a single activity. |
| `app/…/reader/` | Reader mode for Fetch contents: [Readability4J](https://github.com/dankito/Readability4J) (a port of Mozilla's Readability, the engine behind Firefox's Reader View) on [jsoup](https://jsoup.org), which also downloads the page. |
| `epub/` | Plain JVM library that builds the EPUB with [epub4j](https://github.com/documentnode/epub4j), the maintained fork of epublib. epub4j does the EPUB itself (zip layout, OPF, NCX, container); this module only produces one XHTML page plus the images. |

Notes:

- **Images** are re-encoded to JPEG (or PNG when transparent), at most 2000 px on the long side.
  This keeps books small and in formats every Kindle reads, whatever was copied (WebP, HEIC, huge photos).
- **Text**: blank lines start a new paragraph and single line breaks are kept. Characters that are invalid in XML
  are dropped so the book always validates.
- **kxml2 relocation**: epub4j builds kxml2's `KXmlSerializer` directly. Android has a hidden copy of that
  class that apps aren't allowed to use, so `epub/` bundles kxml2 under its own package name
  (the Shadow plugin's `shadowJar`). The app uses that jar.
- **Fetched pictures** smaller than 100 px (icons, trackers) are skipped, and at most 40 are kept per page.
  Lazy-loaded pictures (`data-src`, `srcset`) are found too.
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
  - sharing into the app: new canvas when none is open, appended after a separator when one is, and from a cold start;
  - Fetch contents against a local test web server (button only enabled for a lone link, ads and menus
    dropped, pictures kept in order, EPUB created from it), and a page that can't be fetched;
  - keeping 20 canvases: starting the 21st removes the least recently edited.
- Reader mode on a sample news page, and link detection, as plain unit tests.

  Screenshots are saved to `app/build/screenshots/`.

To check any EPUB:

```bash
./gradlew :epub:epubcheck -Pfile=/path/to/book.epub
```
