# Plan 022 — Navegador interno: pestañas GeckoView para abrir webs desde la terminal

Spec de referencia: `specs/022-navegador-geckoview/spec.md` (Estado: **aprobada,
pendiente de implementación**). Motor elegido: GeckoView **release arm64-v8a**
(pin aprobado `159.0.20261022150525`), integrado como quinto caso del sistema de
pestañas polimórfico (`Terminal` / `FileManager` / `X11` → `Browser`), con el mismo
patrón registro-por-pestaña que `X11SessionRegistry` (spec 014).

> **Orden crítico:** los experimentos de factibilidad (F1–F3) van ANTES de ninguna
> tarea de código de UI. Si F1 o F2 fallan en el dispositivo, la spec se reescopa en
> frío ANTES de escribir código (Constitución § 2): nada de código muerto.

## Delta del catálogo de dependencias (Constitución § 1) — con su porqué

| Delta | Valor | Porqué citado |
|---|---|---|
| Nueva dependencia en `gradle/libs.versions.toml` | `geckoview = "org.mozilla.geckoview:geckoview-arm64-v8a:159.0.20261022150525"` + repositorio `https://maven.mozilla.org/maven2/` en `settings.gradle.kts` | Único motor de navegador self-contained con release vivo verificado hoy (22-oct-2026); Darwin del WebView resulta de alcance "no es un navegador real" y queda descartado en la spec. Es dependencia de la lógica mínima posible: no trae companion apps ni servicios; el AAR arm64 (87 MiB verificado) es el artefacto del fork arm64-only. El artefacto universal `geckoview` de 232 MiB **queda prohibido** en el build (RNF-1). |
| `app/build.gradle.kts` | `implementation(libs.geckoview)` | Consumo por la capa Compose del app (el app es el único módulo con UI). Ningún otro módulo la ve (dirigir el motor a `app` respeta el grafo: `terminal-emulator`/`terminal-view`/`termux-shared` siguen sin depender del motor). |

La dependencia va **fijada con versión exacta** (RF-12): NUNCA `+`/`^`, sin
`dependencyResolutionStrategy` dinámica.

## Archivos y responsabilidades

| Archivo | Cambio | RF |
|---|---|---|
| `gradle/libs.versions.toml` + `settings.gradle.kts` (delta §1) | Dependencia + repos Mozilla | RF-12, RNF-1 |
| `app/build.gradle.kts` | `implementation(libs.geckoview)` | RF-12 |
| `app/.../compose/BrowserLogic.kt` (NUEVO) | Funciones puras: routeo intern/extern, normalización de la barra URL, título de pestaña/nombre numerado | RF-2, RF-3, RF-4, RF-5, RF-7, RNF-4 |
| `app/src/test/.../compose/BrowserLogicTest.kt` (NUEVO) | Tests JUnit sin SDK de Android | RNF-4 |
| `app/.../compose/BrowserSessionsTest.kt` (NUEVO) | Test del nombre numerado (patrón `X11SessionsTest`) | RF-1 |
| `app/.../compose/BrowserTabRegistry.kt` (NUEVO) | Registro por pestaña: `GeckoSession` + delegates + `StateFlow` de estado; `GeckoRuntime` singleton | RF-1, RF-2, RF-3, RF-6, RF-8, RF-9 |
| `app/.../compose/BrowserSessionHost.kt` (NUEVO) | Composable host (patrón `X11SessionHost`): `AndroidView{ GeckoView }` + barra URL + botones navegación + overlay de error/fallback (RF-6) | RF-2, RF-6 |
| `app/.../compose/TermuxUiState.kt` | Caso sealed `Browser(id, name, initialUrl)` aditivo | RF-1 |
| `app/.../compose/TermuxViewModel.kt` | `createBrowserSession(initialUrl: String?)` (patrón `createX11Session`) + preferencia del ajuste intern/extern desde prefs | RF-1, RF-4, RF-5 |
| `app/.../compose/TermuxMainScreen.kt` + `SessionTabs.kt` + `NavigationDrawerContent.kt` | Dispatch `Browser` → host (patrón línea X11 449/749), icono/enlace generación de pestaña nueva | RF-1 |
| `app/.../compose/ComposeTerminalCanvas.kt` | Tap-URL del canvas: de `ShareUtils.openUrl(context, …)` a callback `onOpenLink` (ruteo del activity) | RF-4, RF-5, RF-7 |
| `app/.../app/TermuxComposeActivity.kt` | Callback `onOpenLink` único del activity (pestaña interna o causal externo) + More-menu (línea ≈1430) | RF-4, RF-5, RF-7 |
| `terminal-view/.../TerminalView.java` + `TerminalViewClient` + base en `termux-shared` | Nuevo callback del cliente `onHyperlinkOpen(uri)` con default `false` (comportamiento actual `ACTION_VIEW` intacto si no se sobreescribe); el cliente del app el devuelve `true` y rutea | RF-4, RF-5, RF-7 (patrón Client Interface) |
| `termux-shared/.../preferences/TermuxAppSharedPreferences.java` + `TermuxConstants` (sección `TERMUX_APP`) | Clave aditiva `KEY_LINKS_OPEN_INTERNAL` (default `true`) + getter/setter; **sin tocar ninguna clave existente** | RF-5 |
| `app/.../compose/settings/` (Screen, UiState, ViewModel) | Fila del ajuste enlkace intern/ext (patrón `terminal_hyperlinks`) | RF-5 |
| `app/src/main/res/values*/strings.xml` (EN, es-rVE, pt-rBR) | Strings ×3 | RF-10 |
| `app/.../compose/settings/LicensesScreen.kt` | Entrada del motor (MPL-2.0 + repositorio) | RF-11 |
| `specs/022…/spec.md` | Registro del resultado F1–F3 y de la verificación en dispositivo | Fase 0 |
| `CHANGELOG.md` + conventional commits | "Added" | — |

**Notas**:
- `proguard-rules.pro`: el AAR trae sus consumer rules; sin `keep` manual salvo que R8
  se lo exija al probar `assembleRelease` (verificar, T11).
- `update-site.py`/CI: sin cambio (el tamaño/fecha del APK publicado se recalculan
  por el flow normal). Incluye **verificación** de la página tras release, T12.

## Decisiones justificadas (con alternativa descartada)

1. **Quinto caso sealed, pestaña UI-backed** (id con `UUID` + numbering, sin persistir).
   Alternativa descartada: actividad separada (el webview no se integraría con el
   tab bar/split y perdería contexto), o persistir pestañas al morir el proceso
   (FUERA DE ALCANCE, coherente con X11/AM).
2. **Registro por id (`BrowserTabRegistry`) + delegates → StateFlow.** La pestaña
   sobrevive al recreation de la activity (rotación, IME) sin cerrar la web; una
   vista nueva hace `view.setSession(...)` rebind, como `TerminalViewRegistry`. El
   estado del `uiState` global NUNCA regula la posición/carga del motor (evita
   churn de recomposición a 60 fps).
3. **Closure de la sesión del navegador per pestaña por `GeckoSession.close()`**
   (RF-9). Alternativa descartada: `GeckoSession` reciclado/peek — sin base real.
4. **` GeckoRuntime` singleton por proceso** (docs del motor): creado lazy con la
   primera pestaña de navegador, no en el arranque del app (arranque más rápido y
   menos proceso de RAM para usuarios que nunca abren webs).
5. **Ruteo de enlaces en el activity (único `onOpenLink`)** consultando la
   preferencia; las llamadas antiguas de canvas/More-menu/hiperlejos del legado
   connectan al callback. Alternativa descartada: duplicar el ruteo en cada punto
   (rompería RF-5 enun lugar solo, y las librerías puras no conocen prefs).
6. **Fallback RF-6 por `ProgressDelegate.onPageStop(success=false)`** (verificado en
   el fuente del motor: `onPageStart/onPageStop` existen; el motor pinta su página
   de error propia y el overlay del host se activa) — la alternativa de
   parsear `about:neterror` es frágil y no está documentada.
7. **`ContentDelegate.onNewSession` del motor → nueva pestaña del mismo motor**
   (RF-8 con ajuste interno); con externo, acción del sistema.

## Funciones puras (`app`: `BrowserLogic.kt` — patrón `X11Sessions.kt`)

```kotlin
enum class LinkRoute { INTERNAL_TAB, EXTERNAL }

fun routeLink(uri: String, internalEnabled: Boolean): LinkRoute
  // http/https → INTERNAL_TAB si internalEnabled; si no → EXTERNAL.
  // cualquier otro scheme (mailto:, content:, file:, about:, …) → EXTERNAL SIEMPRE (RF-7).

fun normalizeUrlInput(input: String): String
  // "" → "" ; sin "://" tras recortar espacios → "https://" + input (RF-2).

fun tabLabel(pageTitle: String, pageUrl: String): String
  // pageTitle no vacío → truncado a 40 chars; si no, host corto del URL (RF-3).

fun nextSessionName(existingCount: Int, baseName: String): String
  // mismo contrato que X11Sessions.nextSessionName (RF-1).
```

## Interfaz del registro (`BrowserTabRegistry`) — resumen

```kotlin
object GeckoRuntimeHolder {   // docs del motor: UNA instancia por proceso
  fun get(activity: Activity): GeckoRuntime   // lazy; Settings por defecto
}

class BrowserTabController(val id: String, initialUrl: String?) {
  val state: StateFlow<BrowserTabState>       // location, title, canGoBack/Forward, loading, loadFailed
  val session: GeckoSession                   // 1 por pestaña; create → open(runtime) → delegates
  fun go(url: String); fun goBack(); fun goForward(); fun reload()
  fun openExternally(activity: Context)       // RF-6/RF-7 ACTION_VIEW
  fun dispose()                              // close() + remove del registro (RF-9)
  companion object { fun forTab(id: String, initialUrl: String?, activity: Activity, onClose: () -> Unit): BrowserTabController }
}
```

Delegates del motor que se usan (verificados en el fuente `mobile/android/geckoview/…/GeckoSession.java`):

- `NavigationDelegate`: `onLocationChange` (barra), `onCanGoBack/onCanGoForward`
  (botones RF-2), `onNewSession` (RF-8: `useSession(...)` del ajuste).
- `ContentDelegate`: `onTitleChange` (RF-3), `onCrash` (RF-6: mensaje + salida).
- `ProgressDelegate`: `onPageStop(success=false)` (RF-6).

## Experimentos de factibilidad (ANTES del código; registro en spec.md)

- **F1 — Procesos del motor con `sharedUserId="com.termux"`:** ¿arranca el proceso
  de contenido del motor (`<pkg>:…`) en el dispositivo de pruebas (ESTRIN
  Unisoc/A12) con las demás apps del UID corriendo? Créditos de error a mirar:
  logs de `GeckoStartup`, crash del contenido, StrictMode.
- **F2 — Memoria con la app cargada:** GeckoRuntime + 1 pestaña con web compleja,
  mediendo `adb shell dumpsys meminfo com.estrin217.terminal` con X11+proot vivos;
  límite orientativo del mantenedor conocido en el eat de RNF-3.
- **F3 — Build:** `./gradlew test` verde con el AAR en el classpath (compila,
  descarga de maven.mozilla.org desde el CI) y `assembleDebug` produce el APK
  con `libxul.so` dentro (unzip -l). Tamaño del APK universal resultante documentado.

## Estrategia de tests (`./gradlew test` única puerta de calidad)

- `BrowserLogicTest` (app, JUnit puro): routeLink por scheme marquee × ajuste;
  normalizeUrlInput ("ejemplo.com", "https://a.", "file://x" → sólo scheme: input
  con scheme no se toca; vacío); tabLabel (título largo/no vacío/host corto);
  nextSessionName (0/1/2).
- Test VM numeración: pestaña de navegador en el state (patrón `X11SessionsTest`/
  otros tests del VM existentes, sin SDK de Android en el acceso de sesión).
- Sin Robolectric para el motor: los delegates se prueban solo en dispositivo
  (F1–F2 + criterios de finalización en el hardware).

## Riesgos y mitigación (apuntes de ejecución)

- **Tamaño del APK (RNF-1):** regla del plan: NUNCA importar
  `org.mozilla.geckoview` en las librerías Java puras — solo `app` (Kotlin Compose).
- **minSdk (enmienda aprobada 2026-10-28):** el minSdk del app sube 24→26
  (GV release 159 lo exige). `targetSdk` no cambia. Los comentarios "minSdk 24"
  de AGENTS.md se actualizan; proot/getifaddrs (API 24+) no se ve afectado.
- **CI:** la descarga del AAR exige salida de red a `maven.mozilla.org` en los jobs
  que corren Gradle (ya hace salir para `gradle plugin portal`/maven central ahora
  mismo; solo añade el origin).
- **Problema del R8:** si `assembleRelease` falla con missing/shrinking, se
  añaden solo los binds mínimos citados por el mensaje (no un `-keep class **` gordo).
