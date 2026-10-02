# Plan de Rediseño: Termux Main Screen + Terminal View

## Resumen Ejecutivo

**Objetivo:** Migrar la UI de Java/XML Views → Kotlin + Jetpack Compose MD3 Expressive, manteniendo el PTY nativo Java+JNI y el renderizado nativo de `TerminalView`.

**Alcance:** UI Compose (tabs, drawer, extra keys, MVVM). El terminal se renderiza con el `TerminalView` existente envuelto vía `AndroidView` — no se reimplementa emulación ni rendering.

**Estado:** Fases 1-8 completadas (`TermuxComposeActivity` es el launcher). **Decisión reversada (2026-08-21):** tras investigación, el renderer xterm.js/WebView se descarta en favor de reusar `TerminalView` vía `AndroidView`. Ver **Fase 9**. Las secciones de xterm.js/WebView (Fases 2 y 5, partes de 3-4) se conservan como registro histórico marcadas **SUPERSEDED**.

---

## Arquitectura Actual vs Implementada

**Flujo clásico (TerminalView.java — vuelve a ser el renderer activo con Fase 9):**
```
PTY (JNI) → ByteQueue → TerminalEmulator (Java VT100) → TerminalSessionClient.onTextChanged()
  → TerminalView.onScreenUpdated() → invalidate() → onDraw() → TerminalRenderer.render(Canvas)
```

**Flujo implementado previamente (WebView + xterm.js) — SUPERSEDED por Fase 9:**
```
PTY (JNI) → ByteQueue → TerminalSessionRawDataListener.onRawData()  ← NUEVO callback
  → TerminalBridge → WebView.evaluateJavascript("TermuxTerminal.write(data)") → xterm.js rendering

  TerminalEmulator.append() + notifyScreenUpdate() aún se ejecutan para fallback

User input:
  xterm.js onData → window.Android.onTerminalInput(data)
    → TerminalBridge.onTerminalInput() → session.write(bytes) → PTY

  Hardware keyboard:
    Activity.onKeyDown → handleVirtualKeys(Volume→Ctrl/Fn) → handleCtrlAltShortcuts
    Compose onPreviewKeyEvent → TerminalKeyHandler.getKeyCode → session.write(sequence)

  Extra keys bar:
    ExtraKeysBar click → ExtraKeysCallback → TerminalKeyHandler.getKeySequence → session.write(sequence)
```

**Flujo objetivo (Fase 9 — TerminalView vía AndroidView):**
```
PTY (JNI) → ByteQueue → TerminalEmulator (Java VT100) → ComposeTerminalSessionClient.onTextChanged()
  → TerminalViewRegistry.activeView.onScreenUpdated() → invalidate() → onDraw() → TerminalRenderer.render(Canvas)

User input:
  IME / hardware keys → TerminalView (onCreateInputConnection / onKeyDown: kcm, acentos combinados,
    workarounds Gboard/Samsung, pinch-zoom, selección Material, mouse tracking — ya implementado en el View)
  Extra keys bar:
    ExtraKeysBar click → ExtraKeysCallback → TerminalKeyHandler.getKeySequence → session.write(sequence)
```

**Decisión original (reversada):** Se agregó `TerminalSessionRawDataListener` a `TerminalSession.java` para interceptar bytes raw antes de VT100 processing. El emulator Java aún procesa los datos (para fallback), pero xterm.js recibe los bytes crudos que necesita para renderizar escape sequences correctamente.

**Nueva decisión (2026-08-21):** Revertir el cambio en `TerminalSession.java`, eliminar la ruta xterm.js/WebView y renderizar con `TerminalView` nativo dentro de la UI Compose. Motivos: corrupción UTF-8 en chunks del PTY, doble emulación (Java + JS), memoria de Chromium por sesión, regresión del hardening de input ya existente en el View, fragmentación de System WebView y superficie de ataque innecesaria (`addJavascriptInterface` + `file://`). Detalle completo en Fase 9.

---

## Archivos Creados / Modificados

### Nuevos (archivos Kotlin + assets)

```
app/src/main/assets/terminal/
  index.html                          ← xterm.js v5.5.0 via CDN + bridge JS + reconfigure() + setFont()

app/src/main/java/com/termux/
  TermuxComposeActivity.kt            ← Activity principal (launcher, session persistence, theme/font/IME)

  terminal/bridge/
    TerminalBridge.kt                  ← @JavascriptInterface bidireccional
    TerminalWebConfig.kt               ← Config xterm.js (theme, font, cursor) + toJsConfig()
    TerminalKeyHandler.kt              ← Mapeo de key names/android KeyCodes → escape sequences

  terminal/compose/
    TermuxUiState.kt                   ← StateFlow<TermuxUiState> con sessions, flags, theme, extraKeysConfig, fontFamily, fontBase64
    TermuxViewModel.kt                 ← AndroidViewModel con add/remove/switch/toggle/setExtraKeysConfig/setTerminalTheme/setCustomFont
    TermuxMainScreen.kt                ← Scaffold + drawer + tabs + WebView + extra keys + onPreviewKeyEvent + IME tracking
    SessionTabs.kt                     ← ScrollableTabRow MD3 con close buttons
    NavigationDrawerContent.kt         ← ModalNavigationDrawer MD3
    ExtraKeysComposable.kt             ← CTRL/ALT/SHIFT/FN + nav keys + arrows + long-press repeat support
    ExtraKeysConfig.kt                 ← Parser de JSON extra-keys de termux.properties → data classes
    TerminalEmulatorWebView.kt         ← AndroidView wrapper para WebView+xterm.js (per-session, resize, font inject)
```

### Modificados (archivos existentes)

```
build.gradle.kts (root)                ← Agregado: kotlin.compose + kotlin.serialization plugins
app/build.gradle.kts                   ← Agregado: compose plugin, compose deps, serialization
app/src/main/AndroidManifest.xml       ← TermuxComposeActivity es ahora el launcher; shortcuts.xml actualizado
app/src/main/res/xml/shortcuts.xml     ← targetClass cambiado a TermuxComposeActivity
terminal-emulator/.../TerminalSession.java  ← Agregado: TerminalSessionRawDataListener interface + field
```

### Eliminados

```
app/src/main/java/com/termux/terminal/compose/SessionTabRow.kt  ← Eliminado (prototipo huérfano sin package)
```

### NO modificados (intencional)

```
terminal-view/build.gradle.kts         ← Sin cambios (AGP 9.x maneja Kotlin built-in)
termux-shared/build.gradle.kts         ← Sin cambios
TerminalView.java                      ← Se mantiene como fallback
```

---

## Fase 1: Infraestructura (Build System + Kotlin) — COMPLETADA

### 1.1 Habilitar Compose en módulos

**Decisiones tomadas:**
- AGP 9.3.1 tiene Kotlin built-in → el plugin `org.jetbrains.kotlin.android` **no es necesario** (falla con error explícito si se agrega)
- Solo se necesita `org.jetbrains.kotlin.plugin.compose` y `org.jetbrains.kotlin.plugin.serialization`
- `terminal-view` y `termux-shared` no necesitan plugins Kotlin explícitos

**`build.gradle.kts` (root):**
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

**`app/build.gradle.kts`:**
```kotlin
plugins {
    id("com.android.application")
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
```

**Dependencies agregadas:**
```kotlin
implementation(platform(libs.compose.bom))
implementation(libs.compose.ui)
implementation(libs.compose.material3)
implementation(libs.compose.material.icons)
implementation(libs.compose.foundation)
implementation(libs.compose.runtime)
implementation(libs.androidx.lifecycle.viewmodel.compose)
implementation(libs.androidx.lifecycle.runtime.compose)
implementation(libs.androidx.activity.compose)
implementation(libs.kotlinx.coroutines.android)
```

---

## Fase 2: xterm.js Rendering Layer — COMPLETADA · SUPERSEDED POR FASE 9

### 2.1 `assets/terminal/index.html`

Página HTML auto-contenida con dependencias bundeadas localmente:
- `@xterm/xterm@5.5.0` — terminal core (`xterm.min.js`)
- `@xterm/addon-fit@0.10.0` — auto-resize (`addon-fit.min.js`)
- `@xterm/addon-webgl@0.18.0` — GPU rendering (`addon-webgl.min.js`)
- `@xterm/addon-search@0.15.0` — search (`addon-search.min.js`)
- `@xterm/addon-unicode11@0.8.0` — unicode support (`addon-unicode11.min.js`)
- `@xterm/xterm@5.5.0/css/xterm.min.css` — estilos (`xterm.min.css`)

Bridge global `window.TermuxTerminal` con métodos: `write()`, `resize()`, `fit()`, `focus()`, `clear()`, `scrollToBottom()`, `selectAll()`, `getSelection()`, `reconfigure()`, `setFont()`.

**`reconfigure()` (NUEVO):** Permite actualizar theme, fontSize, cursorBlink, cursorStyle, fontFamily, scrollback, tabStopWidth dinámicamente después de la carga inicial. Llama a `fitAddon.fit()` después del cambio.

**`setFont()` (NUEVO):** Inyecta `@font-face` CSS con base64-encoded TTF font data para fuentes personalizadas.

### 2.2 `TerminalEmulatorWebView.kt`

- `AndroidView` envolviendo `WebView`
- WebView: JS enabled, DOM storage, `addJavascriptInterface(TerminalBridge(...), "Android")`
- Carga `file:///android_asset/terminal/index.html`
- Pending writes con buffer de 16ms interval para flush al WebView
- Escape de caracteres especiales (`\n`, `\r`, `\x1B`, `'`, `\`)
- **Per-session state** via `SessionState` class (eliminado estado global mutable `pendingWebView`/`pendingWrites`)
- Aplica config dinámica (theme, fontSize) vía `TermuxTerminal.reconfigure()` después de `onPageFinished`
- **Resize handler:** `addOnLayoutChangeListener` detecta cambios de tamaño del WebView y llama `TermuxTerminal.fit()`
- **Font injection:** Inyecta `@font-face` CSS vía `TermuxTerminal.setFont()` cuando la config de fuente cambia
- **Smart update:** Solo re-inyecta fuente si `fontFamily:fontBase64Length` cambió (evita re-inyección redundante en recomposition)

### 2.3 `TerminalSession.java` — Raw Data Listener

**Cambio mínimo y no-breaking:**
```java
// Nuevo campo
private TerminalSessionRawDataListener mRawDataListener;

// Nuevo setter
public void setRawDataListener(TerminalSessionRawDataListener listener)

// Nuevo interface
public interface TerminalSessionRawDataListener {
    void onRawData(byte[] data, int length);
}

// En MainThreadHandler.handleMessage(), antes de mEmulator.append():
if (mRawDataListener != null) {
    mRawDataListener.onRawData(mReceiveBuffer, bytesRead);
}
```

---

## Fase 3: Compose UI — COMPLETADA

### 3.1 `TermuxComposeActivity.kt`

- Extiende `ComponentActivity` (no AppCompatActivity — Compose no necesita AppCompat)
- Implementa `ServiceConnection` para binding a `TermuxService`
- Usa `ViewModelProvider` para obtener `TermuxViewModel`
- `setContent {}` con MaterialTheme + Surface + TermuxMainScreen
- Bootstrap: `TermuxInstaller.setupBootstrapIfNeeded()` → `createTermuxSession()`
- **Session persistence:** `onSaveInstanceState()` guarda `activity_recreated=true`; `onStop()` guarda session handle en `SharedPreferences`; `onServiceConnected()` restaura sesión activa via `getTerminalSessionForHandle()`
- **Theme loading:** `loadTerminalColors()` lee `~/.termux/colors.properties`, parsea colores X11, construye `TerminalTheme`, lo pasa al ViewModel para xterm.js
- **Font loading:** `loadCustomFont()` lee `~/.termux/font.ttf`, base64 encode, pasa al ViewModel para inyectar `@font-face` CSS en WebView
- **Volume keys:** `handleVirtualKeys()` mapea Volume Down→Ctrl, Volume Up→Fn
- **Ctrl+Alt shortcuts:** `handleCtrlAltShortcuts()` para next/prev session, toggle keyboard, paste, close session, adjust font size, switch to session N
- **Extra keys config:** `loadExtraKeysConfig()` lee JSON de `termux.properties` y lo pasa al ViewModel
- **Fullscreen:** Aplica `FLAG_FULLSCREEN` si `mProperties.isUsingFullScreen()`
- **onNewIntent:** Maneja `ACTION_RUN` para shortcuts con `EXTRA_FAILSAFE_SESSION`
- **Broadcast:** Envía `BROADCAST_TERMUX_OPENED` en `onServiceConnected`

### 3.2 `TermuxUiState.kt` + `TermuxViewModel.kt`

```kotlin
data class TermuxUiState(
    val sessions: List<TerminalSessionUiModel>,
    val activeSessionIndex: Int,
    val isDrawerOpen: Boolean,
    val isExtraKeysVisible: Boolean,
    val isSoftKeyboardVisible: Boolean,
    val fontSize: Float,
    val fontFamily: String,
    val fontBase64: String?,
    val extraKeysConfig: ExtraKeysConfig,
    val terminalTheme: TerminalTheme
)
```

ViewModel methods: `addSession()`, `removeSession()`, `switchSession()`, `nextSession()`, `previousSession()`, `toggleDrawer()`, `setDrawerOpen()`, `toggleExtraKeys()`, `setExtraKeysVisible()`, `setSoftKeyboardVisible()`, `setFontSize()`, `updateSessionTitle()`, `renameSession()`, `setExtraKeysConfig()`, `setTerminalTheme()`, `setCustomFont()`.

### 3.3 Layout `TermuxMainScreen.kt`

```
TermuxMainScreen
├── TermuxNavigationDrawer (ModalNavigationDrawer MD3)
│   ├── File Manager (NavigationDrawerItem)
│   ├── Settings (NavigationDrawerItem)
│   └── Toggle Keyboard (NavigationDrawerItem)
├── Scaffold (+ onPreviewKeyEvent para hardware keyboard)
│   ├── TopBar: SessionTabs (ScrollableTabRow)
│   │   └── Cada tab = session name + IconButton(close)
│   │   └── Último tab = Add icon
│   ├── Body: Box(weight(1f))
│   │   └── TerminalEmulatorWebView (solo sesión activa visible)
│   ├── Bottom: ExtraKeysBar (si visible, con config desde termux.properties)
│   │   └── Filas dinámicas parseadas de JSON extra-keys
│   └── FAB: New Session (+)
```

### 3.4 Extra Keys como Composable (`ExtraKeysComposable.kt` + `ExtraKeysConfig.kt`)

- `ExtraKeysBar` recibe `ExtraKeysConfig` parseado desde `termux.properties`
- Layout dinámico: filas y botones se generan del JSON, no hardcoded
- Modifiers (CTRL/ALT/SHIFT/FN) toggle con `remember { mutableStateMapOf() }`
- Auto-unset modifiers after key press
- Long-press repeat support para teclas: UP, DOWN, LEFT, RIGHT, BKSP, DEL, PGUP, PGDN
- `ExtraKeysConfig.parse()` resuelve aliases (ESCAPE→ESC, CONTROL→CTRL, PAGEUP→PGUP, etc.)
- `ExtraKeyConfig` data class con: key, display, isMacro, popup, isRepetitive, isModifier

### 3.5 `TerminalKeyHandler.kt` (NUEVO)

- `getKeyCode()`: Mapea Android `KeyEvent` → escape sequence via `KeyHandler.getCode()`
- `getKeySequence()`: Mapea nombre de tecla extra ("UP", "ESC", etc.) → escape sequence
- `applyCtrl()`: Aplica modificador Ctrl a codepoints (a-z → 1-26, etc.)
- `getFnKeyCode()`: Mapea Fn + letter → keyCode virtual (W→UP, A→LEFT, etc.)
- `getFnCodePoint()`: Mapea Fn + letter → codepoint literal (H→~, U→_, etc.)
- Constantes `KEYMOD_CTRL`, `KEYMOD_ALT`, `KEYMOD_SHIFT` alineadas con `KeyHandler.java`

---

## Fase 4: Integración y Wiring — COMPLETADA

### 4.1 Estrategia de coexistencia

```
app/src/main/java/com/termux/
  TermuxComposeActivity.kt         ← Activity principal (launcher, Compose)
  TermuxActivity.java              ← existente, se mantiene como fallback sin launcher
  terminal/
    bridge/
      TerminalBridge.kt            ← @JavascriptInterface
      TerminalWebConfig.kt         ← xterm.js config data classes + toJsConfig()
      TerminalKeyHandler.kt        ← Mapeo de teclas → escape sequences
    compose/
      TermuxMainScreen.kt          ← Scaffold principal + onPreviewKeyEvent + IME detection
      TermuxViewModel.kt           ← MVVM state management
      TermuxUiState.kt             ← Data classes de estado + theme + extraKeysConfig + fontFamily
      ExtraKeysComposable.kt       ← Control keys dinámicos + long-press repeat
      ExtraKeysConfig.kt           ← Parser JSON de termux.properties
      SessionTabs.kt               ← TabRow de sesiones
      NavigationDrawerContent.kt   ← Drawer MD3
      TerminalEmulatorWebView.kt   ← WebView+xterm.js wrapper (per-session, resize, font inject)
```

### 4.2 Puntos de integración implementados

| Componente actual | Reemplazo Compose | Estado |
|---|---|---|
| `activity_termux.xml` | `TermuxMainScreen.kt` | Implementado |
| `TabLayout` | `ScrollableTabRow` | Implementado |
| `DrawerLayout` | `ModalNavigationDrawer` | Implementado |
| `WebView + xterm.js` | `TerminalView` vía `AndroidView` | Fase 9 (pendiente) |
| `ExtraKeysView` (GridLayout) | `ExtraKeysBar` (Row dinámico) | Implementado (sin popup swipe-up) |
| `TermuxActivityRootView` | No needed | Compose insets con `imePadding()` |
| `TerminalToolbarViewPager` | Bottom area in Scaffold | Inline en Scaffold |
| `TermuxTerminalViewClient` (keyboard) | `TerminalView` nativo (`onKeyDown`/InputConnection) + `ComposeTerminalViewClient` | Fase 9 (pendiente) |
| `TermuxTerminalSessionActivityClient` | `ComposeTerminalSessionClient` + ViewModel callbacks | Fase 9 (pendiente) |

### 4.3 Manejo de teclado

- Soft keyboard: `Modifier.imePadding()` en Scaffold
- Hardware keyboard: `Modifier.onPreviewKeyEvent` en Scaffold — delega a `TerminalKeyHandler.getKeyCode()` para escape sequences
- Volume keys: `Activity.onKeyDown/onKeyUp` → Volume Down = virtual Ctrl, Volume Up = virtual Fn
- Virtual Fn key mapping: W→UP, A→LEFT, S→DOWN, D→RIGHT, P→PGUP, N→PGDN, T→TAB, E→ESC, 1-9→F1-F9, 0→F10
- Ctrl+Alt shortcuts: N/C→new session, P→prev session, K→toggle keyboard, V→paste, W→close session, M→drawer, -/+→font size, 1-9→switch session
- External alphabetic keyboards: volume keys passthrough (no steal from full keyboards)
- **IME visibility detection:** `WindowInsets.isImeVisible` tracked via `LaunchedEffect`, updates `isSoftKeyboardVisible` in ViewModel

### 4.4 Session persistence

- `onStop()`: Guarda `session.mHandle` en `SharedPreferences` via `TermuxAppSharedPreferences.setCurrentSession()`
- `onSaveInstanceState()`: Guarda `activity_recreated=true` en Bundle
- `onServiceConnected()`: Si `mIsActivityRecreated`, restaura sesión activa via `getTerminalSessionForHandle(savedHandle)` y `switchSession(index)`
- El servicio (`TermuxService` + `TermuxShellManager` singleton) mantiene las sesiones en memoria durante la recreación de la Activity

### 4.5 Theme colors

- `loadTerminalColors()` en `TermuxComposeActivity`: Lee `~/.termux/colors.properties`, parsea colores X11 (`#RGB`, `#RRGGBB`, `rgb:R/G/B`) con `parseX11Color()`
- Construye `TerminalTheme` con 16 colores ANSI + foreground/background/cursor
- Fallback: `TerminalColors.COLOR_SCHEME.mDefaultColors` (array de 259 colores del terminal-emulator)
- Pasa tema al ViewModel → `TermuxMainScreen` → `TerminalEmulatorWebView` → xterm.js via `TermuxTerminal.reconfigure()`

### 4.6 Custom font loading

- `loadCustomFont()` en `TermuxComposeActivity`: Lee `~/.termux/font.ttf`, base64 encode con `Base64.encodeToString(bytes, NO_WRAP)`
- Pasa font family name ("termux-font") y base64 data al ViewModel
- `TerminalEmulatorWebView` inyecta `@font-face` CSS vía `TermuxTerminal.setFont(family, base64)` después de `onPageFinished`
- `reconfigure()` actualiza `fontFamily` en xterm.js para usar la fuente inyectada
- **Dynamic updates:** `update` block en `AndroidView` re-inyecta solo si `fontFamily:fontBase64Length` cambió

### 4.7 Resize handling

- `addOnLayoutChangeListener` en `WebView` detecta cambios de ancho y llama `TermuxTerminal.fit()`
- `window.addEventListener('resize')` en index.html llama `fitAddon.fit()`
- `reconfigure()` siempre llama `fitAddon.fit()` al final
- `configChanges` en manifest incluye `orientation|screenSize|smallestScreenSize|density|screenLayout|keyboard|keyboardHidden|navigation`
- `onConfigurationChanged()` logea el cambio de orientación

---

## Fase 5: Assets y Configuración — COMPLETADA · ASSETS A ELIMINAR EN FASE 9

### 5.1 Assets implementados

```
app/src/main/assets/terminal/
  index.html                          ← xterm.js v5.5.0 (local) + bridge JS + reconfigure() + setFont()
  xterm.min.css                       ← xterm.js CSS bundeado localmente
  xterm.min.js                        ← xterm.js core bundeado localmente
  addon-fit.min.js                    ← addon-fit bundeado localmente
  addon-webgl.min.js                  ← addon-webgl bundeado localmente
  addon-search.min.js                 ← addon-search bundeado localmente
  addon-unicode11.min.js              ← addon-unicode11 bundeado localmente
```

### 5.2 WebView Configuration (en `TerminalEmulatorWebView.kt`)

- `javaScriptEnabled = true`
- `domStorageEnabled = true`
- `mediaPlaybackRequiresUserGesture = false`
- `cacheMode = LOAD_NO_CACHE`
- `setSupportZoom(false)`
- `addJavascriptInterface(TerminalBridge(...), "Android")`
- `loadUrl("file:///android_asset/terminal/index.html")`
- `addOnLayoutChangeListener` para resize automático

---

## Fase 6: Verificación — COMPLETADA

- `./gradlew assembleDebug` — **BUILD SUCCESSFUL**
- `./gradlew test` — 1 pre-existente falla (`FileReceiverActivityTest` con `UnsatisfiedLinkError` por JNI nativo), resto pasa

---

## Fase 7: Funcionalidad Completa — COMPLETADA

### 7.1 Keyboard handling (COMPLETADO)
- `TerminalKeyHandler.kt`: Mapeo completo de Android KeyCodes y nombres de teclas → escape sequences VT100/xterm
- `onPreviewKeyEvent` en `TermuxMainScreen.kt`: Intercepta teclas hardware, delega a KeyHandler
- Volume keys virtuales en `TermuxComposeActivity.kt`: Volume Down→Ctrl, Volume Up→Fn
- Ctrl+Alt shortcuts para gestión de sesiones y configuración

### 7.2 Extra keys mejoradas (COMPLETADO)
- `ExtraKeysConfig.kt`: Parser de JSON extra-keys de `termux.properties` con resolución de aliases
- Layout dinámico: filas y botones generados del JSON del usuario
- Long-press repeat para teclas de navegación (UP, DOWN, LEFT, RIGHT, BKSP, DEL, PGUP, PGDN)
- Carga automática desde `termux.properties` en `onCreate`

### 7.3 Session persistence (COMPLETADO)
- `onSaveInstanceState` + `SharedPreferences` para session handle
- Restauración automática en `onServiceConnected` tras rotación
- Patrón idéntico a `TermuxActivity` existente

### 7.4 Theme colors (COMPLETADO)
- Lectura de `~/.termux/colors.properties` con parser X11 compatible
- Mapeo de 16 colores ANSI (color0-color15) + foreground/background/cursor
- Fallback a `TerminalColors.COLOR_SCHEME.mDefaultColors`
- Aplicación dinámica a xterm.js via `reconfigure()`

### 7.5 Custom font (COMPLETADO)
- Lectura de `~/.termux/font.ttf` con `File.readBytes()` + `Base64.encodeToString()`
- Inyección de `@font-face` CSS en WebView vía `TermuxTerminal.setFont()`
- Actualización dinámica de `fontFamily` en xterm.js vía `reconfigure()`
- Smart re-inyección solo cuando la config de fuente cambia

### 7.6 Resize handling (COMPLETADO)
- `addOnLayoutChangeListener` en WebView para resize automático
- `window.addEventListener('resize')` en index.html
- `configChanges` en manifest para evitar recreación innecesaria
- `onConfigurationChanged()` como fallback

### 7.7 IME visibility detection (COMPLETADO)
- `WindowInsets.isImeVisible` tracked via `LaunchedEffect` en `TermuxMainScreen`
- Actualiza `isSoftKeyboardVisible` en ViewModel para uso futuro

---

## Fase 8: Migración a Launcher — COMPLETADA

### 8.1 AndroidManifest.xml

- `TermuxComposeActivity`: `android:exported="true"` + intent-filters `MAIN/LAUNCHER` + `MAIN/LEANBACK_LAUNCHER` + `android.app.shortcuts`
- `TermuxActivity`: Sin launcher intent-filter (mantiene `exported="true"` para compatibilidad)

### 8.2 shortcuts.xml

- `targetClass` cambiado de `com.termux.app.TermuxActivity` a `com.termux.app.TermuxComposeActivity`

### 8.3 Funcionalidad de lanzamiento

- **Fullscreen:** Aplica `FLAG_FULLSCREEN` si `mProperties.isUsingFullScreen()`
- **ACTION_RUN intent:** `onNewIntent()` + `handleIntent()` maneja shortcuts con `EXTRA_FAILSAFE_SESSION`
- **Initial intent:** `onServiceConnected()` verifica `intent.getBooleanExtra(EXTRA_FAILSAFE_SESSION)` en el primer lanzamiento
- **Broadcast:** `TermuxUtils.sendTermuxOpenedBroadcast()` en `onServiceConnected()` para notificar a otras apps
- **HomeActivity alias:** Se mantiene apuntando a `TermuxActivity` (compatibilidad Android Things/IOT)

---

## Fase 9: Migración de Renderer — TerminalView vía AndroidView — COMPLETADA

### 9.1 Investigación (2026-08-21) — por qué se descarta xterm.js

**Bugs de corrección en el puente actual:**
- `String(data, 0, length, UTF_8)` sobre chunks arbitrarios del PTY corrompe caracteres multi-byte divididos entre chunks (CJK, emojis). El `TerminalEmulator` Java decodifica incrementalmente y no tiene este problema.
- Escapado manual a `evaluateJavascript("TermuxTerminal.write('$escaped')")`: cualquier byte de control no contemplado rompe el JS o corrompe datos.
- Polling cada 16ms + `evaluateJavascript` = latencia extra y un round-trip IPC de Chromium por chunk.

**Costos estructurales:**
- **Doble emulación:** los bytes se parsean dos veces (`TerminalEmulator` Java sigue corriendo y xterm.js re-parsea); doble scrollback en memoria.
- **Memoria:** cada WebView arrastra un proceso Chromium (~decenas de MB); Termux soporta múltiples sesiones y dispositivos de gama baja. `TerminalView` es trivial.
- **Regresión de features:** `TerminalView` tiene ~1,500 líneas de hardening Android que habría que reimplementar en JS: workarounds IME (Gboard/Samsung/TYPE_NULL), acentos combinados vía `onKeyPreIme`, teclas hardware kcm, pinch-zoom de fuente, selección con handles Material + ActionMode, mouse tracking, middle-click paste, autofill, accesibilidad.
- **Fragmentación:** System WebView varía por dispositivo; el addon WebGL no está garantizado; targetSdk 28 limita aún más.
- **Seguridad:** `addJavascriptInterface` + `file://` + JS habilitado es superficie de ataque innecesaria para una terminal.
- **APK:** ~1MB+ de assets JS vendidos.

**Precedentes:** TUICommander abandonó xterm.js por exactamente estos problemas ("mushy under load", corrupción WebGL, races de scroll) y migró a render nativo. Envolver `TerminalView` con `AndroidView` es el patrón de interop oficial de Compose, con precedentes exitosos.

**Conclusión:** si algún día se quiere render 100% Compose, el camino correcto sería portar `TerminalRenderer` a Canvas de Compose (la grilla ya vive en `TerminalBuffer`) — nunca xterm.js.

### 9.2 Plan aprobado

**1. Nuevo host de terminal** — `app/src/main/java/com/termux/terminal/compose/TerminalViewHost.kt`
- `@Composable TerminalViewHost(session, fontSize, modifier)` con `AndroidView(factory = { TerminalView(it) })`.
- `DisposableEffect(session)`: `attachSession(session)`; el layout del view dispara `updateSize()` e inicializa el emulador si hace falta.
- `update`: aplicar cambios de `fontSize` vía `setTextSize()` + `updateSize()`.
- Registro del view activo en un registry (`object` con `var activeView`) para que los eventos de sesión lo notifiquen; limpiar en `onDispose`.
- `requestFocus()` al crear/cambiar tab (teclado blando).

**2. Clientes Compose** (patrón Client Interface de AGENTS.md)
- `ComposeTerminalViewClient : TerminalViewClient` (Kotlin): logging vía `Logger`, `onScale` → ajustar fontSize del estado, resto no-ops. Interfaz Java sin defaults: implementar todos los métodos.
- `ComposeTerminalSessionClient : TermuxTerminalSessionClientBase`: `onTextChanged`/`onTitleChanged` → `activeView.onScreenUpdated()` + título al ViewModel. **Crítico:** hoy las sesiones usan `TermuxTerminalSessionServiceClient`; sin esto la pantalla quedaría congelada.
- En `TermuxComposeActivity`: al bind llamar `service.setTermuxTerminalSessionClient(composeClient)` y `unsetTermuxTerminalSessionClient()` al desbind (espejo de `TermuxActivity.java:440`).

**3. Ediciones**
- `TermuxMainScreen.kt`: reemplazar `TerminalEmulatorWebView(...)` por `TerminalViewHost(...)`; **eliminar** el bloque `onPreviewKeyEvent` (TerminalView ya maneja teclas hardware/kcm/acentos; dejarlo causaría doble manejo). Mantener extra keys escribiendo directo a la sesión vía `TerminalKeyHandler`.
- `TermuxViewModel.kt` / `TermuxUiState.kt`: quitar campos WebView-only (`fontFamily`, `fontBase64`, `terminalTheme`); conservar `fontSize`. Reubicar/eliminar `TerminalTheme` según referencias.

**4. Limpieza ruta xterm.js**
- Borrar: `TerminalEmulatorWebView.kt`, `bridge/TerminalBridge.kt`, `bridge/TerminalWebConfig.kt`, `app/src/main/assets/`, `package.json`, `package-lock.json`, `node_modules/`.
- Revertir `terminal-emulator/.../TerminalSession.java` (`git checkout -- ...`) — elimina `TerminalSessionRawDataListener`.
- Conservar: plugins/deps Compose en ambos `build.gradle.kts`, entrada `.kotlin/` en `.gitignore`, manifest y shortcuts (launcher sigue siendo `TermuxComposeActivity`), `bridge/TerminalKeyHandler.kt` (usado por extra keys).
- `119.md` no se toca durante la migración.

### 9.3 Verificación — EJECUTADA (2026-08-21)

- `./gradlew assembleDebug` — **BUILD SUCCESSFUL**
- `./gradlew test` — pasa, salvo la falla pre-existente `FileReceiverActivityTest` (`UnsatisfiedLinkError` por JNI nativo en Roblectric), no relacionada
- Smoke manual en dispositivo pendiente: tipeo/IME, teclado hardware, extra keys, cambio de tabs (reattach), pinch-zoom, selección/copiar/pegar, `vim`/`htop`, rotación, salida de sesión.

**Implementación final:**
```
app/src/main/java/com/termux/
  TermuxComposeActivity.kt            ← crea clients, setComposeTerminalSessionClient al bind,
                                        unset al destroy; toggleKeyboard apunta al TerminalView;
                                        eliminados loadTerminalColors/loadCustomFont/parseX11Color
  TermuxService.java                  ← +mComposeTerminalSessionClient, setCompose/unsetCompose,
                                        getTermuxTerminalSessionClient() considera el cliente Compose
  terminal/compose/
    TerminalViewHost.kt               ← AndroidView { TerminalView(context, null) }, attach/detach,
                                        fontSize dp via setTextSize(), registry cleanup en onDispose
    TerminalViewRegistry.kt           ← object con activeView para callbacks globales de sesión
    ComposeTerminalViewClient.kt      ← volume keys virtuales (Ctrl/Fn), pinch-zoom font, tap→IME,
                                        queries de propiedades, logging vía Logger
    ComposeTerminalSessionClient.kt   ← onTextChanged/onColorsChanged → activeView.onScreenUpdated(),
                                        onTitleChanged → ViewModel
    TermuxMainScreen.kt               ← TerminalViewHost reemplaza WebView; onPreviewKeyEvent
                                        eliminado (TerminalView maneja hardware keys); extra keys sin cambios
    TermuxUiState.kt / TermuxViewModel.kt ← removidos fontFamily/fontBase64/terminalTheme
```

**Eliminado:** `TerminalEmulatorWebView.kt`, `bridge/TerminalBridge.kt`, `bridge/TerminalWebConfig.kt`,
`app/src/main/assets/` (xterm.js + addons), `package.json`, `package-lock.json`, `node_modules/`;
revertido `TerminalSession.java` (sin `TerminalSessionRawDataListener`).

### 9.4 Hotfix post-migración (2026-08-21)

Corregidos dos bugs reportados en smoke inicial:

1. **Texto blanco sobre blanco:** `TerminalRenderer` no pinta celdas con fondo por defecto
   (`TerminalRenderer.java:202`); en clásico el fondo lo da `windowBackground`. Fix: nuevo
   `TerminalPalette.kt` deriva bg/fg de `MaterialTheme.colorScheme.surface/onSurface`; host escribe
   índices 256/257/258 de `mEmulator.mColors.mCurrentColors` + `view.setBackgroundColor()` +
   `onScreenUpdated()`. Reaplicado en cambio de sesión y dark/light switch vía `update{}`.
   Limitación: OSC 10/11 de apps respeta hasta próximo re-attach/cambio de paleta.
2. **Teclado no abría:** `showSoftInput(SHOW_IMPLICIT)` falla sin foco de ventana; `requestFocus()`
   en factory era prematuro. Fix: `toggleKeyboard()` → `KeyboardUtils.toggleSoftKeyboard(SHOW_FORCED)`
   (idéntico al clásico); tap usa `KeyboardUtils.showSoftKeyboard()`; focus via `post{}` tras attach
   y guardia en `update{}`.
3. **Dynamic color:** `setContent` construye esquema con `dynamicLight/DarkColorScheme` (API 31+) o
   fallback claro/oscuro según `isSystemInDarkTheme()`; terminal sigue al sistema.

### 9.4 Riesgos nuevos

| Riesgo | Mitigación |
|---|---|
| `TerminalViewClient` sin defaults → métodos sin implementar | Implementar todos; mayoría no-op documentados |
| Pantalla congelada si el session client no notifica | `setTermuxTerminalSessionClient` en bind + registry de view activo |
| Foco inicial del teclado blando | `requestFocus()` en factory/cambio de tab |
| Recreación del view al cambiar tabs (solo sesión activa compuesta) | Aceptado; reattach es barato. Pool de vistas diferido (YAGNI) |

---

## Pendientes / Próximos Pasos

1. ~~**xterm.js offline:** Bundlear assets localmente para uso sin conexión~~ ✅ RESUELTO (luego obsoleto: Fase 9 elimina la ruta WebView)
2. ~~**WebView memory optimization:** Investigar si WebView por sesión es viable o compartir uno solo~~ OBSOLETO (Fase 9 elimina el WebView)
3. **Popup support en extra keys:** Swipe-up para teclas alternativas (implementar `PopupWindow` o Compose `DropdownMenu`)
4. ~~**Eliminar TerminalView.java:** Cuando la migración esté completa y estable~~ INVERTIDO (Fase 9): `TerminalView` vuelve a ser EL renderer; se elimina la ruta xterm.js
5. ~~**Ejecutar Fase 9** (plan aprobado, ver arriba)~~ ✅ COMPLETADA (2026-08-21); smoke en dispositivo pendiente
6. **Probar resize/redraw al rotar pantalla en dispositivo real**

---

## Riesgos Identificados

| Riesgo | Estado | Nota |
|---|---|---|
| xterm.js + WebView consume más RAM que Canvas | ~~Aceptado~~ **Cerrado (Fase 9)** | Se elimina la ruta WebView |
| Bridge latency PTY→WebView | ~~Aceptado~~ **Cerrado (Fase 9)** | Render nativo sin IPC intermedio |
| Compose + AndroidView interop (`TerminalView`) | Estable | Patrón oficial de interop; ver Fase 9 |
| TerminalView.java como renderer principal | Restaurado (Fase 9) | La ruta xterm.js era la temporal; se elimina |
| minSdk 21 | OK | Compose BOM 2025.08.00 soporta minSdk 21 |
| CDN dependency | ~~Riesgo bajo~~ **Resuelto** | Assets bundeados localmente en `app/src/main/assets/terminal/` |
| AGP 9.x no necesita kotlin.android plugin | Resuelto | El plugin falla si se agrega; AGP maneja Kotlin built-in |
| `TerminalColors.parse()` es package-private | Resuelto | Se implementó `parseX11Color()` local en `TermuxComposeActivity` |
| `TermuxUiState` NPE on `ExtraKeysConfig.DEFAULT` | Resuelto | Default cambiado a `ExtraKeysConfig(rows = emptyList())` para evitar companion object val null durante reflexión |
| HomeActivity alias apunta a TermuxActivity | Temporal | Se mantiene para compatibilidad Android Things/IOT |
| TermuxService.startTermuxActivity() usa TermuxActivity | Temporal | La notificación del servicio aún abre TermuxActivity; migrar cuando se establezca |

---

## Tabla de Componentes (actualizada)

| Module | Package | Java files | Kotlin files | Total | Proposito |
|---|---|---|---|---|---|
| `app` | `com.termux` | 37 | 12 | 49 | UI activities, services, Compose UI, key handler, config parsers |
| `terminal-view` | `com.termux.view` | 8 | 0 | 8 | TerminalView — renderer activo del terminal (Fase 9) |
| `terminal-emulator` | `com.termux.terminal` | 14 | 0 | 14 | VT100/xterm engine + JNI PTY (raw listener a revertir en Fase 9) |
| `termux-shared` | `com.termux.shared` | 120+ | 0 | 120+ | Shared logic, settings, utilities |
| **Total** | | **179+** | **12** | **~191+** | |
