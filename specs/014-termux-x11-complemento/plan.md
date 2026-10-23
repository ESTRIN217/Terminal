# Plan — Spec 014 (termux:x11 como librería y pestañas con drawer)

## Archivos y responsabilidades

| Archivo | Responsabilidad | RF |
|---|---|---|
| `.gitmodules`, `termux-x11/` (submódulo) | Fork ESTRIN217 de termux-x11, pin de SHA propio tras los ajustes de esta spec | RF-1 |
| `settings.gradle.kts` | Includes planos `:termux-x11-lorie` (dir `termux-x11/lorie`) y `:termux-x11-stub` (dir `termux-x11/shell-loader/stub`, `compileOnly` de lorie renombrado a este alias) | RF-1 |
| `termux-x11/lorie/build.gradle` | Adaptaciones mínimas de AGP 9 (`compileSdk`, `packaging`) y `abiFilters arm64-v8a`; versiones kotlin 2.4.20/lifecycle 2.11.0 alineadas al catálogo | RF-1, RNF-4 |
| `termux-x11/lorie/src/main/AndroidManifest.xml` | Quitar nodo `<application>` (attrs) e intent-filters MAIN/LAUNCHER/SECONDARY_HOME de `MainActivity`, y el receiver `LorieApp$Receiver` | RF-1, decisiones |
| `termux-x11/lorie/.../LorieView.java` | Nueva interfaz `LorieView.Client`; las referencias a `MainActivity` pasan por ella; `MainActivity` la implementa sin cambiar comportamiento | RF-4..RF-7 |
| `app/build.gradle.kts` | `implementation project(":termux-x11:lorie")` | RF-1 |
| `gradle/libs.versions.toml` | Alias `androidx-lifecycle-viewmodel-ktx` (el resto ya existe: `preference`, `viewpager`, `annotation`) | RNF-4 |
| `TermuxConstants.java` | Constantes: acciones `ACTION_START`, `ACTION_STOP`, `ACTION_CUSTOM`, `ACTION_PREFERENCES_CHANGED`, extras (`EXTRA_DOCUMENT_TAG`, key null del bundle), nombre de proceso `termux-x11` | RNF-3 |
| `TermuxApplication.java` | `extends LorieApp` (hereda attach de `Prefs`, canal de notificación); onCreate conserva crash handler | RF-4 |
| `app/src/main/AndroidManifest.xml` | `tools:replace` de attrs de `<application>`; receiver propio `X11BroadcastReceiver` con los actions | RF-4 |
| `com.termux.terminal.x11/X11BroadcastReceiver.kt` | Recibe broadcast, delega en `X11SessionBus` | RF-4 |
| `com.termux.terminal.x11/X11SessionBus.kt` | Mapa tag→binder vivo (`linkToDeath`), entrega a controladores activos, evita conexión doble (reportFatalError) | RF-4, RF-7 |
| `com.termux.terminal.x11/X11TabController.kt` | Surrogate client por sesión: `connectToService/tryConnect/scheduleConnect/disconnectService`, prefs, extra keys, input handling, heartbeat mínimo | RF-4..RF-7 |
| `com.termux.terminal.x11/X11CompanionCommand.kt` | **Puro:** construir comando de arranque del companion; parsear número de display/tag | RF-8, RNF-1 |
| `com.termux.terminal.x11/X11Sessions.kt` | **Puro:** helpers sobre el modelo (id/tag/etiqueta, resolución tag→sesión, validación display) | RF-2, RNF-1 |
| `com.termux.terminal.compose.SessionTabs.kt`, `TermuxUiState.kt`, `TermuxViewModel.kt` | Nuevo subtipo sellado `X11`, fábrica/cierre de sesión X11, host por id | RF-2, RF-7 |
| `com.termux.terminal.compose/X11SessionHost.kt` | Host Compose: `AndroidView(LorieView)`, barra extra keys (views de lorie), overlay "no server" con botón copiar comando | RF-3..RF-6 |
| `NavigationDrawerContent.kt` | Ítem X11 del drawer (icono + string), callback a ViewModel | RF-2 |
| `strings.xml` ×3 locales | Drawer, overlay no-server, instrucción, botón copiar, nombre de sesión X11 | RNF-2 |
| `wiki/` | Página con instrucciones de arranque del companion y notas de uso (en línea con specs previas) | RF-8 |
| Tests `app/src/test/java/.../x11/` | JUnit puro: comando companion, rutas de tag/sesión | RNF-1, RF-8 |

## Funciones puras (el "hoy" entra por parámetro)

- `buildCompanionLaunchCommand(applicationId, displayNumber, tag?) -> String`
  Hoy: applicationId y display/vars; params obligatorios. Salida:
  `CLASSPATH=$(pm path --user 0 <id> | head -1 | cut -d: -f2) /system/bin/app_process / --nice-name=termux-x11 com.termux.x11.CmdEntryPoint <display>` más `-tag <tag>` si existe.
- `parseDisplayArg(arg: String) -> Result<DisplayTag>`: valida `:N[.M]` y devuelve display y tag numérico (para titular de sesión).
- `resolveSessionForTag(tags: Map<Id, Tag>, tag: String) -> Id`: "" es comodín de "sin tag" (primer tab X11 sin tag), tag no existente pero no nulo → null (no crash, queda pendiente en bus).
- `nextSessionName(existing: List<String>, base: String) -> String`: etiqueta "X11"/"X11 2"... sin colisión, misma lógica que filemanager hoy.

## Algoritmo (pseudocódigo)

1. **Conexión (RF-4):** broadcast `ACTION_START` → bus. Si ya hay controller vivo para ese tag conectado a otro binder → `reportFatalError` y no conecta. Si existe controller de la app activa con la tag → `connectToService(binder)`: guardo `ICmdEntryInterface`, pido `getLogcatOutput()` (fd) → `startLogcat`, y `tryConnect()` en loop 250 ms:
   - `getXConnection()` → fd → `LorieView.connect(fd.detachFd())`, estado `CONNECTED`, `reloadPreferences`.
   - `fd == null` → esperar (25 × 250 ms) sin error ni spam.
   - Muerte del binder (`linkToDeath`) → `disconnectService()` → estado `NO_SERVER`.
2. **Pestaña (RF-2/RF-3):** drawer → `createX11Session(tag = "")` → id polimórfico nuevo, host contiene `LorieView` + controller; si al crear hay binder pendiente en bus, se consume. Estado UI = `NOT_STARTED | NO_SERVER | CONNECTED` derivado en controller.
3. **Cierre (RF-7):** sesión cerrada → controller `disconnectService()` + unregister del bus (el companion sigue vivo).
4. **Preferencias (RF-6):** controller registra `OnSharedPreferenceChangeListener` sobre `Prefs` heredada de `LorieApp` (ya attach al arranque de app); `showAdditionalKbd` cambia → re-inflar toolbar extra keys.

## Interfaz

```kotlin
// termux-x11 (fork)
interface LorieView.Client {
    fun getPrefs(): Prefs              // Prefs vigentes que la View consulta
    fun doHandleKey(e: KeyEvent): Boolean   // antes MainActivity.handleKey
    fun refreshInputHandlers()          // antes activity.mInputHandler.refreshInputDevices()
    fun applyExtraKeyMods(keys: Int)    // antes activity.mExtraKeys. withoutCtrlAlt
    fun resetExtraKeys()                // antes activity.mExtraKeys.unsetSpecialKeys()
    fun isUseTermuxEKBarBehaviour(): Boolean
    fun isCharBasedInputEnforced(): Boolean // prefs.enforceCharBasedInput
}
// MainActivity implementa Client delegando a sí misma (comportamiento sin récord).

// app module (Kotlin)
class X11TabController(view: LorieView, prefs: Prefs) : LorieView.Client
fun X11SessionBus.register(controller, tag) / unregister(controller)
object X11CompanionCommand { fun build(applicationId, display, tag?): String }
```

## Decisiones con alternativa descartada

1. **Submódulo git con mapeo de paths** en vez de copia vendida: historial del fork e
   incorporación de commits aguas arriba; descartada la copia (sin trazabilidad, drift).
   Alias del stub renombrado en `lorie/build.gradle` (una línea) porque los nodos
   anidados colisionan con el `allprojects { repositories }` del build raíz del submódulo.
2. **`LorieView.Client` + `MainActivity` implementa** en vez de duplicar la lógica del
   view en un surrogate heredando MainActivity: descartado heredar (la herencia de
   AppCompatActivity es imposible en un host Compose). Interfaz pequeña, MainActivity
   sin cambio de comportamiento.
3. **TermuxApplication extends LorieApp** en vez de reintroducir attach de prefs en
   app: descartado duplicar (obligación de segunda fuente de la misma store —
   riesgo ref spec 008 de claves perdidas).
4. **Receiver propio en app** en vez de reutilizar `LorieApp$Receiver`: su dispatch
   asume `MainActivity`s que aquí no existen; descartado acoplar, `pendingConnections`
   se replica en bus (mínimo y más testable), receiver de lorie se retira del merge.
5. **build nativo solo arm64-v8a:** runtime del fork ya es arm64-only por proot;
   descartado universal (tiempo build colossal sin consumer).
6. **Versiones kotlin 2.4.20 / lifecycle 2.11.0 fijadas en el submodule.** Se descarta
   subir stdlib a 2.4.21: alinear el catálogo evita doble stdlib en el mismo APK.
7. **Kotlin del app module para capas nuevas** (bus/controller/host), Java para
   `TermuxApplication` existente: la UI Compose ya es Kotlin en este fork según
   AGENTS.md; receiver es Kotlin también (nada lo obliga a Java).

## Estrategia de tests

- `./gradlew test` JUnit: command builder (múltiples displays/tags, caracteres), parser
  de display, resolución tag→sesión, generador de nombre de sesión. Sin Android.
- Resto verificado con `./gradlew assembleDebug` + inspección de APK (`unzip -l`:
  `libXlorie.so`, manifest fusionado sin icono duplicado y con receiver) y checklist
  de dispositivo (conectar companion y dibujar display en la pestaña).
