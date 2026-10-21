# Spec 006 — Iconos distintivos por tipo de archivo en el gestor de archivos

# Spec 006 — Iconos distintivos por tipo de archivo en el gestor de archivos

Estado: **cerrada** (2026-10-20; implementada en revs 1-8 y validada en dispositivo; la rev 8
validada en dispositivo el 2026-10-21)

## Contexto y objetivo

En la lista del gestor de archivos, todos los ficheros regulares se muestran con el mismo
icono genérico, y el usuario no distingue de un vistazo un APK de un script o de una imagen.
El objetivo es que cada familia de archivos muestre un icono reconocible y un color propio,
sin cambiar el resto de la fila (selección, apertura, orden).

## Usuarios

- Quien navega con el gestor de archivos de la app: localiza por vista el tipo de un fichero
  sin abrirlo ni mirar la extensión en el nombre.

## Historias de usuario

- HU-1. Como usuario del gestor de archivos, quiero que cada tipo de archivo tenga un icono
  y color distintivo para identificar los ficheros a simple vista.

## Definiciones

- **Familia**: grupo de extensiones que comparten icono y color (código fuente, binario/app,
  comprimido, imagen, audio, vídeo, texto/datos, PDF, documento de texto rico).
- **Fichero sin tipo reconocido**: fichero regular cuya extensión no pertenece a ninguna
  familia; mantiene el icono y color genéricos de hoy.
- **Variante de icono**: icono más específico que el de su familia para un nombre o extensión
  concretos (p. ej. `.md` usa el glifo *markdown* en vez del genérico de texto). La variante
  cambia el ICONO, nunca el color: el tinte sigue siendo el de la familia.

## Requisitos funcionales (EARS)

- RF-1. CUANDO la lista muestre un fichero regular con extensión reconocida, EL SISTEMA
  pintará el icono y el color de su familia.
- RF-2. EL SISTEMA reconocerá estas familias y, dentro de cada una, al menos las extensiones
  listadas (en minúscula; la lista podrá ampliarse en la misma familia sin cambiar esta spec):
  - **Código fuente**: `kt`, `java`, `py`, `sh`, `bash`, `zsh`, `c`, `cpp`, `h`, `hpp`, `rs`,
    `go`, `js`, `ts`, `rb`, `pl`, `lua`, `sql`
  - **Binario/app**: `apk`, `jar`, `dex`, `so`, `deb`, `exe`, `bin`
  - **Comprimido**: `zip`, `tar`, `gz`, `tgz`, `bz2`, `xz`, `7z`, `rar`
  - **Imagen**: `png`, `jpg`, `jpeg`, `gif`, `webp`, `bmp`, `svg`, `ico`
  - **Audio**: `mp3`, `flac`, `ogg`, `oga`, `wav`, `opus`, `m4a`, `mid`
  - **Vídeo**: `mp4`, `mkv`, `avi`, `webm`, `mov`, `m4v`
  - **Texto/datos**: `txt`, `md`, `json`, `xml`, `html`, `css`, `yaml`, `yml`, `toml`, `csv`,
    `ini`, `conf`, `log`, `prop`, `properties`
  - **PDF**: `pdf`
  - **Documento rico**: `doc`, `docx`, `odt`, `rtf`
- RF-3. SI un fichero regular no pertenece a ninguna familia, ENTONCES EL SISTEMA mantiene el
  icono y el color genéricos actuales (sin color especial).
- RF-4. MIENTRAS el fichero sea un directorio, un enlace o un enlace roto, EL SISTEMA mantiene
  su icono y color actuales (carpeta, enlace, error), y esa condición precede al mapa de
  extensiones: un enlace roto sigue en error aunque apunte a un `.apk`, y una carpeta no entra
  en el mapa aunque su nombre contenga un punto (`backup.zip/`).
- RF-5. CUANDO la extensión contenga mayúsculas o la capitalización sea mixta, EL SISTEMA la
  reconocerá igual (comparación sin distinción de mayúsculas).
- RF-6. EL SISTEMA resolverá familia e icono con lógica pura de biblioteca, cubierta por tests
  JUnit que corren sin Android; la UI solo pinta el resultado.
- RF-7. CUANDO el nombre (sin distinguir mayúsculas) sea `readme.md`, EL SISTEMA pintará el
  icono *info* (versión contorno, para no confundirlo con el botón *Info* de la misma fila).
- RF-8. CUANDO el nombre (sin distinguir mayúsculas) sea `changelog.md`, EL SISTEMA pintará el
  icono *history*.
- RF-9. CUANDO la extensión sea `properties` o `prop`, EL SISTEMA pintará el icono *settings*.
- RF-10. CUANDO la extensión sea `md` o `markdown`, EL SISTEMA pintará el glifo *markdown* de
  Material Symbols. `file_json`: CUANDO la extensión sea `json`, EL SISTEMA pintará el glifo
  *file_json* de Material Symbols (ambos importados como código Kotlin, no dependencia).
- RF-11. CUANDO la extensión sea `js`, EL SISTEMA pintará el icono *javascript*.
- RF-12. MIENTRAS haya una variante, EL SISTEMA pintará su icono en el tinte de la familia
  correspondiente (markdown/info/history/settings → colores del texto/datos; javascript →
  verde del código), y el reconocimiento precede en este orden: readme.md > changelog.md >
  extensión (md/json/properties/js) > familia > genérico.
- RF-13. CUANDO el nombre (sin distinguir mayúsculas) sea uno de `gitignore`, `gitattributes`,
  `gitconfig`, `gitmodules` o `gitkeep` (con o sin punto inicial), EL SISTEMA pintará el glifo
  oficial de git vendido como Kotlin. Con color propio del brand `#F03C2E` de git porque estos
  ficheros no pertenecen a ninguna familia (única excepción además de JSON, RF-14). En orden de
  reconocimiento queda igual que
  RF-12 con GIT en el nivel de `readme.md`/`changelog.md` (por nombre, antes de extensión).
- RF-14. CUANDO el fichero lleve la variante JSON (extensión `json`), EL SISTEMA pintará su
  glifo en AMARILLO `#F9A825` (Material Yellow 800, visible en tema claro y oscuro) en vez del
  índigo de la familia texto/datos: el JSON es el único fichero de datos que va de amarillo.
- RF-15. CUANDO la extensión sea `kt` o `kts`, EL SISTEMA pintará el glifo oficial de Kotlin
  vendido como Kotlin (tile con la K, viewport 48×48, un solo path) en el morado brand
  `#7F52FF` en vez del `Code` verde de la familia código fuente. La variante con color propio
  son ahora GIT (RF-13), JSON (RF-14) y KT (RF-15); la familia CODE no cambia para el resto
  (c, py, js, …).
- RF-16. CUANDO la extensión sea `java`, EL SISTEMA pintará el glifo *local_cafe* de Material
  Symbols vendido como Kotlin (taza de café, guiño al origen del nombre) en ROJO Java
  `#EA2D2E` (el rojo de la taza del logo clásico), como variante con color propio: GIT (RF-13),
  JSON (RF-14), KT (RF-15) y JAVA (RF-16).
- RF-17. CUANDO la extensión sea `py`, EL SISTEMA pintará el glifo oficial de Python vendido
  como Kotlin con SUS DOS COLORES OFICIALES de marca: azul `#3776AB` la serpiente superior y
  amarillo `#FFD43B` la inferior (bakeados en el propio glifo). Para esta variante el sistema
  NO aplicará filtro de color (tinte `Unspecified`): es la primera variante multicolor y
  prevalece sobre cualquier tinte de familia o de variante.
- RF-18 (rev 8). CUANDO el fichero sea un script de Gradle — extensión `gradle` (Groovy DSL)
  o nombre terminado en `.gradle.kts` (Kotlin DSL; ese sufijo exacto precede a la variante
  `kts`→Kotlin, RF-15) — EL SISTEMA pintará el glifo oficial del elefante de Gradle vendido
  como Kotlin (viewport original cuadrado-centrado, un solo path en azul). Los ficheros de
  Gradle no pertenecen a ninguna familia, como GIT (RF-13, sin familia), y llevan color
  propio AZUL `#0288D1` (Material Light Blue 800, legible en tema claro y oscuro): el
  drawable entregado venía negro y el usuario pidió azul. `kts` sin `.gradle.` sigue
  Kotlin, `gradle.properties` sigue SETTINGS (RF-9) y `gradlew` (sin extensión) queda
  genérico.

## Requisitos no funcionales

- RNF-1. Ninguna dependencia nueva de terceros: los iconos salen catálogo existente de iconos
  Material. (Constitución #1.)
- RNF-2. Sin literales visibles en Compose: los iconos no introducen texto visible (si hubiera
  `contentDescription`, va a `strings.xml` en los tres idiomas). (Constitución #6.)
- RNF-3. El mapa extensión→familia vive en una función pura de biblioteca, no inline en la
  UI. (Constitución #3.)
- RNF-4. `./gradlew test` en verde tras cada tarea. (Constitución #4.)

## Casos límite

- **Sin punto**: `Makefile`, `LICENSE` → sin extensión → genérico (RF-3).
- **Punto final**: `file.` → extensión vacía → genérico (RF-3).
- **Puntos múltiples**: `app.tar.gz` → se usa la última extensión (`gz`, familia comprimido).
- **Fichero oculto**: `.bashrc` → todo es nombre, sin extensión → genérico (RF-3).
- **Mayúsculas**: `APP.PY`, `IMG.Jpg` → reconocidos (RF-5).
- **Nombre largo con espacios** (`Mi documento final.txt`) → se usa la última parte tras el
  último punto, no la primera.

## Fuera de alcance

- Vistas previas (thumbnails) de imágenes o iconos gráficos por ejecutable real (MIME de
  contenido); el mapa es por nombre/extension, como hoy ya se hace para ordenar y compartir.
- Cambiar el orden de la lista, la fila (selección, tap, Info) o el diálogo de detalles.
- Iconos para cada lenguaje concreto dentro de una misma familia (un solo icono por familia).
- Otros módulos que muestren ficheros.

## Criterios de finalización

- La lista muestra icono y color distintos para cada familia de la RF-2, y genérico para lo
  no reconocido.
- Directorio/link/link roto se pintan como hoy (RF-4).
- Tests JUnit de la función pura cubren: cada familia (una extensión al menos), mayúsculas,
  sin punto, punto final, puntos múltiples, fichero oculto y vacío/null de nombre.
- `./gradlew test` en verde.

## Dudas abiertas

- Ninguna pendiente de bloquear: los colores exactos por familia son decisión de la UI
  (plan), no de esta spec; el requisito es que cada familia sea distinguible del resto y del
  genérico.
