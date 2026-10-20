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

## Learnings vigentes (por qué)
- **Glifos multicolor en Compose**: los rellenos por-path de un `ImageVector` solo sobreviven con
  `tint = Color.Unspecified` (el ColorFilter los aplasta); la fila propaga `variant?.tint`.
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
