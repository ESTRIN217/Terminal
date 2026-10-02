# MEMORY.md — Terminal
Memoria del proyecto entre sesiones. Máximo ~50 líneas: resume o elimina lo que ya no aporte.

## Estado actual
- Rama `release/2.0`, versión 2.0.0 (versionCode 120). Fase 4 cerrada: firma release local con
  `key.properties` (gitignored) + `key.properties.example`, `CHANGELOG.md`, docs 2.0 al día,
  `119.md`/`120.md` en `docs/archive/`. `enableOnBackInvokedCallback` activo.
- `docs/constitution.md` escrito (6 principios: stack mínimo, spec manda, lógica fuera de la UI,
  tests primero, datos del usuario, idioma por capa). **`specs/` no existe todavía**: el proceso
  real ha sido `docs/roadmap-2.0.md` + `docs/archive/119|120-*.md`.
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
  panes múltiples (foco, IME, flags, geometría, blink).
- Compose 1.13: `ViewConfiguration.current` → `LocalViewConfiguration.current`; y
  `PointerInputChange.positionChange()` hay que importarla (si no, Kotlin resuelve un campo interno).
- `optString("macro", "")` nunca devuelve `null`: no usarlo como bandera de presencia.
- El Java `termux-shared/.../extrakeys/` sigue sin vistas que lo inflen: solo se usan sus
  constantes desde Kotlin.
- Los modificadores sticky nunca se desactivan solos (hay que pulsarlos otra vez).

## Próximos pasos
- **Verificar en dispositivo** con `native_compose_renderer` + split: que los dos paneles pinten, el
  tap cambie el foco, rotar y teclear en ambos, y logcat sin `layout state is not idle` ni
  `updateSize failed`. También el `Popup`, el repeat de flechas y las 4 claves especiales.
- **`docs/` y `AGENTS.md` se corrigen contra la fuente, no de memoria**: `AGENTS.md` arrastraba
  Gradle 9.7.0, AGP 9.3.1 y BOM 2025.08.00 (real: 9.7.1, 9.4.1, 2026.09.00) y sobre todo **mencionaba
  Ktor, una dependencia que no existe** en el proyecto. Antes de fiarse de un número de `AGENTS.md`,
  comprobarlo en `gradle/wrapper/`, `libs.versions.toml` o `gradle.properties`.
- Opcionales: crear `specs/` y migrar el roadmap; auto-desactivar sticky modifiers, lock por
  long-press, altura de barra configurable, y borrar el Java muerto de `extrakeys/`.
