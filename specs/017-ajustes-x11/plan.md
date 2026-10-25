# Plan — Spec 017 (ajustes de X11 dentro de la app)

Estado: aprobada (2026-10-25, junto a la spec)

## Archivos y responsabilidades

| Archivo | Responsabilidad | RF |
|---|---|---|
| `app/src/main/java/com/termux/terminal/compose/settings/X11SettingsPolicy.kt` (nuevo, Kotlin puro) | Defaults del núcleo, validación de resolución personalizada, saneado de escala, recolocación de valores de lista desconocidos, mapeo índice↔valor, visibilidad de controles según modo. Constantes especulares de los arrays pequeños de lorie (fuente de verdad `preferences.xml`) | RF-4, RF-5, RF-6, RF-7, RF-9, RNF-1 |
| `app/src/main/java/com/termux/terminal/compose/settings/X11SettingsUiState.kt` (nuevo) | Data class con las 16 claves núcleo + texto/error del campo custom | RF-2 |
| `app/src/main/java/com/termux/terminal/compose/settings/X11SettingsViewModel.kt` (nuevo, AndroidViewModel) | SOLO lee y despacha: obtiene `Prefs` vía `LorieApp.getPrefs(context)`, `reload()` rellena el UiState pasando por la policy, cada setter persiste con el acceso nativo de `Prefs` (`ListPreference.put`/`StringPreference.put`/`BooleanPreference.put`/`displayScale.put`) y actualiza el estado | RF-2, RF-3 |
| `app/src/main/java/com/termux/terminal/compose/settings/X11SettingsScreen.kt` (nuevo) | Tres secciones (Salida / Puntero / Teclado) reutilizando `SettingsSwitchTile`, `SettingsDialogTile`, `SettingsSliderTile`, `SettingsListTile` de `com.termux.terminal.compose`; campo custom con error; botón atrás | RF-4, RF-5, RF-6, RF-7 |
| `app/src/main/java/com/termux/terminal/compose/settings/SettingsScreen.kt` | Entrada "Ajustes X11" (`SettingsListTile` + chevron, en el grupo junto a "Acerca de") con parámetro nuevo `onX11Click` que lleva default `{}` para no romper | RF-1 |
| `app/src/main/java/com/termux/app/activities/SettingsComposeActivity.kt` | `X11` añadido al enum `SettingsDestination` + branch que compone `X11SettingsScreen`; wire de `onX11Click` | RF-1 |
| `termux-x11/lorie/src/main/java/com/termux/x11/LoriePreferences.java` (`PrefsProto`) | `put(int)` añadido a `IntPreference` (simétrico a `BooleanPreference`/`StringPreference`), commit en el submódulo + pin actualizado (precedente spec 016 T1) | RF-5, RNF-4 |
| `app/src/main/res/values/strings.xml` + `values-es-rVE/` + `values-pt-rBR/` | Título de pantalla, 3 títulos de sección, tamaños/subtítulos de todos los tiles, labels traducibles de los modos (táctil, orientación, filtrado), texto de error de resolución, entrada "Ajustes X11", botón atrás | RNF-3, RF-6, RF-7 |
| `app/src/test/java/com/termux/terminal/compose/settings/X11SettingsPolicyTest.kt` (nuevo) | JUnit puro de la policy | RF-4, RF-5, RF-6, RF-7, RF-9, RNF-1 |

## Claves y contratos (núcleo, según `preferences.xml`)

| Sección | Clave | Tipo / default |
|---|---|---|
| Salida | `displayResolutionMode` | lista native/scaled/exact/custom · `native` |
| Salida | `displayResolutionExact` | lista sobre el array de 24 resoluciones · `1280x1024` |
| Salida | `displayResolutionCustom` | texto `WxH` · `1280x1024` |
| Salida | `displayScale` | int 30–300 paso 10 · `100` |
| Salida | `displayFilteringMode` | lista bilinear/nearest · `bilinear` |
| Salida | `fullscreen` | bool · `false` |
| Salida | `forceOrientation` | lista de 5 valores · `auto` |
| Puntero | `touchMode` | lista "1"/"2"/"3" (Trackpad / simulada / directo) · `"1"` |
| Puntero | `scaleTouchpad`, `tapToMove`, `zoomFollowsCursor` | bool · `true` |
| Teclado | `showAdditionalKbd`, `showIMEWhileExternalConnected`, `hardwareKbdScancodesWorkaround` | bool · `true` |
| Teclado | `preferScancodes`, `filterOutWinkey` | bool · `false` |

## Funciones puras — `X11SettingsPolicy` (object, `@JvmStatic`)

(hoy no entra como parámetro: ninguna función depende de la fecha)

1. `validateCustomResolution(raw: String): X11ResolutionValidation`
   - Trim; regex `^([0-9]{1,5})x([0-9]{1,5})$`; parse de ambas partes.
   - SI falla el parse o alguna dimensión es `0` ENTONCES `Invalid`
     SI NO `Valid("WxH")` normalizado.
   - Sin límite superior (fuera de alcance). RF-7.
2. `sanitizeScale(raw: Int): Int`
   - Clamp a `30..300` primero, luego al múltiplo de 10 más cercano:
     `((v + 5) / 10) * 10` re-clampeado. Casos: 5→30, 29→30, 147→150,
     100→100, 550→300, 301→300, 30→30. RF-5, RF-9.
3. `sanitizeListValue(raw: String?, values: List<String>, default: String): String`
   - null / blank / no pertenece a `values` ENTONCES `default` SI NO `raw`. RF-9.
4. `selectedIndexFor(values: List<String>, current: String): Int`
   - `values.indexOf(current).coerceAtLeast(0)` (desconocido→0, que es el
     default de todas las listas del núcleo). RF-6.
5. `isExactResolutionVisible(mode: String): Boolean = mode == "exact"` y
   `isCustomResolutionVisible(mode: String) = mode == "custom"`. RF-4.
6. Constantes especulares (valores crudos, comentados con su origen y
   "mantener en sincronía si lorie cambia el submódulo"):
   `OUTPUT_MODES=native/scaled/exact/custom`, `FILTERING_MODES=bilinear/nearest`,
   `ORIENTATION_MODES=auto/portrait/landscape/reverse portrait/reverse
   landscape`, `TOUCH_MODES="1"/"2"/"3"`, más el mapa de defaults de la tabla
   de arriba. La lista de 24 resoluciones NO se espeja (ver decisión 6).

Seudoalgoritmo de `reload()` del ViewModel: para cada clave → getter de
`Prefs` → función de saneado de la policy → campo del UiState (escritura
atómica con `MutableStateFlow.update`, igual que en `SettingsViewModel`).

## Decisiones (con alternativa descartada)

1. **Persistir SOLO vía `Prefs` de lorie** obtenida de `LorieApp.getPrefs(context)`.
   *Alternativa descartada:* duplicar SharedPreferences con las claves escritas
   a mano — drift de claves/defaults, rompe el listener en vivo y duplica la
   resolución de contexto shared-UID que `LorieApp` ya hace en `onCreate`.
2. **Destino `X11` nuevo** en la pantalla de Ajustes (mismo patrón que
   ABOUT/LICENSES) en vez de una sección al final de `SettingsScreen`: 16
   controles en 3 secciones justifican un menú propio; la alternativa
   engordaría indefinidamente una pantalla ya larga.
3. **Reutilizar los tiles existentes** (`SettingsSwitchTile`, `SettingsDialogTile`,
   `SettingsSliderTile`, `SettingsListTile`). *Descartada:* crear componentes
   nuevos de preferencia (duplicación sin beneficio).
4. **Slider de escala con commit al soltar** (`onValueChangeFinished`, patrón
   de fontSize/llvmpipe). *Descartada:* persistir en cada tick como hace lorie
   (`updatesContinuously=true`) — escrituras excesivas por movimiento y
   resincronizaciones constantes del X server.
5. **Enmienda mínima a lorie: `put(int)` en `IntPreference`** (`PrefsProto`).
   El generado de `build.gradle` solo modela `get()` para los `Int` porque el
   standalone los escribe vía SeekBarPreference. Sin esto, persistir
   `displayScale` obligaría a escribir `putInt("displayScale", …)` a mano, lo
   que viola la decisión 3. Commit en el submódulo + pin actualizado
   (precedente: spec 016 T1). *Alternativa descartada:* la escritura manual de
   la clave.
6. **Lista de resoluciones exactas leída en runtime** de
   `Prefs.displayResolutionExact.getValues()` (entries == values en lorie) y
   mostradas tal cual ("1280x1024" no se traduce). *Descartada:* espejar el
   array de 24 en la policy (riesgo de drift). Los arrays pequeños sí se
   espejan en la policy para poder testear puro (drift controlado con
   comentario y sin claves en juego).
7. **Labels traducibles en recursos del app**, con `when` UI valor→recurso
   (patrón `logLevelLabels()`). *Descartada:* reutilizar los strings de lorie
   (solo inglés, viola regla 6).
8. **ViewModel propio** (`X11SettingsViewModel`) en vez de inflar
   `SettingsViewModel`/`SettingsUiState` con 16 campos más: pantalla separada,
   ciclo de vida separado, y la pantalla principal no carga estado X11 que casi
   nunca pinta. *Descartada:* reutilizar el raíz (acoplamiento y UiState
   gigante).

## Estrategia de tests — `./gradlew :app:test`

- **Red primero (T1):** `X11SettingsPolicyTest` con los casos de la policy;
  falla (clase inexistente = estado rojo) antes de implementar (T2).
  - RF-7: "1280x1024" válido; "" / "  " / "0x0" / "0x800" / "1280" / "1280x" /
    "-1x800" / "a x b" inválidos.
  - RF-5: 5→30, 29→30, 147→150, 100→100, 550→300, 301→300.
  - RF-9: null / "" / desconocido → default (por lista touch/orient/filtrado
    y modo de salida).
  - RF-4: visibilidad de exact/custom solo en su modo.
  - RF-6: indexOf de valor desconocido → 0.
- **No testeable en JUnit puro:** ViewModel y Screen (Android). La regla 3
  queda cubierta escribiendo en ellos cero lógica: la policy es la única fuente
  de decisiones. Verificación: `assembleDebug` (T4–T7) + checklist de
  dispositivo (T8).

Qué RF cubre cada parte: T1/T2 (policy + tests) → RF-4/5/6/7/9, RNF-1;
T3 (strings) → RNF-3, RF-6/7; T4 (ViewModel+UiState) → RF-2/3; T5/T6 (screen)
→ RF-4/5/6/7; T7 (wire) → RF-1; T8 → RF-1/8 + gate completo.

## Riesgos

- Drift de los arrays pequeños espejados si el submódulo lorie se actualiza:
  comentario de sincronización en la policy; los defaults están abajo de cada
  constante.
- `Prefs.attach` exige el package context de lorie en builds shared-UID:
  resuelto por `LorieApp`; no tocar ese camino.
- No romper `SettingsScreen`: el parámetro nuevo `onX11Click` lleva default y
  no se cambia ninguna llamada existente más allá del wire.
- Cambios en el submódulo lorie: solo Java puro (`PrefsProto`), sin nativo;
  `assembleDebug` + unzip de control como en 016.
