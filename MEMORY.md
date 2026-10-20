# MEMORY.md — Terminal
Memoria del proyecto entre sesiones. Máximo ~50 líneas: resume o elimina lo que ya no aporte.

## Estado actual
- **Terminal 2.0.0 PUBLICADA** (2026-10-03): tag `v2.0.0` en `master` (= `release/2.0` + merge; se trabaja
  sobre `release/2.0`). APKs debug de la CI + universal firmado a mano; firma local con `key.properties`.
- **Web** <https://terminal-gnulinux.web.app> (`public/`, sin build; `scripts/update-site.py` pone lo que
  caduca) y **wiki** <https://terminal-gnulinux-docs.web.app> (`wiki/`); `firebase.json` multitarget
  (`main` y `terminal-gnulinux-docs`). Ojo: `firebase use` SIEMPRE con `-P terminal-gnulinux`.
- `docs/constitution.md` (6 principios) + flujo SDD (`/sdd-spec → plan → tasks → implement → validate`).
- Specs 001 (kitty placement runs), 002 (blanking de placeholders de copilot) y 003 (URLs de texto
  clicables y subrayadas): implementadas y validadas en dispositivo; cerradas 2026-10-19/20.
- `./gradlew test` en verde (~534 tests) tras la spec 003.

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
- **`docs/` y `AGENTS.md` se corrigen contra la fuente** (`gradle/wrapper/`, `libs.versions.toml`...).
- Los "quirks" del reconocedor de URLs son PARIDAD con el diálogo (sin `mailto:`, "http://a.b" casa solo
  "http://a", glifo ancho dentro rompe el match): no arreglarlos sin abrir spec.

## Próximos pasos
- Opcionales: auto-desactivar sticky modifiers, lock por long-press, altura de barra configurable.
- Proponer a AGENTS.md la regla de "mueve utilidades compartidas a terminal-emulator y delega" (usada en 003).

