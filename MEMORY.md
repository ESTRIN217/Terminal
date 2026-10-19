# MEMORY.md — Terminal
Memoria del proyecto entre sesiones. Máximo ~50 líneas: resume o elimina lo que ya no aporte.

## Estado actual
- **Terminal 2.0.0 PUBLICADA** (2026-10-03): tag `v2.0.0` en `master` (= `release/2.0` + merge; se trabaja
  sobre `release/2.0`). Release con los APK debug de la CI + `terminal_2.0.0_universal.apk` firmado a mano
  (opción C). Firma local con `key.properties` (gitignored). `enableOnBackInvokedCallback` activo.
- **Web estática publicada en <https://terminal-gnulinux.web.app>**: `public/` (sin build ni `node_modules`)
  servida por Firebase Hosting con rewrite `**` → `/index.html`. Lo que caduca (versión, APK, hash) lo pone
  `scripts/update-site.py`; regla en `AGENTS.md`. `public/img/` es el único sitio de capturas y banner.
- **Wiki de docs en <https://terminal-gnulinux-docs.web.app>** (2026-10-19): `wiki/` estático (sin build, sin
  JS imprescindible); 7 páginas + `404.html` + `robots.txt`, convertidas de README y `docs/`. Hosting
  multitarget: `firebase.json` → array con targets `main` (public/) y `terminal-gnulinux-docs` (wiki/);
  `.firebaserc` los mapea a sus sitios. Desplegar solo la wiki:
  `firebase deploy --only hosting:terminal-gnulinux-docs`; ambos sitios con `--only hosting`.
- **Ojo con el CLI Firebase**: `firebase use` sin `-P` puede resolver otro proyecto (`secure-notes-502213`):
  pasar SIEMPRE `-P terminal-gnulinux` en scripts/CI de este repo. La wiki no lleva imágenes propias
  (regla: solo `public/img/`): favicon e imágenes apuntan a la URL del sitio principal.
- `docs/constitution.md` (6 principios) y flujo SDD en el repo (`.opencode/agents`, `.agents/skills/sdd`):
  `/sdd-spec` → `/sdd-plan` → `/sdd-tasks` → `/sdd-implement` → `/sdd-validate`. Key de Context7 placeholder.
- `./gradlew test` (523 tests) y `:app:assembleDebug` en verde tras cerrar las specs 001 y 002.

## Spec 001 — kitty placement runs (implementada y validada en dispositivo el 2026-10-19)
- Motivo: las previews de imagen de la TUI de **opencode/OpenTUI** salían descolocadas porque usa `a=p` con
  recorte `x/y/w/h` en píxeles, **varios runs por imagen** (distinto `p=`) y `z<0`; el emulador ignoraba las
  tres cosas. yazi no se rompía (una colocación única con `c=`/`r=`). Parche temporal del cliente:
  `OPENTUI_IMAGE_PROTOCOL=blocks`.
- Piezas: `KittyPlacementGeometry` (funciones puras; `sourcePixelBand` replica a propósito la aritmética del
  viejo `drawImageStrip`, RF-35) + `TerminalImagePlacement` (geometría) y `TerminalImageData` **solo
  payload**; la celda guarda la **clave de colocación** (nunca reutilizada, entradas anuladas sin reindexar);
  identidad `(i=,p=)` en `mKittyPlacementKeys`; `c=`/`r=` celdas y `x/y/w/h` píxeles en `a=p` (`z` con
  signo, `C=` ignorado, cursor inmóvil; en `a=t`/`a=T` siguen siendo celdas); borrados `d=p`/`d=i`/`d=a`/
  `d=c`; tope 256; `shiftPlacements` (scroll/IL/DL/RI, alt por buffer, `resize()` no toca nada) y
  `TerminalImagePainter` compartido por los dos renderers con **dos pasadas por fila** (`skipCells` z>=0,
  `skipBackgroundCells` z<0). Tests en `KittyPlacementRunsTest` (41), `KittyPlacementGeometryTest` e
  `ImagePlacementShiftTest`; fuerza verificada con mutaciones. **Sin fallos de producción.**
- Reglas que no hay que romper: el pintado **recorre colocaciones, no celdas** (por eso `DL` puede dejar una
  fila sin reserva y el rect se pinta igual, y `d=p` deja la celda a 0 sin re-marcar la de debajo); un rect
  que entra en la transcripción **no vuelve al área visible con más scroll** (solo se pinta si la vista se
  desplaza al histórico); `d=i` **borra el payload**, hay que retransmitir antes de recolocar el mismo `i=`;
  el pintor no decodifica ni cachea (bitmap por callback); `U=1`, OSC 1337 y `a=T` no cambian (RF-33/34).
- **Checklist manual:** validado por el usuario el 2026-10-19 (vista de diff de opencode con cuatro previews
  alineadas y completas). Spec en `Estado: implementada`; yazi/OSC 1337/alt-screen no cambiaron (RF-33/34).
  T1–T9 commiteadas en `release/2.0`.
- **T9 (RF-39): previews vacías tras T1–T8.** Causa capturada del TUI real en un PTY: OpenTUI numera las
  imágenes con **ids secuenciales de base alta** (`i=2401244685`, `2401244686`…) y el emulador los leía con
  `Integer.parseInt` → desbordaba a `0`, registraba el payload sin asociarlo al `i=` y cada `a=p` respondía
  `ENOENT` (silenciado por `q=2`) ⇒ caja reservada y vacía en *todas* las previews a la vez. Arreglo:
  `parseKittyId` (`Integer.parseUnsignedInt`) para `i=`/`I=`/`p=` y `Integer.toUnsignedString` en las
  respuestas; `z=` sigue con signo. Regresión en `KittyOpenTuiPreviewTest` (4 casos con la secuencia
  capturada: `a=t,f=100,m=1` troceado → `a=p,…,C=1,z=-1073741823,q=2` → `a=d,d=I`).

## Spec 002 — placeholders de copilot (implementada y validada en dispositivo el 2026-10-19)
- **Causa de los "símbolos" de copilot**: usa `a=T,U=1,c=48,r=14` + rejilla de `U+10EEEE` con diacríticos e id
  en el color de primer plano. Las celdas placeholder que **no decodifican** (fg por defecto) o caen fuera de
  `c×r` se pintaban como texto (`.notdef` + diacríticos). Kitty **nunca** dibuja ese carácter.
- **Arreglo**: `KittyPlaceholderDecoder.UnresolvedCellVisitor` + sobrecarga de `collectRow`; los dos renderers
  meten esas celdas en `skipCells` y las blanquean con el fondo efectivo tras el texto;
  `drawPlaceholderCell`/`drawComposePlaceholderCell` blanquean **antes** de resolver (también limpia una celda
  fuera de rejilla o con la imagen desalojada). Con imágenes desactivadas no cambia nada. Sin dependencias.
- **Regla que no hay que romper**: una celda `U+10EEEE` nunca se pinta como glifo; fuera de `c×r` solo fondo
  (kitty: "solo parte de la imagen"). Tests nuevos: `KittyPlaceholderBlankTest` (4). Total 523.
- **T5 (corrección, primero mal diagnosticado: el decodificador estaba bien en ambos)**: el usuario confirmó
  que el arreglo solo se veía en Compose nativo. Bug legado real en `TerminalRenderer.render()`: tras celdas
  saltadas, `lastRunStartColumn` quedaba en `-1` y el bucle usaba `column == 0` como centinela → flush
  fantasma con `startColumn = -1`, índice de texto obsoleto y `mes = 0` (división/escala ∞) que estiraba los
  glifos placeholder por la fila. Compose lo hace bien (`hasRun` en `forEachRun`); Fix: `lastRunStartColumn
  < 0` al frente de la condición de corte y guardas `lastRunStartColumn >= 0` en los dos flushes. **Lección:
  cuando dos renderers repiten un algoritmo, comparar también los centinelas de estado, no solo las ramas
  nuevas; y no dar "código correcto en ambos" por buena con la sola lectura.** Sin test JUnit posible
  (Canvas), validación en dispositivo. Commit `40c58d09` tras validar el usuario
  ("Perfecto ahora sí quedó", 2026-10-19): copilot limpio en legado y Compose.
  Spec 002 cerrada.

## Decisiones y aprendizajes (y por qué)
- **Las lecturas por fila externa de `TerminalBuffer` no lanzan** (`getHyperlinkAt`/`getImageAt`/`getStyleAt`
  devuelven "nada" fuera de la rejilla; `externalToInternalRow` sí lanza, eso es error de programación):
  validar contra `emulator.mRows` antes de tocar el buffer. Un tap con coordenadas obsoletas reventaba la app.
- **Un solo dueño de la geometría: la `TerminalView` oculta**; el canvas no mide. El split sigue con el
  `TerminalViewHost` legado (`useNativeRenderer && !isSplitActive`) porque con el canvas queda un pane sin
  pintar: **causa raíz sin resolver**.
- **Nunca escribir estado desde el measure de Compose** (`onSizeChanged`/`BoxWithConstraints` → re-entrada
  con dos panes); usar layout de la vista u `onGloballyPositioned`. Y pintar el fondo del pane en cada frame.
- **`BUILD SUCCESSFUL` no significa APK válido**: `optimizeReleaseResources` (AGP 9.4.1) puede empaquetar el
  release sin manifest; mitigado con `android.enableResourceOptimizations=false` y `unzip -l`.
- **Tests de extra keys = JUnit plano, no Robolectric** (su native runtime no existe para Linux aarch64). El
  parser Kotlin reutiliza `ExtraKeysConstants`/`ExtraKeysInfo` de `termux-shared`.
- **`docs/` y `AGENTS.md` se corrigen contra la fuente, no de memoria**: comprobar en `gradle/wrapper/`,
  `libs.versions.toml`, `gradle.properties`.

## Próximos pasos
- **Verificar el fallback del split** con `native_compose_renderer` activo (dos panes pintados, tap cambia el
  foco, teclear en ambos, cerrar el split). Opcional: causa raíz del split con canvas nativo (landscape, log
  Debug y las trazas por pane; la rotación no se puede forzar por adb en este XOS).
- Opcionales: auto-desactivar sticky modifiers, lock por long-press, altura de barra configurable.
