How Wiktionary Reader was made
==============================

*[Leer en español](DESCRIPCIÓN_es.md)*

Wiktionary Reader is a small Android app for reading the English Wiktionary
(<https://en.wiktionary.org>).  You type a word or an expression in any
language, and it shows the whole entry, adapted to the screen of a phone.
Nicolas Vaughan made it in November 2026, as the Android companion of
py-wk, the program that does the same in the terminal and on the desktop;
it was called *wk* in its first version, and the current one is 1.2.
Here I explain how it was made, in eight stages.

Wiktionary Reader is free software, under the GNU General Public License,
version 3 or later.  It uses only the Android framework and the Kotlin standard library
(under the Apache License 2.0), with no other libraries.  The content of
the entries comes from Wiktionary and its contributors, under the CC BY-SA
4.0 licence; the app only displays it.  I have no association whatsoever
with Wiktionary, Wikipedia or the Wikimedia Foundation, which runs them,
and the app is not made, approved or endorsed by any of them; Wiktionary
and Wikipedia are trademarks of the Wikimedia Foundation.

Nicolas Vaughan is Associate Professor of Literature at the School of Arts
and Humanities of the Universidad de los Andes, Bogotá, Colombia.

1. A first version
------------------

The starting point was py-wk.  py-wk asks the REST API of Wiktionary for
the HTML of a page (`/api/rest_v1/page/html/WORD`, the HTML made by
Parsoid), reads it into a small tree and writes it again as text, with its
own code for lists, quotations and inflection tables.  The way of talking
to Wiktionary I kept almost as it was: the same API, the same prefix search
(opensearch) for the suggestions, the same retries when the site answers
429 or 503, waiting what `Retry-After` says.  `Wiktionary.kt` is a short
translation of `api.py` to Kotlin.

The rendering I did not translate.  Android has a complete browser engine
in the WebView, and the HTML of Parsoid is already a good document, with
its tables, its links and the stylesheets of the site.  To turn it into
text would mean to write again, for a phone, the thousand lines of
`render.py`, and the tables would come out worse than in the browser.  So
the app shows the page as it is, and adds two things of its own: a
stylesheet (`wk.css`) for the narrow screen and a script (`wk.js`) that
changes the page after it loads.  The rest is one screen
(`MainActivity.kt`) with a search box and the WebView.

I chose to use only the Android framework, without AndroidX or Jetpack
Compose.  The app needs one screen, one text field, one menu and some
dialogs; the framework has all of them, and the project stays small and
quick to build, with no dependencies to update.

2. Smaller pages and a cache
----------------------------

The first page I tried, *rosa*, weighs one megabyte of HTML: it has
entries in 39 languages.  Most of that weight is not content.  Parsoid
writes for the editors, in each element, attributes such as `data-mw`
(the template that produced it, as JSON), `about` and an `id` of its
own.  `PageCache.kt` removes them before keeping the page, and the page
of *rosa* goes down to about 720 kilobytes.  The attribute `typeof` must
stay, because the stylesheets of the site use it to place the images.

The pages are kept in memory (the last sixteen) and, compressed with
gzip, in the cache folder of the app for a week, so that a word seen
again opens at once and Wiktionary is not asked twice for it.  The menu
has "Download again", for a page that changed, and "Delete saved pages".

3. The languages
----------------

A page of Wiktionary has one section for each language, in alphabetical
order after English.  Somebody who reads Latin does not want to pass over
English, Albanian, Aragonese and twenty more before arriving at it.  As in
py-wk, the settings take a list of languages, as codes or names (`la, es,
Ancient Greek`); the script puts those first, in that order, and can hide
the others.  It finds the code of each language in the `lang` attribute of
the headword, since the title of the section has only the name.

Hidden languages should not be lost, so the script adds a bar of buttons
at the top, one for each language of the page.  The first bar wrapped in
many lines, and on *rosa* it took half the screen; now it is one row that
scrolls sideways.  The pages with many languages also bring their own
list of them (the "minitoc" of the site), which was now repeated, and the
stylesheet hides it.

The titles inside a language (Etymology, Noun, Translations …) open and
close their section when touched.  Translations start closed: they are
the longest part of many entries and the least read on a phone.  Wide
tables, such as the Latin conjugations, go inside a box that scrolls
sideways, and the page keeps its width.

Links to other entries open in the app, and at the right language: a link
to `rosa#Latin` from a Latin entry opens *rosa* and goes to its Latin
section.  Links to anything else, such as appendices or Wikipedia, open
in the browser.  This needed some care, because the app also declares
that it opens links to `en.wiktionary.org`, and a link sent to "the
browser" could come back to it; the intent now asks for a browser
expressly.

4. Dark mode
------------

My first idea for dark mode was the simplest: to let the WebView darken
the page by itself (the "algorithmic darkening" of Android 13).  On the
phone the text was right, but the inflection tables kept their light
blues and greens, and they shone in the dark page.

The tables take their colours from CSS variables, `--wikt-palette-…`,
which the page uses but does not define.  On the site they are defined by
a gadget, `MediaWiki:Gadget-Palette.css`, which has a light and a dark
value for each colour, the dark ones for an `<html>` with the class
`skin-theme-clientpref-os` when the system prefers dark.  So the app now
loads that gadget with the page, puts the class on `<html>`, declares that
the page supports both schemes, and gives its own stylesheet dark colours.
The WebView does not darken anything, and the tables look as on the site
in its automatic theme.  The stylesheet of the site gave the titles a
fixed dark colour, which was invisible on the dark page; the app's
stylesheet sets it again.

5. On the phone
---------------

The emulator of the SDK did not start on my computer at first (it crashed at once),
so I tried the app on a phone, a moto g55 with Android 16.  Three small
things appeared there.  In light mode the icons of the status bar were
white on a light background; the theme now asks for dark icons in light
mode.  After the theme of the system changes, Android creates the screen
again, and the app came back lower in the page than before, because the
images above had not loaded yet; the script now goes back to the place
again when they have.  And the cursor stayed in the search box after a
search, because the box was the only element that could take the focus.

6. Release and F-Droid
----------------------

I wanted the app in F-Droid, which builds each app from its source on its
own servers and signs it with its own key.  A few things follow from
that.  The release build signs the APK only when it finds the key and its
passwords in a file outside git, `keystore.properties`; without it, as
on F-Droid's server, the APK comes out unsigned.  When it is signed, the
build copies it to `dist/`, as with the packages of my other programs.
The release APK is shrunk with R8 and weighs 83 kilobytes, against 2.7
megabytes of the debug one, which carries the whole Kotlin library.

The build no longer adds the block of dependency information that the
Android plugin puts in APKs by default, because it is encrypted with a
key of Google and F-Droid asks to leave it out.  The Gradle of the
wrapper went back from 9.9.2 to 9.8.2: the first had come out two days
before, and the build servers of F-Droid only accept versions they
already know.  The text, the icon and the notes of the version for the
store are in `fastlane/metadata/android/`, in English and Spanish, and
the recipe for F-Droid's repository, in `fdroid/`.  The code went to
GitHub and Codeberg, like py-wk, as `wk-android`.

7. A new name
-------------

The first version was called *wk*, like py-wk without its prefix.  The
name is short, but in a list of apps it says nothing, and nobody looking
for a Wiktionary app would find it.  I wanted to call it simply
*Wiktionary*.  That name, however, is a trademark of the Wikimedia
Foundation, and an unofficial app with exactly that name could be taken
for the official one; F-Droid has asked other apps to change names for
this reason.  So the app became *Wiktionary Reader*, which says what it
does.  Version 1.1 makes the change, and states clearly, in the About
dialog, on the first page of the app, in the READMEs and in the
description for F-Droid, that I have no association with Wiktionary,
Wikipedia or the Wikimedia Foundation.  The internal names (the package
`io.github.nivaca.wk`, the files `wk.css` and `wk.js`, the repository
`wk-android`) did not change: users do not see them, and a new package
would be, for Android and for F-Droid, a different app.

8. The interface of py-wk, on a phone
--------------------------------------

The first versions had only the search box, the page and a menu.  For
1.2 I took the window of py-wk as a model and asked which of its parts
make sense on a phone.  Most of them do, in another form.

The toolbar of py-wk has Back and Forward; the app had only Back, the
button of the system.  Now a bar at the bottom, where the thumb reaches,
has Back, Forward, Contents and Find in page.  The history works as in a
browser: opening a new word empties what was ahead.  A long touch on
Back lists the recent words, which the app now keeps (the last thirty)
and also shows on its first page, where before there was only a
greeting.

The contents pane of py-wk became a list that opens from the bar.  The
page script gives each heading a number and returns the titles, in the
order in which the page shows them, after the languages are reordered;
the app shows the languages in bold and their sections under them.
Touching a title opens what hides it, the language if it was hidden and
the sections if they were closed, and goes there.  Find in page uses
the search of the WebView itself, with the count of matches and the
buttons for the previous and next one, in a bar that takes the place of
the other while it is open.

py-wk can hide quotations and tables.  In the app they are two marks in
the menu, and the change is immediate: the script only adds a class to
`<body>`, and the stylesheet hides what that class says.  Finding what
to hide took some care.  The quotations under a definition are list
items, but not always with the same markup (`citation-whole` in some,
`wiktQuote` in others), and the References use `citation-whole` too, so
the rule hides only list items under a definition that contain a quoted
passage.  For the tables, Wiktionary marks every inflection table with
`data-toggle-category="inflection"`; the boxes of Translations look the
same but are not marked, and they stay.  The sections that only hold a
table (Declension, Conjugation) are hidden with it, so that no empty
title remains.

Double-clicking a word in py-wk looks it up.  On a phone a double touch
is for zooming, so the app adds "Look up" at the top of the menu that
Android shows for selected text.  The app's own item for other apps,
"Look up in Wiktionary", appeared in that menu too, repeated; the app
removes it there.  Last, the scattered dialogs (Languages, Text size)
became one Settings dialog, which also has the theme: system, light or
dark, as the moon and sun button of py-wk, but only from Android 12,
the first version that lets an app choose its own.

The releases got a second signature too.  Android checks the signature
of an APK when it installs it, but someone who downloads the file from
GitHub or Codeberg cannot check it before that without the tools of the
SDK.  py-wk signs its packages with OpenPGP, and the app now does the
same: at the end of the release build, the copy in `dist/` gets a
detached signature (`.apk.asc`) with the same key as py-wk, if gpg has
it, and the build checks the signature it has just made.  Without the
key, as on F-Droid's server, it only says so.  The public key is in the
repository, as `GPG-KEY-wiktionary-reader.asc`, and the releases carry
it with the signature.

Testing
-------

There are no automated tests yet.  The app was checked by hand.  The
stylesheet and the script were first tried on the page of *rosa* in a
browser at the width of a phone (Chrome without a window), with Latin and
Spanish as preferred languages, to see that they reorder and hide the
sections without errors.  Then the app was built and installed on a moto
g55 5G with Android 16, and checked with screenshots taken through adb:
an entry opened from the menu of selected text (*rosa*), links with a
language or a section (`amo#Latin`, `amo#Conjugation`), the suggestions
while typing, a search, a link and the way back (*lupu*, *wolf*), a word
without entry (*lupux*, with its similar titles), and the light and dark
modes.  The release APK of version 1.1, shrunk with R8, was tried on the
same phone too: an entry with its tables, the menu and the About dialog.
Version 1.2 was checked the same way.  On the phone: the bottom bar,
the contents list (with a jump into a hidden language), find in page,
the Quotations switch, Look up on selected text, the long touch on Back,
and the way back to the reading point after following a link.  The
Settings dialog was checked in the emulator, which starts with the
"swangle" graphics of the SDK instead of SwiftShader: languages, hiding
the others, text size and theme, and the reading point kept when the
theme changes.  These tests found four faults, all fixed before the
release: the Look up item did not appear, a long touch on a disabled
Back did nothing, Back returned to the wrong place (the position was
kept in pixels), and after a change of settings the reading point
pointed to another heading (the headings were numbered after the
languages were reordered).  The page script was also tried in Chrome
without a window on three entries, *amo*, *rosa* and *wolf*, which
showed that the first rule for hiding quotations missed most of them.
Before submitting the app to F-Droid, I ran F-Droid's own build of
version 1.2 on my computer, in its build container
(`fdroidserver:buildserver-trixie`), the same steps as the checks of
F-Droid's repository: its scanner found nothing, and the app was built
from the tag `v1.2`.  Apart from the signature, its APK was the same as
the one published on GitHub, so the recipe asks for a reproducible
build: F-Droid publishes my APK, with my signature, after checking that
its own build matches it.  The screenshots of the store were taken in the
emulator.  The build has no warnings from the Kotlin compiler.

The project has 36 files of its own (code, resources and build scripts,
without the Gradle wrapper and the documentation), with about 1900
lines: 1100 of Kotlin in five files, 190 of JavaScript and 140 of CSS;
the rest is XML and Gradle.  The release APK weighs 107 KB, the debug
one 2.7 MB.
