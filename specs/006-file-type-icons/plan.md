# Plan 006 — Iconos distintivos por tipo de archivo

Spec: `specs/006-file-type-icons/spec.md`. Aquí van stack, archivos y algoritmo; el QUÉ lo
define la spec, y la UI solo pinta.

## Archivos y responsabilidades

| Archivo | Estado | Responsabilidad | RF |
|---|---|---|---|
| `filemanager/src/main/java/com/estrin217/filemanager/FileTypeDetector.java` | nuevo | Función pura: nombre de fichero → `FileType` (familia) y → `IconVariant` (variante, rev 2). Sin Android, sin Compose. | RF-2, RF-3, RF-5, RF-7 a RF-12 |
| `filemanager/src/main/java/com/estrin217/filemanager/FileSortOption.java` | sin cambios | Contrato intacto (ver decisión del plan: la delegación cambiaba el orden de ficheros ocultos). Test de regresión nuevo. | (regresión) |
| `filemanager/src/main/java/com/estrin217/filemanager/compose/FileTypeIcons.kt` | nuevo | Solo pintar: `FileType` → `ImageVector` (Material) y → `Color`; `IconVariant` → `ImageVector`. Sin lógica. | RF-1, RF-7 a RF-11 |
| `filemanager/src/main/java/com/estrin217/filemanager/compose/FileTypeGlyphs.kt` | nuevo (rev 2) | Glifos vendidos `markdown` y `file_json` como `ImageVector` Kotlin, generados del endpoint de Material Symbols (URL citada en el fichero). | RF-10 |
| `filemanager/src/main/java/com/estrin217/filemanager/compose/FileTypeGlyphs.kt` | nuevo (rev 2) | Glifos vendidos `markdown` y `file_json` como `ImageVector` Kotlin, generados del endpoint de Material Symbols (URL citada en el fichero). | RF-10 |
| `filemanager/src/main/java/com/estrin217/filemanager/compose/FileManagerScreen.kt` | editar | El bloque del `Icon` de la fila usa el detector con la precedencia actual y aplica la variante si hay (roto > link > dir > readme/changelog > variante por extensión > familia > genérico). | RF-1, RF-3, RF-4, RF-12 |

Rev 3 (git): `FileTypeDetector` añade `IconVariant.GIT` (nombre de fichero, no extensión),
`FileTypeIcons.kt` añade el glifo vendido git y su tinte brand, y `FileManagerScreen.kt` pasa
a sacar el tinte de la variante como respaldo cuando hay familia. Precedencia vieja a nueva:
roto > link > dir > **git** > readme > changelog > variante por extensión > familia > genérico.
| `filemanager/src/test/java/com/estrin217/filemanager/FileTypeDetectorTest.java` | nuevo | JUnit 4 plano, como `FileOperationsHelperSymlinkTest`. | RF-2, RF-3, RF-5, RF-7 a RF-11, límites |
| `filemanager/src/test/java/com/estrin217/filemanager/FileSortOptionExtensionTest.java` | nuevo | Fija el comportamiento actual de `getExtension` tras delegar (regresión). | (regresión) |

## Funciones puras (con "hoy" como parámetro)

```java
// FileTypeDetector.java — final class, utility pattern como FileOperationsHelper
public enum FileType { CODE, BINARY, ARCHIVE, IMAGE, AUDIO, VIDEO, TEXT, PDF, DOCUMENT }

/** Extensión en minúscula sin punto, "" si no hay. "app.tar.gz"→"gz";
 *  ".bashrc"→""; "file."→""; null→"". */
public static String getFileExtension(String name) {
    if (name == null) return "";
    int idx = name.lastIndexOf('.');
    if (idx <= 0 || idx == name.length() - 1) return "";
    return name.substring(idx + 1).toLowerCase(Locale.ROOT);
}

/** Familia del fichero, null si no reconocido. */
public static FileType familyFor(String name) {
    switch (getFileExtension(name)) {           // listas de la RF-2
        case "kt": case "java": ... : return FileType.CODE;
        case "apk": ...                        : return FileType.BINARY;
        case "zip": ...                        : return FileType.ARCHIVE;
        case "png": ...                        : return FileType.IMAGE;
        case "mp3": ...                        : return FileType.AUDIO;
        case "mp4": ...                        : return FileType.VIDEO;
        case "txt": ...                        : return FileType.TEXT;
        case "pdf"                             : return FileType.PDF;
        case "doc": ...                        : return FileType.DOCUMENT;
        default                                 : return null;
    }
}
```

## Algoritmo de la UI (pseudocódigo)

```kotlin
// FileManagerScreen.kt, dentro del item:
val family = remember(file.absolutePath) {
    if (isDir || isLink) null else FileTypeDetector.familyFor(file.name)
}
Icon(
    if (isLink) Icons.Default.Link
    else if (isDir) Icons.Default.Folder
    else family?.icon ?: Icons.Default.Description,      // genérico actual (RF-3)
    tint = if (isBroken) colorScheme.error
           else if (isLink) colorScheme.tertiary
           else if (isDir) colorScheme.primary
           else family?.tint ?: colorScheme.onSurfaceVariant,
    ...
)
```

La precedencia roto > link > dir > familia > genérico es la de hoy; el detector nunca
reemplaza carpeta ni enlace (RF-4).

## Interfaz

- `FileTypeIcons.kt`: `internal val FileType.icon: ImageVector` y
  `internal val FileType.tint: Color` (colores fijos; el mapping vive en un `object`
  `FileTypeIcons`, una sola entrada por familia).

## Iconos y colores por familia (Material, `material-icons-extended`)

Ya está en el catálogo (`compose-material-icons`), sin dependencia nueva (RNF-1). Los glifos
`markdown` y `file_json` NO están en el artefacto (verificado en el AAR); se importan como
código Kotlin generado (`FileTypeGlyphs.kt`), fuente: endpoint oficial de Material Symbols
(`<gstatic>/render/v1/Material+Symbols+Outlined/24dp/markdown.kt` y `.../file_json.kt`,
`var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50`), comentado en el fichero. Resto de variantes de
la librería: `History`, `Javascript` (extended), `Settings` y `Info` (core; para README se usa
`Icons.Outlined.Info` para no duplicar el `Icons.Default.Info` del botón Info de la fila).

| Familia | Icono | Color |
|---|---|---|
| CODE | `Icons.Default.Code` | verde `0xFF2E7D32` |
| BINARY | `Icons.Default.Android` | teal `0xFF00897B` |
| ARCHIVE | `Icons.Default.Archive` | naranja `0xFFEF6C00` |
| IMAGE | `Icons.Default.Image` | azul `0xFF1565C0` |
| AUDIO | `Icons.Default.MusicNote` | violeta `0xFF8E24AA` |
| VIDEO | `Icons.Default.Movie` | carmesí `0xFFE53935` |
| TEXT | `Icons.Default.TextSnippet` | índigo `0xFF3F51B5` |
| PDF | `Icons.Default.PictureAsPdf` | rojo oscuro `0xFFB71C1C` |
| DOCUMENT | `Icons.Default.Article` | marrón `0xFF8D6E63` |
| — genérico (sin familia) | `Icons.Default.Description` (hoy) | `onSurfaceVariant` (hoy) |

## Variantes de icono (rev 2)

Función pura nueva en `FileTypeDetector` (Java):

```java
/** {@code readme.md} (sin mayúscula) → INFO; {@code changelog.md} → HISTORY;
 *  ext md/markdown → MARKDOWN; json → JSON; properties/prop → SETTINGS; js → JS;
 *  si no → NONE. La variante cambia el icono, no el color. */
public enum IconVariant { NONE, MARKDOWN, INFO, HISTORY, SETTINGS, JSON, JS, GIT }
public static IconVariant iconVariantFor(String name);
```

Notas rev 2: `readme.md`/`readme.markdown` → INFO y `changelog.md`/`changelog.markdown` →
HISTORY por nombre COMPLETO (sin distinguir mayúsculas), porque la extensión ya existe de
fábrica. Nombre: readme/changelog preceden a la variante de extensión. `gitignore`,
`gitattributes`, `gitconfig`, `gitmodules` y `gitkeep` (rev 3), por **nombre de base** sin
distinguir mayúsculas (con o sin punto inicial) → GIT, igual al nivel del readme (nombres
con punto inicial, que el parse de extensiones da descartado). La variante GIT va con su
tinte brand y JSON también va con su propio Amarillo (rev 4); KT (rev 5) añade morado brand
`#7F52FF`; el resto de variantes usan el de su familia.

Mapping en `FileTypeIcons.kt` (solo pintar): MARKDOWN → glifo markdown (vendido); JSON →
glifo file_json (vendido); JS → `Icons.Default.Javascript`; HISTORY → `Icons.Default.History`;
SETTINGS → `Icons.Default.Settings`; INFO → `Icons.Outlined.Info`; NONE/heredad → icono de
familia o genérico. El tinte sale SIEMPRE del `FileType` de la familia (RF-12).

Precedencia completa por fila: roto > link > dir > readme.md > changelog.md > variante por
extensión > familia > genérico; el color: roto/error > link/tertiary > dir/primary > tinte
de familia (variante incluida) > genérico.

## Decisiones justificadas (con la alternativa descartada)

Cambio (rev 3): variante GIT pedida por el usuario (el XML adjunto venía con el glifo oficial
de git). *Descartada:* respetar el `fillColor` naranja del drawable original — el tinte va
SIEMPRE por la familia/variante (`Icon` repinta con `ColorFilter` así que basta con negro en
el path) y el color brand se puso como tinte de la propia variante GIT, no drawable por
color. También descartado el parse a runtime del XML: `addPathNodes(String)` alto nivel de
Compose parsea el único path del drawable, sin poder introducir XML de recursos.

Cambio (rev 5): `.kt`/`.kts` usan el glifo oficial de Kotlin (XML adjunto por el usuario) en
mundo del `Code` de la familia código. *Elección del usuario pedida y decidida:* **código plano
morado `#7F52FF` como `ImageVector`** (el mascote de Kotlin es el extremo del gradiente) en vez
del drawable con `aapt:gradient` — el gradiente exige `Painter` (`painterResource`) mezclado
en la cadena de `Icon(imageVector=…)` y desactivar el `tint` (el `ColorFilter` aplasta el
gradiente: hay que pasar `Color.Unspecified`), refactor de la antes única vía
vectorial por un brillo que a 28dp se pierde. El path del glifo se parsea con
`addPathNodes` como venían haciendo los demás glifos vendidos.

Cambio (rev 6): `.java` → glifo *local_cafe* (café; guiño a Java), vendido del endpoint
Material Symbols como los demás. El usuario lo pidió en ROJO Java: tinte propio `#EA2D2E`
(el rojo de la taza del logo clásico de Duke) en vez de heredar el verde CODE; lejos de GIT
(`#F03C2E` naranja) porque se distinguen de contexto (dotfiles vs ficheros).

Cambio (rev 7): `.py` → glifo oficial de Python bicolor (drawable del usuario; el original
venía con las dos serpientes en negro y se rebakeó cada path con su color de marca: azul
`#3776AB` arriba y amarillo `#FFD43B` abajo). Primera variante MULTICOLOR: `IconVariant.tint`
devuelve `Color.Unspecified` para PYTHON — `Icon` no crea `ColorFilter` con unspecified y así
los dos rellenos del glifo llegan intactos; la precedencia de tinte de la fila no cambia
(`variant?.tint ?: family?.tint ?: genérico` pasa a través del Unspecified).

- **Lógica en función pura Java + pintar en Kotlin**: la lógica (`familyFor`) va en Java
  como el resto de helpers del módulo y es testeable sin Android (C.3). *Descartada:* mapear
  extensión inline en la Compose (infringiría C.3 y no sería testeable).
- **`enum FileType` + tabla de pintado en la UI** en vez de que el detector devuelva iconos:
  `ImageVector` es un tipo de Compose; devolverlo desde Java arrastraría la dependencia de
  Compose a la lógica pura. El detector queda libre de UI y testeable (C.3). *Descartada:*
  que el detector devuelva directamente el `ImageVector`.
- **NO delegar `FileSortOption.getExtension`** (revierte la decisión inicial del plan): el original
  parsea con `idx >= 0` y devuelve la extensión con punto, así que un fichero oculto
  (`.bashrc`) hoy forma su propio grupo `.bashrc` en el orden, mientras que `FileTypeDetector`
  considera que los ocultos no tienen extensión (caso límite de la spec, solo para iconos).
  Delegar habría cambiado el orden — comportamiento fuera de esta spec (C.2). El test de
  regresión `FileSortOptionExtensionTest` lo confirmó en rojo con la delegación puesta y en
  verde tras revertir: las 3 líneas se quedan en `FileSortOption` y el test documenta el
  contrato actual. *Descartada:* delegar (cambia comportamiento visible del sort).
- **Colores fijos ARGB** en vez de solo roles del tema: 9 familias vs 3-4 roles Material; el
  requisito (distinción entre familias) no se cumple con roles del tema. *Descartada:*
  `primary/secondary/tertiary...` (familias quedan colisionando).
- **Sin iconos de marca ni badges de texto**: alternativa deseable del usuario cuando eligió
  "Material + color por familia"; los badges quedarían como opción futura, fuera de este plan.

- Cambio (rev 2): variantes pedidas por el usuario tras ver la rev 1 (markdown/json/info/
  history/settings/javascript). *Descartada:* meter glifos faltantes como `vectorDrawable`
  XML (los vendidos son Kotlin `ImageVector` generados por el mismo endpoint, que se tintan
  igual vía `Icon(imageVector=…)` y evitan duplicar la conversión a XML).

## Estrategia de tests (`./gradlew test`)

- `FileTypeDetectorTest.java` (JUnit 4, plano): cada familia con al menos una extensión y la
  primeras de la lista RF-2; mayúscula/mixta (`APP.PY`, `IMG.Jpg`); límites: `null`, `""`,
  `Makefile` (sin punto), `file.` (punto final), `app.tar.gz` (última extensión = `gz`),
  `.bashrc` (oculto → null), `Mi documento final.txt` (espacios). Cubre RF-2, RF-3, RF-5 y
  límites de la spec.
- `FileSortOptionExtensionTest.java`: fija el contrato actual (`"a.txt"` → `".txt"`,
  contraste de nombres que difieren solo en mayúsculas de la extensión, nombre sin punto →
  `""`) para que la delegación (T-4) no cambie el orden. Regresión de `FileSortOption`.
- Flujo: tests primero (en rojo: `FileTypeDetector` no existe → falla de compilación),
  luego el código en cada tarea; `./gradlew :filemanager:test` y al final `./gradlew test`
  en verde (C.4). La vista en dispositivo se valida a mano al cerrar la spec (la usabilidad y
  La vista en dispositivo se valida a mano al cerrar la spec (la usabilidad y
  la visibilidad de los colores en pantalla son subjetivas, no son un gate).
- (rev 2) Los tests de variantes entran en el mismo fichero de detector: `readme.md`/`README.md`
  → INFO (y no MARKDOWN), `CHANGELOG.MD` → HISTORY, `a.md`/`a.markdown` → MARKDOWN,
  `a.json` → JSON, `a.properties`/`build.prop` → SETTINGS, `app.js` → JS, `app.ts` → NONE.

## Tareas añadidas (rev 2)

Ver `tasks.md` rev 2 (T1-T4) y rev 3 (T1-T3).
