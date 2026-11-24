#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Las notas de una versión para las tiendas (F-Droid y el repositorio
propio), en fastlane/metadata/android/LOCALE/changelogs/VERSIONCODE.txt,
sacadas de su entrada en CHANGELOG.md y CHANGELOG_es.md: texto llano, sin
Markdown, una línea por punto, y no más de 500 caracteres, el límite de
F-Droid.

    python3 tools/store_notes.py VERSIONCODE VERSIONNAME [--force]

escribe las de una versión ya publicada (sin --force, no toca las que ya
existen).  El script de publicación lo llama en cada versión nueva.
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FASTLANE = os.path.join('fastlane', 'metadata', 'android')
LOCALES = {'en-US': 'CHANGELOG.md', 'es-ES': 'CHANGELOG_es.md'}
LIMIT = 500                     # caracteres, el máximo de F-Droid


def entry(path: str, name: str) -> str:
    """The text of the version NAME in the changelog PATH, or ''."""
    with open(os.path.join(ROOT, path), encoding='utf-8') as f:
        text = f.read()
    m = re.search(rf'^## {re.escape(name)} — [^\n]*\n(.*?)(?=^## |\Z)', text,
                  re.MULTILINE | re.DOTALL)
    return m[1].strip() if m else ''


def plain(text: str) -> str:
    """TEXT without Markdown, one line per item (or paragraph), each item
    with a bullet, within LIMIT characters."""
    text = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', text)   # [texto](url)
    text = text.replace('**', '').replace('`', '')
    items, current = [], []
    for line in text.splitlines() + ['']:
        if line.startswith('- ') or not line.strip():
            if current:
                items.append(' '.join(current))
            current = [line[2:].strip()] if line.startswith('- ') else []
            if line.startswith('- '):
                current[0] = '• ' + current[0]
        else:
            current.append(line.strip())
    out = ''
    for item in items:
        candidate = f'{out}\n{item}' if out else item
        if len(candidate) <= LIMIT:
            out = candidate
        elif not out:
            # Un solo punto demasiado largo: se corta en una palabra.
            out = item[:LIMIT - 1].rsplit(' ', 1)[0] + '…'
            break
        else:
            break
    return out


def write(code: int, name: str, force: bool = False) -> list[str]:
    """Writes the notes of version NAME (versionCode CODE) in each locale;
    returns the files written."""
    written = []
    for locale, changelog in LOCALES.items():
        text = entry(changelog, name)
        if not text:
            sys.exit(f'store_notes: {changelog} has no entry for {name}')
        path = os.path.join(FASTLANE, locale, 'changelogs', f'{code}.txt')
        full = os.path.join(ROOT, path)
        if os.path.exists(full) and not force:
            continue
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, 'w', encoding='utf-8') as f:
            f.write(plain(text) + '\n')
        written.append(path)
    return written


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog='tools/store_notes.py',
        description='Write the store notes of a version (fastlane changelogs) '
                    'from its entries in CHANGELOG.md and CHANGELOG_es.md.')
    parser.add_argument('code', type=int, help='its versionCode')
    parser.add_argument('name', help='its versionName, as in the changelogs')
    parser.add_argument('--force', action='store_true',
                        help='replace the notes that already exist')
    args = parser.parse_args(argv)
    for path in write(args.code, args.name, args.force):
        print(path)


if __name__ == '__main__':
    main()
