# Spec 017 — Ajustes de X11 dentro de la app

Estado: implementada y verificada en dispositivo (2026-10-25)

## Contexto y objetivo

Las pestañas X11 embebidas (spec 014) y su teclado completo (spec 016) ya
consumen las preferencias del servidor X11 de lorie (`Prefs`, generado desde
`preferences.xml`) y se aplican en vivo: `LorieApp` registra el listener de
cambios y cada pestaña refresca su `LorieView` y su procesador de entrada al
cambiar cualquier clave. Pero **no existe UI para editar esas preferencias**
desde la app: el standalone de lorie no se usa como app normal, y tocar
SharedPreferences con claves escritas a mano es frágil (drift de claves,
defaults duplicados, contexto shared-UID que `LorieApp` ya resuelve).

Objetivo (dirección validada con el usuario, 2026-10-24/25): una pantalla de
ajustes de X11 en Compose nativo, accesible desde la pantalla de Ajustes
existente, que lea/escriba **exclusivamente** a través de la capa de
preferencias existente del servidor (la misma que consumen las pestañas), con
alcance = **solo núcleo** (Salida, Puntero, Teclado). Sin cablear ningún
mecanismo nuevo de aplicación: el refresco en vivo ya funciona; esta spec solo
verifica que persiste.

## Usuarios

- Usuario de X11: abre pestañas X11 (spec 014/016) y quiere acomodar
  resolución, escala, puntero y teclado del display a su dispositivo.
- Usuario general: recorre Ajustes y necesita un punto de entrada claro.
- Mantenedor del fork: compila, publica y valida en dispositivo.

## Historias de usuario

- HU-1. Como usuario de X11, quiero ajustar la salida (modo de resolución,
  resolución exacta o personalizada, escala, filtrado, pantalla completa,
  orientación) para que el display se vea correcto en mi pantalla.
- HU-2. Como usuario de X11, quiero ajustar el puntero (modo táctil, escala de
  touchpad, tap-to-move, zoom sigue al cursor) para controlar apps gráficas
  cómodamente.
- HU-3. Como usuario de X11, quiero ajustar el teclado (barra de teclas extra,
  IME con teclado externo conectado, scancodes, workaround de teclado HW,
  filtro de tecla Windows) para que la entrada se comporte como espero.
- HU-4. Como usuario de X11, quiero que un cambio de ajuste surta efecto en la
  pestaña X11 que tengo abierta sin reiniciarla.
- HU-5. Como usuario general, quiero encontrar "Ajustes X11" dentro de
  Ajustes, junto al resto de secciones, y volver después.

## Definiciones

- **Ajustes núcleo:** las claves seleccionadas en las secciones Salida,
  Puntero y Teclado del `preferences.xml` de lorie (lista cerrada abajo, RF).
  Fuente de verdad de claves, valores de listas y defaults: `preferences.xml`
  del servidor X11; nada se inventa fuera de él.
- **Capa de preferencias del servidor:** la clase `Prefs` de lorie obtenida
  vía `LorieApp.getPrefs(context)` (el `Application` de la app es
  `TermuxApplication extends LorieApp`). Única vía de lectura/escritura.
- **Aplicación en vivo:** el mecanismo YA existente sin wiring nuevo: el
  listener de `LorieApp` y el listener por pestaña (`X11TabController`) que
  hace `reloadPreferences` y re-sincroniza la entrada. La app solo persiste.
- **Valores recolocados:** cuando un valor persistido queda fuera de rango o
  fuera de la lista, se muestra y aplica el default coherente en su lugar.

## Requisitos funcionales (EARS)

- RF-1: CUANDO el usuario entra en Ajustes, EL SISTEMA muestra la entrada
  "Ajustes X11" (mismo patrón que la entrada "Acerca de") que navega a una
  pantalla propia de ajustes de X11; MIENTRAS se navega hacia atrás desde
  ella, EL SISTEMA regresa a Ajustes intacta.
- RF-2: CUANDO se abre la pantalla de ajustes X11, EL SISTEMA muestra el valor
  vigente de TODAS las claves del núcleo, leídas de la capa de preferencias del
  servidor (nunca de una copia ni de valores escritos a mano).
- RF-3: CUANDO el usuario cambia un conmutador del núcleo (`fullscreen`,
  `scaleTouchpad`, `tapToMove`, `zoomFollowsCursor`, `showAdditionalKbd`,
  `showIMEWhileExternalConnected`, `preferScancodes`,
  `hardwareKbdScancodesWorkaround`, `filterOutWinkey`), EL SISTEMA persiste el
  cambio al instante en la capa de preferencias del servidor.
- RF-4: CUANDO el usuario elige el modo de resolución (`displayResolutionMode`:
  native / scaled / exact / custom), EL SISTEMA lo persiste; CUANDO el modo es
  exact, EL SISTEMA ofrece la lista de resoluciones del servidor
  (`displayResolutionExact`, default 1280x1024); CUANDO el modo es custom,
  ofrece el campo de texto `displayResolutionCustom`; SI el modo no es exact
  ni custom, ENTONCES el control correspondiente no se muestra.
- RF-5: CUANDO el usuario confirma la escala (`displayScale`, rango 30–300,
  paso 10, default 100), EL SISTEMA persiste siempre un múltiplo de 10 dentro
  del rango.
- RF-6: CUANDO el usuario elige una opción de cualquiera de las listas
  (resolución exacta, `displayFilteringMode` bilinear/nearest,
  `forceOrientation` auto/portrait/landscape/reverse portrait/reverse
  landscape, `touchMode` 1/2/3), EL SISTEMA muestra las opciones reales del
  servidor y persiste el valor crudo seleccionado; la etiqueta visible se toma
  de los recursos de idioma del app, nunca literal en la UI.
- RF-7: CUANDO el usuario confirma la resolución personalizada, EL SISTEMA
  valida el formato anchoxalto (ambas dimensiones numéricas y > 0) antes de
  persistir; SI el formato no es válido (vacío, espacios, "0x0", "1280",
  "1280x", negativos, no numérico), ENTONCES EL SISTEMA lo indica con texto de
  los recursos y conserva el valor vigente sin persistir nada.
- RF-8: MIENTRAS hay una pestaña X11 conectada y el usuario cambia cualquier
  ajuste del núcleo, EL SISTEMA aplica el efecto en la pestaña abierta sin
  reiniciarla ni tocar sesiones (por el refresco en vivo ya existente; esta
  spec no añade wiring, solo lo verifica manualmente en dispositivo).
- RF-9: SI una clave del núcleo no tiene valor persistido o alguno persistido
  es desconocido/fuera de rango (instalación nueva, edición manual,
  actualización), ENTONCES EL SISTEMA muestra y usa el valor por defecto
  coherente con `preferences.xml` (degradación sin crash).

## Requisitos no funcionales

- RNF-1 (regla 3): validación de resolución, saneado de escala, recolocación
  de valores de lista y mapeo índice↔valor viven en lógica pura con tests
  JUnit sin Android; la UI solo lee y despacha.
- RNF-2 (regla 1): cero dependencias nuevas; solo lo que ya está en el
  catálogo (material3/lifecycle/coroutines ya presentes).
- RNF-3 (regla 6): todo texto visible en `strings.xml` (EN + `values-es-rVE` +
  `values-pt-rBR`); el grep de literales de la constitución no devuelve nada
  nuevo.
- RNF-4 (regla 5): nada se borra ni re-serializa: se usan los accesores de la
  capa de preferencias tal cual existen (solo se añade el `put()` que falta en
  `IntPreference` de lorie, simétrico a los demás — ver plan); ninguna clave
  preexistente se pierde.
- RNF-5: `./gradlew test` es el único gate de CI.

## Casos límite

- Primera apertura sin valores persistidos: se muestran y aplican los defaults
  de la fuente de verdad (RF-9).
- Valor persistido fuera de rango (p. ej. `displayScale=500` o `touchMode="9"`):
  se recoloca (escala dentro de 30–300 con paso 10; valor de lista al default)
  sin crash ni escritura corrupta (RF-9).
- Resolución personalizada rechazable una a una: vacío, con espacios, "0x0",
  dimensión única ("1280"), separador mal puesto, dimensión en 0 o negativa,
  no numérico; en todos los casos no se persiste nada (RF-7).
- Resolución válida tipo 1280x1024 se persiste tal cual (no hay límite
  superior: fuera de alcance restringirlo).
- Rotación mientras se edita la resolución personalizada: el texto y el mensaje
  de error se conservan.
- Cambios hechos por otro camino (standalone, edición manual) mientras la
  pantalla está abierta: al volver a la pantalla se re-lee y muestra el valor
  vigente (mismo patrón de reload que Ajustes).
- Dos pantallas compitiendo (Ajustes y pestaña X11 abiertas al mismo proceso):
  ambos extremos leen la misma capa; no hay estado duplicado que diverja.

## Fuera de alcance (explícito, decisión del usuario)

- Sección ekbar completa y editor `extra_keys_config`.
- Sección userActions (`swipeUpAction`, `volumeUpAction`, …).
- `enableAccessibilityService`, `enableAccessibilityServiceAutomatically`.
- `requestNotificationPermission`.
- `additionalKbdVisible` (estado interno, no ajuste).
- Gestión de display secundario: `storeSecondaryDisplayPreferencesSeparately`
  (se maneja siempre el display principal; `getPrefs(context)` decide).
- `screenIdleTimeout`, `hideCutout`, `displayStretch`, `Reseed`, `PIP`,
  `adjustResolution`.
- Resto del puntero: stylus (`showStylusClickOverride`, `stylusIsMouse`,
  `stylusButtonContactModifierMode`), gamepad (`ignoreGamepadEvents`),
  captura de puntero (`pointerCapture`, `transformCapturedPointer`,
  `capturedPointerSpeedFactor`), `showMouseHelper`.
- `dexMetaKeyCapture`, `pauseKeyInterceptingWithEsc`, `enforceCharBasedInput`.
- El standalone de lorie (`MainActivity`, `LoriePreferences` XML) no se toca.
- Cualquier wiring nuevo de aplicación en vivo (ya existe).

## Criterios de finalización

- Spec aprobada; plan y tareas redactados e implementados en orden.
- Tests rojos → verdes de la lógica pura; `./gradlew test` completo en verde.
- `./gradlew assembleDebug` produce APK sin regresiones (sin literales Compose;
  strings ×3 completas).
- Verificación en dispositivo (checklist en T8): acceso desde Ajustes, listas/
  sliders/conmutadores persisten (cerrada y reabierta la pantalla muestra los
  valores), y un cambio (p. ej. `touchMode` o `showAdditionalKbd`) se refleja
  en una pestaña X11 conectada sin reiniciar (RF-8).
- `MEMORY.md` actualizado y estado de la spec anotado.

## Dudas abiertas

Ninguna. Dos puntos quedan fijados por la fuente de verdad, no se re-deciden:

- Filtrado de pantalla: los valores reales del array son **bilinear / nearest**
  (default bilinear) — la variante "none/sharp" citada en la discusión no existe
  en `preferences.xml`.
- Orientación forzada: el array tiene **5** valores (auto, portrait, landscape,
  reverse portrait, reverse landscape; default auto) — se ofrecen los cinco.
