# Plan 009 — Gestor de archivos: menú More por ítem y diálogo de propiedades

Spec: `specs/009-filemanager-item-menu/spec.md`. Cubre RF-1 a RF-13 y RNF-1 a RNF-6.

## Archivos y responsabilidades

| Fichero | Cambio | RF |
|---|---|---|
| `filemanager/build.gradle.kts` | Alta de `libs.commons.compress` y `libs.xz` (delta del catálogo citado en RNF-1: ya existían en `libs.versions.toml`, antes solo en `app`) | RNF-1 |
| `filemanager/…/compose/ArchiveNamePolicy.kt` (nuevo) | `enum class ArchiveFormat(ext)` (ZIP/TAR_XZ/SEVEN_Z) y `buildCompressionPlan(dir, name, format, destinationExists)` → `Ready/Invalid/Collision` | RF-7 |
| `filemanager/…/compose/EditorPolicy.kt` (nuevo) | `EditorCandidate(name, available)` + `chooseEditor(candidates)` → primer disponible o `null` | RF-2, RF-13 |
| `filemanager/…/compose/PosixModeText.kt` (nuevo) | `format(mode, permissions)` → `"rwxr-xr-x (644)"`; lo simbólico sale de `FilePermissions.toString()` (termux-shared), el octal se añade aquí | RF-12, RNF-3 |
| `filemanager/…/compose/PastePolicy.kt` (nuevo) | `outcome(movedCount, total, isCut)` → `PasteOutcome(clearClipboard, allFailed)` | RF-4 |
| `filemanager/…/ArchiveCreator.kt` (nuevo, Kotlin, raíz junto a `FileOperationsHelper`) | Escritura en disco con commons-compress: `.zip` (`ZipArchiveOutputStream`), `.tar.xz` (`TarArchiveOutputStream` + `XZCompressorOutputStream`), `.7z` (`SevenZOutputFile(File, char[])` para la contraseña); entradas recursivas del archivo/carpetas | RF-7, RNF-4 |
| `filemanager/…/FileManagerViewModel.kt` | + `cutItem(file)` / `copyItem(file)` (portapapeles de un solo ítem, sin tocar selección); `paste()` reescrito con `PastePolicy` (aviso de error si count==0, limpieza de cortar solo si count>0); `compressItem(file, name, format, password)` en IO con busy + refresh; `addFavoriteFor(dir)` / `removeFavoriteFor(path)` (generaliza los actuales, casados al ítem); `loadProperties(file)` (lstat + UserUtils + SELinuxUtils + recuentos) cacheado en state; `notifyNoRootfsEditor()` (snack bar de RF-2) | RF-3, RF-4, RF-7, RF-10, RF-12 |
| `filemanager/…/FileManagerUiState.kt` | + `properties: FileManagerProperties?` (por ruta) y `folderContents: Map<String, Pair<Int, Int>>` (cache de recuentos, precedente `folderSizes`) | RF-12 |
| `filemanager/…/FileManagerScreen.kt` | Fila de lista: «Info» → icono «More» con `DropdownMenu` por ítem (orden y estados busy de RF-1, «Pegar» condicional, «Abrir con…» solo archivos, favorito según carpeta, callbacks opcionales ocultan elemento); submenú «Abrir con…»; diálogo `COMPRESS` (nombre + 3 formatos + contraseña solo `.7z`); `DialogKind.DETAILS` se sustituye por `PROPERTIES` (dos pestañas + botón aceptar); el mosaico de cuadrícula sigue con «Info» pero ahora abre `PROPERTIES` | RF-1, RF-2, RF-5..RF-13 |
| `app/…/FileManagerSessionHost.kt` | + `onCreateFileShortcut: ((File) -> Unit)? = null` (parámetro opcional; `null` ⇒ ocultar el elemento, precedente 008); «Rootfs» base reusa el callback ya opcional `onEditFile` | RF-2, RF-11, RNF-6 |
| `app/…/TermuxMainScreen.kt` | Propaga `onCreateFileShortcut` hacia la activity | RF-11 |
| `app/…/TermuxComposeActivity.kt` | `createFileLauncherShortcut(file)` (id estable + icono de archivo + intent nuevo); `handleIntent`: rama `ACTION_OPEN_FILE` (extra ruta; abre el archivo con la app Android; si al ejecutar el atajo ya no existe, abre el gestor en su carpeta por defecto sin error, paridad 008 RF-16; consume el action) | RF-11 |
| `termux-shared/…/TermuxConstants.java` | En `TERMUX_ACTIVITY`: `ACTION_OPEN_FILE`, `EXTRA_OPEN_FILE_PATH` | RF-11 |
| `filemanager/res/values{,-es-rVE,-pt-rBR}/strings.xml` | Textos nuevos listados abajo | RNF-2 |

Lectura de datos reutilizada sin duplicar (definición de la spec): `FileAttributes.get(path, false)` (escape `lstat`), `UserUtils.getNameForUid(context, uid)` con fallback al uid/gid numérico, `FilePermissions.toString(attrs.permissions())`, `SELinuxUtils.getFileContext(path)`, `FileOperationsHelper.{getMimeType, readSymlinkTargetRaw, isBrokenSymlink, formatSize, shareFiles, resolveFileForOpen}`.

## Funciones puras (con «hoy» como parámetro)

```kotlin
// ArchiveNamePolicy.kt
enum class ArchiveFormat(val extension: String) { ZIP("zip"), TAR_XZ("tar.xz"), SEVEN_Z("7z") }

sealed interface CompressionPlan {
    data class Ready(val outputFile: File)     // crear sin tocar nada previo
    data class Collision(val outputFile: File) // existía: error, sin sobreescritura (RNF-5)
    data class Invalid(val outputFile: File)   // nombre vacío o con '/'
}
fun buildCompressionPlan(dir: File, name: String, format: ArchiveFormat, destinationExists: Boolean): CompressionPlan
// hoy: no existe compresión (RF-7 nuevo); el nombre se recorta antes, con espacio en
// blanco el plan es Invalid igual que un nombre vacío; solo las 3 extensiones de la enum
```

```kotlin
// EditorPolicy.kt
data class EditorCandidate(val name: String, val available: Boolean)

/** Primer candidato disponible en el orden dado (nano → vim), o null. */
fun chooseEditor(candidates: List<EditorCandidate>): EditorCandidate?
// hoy: el orden nano→vim vive implícito en TermuxConstants.DEBIAN_EDITOR_CANDIDATES
// filtrado por canExecute() en TermuxComposeActivity.openFileInEditor; el menú por ítem
// necesita decidir ANTES de llamar para avisar por snack bar y no abrir nada (RF-2)
```

```kotlin
// PosixModeText.kt
/** Cadena "rwxr-xr-x (644)" a partir del modo POSIX crudo de FileAttributes. */
fun format(mode: Int, permissions: Set<FilePermission>): String
// hoy: FilePermissions.toString(perms) da solo lo simbólico; el octal (mode & 07777)
// se compone aquí. Se reutiliza FilePermissions en lugar de reescribir los bits.
```

```kotlin
// PastePolicy.kt
data class PasteOutcome(val clearClipboard: Boolean, val allFailed: Boolean)

/** RF-4: el portapapeles de cortar solo se limpia si algo se movió; todo fallo → aviso. */
fun outcome(movedCount: Int, total: Int, isCut: Boolean): PasteOutcome
// hoy: paste() limpia el portapapeles de corte SIEMPRE y no avisa cuando count==0
// (defecto vigente aprobado a enmendar en la clarificación)
```

## Algoritmo en pseudocódigo

### RF-1/RF-3: menú por ítem (fila de lista)
```
icono More de la fila -> DropdownMenu anclado a la fila (precedente: menú More topbar):
  "Abrir con…"       solo si el ítem resuelve a archivo; submenú Sistema/Rootfs (RF-2)
  "Cortar"           enabled=!busy; viewModel.cutItem(item) -> setClipboard([item], CUT)
  "Copiar"           enabled=!busy; viewModel.copyItem(item) -> setClipboard([item], COPY)
  "Pegar"            solo si state.hasClipboard; enabled=!busy; viewModel.paste()
  "Eliminar"         enabled=!busy; abre el diálogo DELETE con dialogFile=item (flujo vigente)
  "Renombrar"        enabled=!busy; abre el diálogo RENAME con nameInput=item.name
  "Comprimir…"       enabled=!busy; abre el diálogo COMPRESS (RF-7)
  "Compartir"        onShareFiles([item])
  "Copiar ruta"      copyPathsToClipboard([item])
  "Añadir/quitar favoritos" solo carpetas (RF-10); add/removeFavoriteFor(item)
  "Crear acceso directo" solo si el callback correspondiente está cableado (RF-11)
  "Propiedades"      abre DialogKind.PROPERTIES con dialogFile=item
Enlaces (RF-13): todas las acciones operan sobre el NO resuelto (el File de la propia
  lista); solo "Abrir con…" resuelve con resolveFileForOpen antes de actuar.
La selección múltiple no se toca: ninguna acción del menú limpia selectedPaths.
```

### RF-2: «Abrir con…»
```
Sistema: onOpenFile(resolveForOpen(item))            (flujo open actual del host)
Rootfs:  candidates = DEBIAN_EDITOR_CANDIDATES.map { EditorCandidate(it, File(rootfs+"/usr/bin/"+it).canExecute()) }
         editor = chooseEditor(candidates)
         if editor == null -> viewModel.notifyNoRootfsEditor()  // snack bar, no abre nada
         else            -> onEditFile(resolveForOpen(item))    (cableado de la app)
Sin rootfs, los .canExecute() son todos false (los binarios no existen) -> aviso.
onEditFile == null (host sin cablear) -> elemento Rootfs oculto (precedente 008).
```

### RF-7: comprimir
```
diálogo COMPRESS(item):
  nombre por defecto = item.name; aceptar deshabilitado si el campo queda vacío
  3 radios: .zip / .tar.xz / .7z ; campo contraseña visible solo con .7z
al confirmar:
  exists = File(dir, name + "." + ext).exists()      (IO, en el ViewModel)
  plan = buildCompressionPlan(dir, name, format, exists)
  Invalid / Collision -> snack bar de error y no se crea nada (RNF-5)
  Ready -> busy=true; IO: ArchiveCreator.create(plan.outputFile, item, format, password)
           refresh + snack bar de resultado (creado / fallo); busy=false
```
Cifrado: solo `.7z` acepta contraseña (AES-256 de commons-compress con
`SevenZOutputFile(File, char[])`); `.zip` y `.tar.xz` ignoran el campo.

### RF-12: propiedades (dos pestañas)
```
loadProperties(item) {                                   (IO, desde el diálogo)
  attrs = FileAttributes.get(item.absolutePath, false)   // lstat: del enlace, no del destino
  owner  = UserUtils.getNameForUid(context, attrs.uid()) ?: attrs.uid().toString()
  group  = idem con gid
  mode   = PosixModeText.format(attrs.mode(), attrs.permissions())
  selinux= SELinuxUtils.getFileContext(item.absolutePath)  // null -> «—»
  básico = nombre + carpeta superior (BreadcrumbPolicy.crumbs -> penúltimo crumb amable)
           + MIME (getMimeType) + contenido
           (archivo: formatSize(item.length()); carpeta: listFiles + ListingCounts
            con showHidden vigente, sin filtro de búsqueda, cacheado en folderContents)
           + última modificación (fecha legible por defecto del sistema)
  enlace = destino raw + estado roto (readSymlinkTargetRaw / isBrokenSymlink)
  dato ilegible (excepción/IO) -> «—» en esa fila y el diálogo se abre igual
}
```

### RF-11: atajo de un archivo
```
createFileLauncherShortcut (app):
  id = "fmfile_" + hash(absolutePath); label = nombre
  intent = ACTION_OPEN_FILE + EXTRA_OPEN_FILE_PATH (FLAG_ACTIVITY_NEW_TASK|CLEAR_TASK)
  requestPinShortcut (fallback aviso si no se admite, igual que la carpeta)
handleIntent: rama ACTION_OPEN_FILE ->
  action = null (consumir, paridad ACTION_OPEN_FILE_MANAGER)
  f = File(extra)
  if f.isFile -> abrir con app Android (FileProvider + ACTION_VIEW, flujo del host)
  else        -> addFileManagerSession(null)      // sin error, carpeta por defecto (RF-11)
```

## Interfaz (firmas relevantes)

- `FileManagerScreen(…, onOpenInTerminal, onEditFile, onNewFileManagerTab?`,
  `onCreateShortcut: ((File) -> Unit)?` (carpeta, vigente),
  **+ `onCreateFileShortcut: ((File) -> Unit)? = null`** (archivo, nuevo)).
- `FileManagerSessionHost(…)` añade el mismo parámetro opcional.
- `ViewModel`: `fun cutItem(file: File)`, `fun copyItem(file: File)`,
  `fun compressItem(file: File, name: String, format: ArchiveFormat, password: CharArray?, onDone: (ok: Boolean) -> Unit)`,
  `fun addFavoriteFor(dir: File)`, `fun removeFavoriteFor(path: String)`,
  `fun loadProperties(file: File)`, `fun clearProperties()`,
  `fun notifyNoRootfsEditor()`.
- `ArchiveCreator.create(outputFile: File, source: File, format: ArchiveFormat, password: CharArray?): Boolean`.

## Decisiones (con alternativa descartada)

1. **«Rootfs» reusa el callback ya opcional `onEditFile`.** Su implementación en la
   app ya es exactamente "nano→vim en sesión de rootfs sobre ese archivo"; el módulo
   solo necesita la pureza de `EditorPolicy` para el aviso previo por snack bar.
   Descartado un `onOpenInRootfs` nuevo: duplicaría `openFileInEditor` y su lógica de
   mapeo de rutas/máx. sesiones. RNF-6 se cumple igual: `null` ⇒ ocultar el elemento.
2. **La escritura de la compresión vive en el módulo (`ArchiveCreator`, usada por su
   `ViewModel`).** El módulo ya es dueño de sus operaciones de disco
   (`FileOperationsHelper`), y el diálogo lo necesita con un resultado directo;
   mantenerla dentro deja el cableado de la app a cero para este RF.
   Descartado subirlo a la app tras un callback `onCompress`: haría a la app dueña de
   datos del gestor (contraviene RNF-6 de la 008) y obligaría a exponer el tipo de
   formato en el límite host/biblioteca sin ganancia.
3. **`zip`/`tar.xz`/`7z` con una sola API (commons-compress).** Descartado
   mezclar `java.util.zip` para zip: dos APIs para lo mismo y `java.util.zip` no
   cifra; zip4j queda descartado en RNF-1 por ser una dependencia nueva.
4. **Octal añadido sobre `FilePermissions.toString()`.** Descartado reimplementar
   los 9 bits simbólicos: duplicaría el helper vigente de termux-shared.
5. **Permisos con `FileAttributes.get(path, false)` (lstat).** Descartado
   `Files.readAttributes` nio: sigue enlaces (contraviene RF-12/RF-13) y no da
   uid/gid crudos para `UserUtils`.
6. **`DialogKind.PROPERTIES` sustituye a `DETAILS`.** Las acciones del diálogo de
   detalles (abrir/copiar ruta/renombrar/editar) pasan al menú por ítem o
   desaparecen (editar, cubierta por «Abrir con › Rootfs»); el mosaico de cuadrícula
   redirige su «Info» al nuevo diálogo (fuera de alcance de menú, en la spec).
7. **Menú por ítem como `DropdownMenu` anclado a la fila.** Descartado
   `ModalBottomSheet`: demasiado aparato para cada fila y rompe el precedente
   visual del menú More de la topbar.
8. **Recuentos de «contenido» cacheados en `folderContents`** (precedente
   `folderSizes`); se llenan al abrir propiedades de una carpeta y respetan el
   ajuste de ocultos vigente, no el filtro de búsqueda.

## Estrategia de tests (`./gradlew test`)

- `ArchiveNamePolicyTest.kt` (JUnit): nombre normal → Ready; vacío y solo espacios →
  Invalid; con `/` → Invalid; `destinationExists=true` → Collision (sin exclusión);
  extensión correcta por formato (`zip`/`tar.xz`/`7z`); nombre con puntos (`a.tar`).
- `EditorPolicyTest.kt`: nano disponible → nano; nano no y vim sí → vim; ninguno →
  null; lista vacía → null; dos disponibles respeta el orden dado.
- `PosixModeTextTest.kt`: 0644 → `rw-r--r-- (644)`; 0755 → `rwxr-xr-x (755)`; 0000 →
  `--------- (0)`; 0777; la parte simbólica generada por `FilePermissions.toString`.
- `PastePolicyTest.kt`: todo se movió + cortar → limpia, sin error; nada se movió →
  error sin limpiar aunque sea cortar; parcial (1 de 2) → limpia si cortar; copiar
  nunca limpia aunque falle todo... (copiar no limpia jamás el portapapeles de hoy).
- Compose y ViewModel sin test (coherente con el repo: no hay Robolectric en CI;
  los flujos IO/UI se validan en dispositivo según los criterios de finalización).
- Delta del catálogo citado: este plan (RNF-1) ya documenta la alta en
  `filemanager/build.gradle.kts` de `libs.commons.compress` + `libs.xz`.

## Strings nuevos (los tres locales, EN base)

`action_open_with`, `open_with_system`, `open_with_rootfs`,
`action_item_cut`, `action_item_copy`, `action_paste`, (reusar
`action_delete`/`action_rename`/`action_copy_path`/`action_share` donde ya existen),
`action_compress` ("Compress…"), `compress_name_label`, `compress_format_zip`,
`compress_format_tarxz`, `compress_format_7z`, `compress_password_label`,
`compress_password_hint`, `msg_compress_created`, `msg_compress_failed`,
`msg_compress_collision`, `msg_compress_invalid_name`, `msg_paste_failed`,
`msg_no_rootfs_editor`, `properties_title` ("Properties of %1$s"),
`properties_tab_basic`, `properties_tab_permissions`, `properties_owner`,
`properties_group`, `properties_mode`, `properties_selinux`,
`properties_last_modified`, `properties_parent` ("Parent folder"),
`properties_link_target`, `properties_broken_assert`, `properties_content`,
`properties_uses_dash` ("—"). Los textos exactos se pulen al volcar a cada locale
sin duplicar keys presentes.
