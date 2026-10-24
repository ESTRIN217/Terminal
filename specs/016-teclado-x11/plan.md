# Plan — Spec 016 (teclado completo en pestañas X11)

## Archivos y responsabilidades

| Archivo | Responsabilidad | RF |
|---|---|---|
| `termux-x11/lorie/.../input/InputEventSender.java` | Desacople de `MainActivity`: constructor nuevo `(InputStub, Consumer<Boolean>)`, campo `mActivity` retirado; el constructor con `MainActivity` delega | RF-01 |
| `app/.../x11/X11ExtraKeys.kt` | **Puro:** clasificación de extra keys (especiales vs pulsación) y expansión de macros con modificadores → lista de `KeyStep(name, ctrl, alt, shift, meta)` | RF-03, NFR-01 |
| `app/.../x11/X11TabController.kt` | `InputEventSender` + `setOnKeyListener` en `bindView`; `handleKey` espejo de `MainActivity.handleKey`; sync `preferScancodes`; `sendExtraKey` con aplicador de pasos (modificadores por delta, keycode/text, PASTE, KEYBOARD, zoom) | RF-01..RF-03 |
| `app/.../x11/X11SessionRegistry.kt` | `get(sessionId)` para que la barra Compose resuelva el controller | RF-03 |
| `app/.../compose/TermuxMainScreen.kt` | Branch `is X11` del callback → `sendExtraKey` con prefijo de modificadores sticky | RF-03 |
| `app/src/test/.../x11/X11ExtraKeysTest.java` | JUnit puro (clasificación + macros + modificadores) | NFR-01 |
| Submódulo `termux-x11` | Commit del cambio lorie + pin actualizado en el repo principal | RF-01, decisiones |

## Algoritmo — aplicador de pasos (mirroring de `TermuxX11ExtraKeys`)

1. Clasificar: especial (`KEYBOARD`/`DRAWER`/`PREFERENCES`/`EXIT`/`PASTE`/
   `MOUSE_HELPER`/`STYLUS_HELPER`/`ZOOM_*`) vs pulsación (paso de macro).
2. Para cada paso de pulsación, con deltas sobre el último estado de
   modificadores enviado: `sendKeyEvent(0, CTRL/ALT/SHIFT/META, down/up)`.
3. `PRIMARY_KEY_CODES_FOR_STRINGS[name]` → down+up del keycode; si no está,
   nombre corto → `sendTextEvent(name)` (texto crudo, comportamiento upstream).
4. Especiales en pestaña: `KEYBOARD` → `view.toggleKeyboardVisible()`;
   `PASTE` → portapapeles → `sendTextEvent`; `ZOOM_*` → `adjustRendererZoom`;
   el resto de especiales del standalone → no-op (flag en el clasificador).

## Teclado (Rutas de eventos → `InputEventSender`)

- `bindView`: `sender = InputEventSender(newView) {}` (consumer no-op);
  `newView.setOnKeyListener { _, _, e -> sender.sendKeyEvent(e) }`.
- `handleKey` (pre-IME): filtro win-key por pref → `sender.sendKeyEvent(event)`.
- Sync de `preferScancodes` en `bindView`, `tryConnect` OK y cambio de prefs.
- `detach`: `keyInput = null`, listener se va con la view (que muere).

## Riesgos

- Módulo lorie: **no** tocar `TouchInputHandler`/nativo; solo Java puro.
- `BUILD SUCCESSFUL` no garantiza APK utilizable (lorie aapt2/aarch64): si se
  toca el APK, inspeccionar con unzip (`libXlorie.so` presente).
- El consumer `setCapturingEnabled` no-op en pestaña: ahí no existe captura de
  puntero (uX11 standalone), comportamiento aceptado por diseño.
