# Changelog

All notable changes to Wiktionary Reader (called wk in 1.0). The source is on
[GitHub](https://github.com/nivaca/wk-android) and
[Codeberg](https://codeberg.org/nivaca/wk-android).

## 1.2 — 2026-11-21

Changes to the interface, after the window of py-wk:

- A bar at the bottom with Back, Forward, Contents and Find in page.
  Forward history, as in a browser; a long touch on Back lists the
  recent words.
- Contents: the languages of the page and their sections; touching one
  goes there, and opens it if it was hidden or closed.
- Find in page, with the count of matches and the previous and next one.
- Quotations and Tables in the menu, switched on and off at once,
  without loading the page again.
- Selecting text in an entry gives "Look up" in the menu of the
  selection.
- Recent words, in the menu and on the first page.
- Settings… in one dialog: languages, quotations, tables, whether
  Translations start closed, suggestions while typing, text size, theme
  (system, light or dark, from Android 12), and the buttons to delete
  saved pages and to forget the recent words.
- The APK in `dist/` is also signed with OpenPGP (`.apk.asc`), with the
  key of py-wk, whose public half is `GPG-KEY-wiktionary-reader.asc`;
  the releases carry both.

## 1.1 — 2026-11-21

- The app is now called Wiktionary Reader (it was wk).
- The About dialog, the first page of the app, the READMEs and the
  description for F-Droid say that the author has no association with
  Wiktionary, Wikipedia or the Wikimedia Foundation.
- The signed APK in `dist/` is now `wiktionary-reader-VERSION.apk`.

## 1.0 — 2026-11-21

- First version: a search box with suggestions from Wiktionary, and the
  whole entry in a WebView, with a stylesheet and a script of its own.
- A bar with the languages of the page; preferred languages first, and
  the others hidden if wanted (Languages…).
- Section titles that open and close (Translations start closed), and
  wide tables that scroll sideways.
- Links to entries open in the app, at their language; history with Back.
- Light and dark mode, with Wiktionary's palette for the tables.
- Lookup from other apps: selected text, shared text and Wiktionary links.
- Pages kept for a week; text size; English and Spanish interface.
