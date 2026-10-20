# MEMORY.md — Terminal
Memoria del proyecto entre sesiones. Máximo ~50 líneas: resume o elimina lo que ya no aporte.

## Estado actual
- **Terminal 2.0.0 PUBLICADA** (2026-10-03): tag `v2.0.0` en `master` (= `release/2.0` + merge; se trabaja
  sobre `release/2.0`). APKs debug de la CI + universal firmado a mano; firma local con `key.properties`.
- **Web** <https://terminal-gnulinux.web.app> (`public/`, sin build; `scripts/update-site.py` pone lo que
  caduca) y **wiki** <https://terminal-gnulinux-docs.web.app> (`wiki/`); `firebase.json` multitarget
  (`main` y `terminal-gnulinux-docs`). Ojo: `firebase use` SIEMPRE con `-P terminal-gnulinux`.
- `docs/constitution.md` (6 principios) + flujo SDD (`/sdd-spec → plan → tasks → implement → validate`).
- Spec 001 (kitty placement runs) y 002 (blanking de placeholders de copilot): implementadas, validadas
  en dispositivo y cerradas el 2026-10-19. Commiteadas en `release/2.0`.
- `./gradlew test` en verde (524 tests aprox; +10 del scanner) tras la spec 003 T1–T8.

## Spec 003 — URLs de texto clicables y subrayadas (T1-T8 implementadas, 2026-10-20)
- ¿Qué?: tap sobre un URL impreso lo abre (tras el miss de OSC 8, misma allowlist de
  `isAllowedHyperlinkUri`) y sale subrayado como los OSC 8; un link partido por wrap suave es UN span
  (el tap en cualquier tramo abre la URL completa). En ambos renderers; gate único: el ajuste de
  hipervínculos ya existente. El diálogo More queda intacto.
- Piezas: `TerminalUrlScanner` (terminal-emulator): regex del diálogo MOVIDA ahí (`TermuxUrlUtils` delega,
  one source of truth), renglón lógico por flags de wrap, mapeo celdas↔chars por `findStartOfColumn`
  (glifo ancho = 2 celdas), caché por huella FNV-1a (texto+wrap de las filas; solo rescan si cambia el
  contenido; single UI thread). Compose: `TextRun.insideUrl` + corte de run; canvas fuerza `|| insideUrl`.
  Legado: `lastRunInsideUrl` en los centinelas (Spec-002 lesson aplicada) + `drawTextRun(forceUnderline)`.
  `TerminalView.openHyperlinkAt` añade el probe tras el miss de OSC 8.
- Límites paridad (no bugs): el reconocedor NO acepta `mailto:` (requiere `scheme://`), dominio de una
  etiqueta ("http://a.b" casa solo "http://a"), glifo ancho DENTRO de la URL corta el match. Son los del
  diálogo — no arreglar aquí sin abrir otra spec.
- **Pendiente**: validar en dispositivo con `specs/003-url-tap-links/device-checklist.md` (subrayar/tap/
  wrap/transcript/ajuste OFF/More/ratón) en AMBOS renderers → entonces Estado: implementada y cerrar.
- Commits: `d40eb9ea` (scanner+delegación), `1e852816` (canvas), `ef9e0207` (view),
  `c0fac291` (strings), `9c77f4fb` (spec/checklist/memoria).

## Decisiones y aprendizajes (y por qué)
- **Las lecturas por fila externa de `TerminalBuffer` no lanzan** (devuelven "nada" fuera de la rejilla;
  `externalToInternalRow` SÍ lanza): validar contra `emulator.mRows` antes de tocar. Compartido por
  taps OSC 8 y scanner de URLs.
- **Un solo dueño de la geometría: la `TerminalView` oculta**; el split sigue con el renderer legado
  (`useNativeRenderer && !isSplitActive`): con canvas nativo queda un pane sin pintar (sin resolver).
- **Nunca escribir estado desde el measure de Compose**; pintar el fondo del pane en cada frame.
- **Cuando dos renderers repiten un algoritmo, comparar TAMBIÉN los centinelas de estado** (Spec 002):
  en 003 se trasladó al corte de runs `insideUrl` en ambos.
- **`BUILD SUCCESSFUL` ≠ APK válido** (agp 9.4.1): `android.enableResourceOptimizations=false` + `unzip -l`.
- **Tests de extra keys = JUnit plano, no Robolectric** (no existe su native runtime en Linux aarch64).
- **`docs/` y `AGENTS.md` se corrigen contra la fuente** (`gradle/wrapper/`, `libs.versions.toml`...).
- Los helper-type (regex/urls) replicado en módulos dependientes no: mover a terminal-emulator y delegar
  (termux no alcanza termux-shared → ciclo); así dialog y renderers comparten UNA fuente (003).

## Próximos pasos
- Validar spec 003 en dispositivo (checklist) → cerrar spec + tareas.
- Opcionales: auto-desactivar sticky modifiers, lock por long-press, altura de barra configurable.
- Al cerrar 003: proponer llevar a AGENTS.md la regla "mueve utilidades compartidas a terminal-emulator
  y delega, nunca dupliques entre módulos" (ya la usamos 2 veces).
