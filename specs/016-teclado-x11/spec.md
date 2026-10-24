# Spec 016 — Teclado completo en pestañas X11 (HW, extra keys, IME)

Estado: implementada y verificada en dispositivo (2026-10-24): teclado HW,
enter/borrar del IME y barra extra keys funcionan en la pestaña X11; touch sin
regresión. Spec cerrada.

## Problema

En las pestañas X11 embebidas (spec 014) no funciona el teclado más allá del
texto committed por el IME:

- **Teclado HW muerto:** el standalone resuelve todas las teclas hardware vía
  `LorieView.setOnKeyListener` → `TouchInputHandler.sendKeyEvent` →
  `InputEventSender.sendKeyEvent`. La pestaña no instala ese listener; su
  `handleKey` (ruta `dispatchKeyEventPreIme`, solo activa cuando
  `hardwareKbdScancodesWorkaround=false`) reenvía directo al nativo sin el
  procesado, y con el workaround por defecto no ocurre nada.
- **Enter/Borrar del teclado virtual muertos:** los IMEs los entregan como
  `InputConnection.sendKeyEvent(KeyEvent)` → `LorieView.dispatchKeyEvent` →
  `super` → sin listener → perdidos. El texto normal llega por
  `commitText`→`sendTextEvent` (ese es el porqué de que las letras sí
  funcionen y solo enter/del mueran).
- **Extra keys muertas:** la barra Compose hace literalmente nada para X11
  (`TermuxMainScreen.kt`: `is TermuxSessionUiModel.X11 -> {}`), y el
  `getExtraKeys()` del surrogate es `null` por diseño de la 014.

## Requisitos

- **RF-01.** Las teclas hardware del guest X11 en pestañas embebidas usan el
  mismo procesador de eventos que el standalone (`InputEventSender` de lorie):
  texto contra teclado event, AltGr, supresión de autorepeat, tracking de
  teclas-release sintéticos, `FLAG_CANCELED`, `preferScancodes`.
- **RF-02.** Enter/Borrar (y el resto de teclas del IME entregadas como
  `KeyEvent`) funcionan en teclados virtuales: el `InputConnection`
  de `LorieView` llega al mismo procesador.
- **RF-03.** La barra extra keys Compose envía a la pestaña X11 activa,
  espejando a `TermuxX11ExtraKeys`: macros de modificadores (CTRL/ALT/SHIFT +
  tecla), teclas de control por `PRIMARY_KEY_CODES_FOR_STRINGS`, texto crudo
  para el resto, y las especiales: `KEYBOARD` (toggle de visibilidad del IME),
  `PASTE` (texto del portapapeles como text event), `DRAWER`/`PREFERENCES`
  (no-op en pestaña), `ZOOM_IN`/`ZOOM_OUT`/`ZOOM_RESET` (zoom del renderer).
- **RF-04.** Sin regresión en el standalone de lorie (checkout intacto) ni en
  el touch de pestañas (XI 18/19/20, ya validado).

## Decisiones

- **Reutilización, no duplicación:** `InputEventSender` se desacopla de
  `MainActivity` con un overload nuevo
  (`InputEventSender(InputStub, Consumer<Boolean>)`); el constructor viejo
  delega y el standalone no toca una línea del flujo. `LorieView` ya es
  `InputStub`, así que la inyección es directa.
- Sin `TouchInputHandler` en pestañas: exige `MainActivity` tipada
  (stylus/captura/prefs) y su reconnect de dispositivos es cosmético para el
  caso teclado; `refreshInputDevices()` del client sigue siendo no-op.
- La traducción extra keys → pasos es **lógica pura** en
  `X11TabController`-adjacente (`X11ExtraKeys`, Kotlin, en
  `com.termux.terminal.x11`) con test JUnit sin Android: los pasos llevan el
  **nombre** de tecla (no keycodes Android) y el mapeo keycode queda en el
  lado Android del app (usa `PRIMARY_KEY_CODES_FOR_STRINGS` de lorie).
- Modificadores sticky de la barra Compose: entran como prefijo de macro
  (`uiState.extraKeysModifiers`), igual semántica que el terminal (los tokens
  aplican a la tecla siguiente y se limpian).
- Filtro win-key de `MainActivity.handleKey` se espeja con la preferencia
  `filterOutWinkey` de lorie.

## No funcionales

- **NFR-01.** Lógica fuera de UI: clasificación/expansión de extra keys con
  tests JUnit sin Android; el wiring es despacho.
- **NFR-02.** Sin dependencias nuevas (regla 1).
- **NFR-03.** Cambios en lorie posibles de compilar sin recompilar el nativo
  (solo Java, módulo `:termux-x11-lorie`); build del bin no se reconstruye.

## Referencias

- `lorie/.../input/InputEventSender.java` (procesador de teclas, dependencia única: `setCapturingEnabled`)
- `lorie/.../MainActivity.java` (`onKey` wiring, `handleKey`, `toggleKeyboardVisibility`)
- `lorie/.../utils/TermuxX11ExtraKeys.java` (semántica extra keys: modificadores por delta, teclas de control, PASTE, zoom)
- `app/.../x11/X11TabController.kt`, `app/.../compose/X11SessionHost.kt`, `app/.../compose/TermuxMainScreen.kt`
- `app/src/test/.../x11/X11ExtraKeysTest.java` (nuevo)
