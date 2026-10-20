# MEMORY.md — Terminal
Memoria del proyecto entre sesiones. Máximo ~50 líneas: resume o elimina lo que ya no aporte.

## Estado actual
- **Terminal 2.0.0 PUBLICADA** (2026-10-03): tag `v2.0.0` en `master` (= `release/2.0` + merge; se trabaja
  sobre `release/2.0`). APKs debug de la CI + universal firmado a mano; firma local con `key.properties`.
- **Web** <https://terminal-gnulinux.web.app> (`public/`, sin build; `scripts/update-site.py` pone lo que
  caduca) y **wiki** <https://terminal-gnulinux-docs.web.app> (`wiki/`); `firebase.json` multitarget
  (`main` y `terminal-gnulinux-docs`). Ojo: `firebase use` SIEMPRE con `-P terminal-gnulinux`.
- `docs/constitution.md` (6 principios) + flujo SDD (`/sdd-spec → plan → tasks → implement → validate`).
- Specs 001-006: implementadas, validadas en dispositivo y cerradas (kitty placement
  runs, blanking de placeholders, URLs clicables, TZ del guest, identidad de proceso
  `Terminal` en `comm` (fix `fastfetch` "rin217.terminal"), iconos por tipo de archivo
  con 9 familias `FileTypeDetector` + 11 variantes `IconVariant`; 006 cerrada
  2026-10-20). Detalle en `specs/NNN-*/`.
- **Spec 007 (cursor de teclado y scroll del gestor): implementada, PENDIENTE de
  validar en dispositivo.** Bug: abrir carpeta scrolleaba y resaltaba la fila 10
  (cursor heredado entre directorios + `animateScrollToItem` que alinea arriba +
  `LazyColumn` que retiene offset entre listados). Fix: `FocusCursorPolicy` (puro,
  11 tests JUnit), cursor a `-1` al cambiar de directorio (lista limpia, decisión del
  usuario), scroll a fila 1 al cambiar de directorio y scroll mínimo
  (`ensureItemVisible`).
- `./gradlew test` en verde (592 tests) tras la spec 007.
- **Glifos multicolor en Compose (`Icon(imageVector=…)`)**: los rellenos por-path de un
  `ImageVector` solo sobreviven si el `tint` del `Icon` es `Color.Unspecified` (el ColorFilter
  los aplastaría). La fila propaga `variant?.tint` (`Color?`, `Unspecified` vale) y no lo
  consume. Fuente de glifos vendidos: endpoint gstatic `.kt` = GZ con código Kotlin
  (parseado con `addPathNodes`; detalle en spec 006).

## Learnings vigentes (por qué; de las specs 001-007)
- **`animateScrollToItem(i)` alinea la fila i con el borde SUPERIOR del viewport**
  (aunque ya sea visible) y `LazyListState` retiene el índice/offset de scroll al
  cambiar los índices de los ítems: cualquier "entra en otra carpeta y enlista otra
  vez" con ambos daba auto-scroll fantasma. Regla: cursor de teclado en clase pura
  con reset por cambio de directorio (`FocusCursorPolicy`) y scroll con
  `ensureItemVisible` (nudge por `visibleItemsInfo`, `scrollToItem(i, h-V)` para
  dock-lo por abajo) — spec 007.
- **Semánticas de extensión que difieren NO se unifican sin spec**: `FileSortOption.getExtension`
  (sort, `idx>=0`, oculto → su grupo `.name`) ≠ `FileTypeDetector` (iconos, oculto → genérico).
  Intento de delegar el sort al detector cambió el orden de ocultos: el test de regresión lo
  cazó en rojo y se revirtió (spec 006, decisión del plan enmendada).
- **Los URLs `.../Material+Symbols+Outlined/24dp/<glifo>.kt?var=...` de gstatic devuelven GZ con
  el código Kotlin del `ImageVector`**, no SVG (y con GET se cayó un 405 POST). Se vende como
  Kotlin generado (no drawable XML, no fuente variable) — fuente citada en el fichero de glifos.
  `markdown` y `file_json` NO están en el artefacto `material-icons-extended`; verificación por
  grep de las clases en el AAR descomprimido es rápida. (spec 006 rev 2).
- **Las lecturas por fila externa de `TerminalBuffer` no lanzan** (devuelven "nada" fuera de la rejilla;
  `externalToInternalRow` SÍ lanza): validar contra `emulator.mRows` antes de tocar (taps de OSC 8 y del
  scanner de URLs, Spec 003).
- **Un solo dueño de la geometría: la `TerminalView` oculta**; el split sigue con el renderer legado
  (`useNativeRenderer && !isSplitActive`): con canvas nativo queda un pane sin pintar (sin resolver).
- **Nunca escribir estado desde el measure de Compose**; pintar el fondo del pane en cada frame.
- **Cuando dos renderers repiten un algoritmo, comparar TAMBIÉN los centinelas de estado** (Spec 002;
  reaplicado en 003: corte de runs `insideUrl` en canvas y legado).
- **Utilidades que necesitan dos módulos no se duplican ni se pasan por plomería: se mueven a
  `terminal-emulator` y delegan** (regex de URLs: `TermuxUrlUtils` → `TerminalUrlScanner.getUrlPattern()`;
  termux-shared no es visible desde terminal-view → ciclo). Merece regla en AGENTS.md.
- **`BUILD SUCCESSFUL` ≠ APK válido** (agp 9.4.1): `android.enableResourceOptimizations=false` + `unzip -l`.
- **Tests de extra keys = JUnit plano, no Robolectric** (no existe su native runtime en Linux aarch64).
- **El aviso Bionic `__bionic_open_tzdata` de logcat no sale de este código**: es ruido del
  ROM/emulador (la libc busca los datos de zona del dispositivo y no los encuentra); no se puede
  arreglar desde la app, no abrir spec para suprimirlo.
- **`proot warning: can't set tracee registers in workaround: No such process`** (descarga de
  `proot/syscall/syscall.c:330`): carrera ESRCH — el tracee muere entre la lectura y la escritura
  de regs del workaround de syscall cancelada. Ruido inofensivo; decidido 2026-10-20 NO parchearlo,
  no abrir spec por ello.
- **`docs/` y `AGENTS.md` se corrigen contra la fuente** (`gradle/wrapper/`, `libs.versions.toml`...).
- **Detección de terminal por apps CLI (fastfetch/neofetch)**: suben por `/proc/<pid>/comm`; el
  `comm` se trunca a 15 chars por el FINAL → `com.estrin217.terminal` se ve como `rin217.terminal`.
  Fastfetch solo tiene caso especial para `com.termux`; el fix no se puede hacer por env vars: se
  fijó el `comm` con `prctl(PR_SET_NAME)` en `Application.onCreate`
  (`TerminalProcessName.applyProcessIdentity()`, terminal-emulator). No altera el framework
  Android (usa `cmdline`).
- **`Os.prctl` de libcore no sirve para `PR_SET_NAME`** (solo args numéricos, no `char*`): la vía
  es JNI nativo en `termux.c`; escribir `/proc/self/comm` se descartó (SELinux en según qué ROMs).
- Los "quirks" del reconocedor de URLs son PARIDAD con el diálogo (sin `mailto:`, "http://a.b" casa solo
  "http://a", glifo ancho dentro rompe el match): no arreglarlos sin abrir spec.

## Próximos pasos
- Opcionales: auto-desactivar sticky modifiers, lock por long-press, altura de barra configurable.

