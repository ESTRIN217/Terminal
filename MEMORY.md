# MEMORY.md — Terminal
Memoria del proyecto entre sesiones. Máximo ~50 líneas: resume o elimina lo que ya no aporte.

## Estado actual
- **Rama `3.0` creada** (2026-10-21, desde master). **Spec 010 aprobada**:
  htop/proc-stat — overlay en proot con snapshot vivo (carga propia
  `/proc/<pid>/stat`, SystemClock→btime, MemoryInfo→RAM, loadavg si legible;
  nada de muestras fijas tipo Termux; per-core reparto uniforme; cadencia 1 s).
  `specs/010-procstat-htop/{spec,plan,tasks}.md` listos para implementar
  (T1–T7). Piezas: `ProcStatOverlayLibrary` (pura) + `ProcStatOverlaySampler`
  (glue Android) + overload `buildProotCommand(overlayBinds)`.
- **Terminal 2.1.0 PUBLICADA** (2026-10-21): tag `v2.1.0` en master
  (`e0eb3c30` merge de release/2.0 + `a1d5b574` sitio). APK universal firmado
  subido + sha256 (asset `terminal_2.1.0_sha256sums`, hash 1f15a173…); CI
  adjunta los `+github-debug`. Web y wiki redesplegadas (update-site.py +
  `firebase deploy --only hosting:main,hosting:terminal-gnulinux-docs`).
  Ambas ramas sincronizadas al mismo commit; se sigue en `master` por
  petición del usuario. Firma local con `key.properties`.
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
    snapshot/restore) y More completo (RF-9; «Pegar» al final por diseño, decisión cerrada
    2026-10-21). Atajo
    del launcher: `ACTION_OPEN_FILE_MANAGER`(+`EXTRA_…_PATH`) en `TermuxConstants.TERMUX_ACTIVITY`,
    `handleIntent` también en `onCreate` (arranque frío, action se consume), `ShortcutManagerCompat`
    id `fm_<hash>` + `ic_folder`; MAX_SESSIONS respetado. `./gradlew test` verde, APK válido.
- Spec 009 (More por ítem + propiedades en pestañas): implementada completa
  (T1-T10), decidida: «Rootfs» reutiliza `onEditFile` de la app
  (nano→vim con `DEBIAN_EDITOR_CANDIDATES`), compresión con commons-compress
  en el propio módulo (`ArchiveCreator`), `PastePolicy` corrige el pegado
  fallido que mostraba «0 pegados». La grid se queda con «Info»→propiedades.
  Gotchas: commons-compress 7z usa `closeArchiveEntry()` (no `closeEntry()`).
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
- **Candidatura 2.1.0 auditada (2026-10-21)**: 41 commits desde `v2.0.0`
  (specs 002-009 + tangente selección), todo aditivo, sin rupturas de API →
  bump minor: 2.0.0→2.1.0. `./gradlew test` verde a fecha de hoy. **Hecho:
  `versionCode 121` + `versionName 2.1.0` en app/build.gradle.kts y entrada
  `## [2.1.0] — 2026-10-21` en CHANGELOG.md** (`:app:versionName` imprime
  2.1.0). Commits en `release/2.0` sin push: cierre «Pegar» por diseño
  (`814a491f`), docs 2.1.0 (`e00ebef6`), bump+changelog (`3e945a5e`).
  Pendiente para el release: push de
  `release/2.0` (34+ commits sin publicar), release build+tag en master,
  update-site.py. NOTA: la fecha del changelog es la del bump; si el corte
  tarda, ajustarla a la fecha real del tag.
- **Docs actualizados para 2.1.0 (2026-10-21)**: README (características URL
  impresas + gestor ampliado), `docs/hipervinculos-y-seleccionar-url.md`
  (sección nueva «URLs impresas tocables (spec 003)» + relación actualizada),
  wiki `hipervinculos.html` (sección nueva + tabla «¿Cuál usar?»),
  `wiki/uso-terminal.html` (bullet gestor), `docs/proot-debian-arm64-plan.md`
  (estado actual 2.1.0), `docs/en/index.md` (wiki apunta al hosting, no al
  GitHub wiki). Sin tocar `docs/roadmap-2.0.md` (histórico completo) ni
  `public/` (lo escribe update-site.py).
- Selección del gestor = segundo estado de la topbar (2026-10-21): título «n seleccionados»,
  nav-icon «Cerrar»→`clearSelection()` (el cajón no abre durante la selección), acciones
  copiar/cortar/copiar ruta/compartir/editar/borrar en la propia topbar; `BottomAppBar`
  retirada del Scaffold. Sin strings nuevos. **Validada en dispositivo (2026-10-21);
  commit `e118ca78` en `release/2.0`, sin push. Fuera de spec (tangent de la 008).**

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
