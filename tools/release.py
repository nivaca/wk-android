#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Publica una versión nueva de Wiktionary Reader en GitHub (con gh) y en
Codeberg (con tea), con lo que se haya escrito en los registros de cambios
bajo «Unreleased» y «Sin publicar».

    python3 tools/release.py [--version X.Y] [--zenodo-draft | --no-zenodo] [--dry-run] [--yes]

En orden:

1. comprueba que el repositorio esté en main, sin nada por confirmar; que
   gh, tea y gpg estén instalados, con la clave de OpenPGP del proyecto en
   el llavero; que exista keystore.properties, y que CHANGELOG.md tenga una
   sección «## Unreleased» y CHANGELOG_es.md una «## Sin publicar», no
   vacías;
2. elige la versión: la de --version, o la siguiente (1.2 → 1.3), con
   versionCode uno más;
3. pone la versión en app/build.gradle.kts, en los títulos de esas dos
   secciones (con la fecha de hoy) y en los documentos de docs/, y
   escribe con tools/store_notes.py las notas de la versión para las
   tiendas (fastlane/metadata/android/*/changelogs/VERSIONCODE.txt);
4. confirma los cambios («vX.Y») y pone la etiqueta firmada vX.Y;
5. compila el APK firmado desde esa etiqueta (./gradlew clean
   assembleRelease, que lo copia a dist/ con su firma de OpenPGP; gpg
   pide la frase de paso).  F-Droid compara su compilación con este APK,
   que guarda el commit del que sale, así que debe salir de la etiqueta.
   Si la compilación falla, quita la etiqueta y la confirmación;
6. escribe dist/SHA256SUMS (el SHA-256 del APK, como lo lee
   `sha256sum -c`) y su firma SHA256SUMS.asc, y comprueba las firmas como
   lo haría quien baja la versión: en un GNUPGHOME temporal que solo tiene
   la clave pública del proyecto; comprueba también la etiqueta (git tag
   --verify).  Si algo no se verifica, se detiene sin publicar nada;
7. sube main y la etiqueta a origin (que sube a GitHub y a Codeberg) y
   crea la versión en cada forja, con el APK, SHA256SUMS, sus firmas y la
   clave pública; las notas salen del registro de cambios.  Al final pone
   el APK nuevo en el repositorio de F-Droid propio y lo publica
   (../fdroid-repo/tools/update.py --publish), si ese proyecto está junto
   a este; si falla, solo avisa, porque la versión ya está publicada;
8. reúne el depósito para Zenodo en dist/zenodo/ (tools/zenodo.py
   collect: el árbol de la etiqueta, firmado, con SHA256SUMS y la clave
   pública, cada firma comprobada con solo esa clave), antes de subir
   nada a las forjas, y al final, salvo con --no-zenodo, lo sube a Zenodo
   y lo publica (tools/zenodo.py upload --publish, con el token de
   $ZENODO_TOKEN o del llavero), con un DOI nuevo bajo el mismo DOI de
   concepto.  Con --zenodo-draft lo deja como borrador, para publicarlo a
   mano (o con tools/zenodo.py publish).

Antes del paso 3 muestra lo que va a hacer y pregunta; con --yes no
pregunta, y con --dry-run solo lo muestra.  Si algo falla en el paso 7,
correr el script de nuevo publica lo que falte: la versión de
app/build.gradle.kts ya tiene su etiqueta.  Lo mismo vale para una versión
publicada sin alguno de sus archivos en una de las forjas: se le añaden,
si los que ya tiene en GitHub son los de dist/.
"""

import argparse
import datetime
import hashlib
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile

import store_notes              # (tools/, junto a este script)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE = os.path.join('app', 'build.gradle.kts')
PUBLIC_KEY = 'GPG-KEY-wiktionary-reader.asc'
BRANCH = 'main'
REPO = 'nivaca/wk-android'
TEA_LOGIN = 'codeberg'
SUMS = os.path.join('dist', 'SHA256SUMS')
# The own F-Droid repository, next to this project: after each release it
# gets the new APK (its tools/update.py checks it and publishes the site).
FDROID_REPO = os.path.join(os.path.dirname(ROOT), 'fdroid-repo')
PENDING = {'CHANGELOG.md': 'Unreleased', 'CHANGELOG_es.md': 'Sin publicar'}

DESCRIPTION = ('Release a new version of Wiktionary Reader on GitHub and '
               'Codeberg, from the "Unreleased" sections of the changelogs.')


def zenodo_token() -> bool:
    """Whether tools/zenodo.py finds a Zenodo token, in $ZENODO_TOKEN or
    in the desktop keyring (secret-tool)."""
    tool = os.path.join(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))), 'tools', 'zenodo.py')
    done = subprocess.run([sys.executable, tool, 'check-token'],
                          capture_output=True, check=False)
    return done.returncode == 0


def run(*command, check=True, capture=True, cwd=ROOT) -> str:
    out = subprocess.run(command, cwd=cwd, text=True,
                         capture_output=capture)
    if check and out.returncode != 0:
        detail = (out.stderr or out.stdout or '').strip() if capture else ''
        sys.exit(f'release: {shlex.join(command)} failed'
                 + (f': {detail}' if detail else ''))
    return (out.stdout or '').strip() if capture else ''


def ok(*command, cwd=ROOT) -> bool:
    return subprocess.run(command, cwd=cwd, capture_output=True
                          ).returncode == 0


def read(path: str) -> str:
    with open(os.path.join(ROOT, path), encoding='utf-8') as f:
        return f.read()


def write(path: str, text: str):
    with open(os.path.join(ROOT, path), 'w', encoding='utf-8') as f:
        f.write(text)


def substitute(path: str, pattern: str, replacement: str):
    """Replace PATTERN in PATH once; stop if it is not there, since then
    the text changed and this script must change with it."""
    text, n = re.subn(pattern, replacement, read(path), count=1,
                      flags=re.MULTILINE)
    if n == 0:
        sys.exit(f'release: {path}: did not find /{pattern}/')
    write(path, text)


def section(path: str, heading: str) -> str:
    """The text under '## HEADING' up to the next '## ', or ''."""
    m = re.search(rf'^## {re.escape(heading)}\n(.*?)(?=^## |\Z)', read(path),
                  re.MULTILINE | re.DOTALL)
    return m[1].strip() if m else ''


def heading(name: str) -> str:
    # El título de la versión en CHANGELOG.md: «1.3 — FECHA»
    m = re.search(rf'^## ({re.escape(name)} — .*)$', read('CHANGELOG.md'),
                  re.MULTILINE)
    if not m:
        sys.exit(f'release: CHANGELOG.md has no section for {name}')
    return m[1]


def signing_key() -> str:
    # La misma clave que usa distApk: WK_GPG_KEY, o la fijada en Gradle.
    key = os.environ.get('WK_GPG_KEY', '').strip()
    if key:
        return key
    m = re.search(r'val SIGNING_KEY = "([0-9A-F]{40})"', read(GRADLE))
    if not m:
        sys.exit(f'release: no SIGNING_KEY in {GRADLE}')
    return m[1]


def update_fdroid_repo():
    """Puts the new APK in the own F-Droid repository and publishes it, if
    that project is next to this one.  The release is already made, so a
    failure here only warns."""
    script = os.path.join(FDROID_REPO, 'tools', 'update.py')
    if not os.path.isfile(script):
        print(f'(There is no {FDROID_REPO}: the F-Droid repository is not '
              'updated.)')
        return
    print('\nUpdating the F-Droid repository…', flush=True)
    if subprocess.run([sys.executable, script, '--publish', '--yes'],
                      cwd=FDROID_REPO).returncode != 0:
        print('release: the release is made, but the F-Droid repository is '
              f'not updated; run python3 {script} --publish', file=sys.stderr)


def sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(os.path.join(ROOT, path), 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def app_version() -> tuple[int, str]:
    text = read(GRADLE)
    code = re.search(r'versionCode = (\d+)', text)
    name = re.search(r'versionName = "([\d.]+)"', text)
    if not code or not name:
        sys.exit(f'release: no versionCode or versionName in {GRADLE}')
    return int(code[1]), name[1]


def apksigner() -> str:
    """apksigner of the newest build-tools of the SDK."""
    sdk = os.environ.get('ANDROID_HOME', '')
    if os.path.isfile(os.path.join(ROOT, 'local.properties')):
        m = re.search(r'^sdk\.dir=(.+)$', read('local.properties'),
                      re.MULTILINE)
        sdk = m[1].strip() if m else sdk
    tools = os.path.join(sdk, 'build-tools')
    versions = sorted((v for v in os.listdir(tools) if re.fullmatch(
        r'[\d.]+', v)), key=lambda v: tuple(map(int, v.split('.')))
    ) if sdk and os.path.isdir(tools) else []
    for v in reversed(versions):
        found = os.path.join(tools, v, 'apksigner')
        if os.path.isfile(found):
            return found
    sys.exit('release: no apksigner in the build-tools of the SDK')


def spaced(fingerprint: str) -> str:
    # 8187 0643 … 3599 4948, con dos espacios en medio, como en gpg
    groups = [fingerprint[i:i + 4] for i in range(0, 40, 4)]
    return ' '.join(groups[:5]) + '  ' + ' '.join(groups[5:])


class Release:

    def __init__(self, args):
        self.args = args
        self.key = ''

    def check(self, resuming: bool):
        for tool in ('git', 'gh', 'tea', 'gpg'):
            if not shutil.which(tool):
                sys.exit(f'release: {tool} is not installed')
        if not os.path.isfile(os.path.join(ROOT, 'keystore.properties')):
            sys.exit('release: there is no keystore.properties, so the APK '
                     'would not be signed (see the README)')
        self.key = signing_key()
        # Sin token se para aquí, antes de hacer nada, y no al final
        if not self.args.no_zenodo and not self.args.dry_run \
                and not zenodo_token():
            sys.exit('release: the upload to Zenodo needs a token '
                     '(see tools/zenodo.py check-token); --no-zenodo '
                     'leaves it out')
        if not ok('gpg', '--batch', '--list-secret-keys', self.key):
            sys.exit(f"release: the gpg key {self.key} is not in this "
                     "machine's keyring")
        branch = run('git', 'rev-parse', '--abbrev-ref', 'HEAD')
        if branch != BRANCH:
            sys.exit(f'release: on branch {branch}, not {BRANCH}')
        if run('git', 'status', '--porcelain'):
            message = 'there are changes not committed (see git status)'
            if not self.args.dry_run:
                sys.exit(f'release: {message}')
            print(f'release: (dry run) {message}', file=sys.stderr)
        if resuming:
            return
        for path, heading in PENDING.items():
            if not section(path, heading):
                sys.exit(f'release: {path} has no "## {heading}" section, or '
                         'it is empty: write there what is new first')

    # Notas ---------------------------------------------------------------

    def notes(self, name: str, apk: str) -> str:
        sha = sha256(apk)
        cert = re.search(r'certificate SHA-256 digest: ([0-9a-f]{64})', run(
            apksigner(), 'verify', '--print-certs', apk))
        if not cert:
            sys.exit(f'release: apksigner shows no certificate in {apk}')
        # Cada punto de la lista, en una sola línea.
        what = re.sub(r'\n  +', ' ', section('CHANGELOG.md', heading(name)))
        return f'''\
Wiktionary Reader is a small Android app for reading the English \
Wiktionary: you type a word, in any language, and it shows the whole entry, \
with its inflection tables, adapted to the phone. It needs Android 10 or newer.

What is new in {name}:

{what}

**Not an official app.** I have no association whatsoever with Wiktionary, \
Wikipedia or the Wikimedia Foundation, which runs them. The app is not made, \
approved or endorsed by any of them; it only shows the content of Wiktionary \
(CC BY-SA 4.0). Wiktionary and Wikipedia are trademarks of the Wikimedia \
Foundation.

**Install:** download `wiktionary-reader-{name}.apk` and open it on the \
phone; it updates the earlier versions in place. To get each new version by \
itself, add this repository to Obtainium, or add the F-Droid repository \
`https://nivaca.codeberg.page/fdroid/repo` to F-Droid (see "Installing" in \
the README).

**Verify:**

```
SHA-256 of the APK:            {sha}
SHA-256 of the signing cert:   {cert[1]}
```

`apksigner verify --print-certs wiktionary-reader-{name}.apk` shows the \
second one.

The APK is also signed with OpenPGP, with the same key as py-wk \
(`{spaced(self.key)}`). Import the key once and check the signature:

```
gpg --import {PUBLIC_KEY}
gpg --verify wiktionary-reader-{name}.apk.asc wiktionary-reader-{name}.apk
```

`SHA256SUMS` has the same SHA-256 of the APK, and `SHA256SUMS.asc` is its \
signature, with the same key:

```
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS
```

The tag `{'v' + name}` is signed with the same key.

Free software, under the GNU General Public License, version 3 or later.
'''

    # Pasos -------------------------------------------------------------

    def edit(self, code: int, name: str):
        today = datetime.date.today().isoformat()
        substitute(GRADLE, r'versionCode = \d+', f'versionCode = {code}')
        substitute(GRADLE, r'versionName = "[\d.]+"',
                   f'versionName = "{name}"')
        for path, heading in PENDING.items():
            substitute(path, rf'^## {re.escape(heading)}$',
                       f'## {name} — {today}')
        substitute('docs/DESCRIPTION.md', r'the current one is [\d.]+\.',
                   f'the current one is {name}.')
        substitute('docs/DESCRIPCIÓN_es.md',
                   r'^# Wiktionary Reader [\d.]+: descripción',
                   f'# Wiktionary Reader {name}: descripción')
        # Las notas de la versión para las tiendas, del registro de cambios.
        notes = store_notes.write(code, name, force=True)
        run('git', 'add', GRADLE, *PENDING, 'docs/DESCRIPTION.md',
            'docs/DESCRIPCIÓN_es.md', *notes)
        run('git', 'commit', '--quiet', '--message', f'v{name}')
        run('git', 'tag', '--sign', '--local-user', self.key, '--message',
            f'Wiktionary Reader {name}', f'v{name}', capture=False)

    def sums(self, apk: str):
        """Write SUMS for APK and sign it, unless it is already there,
        signed, for this same APK (a run that stopped half-way)."""
        text = f'{sha256(apk)}  {os.path.basename(apk)}\n'
        path = os.path.join(ROOT, SUMS)
        if os.path.isfile(path + '.asc') and os.path.isfile(path) and \
                read(SUMS) == text:
            return
        write(SUMS, text)
        run('gpg', '--batch', '--yes', '--armor', '--local-user', self.key,
            '--detach-sign', '--output', path + '.asc', path, capture=False)

    def verify(self, tag: str, signed: list[str]):
        """Stop unless every signature verifies as a user would check it:
        with only the published public key."""
        failed = []
        with tempfile.TemporaryDirectory(prefix='wk-release-') as home:
            # gpg quiere su carpeta solo para el usuario.
            os.chmod(home, 0o700)
            env = {**os.environ, 'GNUPGHOME': home}
            subprocess.run(['gpg', '--batch', '--quiet', '--import',
                            os.path.join(ROOT, PUBLIC_KEY)], env=env,
                           capture_output=True, check=True)
            for f in signed:
                done = subprocess.run(
                    ['gpg', '--batch', '--verify', f + '.asc', f], cwd=ROOT,
                    env=env, capture_output=True, text=True)
                if done.returncode != 0:
                    failed.append(f)
            done = subprocess.run(['git', 'tag', '--verify', tag], cwd=ROOT,
                                  env=env, capture_output=True, text=True)
            if done.returncode != 0:
                failed.append(f'the tag {tag}')
            # Un GNUPGHOME nuevo puede arrancar gpg-agent o keyboxd: se
            # detienen antes de borrar la carpeta.
            subprocess.run(['gpgconf', '--kill', 'all'], env=env,
                           capture_output=True)
        if failed:
            sys.exit('release: these do not verify with ' + PUBLIC_KEY
                     + ': ' + ', '.join(failed) + '; nothing was published')
        print('Verified with ' + PUBLIC_KEY + ': ' + ', '.join(
            os.path.basename(f) for f in signed) + f', the tag {tag}')

    def build(self) -> bool:
        return subprocess.run([os.path.join(ROOT, 'gradlew'), 'clean',
                               'assembleRelease'], cwd=ROOT).returncode == 0

    def files(self, name: str) -> list[str]:
        apk = os.path.join('dist', f'wiktionary-reader-{name}.apk')
        return [apk, apk + '.asc', SUMS, SUMS + '.asc', PUBLIC_KEY]

    def publish(self, name: str):
        tag = f'v{name}'
        apk = self.files(name)[0]
        for f in (apk, apk + '.asc', PUBLIC_KEY):
            if not os.path.isfile(os.path.join(ROOT, f)):
                sys.exit(f'release: there is no {f}')
        self.sums(apk)
        self.verify(tag, [apk, SUMS])
        # The deposit for Zenodo, from the tag: made before anything leaves
        # this machine, uploaded at the end
        zenodo = [sys.executable, os.path.join(ROOT, 'tools', 'zenodo.py')]
        run(*zenodo, 'collect', '--tag', tag, capture=False)
        files = self.files(name)
        run('git', 'push', 'origin', BRANCH, capture=False)
        run('git', 'push', 'origin', f'refs/tags/{tag}', capture=False)
        title = f'Wiktionary Reader {name}'
        with tempfile.TemporaryDirectory() as tmp:
            notes = os.path.join(tmp, 'notes.md')
            with open(notes, 'w', encoding='utf-8') as f:
                f.write(self.notes(name, apk))
            if self.github_assets(tag) is not None:
                self.add_missing_github(tag, files)
            else:
                run('gh', 'release', 'create', tag, *files, '--repo', REPO,
                    '--title', title, '--notes-file', notes, '--verify-tag',
                    capture=False)
            # tea, dentro del repositorio, toma el remoto de GitHub aunque
            # se le dé --repo; por eso corre en otra carpeta, con rutas
            # absolutas.
            where = ['--repo', REPO, '--login', TEA_LOGIN]
            if self.codeberg_assets(tag) is not None:
                self.add_missing_codeberg(tag, files)
            else:
                assets = [a for f in files
                          for a in ('--asset', os.path.join(ROOT, f))]
                run('tea', 'releases', 'create', *where, '--tag', tag,
                    '--title', title, '--note-file', notes, *assets,
                    capture=False, cwd=tmp)
        print(f'\nReleased Wiktionary Reader {name}.')
        update_fdroid_repo()
        if self.args.no_zenodo:
            print('Zenodo: not uploaded (--no-zenodo).')
        elif self.args.zenodo_draft:
            run(*zenodo, 'upload', capture=False)
        else:
            run(*zenodo, 'upload', '--publish', capture=False)

    # Una versión ya publicada ------------------------------------------

    def github_assets(self, tag: str) -> dict | None:
        """{name: 'sha256:…'} of the files of the release TAG on GitHub,
        or None if there is no such release."""
        if not ok('gh', 'release', 'view', tag, '--repo', REPO):
            return None
        return dict(line.split(' ', 1) for line in run(
            'gh', 'release', 'view', tag, '--repo', REPO, '--json', 'assets',
            '--jq', '.assets[] | "\\(.name) \\(.digest)"').splitlines()
            if line)

    def codeberg_assets(self, tag: str) -> set | None:
        """The names of the files of the release TAG on Codeberg, or None
        if there is no such release."""
        where = ['--repo', REPO, '--login', TEA_LOGIN]
        # (tea corre fuera del repositorio; véase publish)
        with tempfile.TemporaryDirectory() as tmp:
            listed = run('tea', 'releases', 'ls', *where, '--output',
                         'simple', cwd=tmp)
            if not re.search(rf'^{re.escape(tag)}\b', listed, re.MULTILINE):
                return None
            return set(re.findall(r'\S+', run(
                'tea', 'releases', 'assets', 'ls', *where, '--output',
                'simple', tag, cwd=tmp)))

    def incomplete(self, name: str) -> bool:
        """Whether vNAME lacks a release, or a file, on either forge."""
        names = [os.path.basename(f) for f in self.files(name)]
        github = self.github_assets(f'v{name}')
        codeberg = self.codeberg_assets(f'v{name}')
        return github is None or codeberg is None or any(
            n not in github or n not in codeberg for n in names)

    def add_missing_github(self, tag: str, files: list[str]):
        """Upload to a release already made the files it lacks, if the
        ones it has are those in dist/."""
        have = self.github_assets(tag) or {}
        for f in files:
            digest = have.get(os.path.basename(f))
            if digest and digest != f'sha256:{sha256(f)}':
                sys.exit(f'release: GitHub {tag} has another '
                         f'{os.path.basename(f)} than {f}: nothing added')
        missing = [f for f in files if os.path.basename(f) not in have]
        if not missing:
            print(f'GitHub: {tag} is already released')
            return
        run('gh', 'release', 'upload', tag, *missing, '--repo', REPO,
            capture=False)
        print(f'GitHub: added to {tag}: '
              + ', '.join(os.path.basename(f) for f in missing))

    def add_missing_codeberg(self, tag: str, files: list[str]):
        have = self.codeberg_assets(tag) or set()
        missing = [f for f in files if os.path.basename(f) not in have]
        if not missing:
            print(f'Codeberg: {tag} is already released')
            return
        with tempfile.TemporaryDirectory() as tmp:
            run('tea', 'releases', 'assets', 'create', '--repo', REPO,
                '--login', TEA_LOGIN, tag,
                *(os.path.join(ROOT, f) for f in missing), capture=False,
                cwd=tmp)
        print(f'Codeberg: added to {tag}: '
              + ', '.join(os.path.basename(f) for f in missing))

    def ask(self, text: str):
        print(text)
        if self.args.dry_run:
            sys.exit(0)
        if not self.args.yes:
            answer = input('Go on? [y/N] ').strip().lower()
            if answer not in ('y', 'yes', 's', 'sí', 'si'):
                sys.exit('Nothing done.')

    def __call__(self):
        code, name = app_version()
        tagged = run('git', 'rev-parse', '--verify', '--quiet',
                     f'refs/tags/v{name}^{{commit}}', check=False)
        head = run('git', 'rev-parse', 'HEAD')
        if tagged and self.incomplete(name):
            # Una vez anterior se detuvo después de etiquetar, o la versión
            # se publicó sin alguno de sus archivos (SHA256SUMS, antes de
            # que el script lo hiciera).
            self.check(resuming=True)
            self.ask(f'v{name} is tagged but not released on both forges '
                     'with all its files: build it if needed, push it and '
                     'release what is missing.')
            apk = os.path.join(ROOT, self.files(name)[0])
            if not os.path.isfile(apk):
                # El APK debe salir de la etiqueta (compilación reproducible).
                if tagged != head:
                    sys.exit(f'release: there is no {self.files(name)[0]}, '
                             f'and HEAD is not v{name}, from which it must '
                             'be built')
                if not self.build():
                    sys.exit('release: the build failed')
            self.publish(name)
            return
        self.check(resuming=False)
        if self.args.version:
            new = self.args.version
            if tuple(map(int, new.split('.'))) <= tuple(
                    map(int, name.split('.'))):
                sys.exit(f'release: {new} is not newer than {name}')
        else:
            major, minor = name.split('.')[:2]
            new = f'{major}.{int(minor) + 1}'
        if ok('git', 'rev-parse', '--verify', '--quiet', f'refs/tags/v{new}'):
            sys.exit(f'release: the tag v{new} already exists')
        self.ask(f'Wiktionary Reader {name} → {new} (versionCode {code + 1}), '
                 'with the "Unreleased" notes.\nCommit, tag, build the signed '
                 'APK from the tag, push to origin, and release on GitHub '
                 'and Codeberg.')
        self.edit(code + 1, new)
        if not self.build():
            # Solo se deshace lo que hizo este script: el árbol estaba limpio.
            run('git', 'tag', '--delete', f'v{new}', check=False)
            run('git', 'reset', '--quiet', '--hard', 'HEAD~1', check=False)
            sys.exit('release: the build failed; the commit and the tag are '
                     'undone')
        self.publish(new)


def main(argv=None):
    parser = argparse.ArgumentParser(prog='tools/release.py',
                                     description=DESCRIPTION)
    parser.add_argument('--version', metavar='X.Y',
                        help='the new version (default: the next minor one)')
    zenodo = parser.add_mutually_exclusive_group()
    zenodo.add_argument('--no-zenodo', action='store_true',
                        help='do not upload the deposit to Zenodo (by default '
                             'it is uploaded and published, with the token in '
                             '$ZENODO_TOKEN or the keyring)')
    zenodo.add_argument('--zenodo-draft', action='store_true',
                        help='upload it to Zenodo as a draft, not published')
    parser.add_argument('-n', '--dry-run', action='store_true',
                        help='only say what would be done')
    parser.add_argument('-y', '--yes', action='store_true',
                        help='do not ask before doing it')
    Release(parser.parse_args(argv))()


if __name__ == '__main__':
    main()
