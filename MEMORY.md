# MEMORY.md — Terminal
Memoria del proyecto entre sesiones. Máximo ~50 líneas: resume o elimina lo que ya no aporte.

## Estado actual
- **Terminal 2.0.0 PUBLICADA** (2026-10-03): tag `v2.0.0` en `master` (= `release/2.0` + merge; se trabaja
  sobre `release/2.0`). Firma local con `key.properties`; APKs debug de la CI + universal a mano.
- **Web** <https://terminal-gnulinux.web.app> (`public/`, sin build; `scripts/update-site.py` pone lo que
  caduca) y **wiki** (<https://terminal-gnulinux-docs.web.app>, `wiki/`); `firebase use` SIEMPRE con
  `-P terminal-gnulinux`. `firebase.json` multitarget (`main` y docs).
- `docs/constitution.md` (6 principios) + flujo SDD (`/sdd-spec → plan → tasks → implement → validate`).
- **Specs 001-008 cerradas**, todas validadas en dispositivo. Detalle en `specs/NNN-*/`.
  - 007: cursor de teclado/scroll del gestor (`FocusCursorPolicy` puro; cursor a -1 al cambiar de
    directorio; scroll mínimo con `ensureItemVisible`, nunca `animateScrollToItem` al entrar).
  - 008 (2026-10-20): topbar del gestor: título = carpeta + recuento (`ListingCounts`), breadcrumb
    clicable (`BreadcrumbPolicy`), cajón de marcadores (favoritos = StringSet en prefs), menú
    «Organizar» (lista/cuadrícula, orden, overrides por carpeta `folder:<path>:*` con
    snapshot/restore) y More completo (RF-9; «Pegar» se conserva al final — decisión abierta). Atajo
    del launcher: `ACTION_OPEN_FILE_MANAGER`(+`EXTRA_…_PATH`) en `TermuxConstants.TERMUX_ACTIVITY`,
    `handleIntent` también en `onCreate` (arranque frío, action se consume), `ShortcutManagerCompat`
    id `fm_<hash>` + `ic_folder`; MAX_SESSIONS respetado. `./gradlew test` verde, APK válido.
- Cambios de la 008 aún sin commit en `master` (sin push).
- Spec 009 (More por ítem + propiedades en pestañas) **aprobada** con plan y
  tareas listos en `specs/009-filemanager-item-menu/` (10 tareas, T1-T10);
  pendiente de implementar. Decisiones: «Rootfs» reutiliza el callback `onEditFile`
  de la app (ya hace nano→vim con `DEBIAN_EDITOR_CANDIDATES`), compresión con
  commons-compress en el propio módulo (`ArchiveCreator`), `PastePolicy` corrige el
  pegado fallido que mostraba «0 pegados». La grid se queda con «Info»→propiedades.
- Imple 009 en marcha: **T1-T4 cerradas** (políticas, ViewModel con paste/
  clipboard-fix, `ArchiveCreator` con tests de disco rojo→verde: zip/tar.xz/7z
  cifrado descifran y rondan contenido; `compressItem` con plan/collision y
  strings de compresión en 3 locales). Gotcha: commons-compress 7z usa
  `closeArchiveEntry()` (no `closeEntry()`); `libs.xz` no estaba en cache local
  (primera ejecución de Gradle con red). Siguiente T5: menú «More» por fila.
- **T5 cerrada**: `ItemMoreMenu` por fila (Icono «More» sustituye a «Info»),
  `addFavoriteFor(dir)` en el VM, parámetro opcional `onCreateFileShortcut` en la
  Screen (cableado en T9), «Propiedades» abre temporalmente DETAILS (T8 lo
  sustituye), «Abrir con» y «Comprimir» llegan en T6/T7. Cero strings nuevos
  (reusados action_cut/copy/delete/rename/share/paste/copy_path…). App compila.
- **T6 cerrada**: «Abrir con…» file-only (symlink → destino): Sistema =
  `onOpenFile(resolved)`; Rootfs = pre-check `EditorPolicy` sobre
  `DEBIAN_EDITOR_CANDIDATES`+`canExecute()` → `onEditFile(resolved)` o snack
  `msg_no_rootfs_editor` (el host decide MAX_SESSIONS/ruta mapeable/chooser).
  Submenú inline expandible en el DropdownMenu. Sin cableado de `onEditFile` el
  elemento Rootfs se oculta. Siguiente T7: diálogo «Comprimir…».
- **T7 cerrada**: `DialogKind.COMPRESS` + `CompressDialog` (nombre por defecto
  del ítem, radios zip/tar.xz/7z, contraseña solo visible con 7z y enmascarada,
  aceptar deshabilitado en vacío); `compressItem` del VM da los mensajes de
  resultado. Glifo `Icons.Default.Archive` verificado en el AAR. Elemento
  «Comprimir…» con `enabled=!busy` en su slot RF-1. Test=literales OK + app
  compila. Siguiente T8: propiedades en dos pestañas.
- **T8 cerrada**: `DialogKind.PROPERTIES` sustituye a DETAILS (también para el
  «Info» del mosaico); `PropertiesDialog` con TabRow Básico/Permisos +
  `PropertyRow` (valor con wrap, «—» en huérfano); VM `loadProperties` (lstat +
  UserUtils + PosixModeText + SELinuxUtils) y `loadFolderCounts` (ocultos
  vigente, sin filtro de búsqueda; guarda por ruta en state.properties). Se
  retiró el caché muerto `folderSizes`/`loadFolderSize`. 15 strings ×3 locales.
  Gotcha: `ListingCounts.counts` devuelve `Counts(folders, files)` (no un Pair).
- **T9 cerrada**: `ACTION_OPEN_FILE` + `EXTRA_OPEN_FILE_PATH` en
  `TermuxConstants.TERMUX_ACTIVITY`; rama en `handleIntent` (consume la
  acción; si el archivo ya no existe → `addFileManagerSession(null)` sin
  error, decisión del spec); `createFileLauncherShortcut` (id
  `fmf_<hash>`, icono `ic_file`) y `openFileWithSystem` vía `FileProvider`
  con falla → toast `error_file_open_failed` (nueva, ×3 locales). Rosca
  `onCreateFileShortcut` (nullable, `((File) -> Unit)?`) en TermuxMainScreen →
  SessionPane (3 sitios) → FileManagerSessionHost → FileManagerScreen;
  `FileManagerComposeActivity` legacy no lo pasa (paridad con el de carpeta).
  Aviso builds: dos builds Gradle a la vez envenenan el IC de Kotlin
  (output sin clases, javac «cannot find symbol»); cura = `rm -rf app/build`
  + recompilar `--no-build-cache`. No lanzar builds paralelos.
- **T10 cerrada**: auditoría de locales del módulo por script (claves
  referidas vs definidas en los 3 ficheros): cazó 28 claves pre-009 que
  faltaban en `values-pt-rBR` (caían al EN en runtime) — rellenadas.
  Literal grep limpio (solo `measureText("X")` y clip label "paths",
  preexistentes y no visibles). `./gradlew test` completo: 638/0.
  **Spec 009 CERRADA** (2026-10-21, Estado en spec.md). Commits
  Conventional Commits de 008+009 y de la rev 8 de iconos Gradle
  (elefante azul) confirmados en `release/2.0` (`093d6b6b`, `0d2c9459`;
  locales-audit incluida en `29f50751`). Nada en push.
  Aviso sesión: Kotlin no soporta literales `0o` octales (usar `0x` o decimal).
- **Spec 006 rev 8 (2026-10-21)**: variante `GRADLE` en el gestor: extensión `gradle`
  (Groovy DSL) y sufijo de nombre exacto `.gradle.kts` (precede a `kts`→KT en
  `iconVariantFor`; el `.kts` suelto sigue Kotlin, `gradle.properties` sigue SETTINGS,
  `gradlew` queda genérico). Glifo del elefante vendido en `FileTypeGlyphs.kt` (pathData
  verbatim del drawable del usuario; viewport 341.71×250 cuadrado-centrado a 341.71×341.71
  con `group(translationY = 45.855)`) y tinte propio azul `#0288D1` (venía en negro, el
  usuario pidió azul). Sin familia (como `kts`); RF-18. Tests rojo→verde; `./gradlew test`
  global verde. **Validada en dispositivo el 2026-10-21 (rev 8); spec 006 cerrada.**
- Selección del gestor = segundo estado de la topbar (2026-10-21): título «n seleccionados»,
  nav-icon «Cerrar»→`clearSelection()` (el cajón no abre durante la selección), acciones
  copiar/cortar/copiar ruta/compartir/editar/borrar en la propia topbar; `BottomAppBar`
  retirada del Scaffold. Sin strings nuevos.

## Learnings vigentes (por qué)
- **Glifos multicolor en Compose**: los rellenos por-path de un `ImageVector` solo sobreviven con
  `tint = Color.Unspecified` (el ColorFilter los aplasta); la fila propaga `variant?.tint`.
- **Glifos con viewport no cuadrado**: cuadrar el viewport y centrar con `group(translationY)` —
  `VectorPainter` escala ejes por separado y en la caja cuadrada de la fila (28/44dp) el arte
  ancho distorce. Delta exacto: `(viewport - arte)/2` (bbox muestreado).
- **Endpoint gstatic `.../Material+Symbols/24dp/<glifo>.kt`** devuelve GZ con código Kotlin del
  `ImageVector` (no SVG; con POST dio 405, usar GET). `markdown`/`file_json` NO están en el AAR
  `material-icons-extended`: verificar clases grep-eando el AAR descomprimido.
- **Semánticas de extensión que difieren NO se unifican sin spec**: `FileSortOption.getExtension`
  (sort) ≠ `FileTypeDetector` (iconos, oculto → genérico); la delegación cambió el orden y el test
  lo cazó (rev de la 006 revertida).
- **Lecturas por fila externa de `TerminalBuffer` no lanzan** (devuelven «nada»); `externalToInternalRow`
  SÍ lanza: validar contra `emulator.mRows` antes de tocar.
- **Un solo dueño de la geometría: la `TerminalView` oculta**; el split sigue con el renderer legado
  (`useNativeRenderer && !isSplitActive`): con canvas nativo queda un pane sin pintar (sin resolver).
- **Nunca escribir estado desde el measure de Compose**; pintar el fondo del pane en cada frame.
- **Dos renderers que repiten un algoritmo: comparar TAMBIÉN los centinelas de estado** (specs 002/003).
- **`BUILD SUCCESSFUL` ≠ APK válido** (agp 9.4.1): `android.enableResourceOptimizations=false` + `unzip -l`.
- **Tests de extra keys = JUnit plano, no Robolectric** (no existe su native runtime en Linux aarch64).
- **Detección de terminal por apps CLI** (`fastfetch`/`neofetch`): por `/proc/<pid>/comm`, truncado a 15
  chars por el FINAL (`rin217.terminal`); fijado con `prctl(PR_SET_NAME)` JNI en
  `TerminalProcessName.applyProcessIdentity()`. (`Os.prctl` no sirve: args sin `char*`.)
- **Quirks del reconocedor de URLs son PARIDAD con el diálogo** (sin `mailto:`, ancho de glifos):
  no arreglar sin spec.
- **Ruido inofensivo de logcat, NO parchear/abrir spec**: aviso Bionic `__bionic_open_tzdata` del ROM;
  `proot warning: can't set tracee registers…` (carrera ESRCH, decidido 2026-10-20).
- **`docs/` y `AGENTS.md` se corrigen contra la fuente** (`gradle/wrapper/`, `libs.versions.toml`…).

## Próximos pasos
- Opcionales: auto-desactivar sticky modifiers, lock por long-press, altura de barra configurable.
