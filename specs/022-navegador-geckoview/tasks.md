# Tasks 022 — Navegador GeckoView (spec aprobada 2026-10-28)

Orden de dependencia; una tarea a la vez: tests en rojo → código → `./gradlew test`
verde → marcar. F1–F3 antes de tocar UI (ver plan).

- [ ] **T1. Factibilidad F1/F2 en dispositivo.** Fase 0
  - Crear app mínima con el motor añadido temporalmente (o `Demo de GV` standalone
    copiando el quick-start del fabricante con el UID compartido) y medir en el
    Unisoc/A12: arranque del proceso de contenido con X11+proot vivos (F1) y
    `dumpsys meminfo` con web compleja (F2).
  - Hecho cuando: resultado positivo/negativo de F1 y F2 pero **registrado en la
    sección "Resultados de factibilidad" de spec.md**; si negativo, la spec se
    reescopa en frío ANTES de bajar al código de UI.

- [x] **T2. Delta del catálogo + build.** RF-12, RNF-1, F3
  - Añadir repositorio Mozilla en `settings.gradle.kts`, versión del motor
    (`159.0.20261022150525`) en `libs.versions.toml` y `implementation(libs.geckoview)`
    en `app/build.gradle.kts`.
  - Hecho cuando: `./gradlew test` verde (el AAR compila en el classpath y se
    resuelve en local Y en el CI de GH) y `assembleDebug` produce APK con
    `libxul.so` (`unzip -l | grep libxul`); tamaño del APK universal documentado
    en la spec (criterio RNF-3/RNF-1).

- [x] **T3. Funciones puras de navegador.** RF-2, RF-3, RF-4, RF-5, RF-7, RNF-4
  - `BrowserLogic.kt` con las 4 funciones del plan + `BrowserLogicTest.kt`
    (JUnit puro, casos del plan).
  - Hecho cuando: test en rojo primero, luego verde con `./gradlew test`.

- [x] **T4. Ajuste intern/extern (prefs + settings UI).** RF-5, RF-10
  - Clave aditiva `KEY_LINKS_OPEN_INTERNAL` en `TermuxConstants` + getter/setter en
    `TermuxAppSharedPreferences`; fila de ajuste (patrón `terminal_hyperlinks`) en
    Screen/UiState/ViewModel; strings ×3 locales.
  - Hecho cuando: ajuste persiste entre arranques, sin perder ninguna clave ya
    existente; `./gradlew test` verde.

- [x] **T5. Caso sealed Browser + VM.** RF-1
  - `TermuxSessionUiModel.Browser(id, name, initialUrl)` + índice del esquema
    `BrowserSessions.nextSessionName` (unit test) + `createBrowserSession()` en el
    VM (patrón `createX11Session`).
  - Hecho cuando: test del nombre numerado verde y pestaña se activa al crear
    (cobertura del VM como los tests existentes del split).

- [x] **T6. Registro de pestañas (`BrowserTabRegistry` + runtime).** RF-1, RF-9
  - `GeckoRuntimeHolder` y `BrowserTabController` con delegates del plan
    (`onLocationChange`, `onCanGoBack/Forward`, `onTitleChange`, `onPageStop`,
    `onNewSession`, `onCrash`) expuestos por `StateFlow`; `dispose()` cierra la
    sesión y desregistra.
  - Hecho cuando: el registro se crea/borra por id sin fuga (verificación manual
    en cíclo crear/cerrar/rotar en el dispositivo de T11); `./gradlew test` verde.

- [x] **T7. Host Compose de la pestaña.** RF-2, RF-6
  - `BrowserSessionHost.kt` (patrón `X11SessionHost`): `AndroidView{ GeckoView }`,
    barra URL editable (enter → `normalizeUrlInput` → `go`), recargar,
    atrás/adelante por estado, overlay de error con botón "abrir externamente"
    (RF-6/RF-7 vía `openExternally`).
  - Hecho cuando: strings ×3 para todo lo visible; `./gradlew test` verde;
    smoke vivo en dispositivo de T11.

- [x] **T8. Dispatch en la pantalla principal + generación de pestañas.** RF-1
  - Caso `Browser` en `TermuxMainScreen` (colocación como X11: fuera del split),
    entrada de "nueva pestaña de navegador" en el drawer (patrón de X11/Files),
    icono de pestaña en `SessionTabs`. *(Enmienda RF-1 2026-10-28: acciones del
    tab bar movidas al drawer; `SessionTabs` queda como fila pura sin botones.)*
  - Hecho cuando: botón crea/activa pestaña "Navegador"/"Navegador 2", cierre la
    deja fuera sin tocar el service; `./gradlew test` verde.

- [x] **T9. Ruteo de enlaces desde la terminal y More-menu.** RF-4, RF-5, RF-7
  - Callback único `onOpenLink(uri)` en `TermuxComposeActivity` que lee la
    preferencia y llama `routeLink`; `_blank`/`onNewSession` a la misma política
    (RF-8); canvas (2 taps) y More-menu (≈ línea 1430) conectan a la callback.
  - Hecho cuando: con ajuste interno, tap abre pestaña; con externo, como hoy;
    esquemas no-http siempre externos. `./gradlew test` verde.

- [x] **T10. Legado de `TerminalView` por callback de cliente.** RF-4, RF-5
  - Añadir al `TerminalViewClient` (y base en `termux-shared`) el método
    `onHyperlinkOpen(uri)` con default `false` (cauce `ACTION_VIEW` actual intacto);
    el cliente del app sobreescrito→ rutea (return `true`).
  - Hecho cuando: `./gradlew test` verde (los tests existentes del hipervínculo
    del terminal-view no rompen) y la tap del renderizador legado obedece el
    ajuste (T11).

- [ ] **T11. Licencias + Verificación en dispositivo (completa).** RF-11, RNF-3
  - **2026-10-28, parcial del usuario:** probado en dispositivo y funcionando
    ("magnífico"), incluido el ruteo de enlaces del terminal (tap → pestaña nueva).
  Falta por confirmar: el punto formal RNF-3 (procesos GeckoView con UID
  compartido y `dumpsys meminfo` con X11+proot).
  - Entrada del motor en `LicensesScreen` (MPL-2.0); corrida de criterios
    1–7 de la spec en el Unisoc/A12; memoria constatada documentada en la spec
    + tamaño final del APK; R8/`assembleRelease` sanos (consumer rules del
    AAR, binds mínimos si hicieran falta).
  - Hecho cuando: checklist de "Criterios de finalización" de spec.md completa;
    memoria y resultados registrados en spec.md.

- [x] **T12. Solo commits.** *(2026-10-28: el mantenedor decide — T12 se reduce a
  Conventional Commits del código; sin `CHANGELOG.md` ni flujo web/update-site, que
  quedan para la release general de la 3.1.)*
  - Tipo `Added` (capitalizado), commits diferenciados por capa (build/prefs/lógica/
    UI/drawer) según partición acordada; `MEMORY.md` final incluido.
  - Hecho cuando: árbol de commits limpio en la rama; `MEMORY.md` actualizado;
    spec pasa a "implementada y verificada" solo con T11/T12 completos.
