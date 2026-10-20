# MEMORY.md — Terminal
Memoria del proyecto entre sesiones. Máximo ~50 líneas: resume o elimina lo que ya no aporte.

## Estado actual
- **Terminal 2.0.0 PUBLICADA** (2026-10-03): tag `v2.0.0` en `master` (= `release/2.0` + merge; se trabaja
  sobre `release/2.0`). APKs debug de la CI + universal firmado a mano; firma local con `key.properties`.
- **Web** <https://terminal-gnulinux.web.app> (`public/`, sin build; `scripts/update-site.py` pone lo que
  caduca) y **wiki** <https://terminal-gnulinux-docs.web.app> (`wiki/`); `firebase.json` multitarget
  (`main` y `terminal-gnulinux-docs`). Ojo: `firebase use` SIEMPRE con `-P terminal-gnulinux`.
- `docs/constitution.md` (6 principios) + flujo SDD (`/sdd-spec → plan → tasks → implement → validate`).
- Specs 001 (kitty placement runs), 002 (blanking de placeholders de copilot), 003 (URLs de texto
  clicables y subrayadas), 004 (TZ del guest Debian) y 005 (identidad de proceso `Terminal` en
  `comm`, fix `fastfetch` "rin217.terminal"): implementadas y validadas en dispositivo; 005
  cerrada 2026-10-20.
- `./gradlew test` en verde (~543 tests) tras la spec 005.

## Learnings vigentes (por qué; de las specs 001-003)
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

