# Wiktionary Reader

*[Leer en español](README.es.md)*

Wiktionary Reader is a small Android app for reading the
[English Wiktionary](https://en.wiktionary.org). You type a word, in any language, and it
shows the whole entry: etymology, pronunciation, definitions with their quotations,
inflection tables, derived terms, translations and the rest. It is the Android sibling of
[py-wk](https://github.com/nivaca/py-wk), which does the same in the terminal and on the
desktop, and it gets the pages from Wiktionary in the same way. Up to version 1.0 it was
called *wk*.

**Not an official app.** I have no association whatsoever with Wiktionary, Wikipedia or
the Wikimedia Foundation, which runs them. Wiktionary Reader is not made, approved or
endorsed by any of them; it only shows the content that Wiktionary publishes. Wiktionary
and Wikipedia are trademarks of the Wikimedia Foundation.

I am Nicolás Vaughan (<nicolasvaughan@proton.me>), and I wrote it in 2026.

## What it does

- A search box with suggestions from Wiktionary as you type. The first page lists your
  recent words.
- A bar at the bottom, after the window of py-wk: **Back** and **Forward**, as in a browser
  (a long touch on Back lists the recent words), **Contents**, with the languages of the
  page and their sections, and **Find in page**, with the count of matches.
- **Quotations** and **Tables** in the menu, switched on and off at once.
- Select any text in an entry and choose **Look up** to open it.
- The entry, with the styles of the site adapted to a phone: wide tables scroll
  sideways in their own box, and the titles of the sections (Etymology, Noun,
  Translations …) open and close when you touch them. Translations start closed.
- A bar at the top with the languages of the page. In **Settings…** you can give the
  ones you want first (codes or English names: `la, es, Ancient Greek`), and hide the
  others; they stay in the bar, a touch away.
- Links to other entries open in the app, at the right language (`rosa#Latin`); links
  to anything else (appendices, Wikipedia, files) open in the browser.
- Back returns to the previous word, where you left it.
- Light and dark mode, following the system or, from Android 12, chosen in Settings. The inflection tables use Wiktionary's own
  palette, which has colours for both.
- From any app: select a word and choose **Look up in Wiktionary** in the menu of the
  selection, or share a text with the app. Links to `en.wiktionary.org` can open in it too.
- Pages are kept for a week, so a word seen again opens at once.
- Settings… gathers the rest: Translations open or closed, suggestions, text size, theme,
  deleting the saved pages and forgetting the recent words. The menu also has Open in the
  browser, Share the link and Download again.
- English and Spanish interface.

## Requirements

Android 10 (API 29) or newer, and an Internet connection for words not seen in the last
week.

## Building

You need a JDK (17 or newer) and the Android SDK with platform 36. Write the path of the SDK
in `local.properties` (`sdk.dir=/home/me/Android/Sdk`), or set `ANDROID_HOME`, and then:

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper downloads the right Gradle by itself. The project uses only the
Android framework (no AndroidX, no other libraries), so the build needs few downloads.

## Releases and signing

`./gradlew assembleRelease` makes the release APK, shrunk with R8 (about 110 KB), and copies
it to `dist/wiktionary-reader-VERSION.apk`, replacing the older ones there. It is signed only if the file
`keystore.properties` exists in the root of the project, with the keys `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`; the key and that file are not in git. Without
them, the release APK comes out unsigned, which is what F-Droid needs, since it signs the
apps with its own key.

The copy in `dist/` is also signed with OpenPGP, as `dist/wiktionary-reader-VERSION.apk.asc`,
when gpg has the secret key of the project (the same as py-wk's; its public half is
[GPG-KEY-wiktionary-reader.asc](GPG-KEY-wiktionary-reader.asc)). The variable `WK_GPG_KEY`
names another key; without the key, the build says so and leaves only the Android signature.
To check a release:

```sh
gpg --import GPG-KEY-wiktionary-reader.asc
gpg --verify wiktionary-reader-VERSION.apk.asc wiktionary-reader-VERSION.apk
apksigner verify --print-certs wiktionary-reader-VERSION.apk
```

The Android certificate's SHA-256 is
`4f4bd72456f162f72aeec33a29d7a8487b8622eb81490dc54629ac3588661c51`.

## F-Droid

The description, the icon and the notes of each version for F-Droid are in
`fastlane/metadata/android/` (in English and Spanish), where F-Droid looks for them. The
build recipe for the F-Droid repository is `fdroid/io.github.nivaca.wk.yml`: it builds the
tag `vVERSION` from the folder `app`, and finds new versions by their tags.

## How it works

Like py-wk, the app asks Wiktionary's REST API for the HTML of a page
(`/api/rest_v1/page/html/WORD`), and the opensearch API for the suggestions. It does not
turn the HTML into text: it shows it in a WebView, with a stylesheet (`assets/wk.css`) and a
script (`assets/wk.js`) of its own, which make the language bar, order and hide the
languages, and fold the sections. Before showing a page it drops the attributes that
Parsoid leaves for editors, about a third of its size, and keeps it gzipped in the cache
folder of the app.

The code is in `app/src/main/java/io/github/nivaca/wk/`: `Wiktionary.kt` talks to the site,
`PageCache.kt` keeps the pages, `Pages.kt` builds the documents for the WebView, `Prefs.kt`
keeps the settings and the recent words, and `MainActivity.kt` is the only screen. `docs/DESCRIPTION.md` tells how it was made.

## License

Wiktionary Reader is free software, under the GNU General Public License, version 3 or later
(`GPL-3.0-or-later`); see [LICENSE](LICENSE). The content of the entries comes from
Wiktionary and its contributors, under the CC BY-SA 4.0 licence. The app only shows it,
and, as said above, it has no association with Wiktionary, Wikipedia or the Wikimedia
Foundation.
