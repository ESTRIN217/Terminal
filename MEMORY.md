# MEMORY.md — Terminal
Memoria del proyecto entre sesiones. Máximo ~50 líneas: resume o elimina lo que ya no aporte.

## Estado actual
- **Terminal 2.0.0 PUBLICADA** (2026-10-03): tag `v2.0.0` sobre el merge a `master`, Release con
  los APK debug de la CI + `terminal_2.0.0_universal.apk` firmado (subido a mano, opción C).
  `master` = `release/2.0` + merge; se trabaja sobre `release/2.0`. Firma release local con
  `key.properties` (gitignored) + `key.properties.example`. `enableOnBackInvokedCallback` activo.
- `docs/constitution.md` escrito (6 principios: stack mínimo, spec manda, lógica fuera de la UI,
  tests primero, datos del usuario, idioma por capa). **`specs/` sigue sin existir**: hasta ahora
  el proceso real ha sido `docs/roadmap-2.0.md` + `docs/archive/119|120-*.md`.
- **El flujo SDD ya está en el repo** (commit `d628080a`): `.opencode/agents` (coordinator, planner,
  implementer, reviewer), `.opencode/commands/sdd-*.md`, `.agents/skills/sdd/SKILL.md` y
  `opencode.json` (LSP de Kotlin + MCP de Context7 con la key todavía como placeholder). Para
  trabajar con spec: `/sdd-spec NNN-nombre` → `/sdd-plan` → `/sdd-tasks` → `/sdd-implement` →
  `/sdd-validate`. Solo la constitution se escribe sin pasar por el ciclo.
- Extra keys con paridad de Termux: macros, popup swipe-up, `extra-keys-style`,
  `extra-keys-text-all-caps`, claves especiales y DECCKM.
- `./gradlew test`, `:app:assembleDebug` y `:app:assembleRelease` en verde.

## Decisiones (y por qué)
- El parser Kotlin de extra keys **reutiliza** `ExtraKeysConstants.CONTROL_CHARS_ALIASES` y
  `ExtraKeysInfo.getCharDisplayMapForStyle()` de `termux-shared` en vez de duplicar los mapas. Si se
  borra el paquete Java `extrakeys/`, trasplantarlos a Kotlin antes.
- El popup usa un `pointerInput` propio que **consume el `move`**: cancela tap y long-press de
  `combinedClickable`, así que no hay doble disparo y se conserva TalkBack. Alternativa si el offset
  se desfasara en dispositivo: overlay en la `Box` de `ExtraKeysBar` con `localBoundingBoxOf`.
- `ExtraKeyConfig.isMacro` lo decide el config (`key` ausente + `macro` presente); antes se infería
  de `prefix.isNotEmpty()` y por eso `{macro: ...}` se escribía literal en el terminal.
- El split de macros vive en `TerminalKeyHandler.parseMacro()` (función pura) para testearlo sin
  Compose; `TermuxMainScreen` solo la consume.
- **Tests de extra keys = JUnit plano, no Robolectric**: su native runtime no existe para Linux
  aarch64 (este host) y dejaba los tests en rojo. Añadido `testImplementation(libs.org.json)` +
  `unitTests.isReturnDefaultValues = true`.
- `extra-keys-style=default` cambia el aspecto de la barra (ENTER ↲, TAB ↹, BKSP ⌫, "-" → ―).
- **Un solo dueño de la geometría: la `TerminalView` oculta** (deriva cols/rows, llama
  `updateSize`, publica `paneSize` en `addOnLayoutChangeListener`); el canvas no mide. Motivo: dos
  dueños se peleaban y el tamaño se escribía desde measure.
- **El split solo se renderiza con el `TerminalViewHost` legado**: `TermuxUiState.useNativeCanvasRenderer`
  (= `useNativeRenderer && !isSplitActive`) deja el canvas para pane único. Motivo: con el canvas,
  abrir el split deja el subárbol a medias (un pane sin pintar y sin `VerticalDivider`, comprobado
  píxel a píxel en la grabación del 2026-10-02); el fallback es determinista y barato, y el canvas
  conserva toda la UI nueva en el caso de un solo pane. La **causa raíz sigue sin resolver**.

## Aprendizajes y errores a evitar
- **`BUILD SUCCESSFUL` no significa APK válido**: `optimizeReleaseResources` (AGP 9.4.1) falla en
  silencio y empaqueta el release sin manifest. Mitigado con
  `android.enableResourceOptimizations=false`. Antes de distribuir, comprobar `AndroidManifest.xml`
  con `unzip -l` y borrar `app/build/intermediates/optimized_processed_res/release` (el cache
  guarda el resultado vacío).
- **Nunca escribir estado desde el measure de Compose**: `Modifier.onSizeChanged` y
  `BoxWithConstraints` lo hacen, y con dos panes hay re-entrada → `layout state is not idle before
  measure starts`. Para "mi tamaño": layout de la vista Android u `onGloballyPositioned`.
- **Renderer único de un pane = pintar su fondo en cada frame, siempre**: gatear el paint por un
  tamaño aún no recibido deja el panel en blanco.
- El split se implementó solo para `TerminalViewHost`: un renderer nuevo hay que revisarlo contra
  panes múltiples (foco, IME, flags, geometría, blink). Con el canvas el `Row` del split ni siquiera
  llegaba a componerse, y el fondo del canvas es el mismo `surface` del Scaffold, así que un pane
  sin pintar **no se distingue a ojo**: se deduce por la ausencia del `VerticalDivider`. Trazas por
  pane (`Pane laid out`, `Pane draw`, `Pane grid`) en `logDebug` → hay que subir Ajustes → nivel
  de log.
- Compose 1.13: `ViewConfiguration.current` → `LocalViewConfiguration.current`; y
  `PointerInputChange.positionChange()` hay que importarla (si no, Kotlin resuelve un campo interno).
- `optString("macro", "")` nunca devuelve `null`: no usarlo como bandera de presencia.
- El Java `termux-shared/.../extrakeys/` sigue sin vistas que lo inflen: solo se usan sus
  constantes desde Kotlin.
- Los modificadores sticky nunca se desactivan solos (hay que pulsarlos otra vez).

## Próximos pasos
- **Cada release hay que subir el APK firmado a mano** (opción C): la CI solo adjunta debug. Es el
  precio de no meter el keystore en GitHub; si molesta, la alternativa es la opción B (secrets).
- **Verificar en dispositivo** el fallback del split con `native_compose_renderer` activo: dos panes
  pintados, tap cambia el foco, teclear en ambos y que al cerrar el split vuelva el canvas. Luego,
  el resto de la lista de paridad: `Popup`, repeat de flechas y las 4 claves especiales.
- **Causa raíz del split con canvas nativo** (opcional, sin prisa): reproducir en landscape con el
  log en nivel Debug y las tres trazas por pane. Ojo: la rotación no se puede forzar por adb en
  este XOS (`wm user-rotation lock` no la aplica), hay que rotar el móvil a mano.
- **`docs/` y `AGENTS.md` se corrigen contra la fuente, no de memoria** (regla permanente, candidata
  a subir a `AGENTS.md`): un número de `AGENTS.md` puede venir obsoleto o de una dependencia que ya no
  existe; comprobar en `gradle/wrapper/`, `libs.versions.toml` o `gradle.properties`.
- Opcionales: crear `specs/` y migrar el roadmap; auto-desactivar sticky modifiers, lock por
  long-press, altura de barra configurable, y borrar el Java muerto de `extrakeys/`.
