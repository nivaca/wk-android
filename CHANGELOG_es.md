# Cambios

Los cambios de cada versión de Wiktionary Reader (wk en la 1.0). El código está en
[GitHub](https://github.com/nivaca/wk-android) y en
[Codeberg](https://codeberg.org/nivaca/wk-android).

## Sin publicar

- `tools/zenodo.py`: la descripción del depósito es ahora el README entero
  de la etiqueta, en HTML. El borrador recibe además el colaborador (el
  director del proyecto), los idiomas, el copyright, el repositorio en
  GitHub, el lenguaje de programación y el estado del desarrollo; estos
  últimos, por la API de registros de Zenodo, porque la de depósitos no los
  admite. El token puede guardarse en el llavero del escritorio
  (`secret-tool store --label="Zenodo token" service zenodo`) en vez de en
  `$ZENODO_TOKEN`; `tools/zenodo.py check-token` dice si hay uno.
- `tools/zenodo.py`: la descripción del depósito deja fuera la línea que
  lleva a los README en otras lenguas y la sección Screenshots, cuyas
  imágenes Zenodo no muestra.

## 1.4 — 2026-11-23

- Cada versión lleva también `SHA256SUMS`, con el SHA-256 del APK, y su
  firma OpenPGP `SHA256SUMS.asc`. Antes de publicar, `tools/release.py`
  comprueba todas las firmas y la de la etiqueta solo con la clave
  pública, como lo haría un usuario, y se detiene si alguna falla.

## 1.3 — 2026-11-22

- `tools/release.py` publica una versión con una orden: sube la versión,
  confirma, firma la etiqueta, compila desde ella el APK y crea las
  versiones en GitHub y en Codeberg, con las notas de este registro. Las
  notas dicen cómo recibir cada versión nueva sola con Obtainium.

## 1.2 — 2026-11-21

Cambios en la interfaz, a partir de la ventana de py-wk:

- Una barra abajo con Atrás, Adelante, Índice y Buscar en la página.
  Historial hacia adelante, como en un navegador; un toque largo en
  Atrás muestra las palabras recientes.
- Índice: las lenguas de la página y sus secciones; al tocar una se va a
  ella, y se abre si estaba oculta o cerrada.
- Buscar en la página, con el número de coincidencias y la anterior y la
  siguiente.
- Citas y Tablas en el menú, que se encienden y se apagan al instante,
  sin volver a cargar la página.
- Al seleccionar texto en una entrada, el menú de la selección ofrece
  «Buscar».
- Palabras recientes, en el menú y en la primera página.
- Ajustes… en un solo diálogo: lenguas, citas, tablas, si las
  traducciones empiezan cerradas, sugerencias mientras se escribe,
  tamaño del texto, tema (del sistema, claro u oscuro, desde Android 12)
  y los botones para borrar las páginas guardadas y olvidar las palabras
  recientes.
- El APK de `dist/` lleva además una firma OpenPGP (`.apk.asc`), con la
  clave de py-wk, cuya parte pública es `GPG-KEY-wiktionary-reader.asc`;
  las versiones publicadas llevan las dos.

## 1.1 — 2026-11-21

- La app se llama ahora Wiktionary Reader (antes, wk).
- El diálogo Acerca de, la primera página de la app, los README y la
  descripción para F-Droid dicen que el autor no tiene relación alguna con
  Wiktionary, Wikipedia ni la Fundación Wikimedia.
- El APK firmado de `dist/` se llama ahora `wiktionary-reader-VERSIÓN.apk`.

## 1.0 — 2026-11-21

- Primera versión: una casilla de búsqueda con sugerencias del Wiktionary
  y la entrada entera en un WebView, con una hoja de estilo y un script
  propios.
- Una barra con las lenguas de la página; las preferidas primero, y las
  demás ocultas si se quiere (Lenguas…).
- Títulos de sección que se abren y se cierran (las traducciones empiezan
  cerradas) y tablas anchas que se desplazan de lado.
- Los enlaces a entradas se abren en la app, en su lengua; historial con
  Atrás.
- Modo claro y oscuro, con la paleta del Wiktionary para las tablas.
- Consulta desde otras apps: texto seleccionado, texto compartido y
  enlaces del Wiktionary.
- Páginas guardadas una semana; tamaño del texto; interfaz en inglés y en
  español.
