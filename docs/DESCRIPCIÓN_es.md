# Wiktionary Reader 1.2: descripción

*[Read in English](DESCRIPTION.md)*

## Análisis

Wiktionary Reader es una app pequeña de Android para leer el Wiktionary en inglés (https://en.wiktionary.org). Se le da una palabra o una expresión de cualquier lengua y muestra la entrada completa, adaptada a la pantalla de un teléfono. Es la compañera de py-wk, el programa que hace lo mismo en la terminal y en el escritorio, y nació de la pregunta obvia: cómo tener lo mismo en el bolsillo. En su primera versión se llamó *wk*; en la 1.1 pasó a llamarse Wiktionary Reader, un nombre que dice lo que hace y que no se confunde con el de la app oficial, porque *Wiktionary* a secas es una marca de la Fundación Wikimedia. Es software libre, bajo la Licencia Pública General de GNU, versión 3 o posterior. El contenido de las entradas procede del Wiktionary y de sus colaboradores, bajo la licencia CC BY-SA 4.0; la app solo lo muestra. Su autor no tiene relación alguna con Wiktionary, Wikipedia ni la Fundación Wikimedia, que los mantiene, y la app no está hecha, aprobada ni respaldada por ninguno de ellos; lo dicen el diálogo Acerca de, la primera página de la app, los README y la descripción para F-Droid. Wiktionary y Wikipedia son marcas registradas de la Fundación Wikimedia.

Los requisitos vinieron de py-wk y del uso en el teléfono. Debía mostrar la entrada entera, con las tablas de flexión legibles, y dejar que el usuario ponga primero las lenguas que le interesan (latín, español, griego antiguo…) y oculte las demás. Tenía que ser rápida al escribir, con sugerencias; seguir los enlaces entre entradas sin salir de la app; volver atrás a la palabra anterior, y poder consultarse desde cualquier otra app, con la palabra seleccionada. Debía respetar el modo oscuro del sistema y ser respetuosa con los servidores del Wiktionary: no pedir dos veces la misma página y esperar cuando el sitio lo pide.

## Diseño

La app tiene una sola pantalla y cinco archivos de Kotlin. `Wiktionary.kt` habla con el sitio: descarga el HTML de una página, busca títulos por prefijo y decide adónde lleva cada enlace (a una entrada, con su lengua, o a otra cosa, que va al navegador). `PageCache.kt` guarda las páginas descargadas. `Pages.kt` arma los documentos que muestra el WebView: la entrada con la hoja de estilo y el script de la app, y las páginas propias (palabra sin entrada, error de red, bienvenida). `Prefs.kt` reúne los ajustes y las palabras recientes, con sus valores por omisión. `MainActivity.kt` es la pantalla: arriba, la casilla de búsqueda y el menú; en medio, el WebView; abajo, una barra con Atrás, Adelante, Índice y Buscar en la página, que deja su lugar a la barra de búsqueda mientras esta está abierta. Esa disposición sigue la ventana de py-wk (botones de navegación, panel de índice, búsqueda en la página, citas y tablas que se ocultan), llevada a un teléfono.

La decisión principal fue no convertir el HTML en texto, como hace py-wk, sino mostrarlo en un WebView. El HTML de Parsoid ya es un documento bueno, con sus tablas y sus enlaces, y el motor del navegador lo dibuja mejor que cualquier cosa que se escribiera a mano para un teléfono. La app lo cambia con dos archivos propios: `wk.css`, para la pantalla estrecha y los colores claros y oscuros, y `wk.js`, que, una vez cargada la página, hace la barra de lenguas, ordena y oculta las lenguas, pliega las secciones y mete las tablas anchas en una caja que se desplaza de lado.

No hay datos propios. Los ajustes (las lenguas, si se ocultan las demás, las citas, las tablas, las traducciones, las sugerencias, el tamaño del texto, el tema) y las últimas treinta palabras consultadas se guardan en las preferencias compartidas de Android. Las páginas se guardan en memoria y, comprimidas, en la carpeta de caché de la app durante una semana.

La app usa solo el framework de Android, sin AndroidX ni Jetpack Compose: una pantalla, una casilla, un menú y unos diálogos no necesitan más, y así el proyecto es pequeño y no tiene dependencias que actualizar.

## Implementación

Está escrita en Kotlin, con unas 1100 líneas en cinco archivos, más 190 de JavaScript y 140 de CSS; el resto es XML de recursos y Gradle. Usa el framework de Android y la biblioteca estándar de Kotlin, y nada más. El APK de depuración pesa 2,7 MB; el release, reducido con R8, 107 KB. El bloque de información de dependencias que el plugin de Android añade por omisión está desactivado, porque va cifrado con una clave de Google y F-Droid pide quitarlo.

### Las páginas

Las páginas vienen de la API REST del Wiktionary (`/api/rest_v1/page/html/…`) y las sugerencias de opensearch, por `HttpURLConnection`. Si el sitio responde 429, 502, 503 o 504, la app espera lo que diga `Retry-After` (entre uno y diez segundos) y reintenta, hasta tres veces. La página de *rosa* pesa un megabyte; cerca de un tercio son atributos que Parsoid deja para los editores (`data-mw`, `about`, los `id` propios), y `PageCache` los quita antes de guardarla. El atributo `typeof` se conserva porque las hojas de estilo del sitio lo usan con las imágenes.

### Las lenguas

El script toma como lengua cada `<section>` de primer nivel con un `<h2>`, y su código del atributo `lang` del lema. Las lenguas de los ajustes, por código o por nombre, pasan al principio en ese orden, y las demás pueden ocultarse; siguen todas en una barra de botones arriba, de una sola fila que se desplaza de lado. La lista de lenguas del propio sitio (el «minitoc») se oculta, porque repetía la barra.

### El modo oscuro

Las tablas de flexión toman sus colores de variables CSS (`--wikt-palette-…`) que la página usa pero no define; en el sitio las define el gadget `MediaWiki:Gadget-Palette.css`, con un valor claro y otro oscuro. La app carga ese gadget con la página, pone en `<html>` la clase `skin-theme-clientpref-os` y declara que la página admite los dos esquemas, de modo que el WebView no oscurece nada por su cuenta y las tablas se ven como en el sitio.

### Los enlaces y el historial

Un enlace a otra entrada se abre en la app, en la lengua indicada (`rosa#Latin`); uno a la misma página solo la desplaza. Los demás se abren en el navegador, pidiéndolo expresamente, porque la app también declara que abre los enlaces de `en.wiktionary.org`. El historial guarda cada palabra con su posición en la página. Desde Android 13 el botón Atrás se registra solo cuando hay historial, para que el gesto predictivo sepa si va a cerrar la app.

### La interfaz

El historial va en las dos direcciones, como en un navegador: abrir una palabra nueva vacía lo de adelante, y un toque largo en Atrás muestra las palabras recientes. El índice lo da el script: numera cada título y devuelve la lista en el orden en que se ve la página; al tocar un título, abre la lengua y las secciones que lo esconden y va a él. La búsqueda en la página es la del propio WebView (`findAllAsync`). Citas y Tablas cambian una clase de `<body>` y la hoja de estilo hace el resto, sin recargar: se ocultan los elementos de lista bajo una definición que contienen un pasaje citado (las referencias, con el mismo marcado, se quedan) y lo que el Wiktionary marca con `data-toggle-category="inflection"`, junto con las secciones que solo tienen una tabla. Al seleccionar texto en la entrada, el menú de la selección lleva primero «Buscar»; la entrada que la app ofrece a las demás apps se quita de ese menú para no repetirla. El tema propio (del sistema, claro u oscuro) usa `UiModeManager.setApplicationNightMode`, que existe desde Android 12.

### Desde otras apps

La app aparece en el menú del texto seleccionado («Buscar en Wiktionary», `PROCESS_TEXT`), acepta texto compartido (`SEND`) y puede abrir los enlaces del sitio (`VIEW`).

## Validación

Todavía no hay pruebas automáticas; la app se comprobó a mano. La hoja de estilo y el script se probaron primero con la página de *rosa* en Chrome sin ventana, al ancho de un teléfono, con el latín y el español como lenguas preferidas, para ver que reordenan y ocultan las secciones sin errores. Luego la app se instaló en un moto g55 5G con Android 16 y se revisó con capturas de pantalla tomadas por adb: una entrada abierta desde el menú del texto seleccionado (*rosa*), enlaces con lengua o sección (`amo#Latin`, `amo#Conjugation`), las sugerencias al escribir, una búsqueda, un enlace y la vuelta atrás (*lupu*, *wolf*), una palabra sin entrada (*lupux*, con los títulos parecidos) y los modos claro y oscuro. Esas pruebas mostraron tres defectos que se corrigieron: los iconos de la barra de estado en el modo claro, la posición en la página al cambiar de tema y el cursor que quedaba en la casilla después de buscar. El APK release de la 1.1, reducido con R8, se probó también en el mismo teléfono: una entrada con sus tablas, el menú y el diálogo Acerca de. La 1.2 se comprobó igual: en el teléfono, la barra de abajo, el índice (con un salto a una lengua oculta), la búsqueda en la página, las citas, «Buscar» sobre el texto seleccionado y la vuelta al punto de lectura; en el emulador del SDK (que arranca con los gráficos «swangle»), el diálogo de ajustes, el tema y el punto de lectura al cambiarlo. Esas pruebas hallaron cuatro fallos, corregidos antes de publicar: «Buscar» no aparecía, el toque largo en Atrás no hacía nada sin historial, Atrás volvía a otro lugar (la posición se guardaba en píxeles) y, tras cambiar los ajustes, el punto de lectura señalaba otro título. El compilador de Kotlin no da advertencias.

## Plataforma

Funciona en Android 10 (API 29) o posterior, en cualquier teléfono o tableta; se compila contra la API 36. Necesita Internet para las palabras que no se hayan consultado en la última semana, y también para las imágenes y las hojas de estilo del sitio, que el WebView guarda en su propia caché. No pide más permiso que el de Internet.

## Ambiente

Fue desarrollada en Fedora Linux, con OpenJDK 25, el SDK de Android (plataforma 36, build-tools 36.1) y Gradle 9.8 con el Android Gradle Plugin 9.5, que trae Kotlin integrado. El código está en GitHub (https://github.com/nivaca/wk-android) y en Codeberg (https://codeberg.org/nivaca/wk-android). Cada versión lleva una etiqueta `vVERSIÓN`, de la que F-Droid compila la app según la receta de `fdroid/io.github.nivaca.wk.yml`, y la firma con su propia clave; la descripción para la tienda está en `fastlane/metadata/android/`.

Para compilarla hacen falta un JDK 17 o posterior y el SDK de Android, cuya ruta va en `local.properties` (`sdk.dir=…`) o en `ANDROID_HOME`. `./gradlew assembleDebug` produce `app/build/outputs/apk/debug/app-debug.apk`, que se instala con `adb install -r`. `./gradlew assembleRelease` produce el APK release, reducido con R8 (107 KB), firmado si existe `keystore.properties` (fuera de git) y copiado entonces a `dist/wiktionary-reader-VERSIÓN.apk`. Si gpg tiene la clave del proyecto (la de py-wk, o la que indique `WK_GPG_KEY`), esa copia lleva además una firma OpenPGP aparte, `.apk.asc`, que la compilación comprueba; la parte pública de la clave es `GPG-KEY-wiktionary-reader.asc`, y las versiones publicadas llevan el APK, su firma y la clave. Los ajustes viven en las preferencias de la app (`settings.xml`), y las páginas guardadas, en su carpeta de caché, que se vacía desde el menú o desde los ajustes de Android.
