# Wiktionary Reader

*[Read in English](README.md)*

Wiktionary Reader es una pequeña app de Android para leer el
[Wiktionary en inglés](https://en.wiktionary.org). Se escribe una palabra, de cualquier
lengua, y muestra la entrada entera: etimología, pronunciación, definiciones con sus citas,
tablas de flexión, términos derivados, traducciones y lo demás. Es la hermana para Android
de [py-wk](https://github.com/nivaca/py-wk), que hace lo mismo en la terminal y en el
escritorio, y obtiene las páginas del Wiktionary de la misma manera. Hasta la versión 1.0 se
llamó *wk*.

**No es una app oficial.** No tengo relación alguna con Wiktionary, Wikipedia ni la
Fundación Wikimedia, que los mantiene. Wiktionary Reader no está hecha, aprobada ni
respaldada por ninguno de ellos; solo muestra el contenido que publica Wiktionary.
Wiktionary y Wikipedia son marcas registradas de la Fundación Wikimedia.

Soy Nicolás Vaughan (<nicolasvaughan@proton.me>) y la escribí en 2026.

## Qué hace

- Una casilla de búsqueda con sugerencias del Wiktionary mientras se escribe. La primera
  página muestra las palabras recientes.
- Una barra abajo, a partir de la ventana de py-wk: **Atrás** y **Adelante**, como en un
  navegador (un toque largo en Atrás muestra las palabras recientes), **Índice**, con las
  lenguas de la página y sus secciones, y **Buscar en la página**, con el número de
  coincidencias.
- **Citas** y **Tablas** en el menú, que se encienden y se apagan al instante.
- Cualquier texto seleccionado en una entrada se abre con **Buscar**.
- La entrada, con los estilos del sitio adaptados al teléfono: las tablas anchas se
  desplazan de lado dentro de su caja, y los títulos de las secciones (Etymology, Noun,
  Translations…) se abren y se cierran al tocarlos. Las traducciones empiezan cerradas.
- Una barra arriba con las lenguas de la página. En **Ajustes…** se indican las que se
  quieren primero (códigos o nombres en inglés: `la, es, Ancient Greek`) y se pueden ocultar
  las demás, que siguen en la barra, a un toque.
- Los enlaces a otras entradas se abren en la app, en la lengua indicada (`rosa#Latin`); los
  demás (apéndices, Wikipedia, archivos), en el navegador.
- Atrás vuelve a la palabra anterior, al punto donde se la dejó.
- Modo claro y oscuro, según el sistema o, desde Android 12, elegido en Ajustes. Las tablas de flexión usan la paleta del propio
  Wiktionary, que tiene colores para ambos.
- Desde cualquier app: se selecciona una palabra y se elige **Buscar en Wiktionary** en el
  menú de la selección, o se comparte un texto con la app. Los enlaces a `en.wiktionary.org`
  también pueden abrirse en ella.
- Las páginas se guardan una semana, así que una palabra ya vista se abre al instante.
- Ajustes… reúne lo demás: traducciones abiertas o cerradas, sugerencias, tamaño del texto,
  tema, borrar las páginas guardadas y olvidar las palabras recientes. El menú tiene además
  Abrir en el navegador, Compartir el enlace y Descargar de nuevo.
- Interfaz en inglés y en español.

## Requisitos

Android 10 (API 29) o posterior, y conexión a Internet para las palabras que no se hayan
visto en la última semana.

## Compilación

Hacen falta un JDK (17 o posterior) y el SDK de Android con la plataforma 36. Se escribe la
ruta del SDK en `local.properties` (`sdk.dir=/home/yo/Android/Sdk`), o se define
`ANDROID_HOME`, y luego:

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El wrapper de Gradle descarga por su cuenta el Gradle que corresponde. El proyecto usa solo
el framework de Android (ni AndroidX ni otras bibliotecas), así que la compilación descarga
poco.

## Versiones y firma

`./gradlew assembleRelease` produce el APK release, reducido con R8 (unos 110 KB), y lo copia
a `dist/wiktionary-reader-VERSIÓN.apk`, en lugar de los anteriores. Solo sale firmado si en la raíz del
proyecto está el archivo `keystore.properties`, con las claves `storeFile`, `storePassword`,
`keyAlias` y `keyPassword`; la clave y ese archivo no están en git. Sin ellos, el APK
release sale sin firmar, como en el servidor de compilación de F-Droid.

La copia de `dist/` lleva además una firma OpenPGP, `dist/wiktionary-reader-VERSIÓN.apk.asc`,
cuando gpg tiene la clave secreta del proyecto (la misma de py-wk; su parte pública es
[GPG-KEY-wiktionary-reader.asc](GPG-KEY-wiktionary-reader.asc)). La variable `WK_GPG_KEY`
indica otra clave; sin la clave, la compilación lo dice y deja solo la firma de Android. Para
comprobar una versión publicada:

```sh
gpg --import GPG-KEY-wiktionary-reader.asc
gpg --verify wiktionary-reader-VERSIÓN.apk.asc wiktionary-reader-VERSIÓN.apk
apksigner verify --print-certs wiktionary-reader-VERSIÓN.apk
```

El SHA-256 del certificado de Android es
`4f4bd72456f162f72aeec33a29d7a8487b8622eb81490dc54629ac3588661c51`.

Una versión nueva se publica con una orden, después de escribir lo nuevo bajo
`## Unreleased` en `CHANGELOG.md` y bajo `## Sin publicar` en `CHANGELOG_es.md`:

```sh
python3 tools/release.py [--version X.Y] [--dry-run] [--yes]
```

Sube `versionCode` y `versionName` (de 1.2 a 1.3, salvo que `--version` diga otra), da a
esas dos secciones la versión y la fecha de hoy, y pone la versión en `docs/`. Confirma
(`vX.Y`), firma la etiqueta y compila desde ella el APK firmado, como pide F-Droid; si la
compilación falla, deshace la confirmación y la etiqueta. Luego sube `main` y la etiqueta a
`origin` (GitHub y Codeberg) y crea la versión en cada forja, con el APK, su `.asc` y la
clave pública, y con las notas tomadas del registro de cambios. Pregunta antes de cambiar
nada, y `--dry-run` solo dice qué haría. Necesita `gh`, `tea`, `gpg` con la clave del
proyecto, `main` sin cambios por confirmar y `keystore.properties`. Si se detiene después de
etiquetar, correrlo de nuevo publica lo que falte.

En el teléfono, [Obtainium](https://github.com/ImranR98/Obtainium) puede seguir las
versiones de este repositorio e instalar cada APK nuevo sobre el anterior.

## F-Droid

La descripción, el icono y las notas de cada versión para F-Droid están en
`fastlane/metadata/android/` (en inglés y en español), donde F-Droid los busca. La receta de
compilación para el repositorio de F-Droid es `fdroid/io.github.nivaca.wk.yml`: compila la
etiqueta `vVERSIÓN` desde la carpeta `app` y encuentra las versiones nuevas por sus
etiquetas.

La compilación es reproducible. F-Droid compila cada versión por su cuenta, compara el
resultado con el APK firmado de la versión publicada en GitHub y, si son iguales salvo la
firma, publica ese APK con su firma original. Así, la versión de F-Droid y la de las
publicaciones son la misma app, y cualquiera de las dos actualiza a la otra. Para eso, el APK
release se ha de compilar desde el commit de la etiqueta, porque el APK guarda el commit del
que salió.

## Cómo funciona

Como py-wk, la app pide a la API REST del Wiktionary el HTML de cada página
(`/api/rest_v1/page/html/PALABRA`), y a la API opensearch las sugerencias. No convierte el
HTML en texto: lo muestra en un WebView, con una hoja de estilo (`assets/wk.css`) y un script
(`assets/wk.js`) propios, que hacen la barra de lenguas, ordenan y ocultan las lenguas y
pliegan las secciones. Antes de mostrar una página le quita los atributos que Parsoid deja
para los editores, cerca de un tercio de su tamaño, y la guarda comprimida en la carpeta de
caché de la app.

El código está en `app/src/main/java/io/github/nivaca/wk/`: `Wiktionary.kt` habla con el
sitio, `PageCache.kt` guarda las páginas, `Pages.kt` arma los documentos para el WebView, `Prefs.kt`
guarda los ajustes y las palabras recientes, y `MainActivity.kt` es la única pantalla. `docs/DESCRIPCIÓN_es.md` la describe en detalle.

## Licencia

Wiktionary Reader es software libre, bajo la Licencia Pública General de GNU, versión 3 o posterior
(`GPL-3.0-or-later`); véase [LICENSE](LICENSE). El contenido de las entradas viene del
Wiktionary y de sus colaboradores, bajo la licencia CC BY-SA 4.0. La app solo lo muestra y,
como se dijo arriba, no tiene relación alguna con Wiktionary, Wikipedia ni la Fundación
Wikimedia.
