# Tareas — Spec 017

Estado: aprobado (2026-10-25, junto a la spec)

- [x] **T1. Tests en rojo de `X11SettingsPolicy`.** RF-4, RF-5, RF-6, RF-7, RF-9
  - Hecho cuando: existe `app/src/test/java/com/termux/terminal/compose/settings/X11SettingsPolicyTest.kt`
    con los casos del plan (validación `WxH`: "1280x1024" válido y
    "" / "  " / "0x0" / "0x800" / "1280" / "1280x" / "-1x800" / "a x b"
    rechazados; escala 5→30, 29→30, 147→150, 100→100, 550→300, 301→300;
    valor de lista null/blank/desconocido → default; índice de valor
    desconocido → 0; visibilidad exact/custom solo en su modo) y
    `./gradlew :app:test` falla porque la clase no existe aún.
- [x] **T2. `X11SettingsPolicy` en verde.** RF-4, RF-5, RF-6, RF-7, RF-9
  - Hecho cuando: la policy está implementada (funciones 1–5 del plan +
    constantes especulares de arrays pequeños con comentario de
    sincronización) y `./gradlew :app:test` está en verde.
- [x] **T3. Strings ×3 de la pantalla.** RF-6, RF-7, RNF-3
  - Hecho cuando: `values/strings.xml`, `values-es-rVE/strings.xml` y
    `values-pt-rBR/strings.xml` contienen las claves nuevas: entrada
    "Ajustes X11", título de pantalla, títulos de las 3 secciones
    (Salida/Puntero/Teclado), tamaños/subtítulos de todos los tiles,
    labels traducibles de los modos (táctil 3, orientación 5, filtrado 2),
    texto de error de resolución personalizada y botón atrás;
    `grep -rnP '(Text|contentDescription)\(\s*"' --include=*.kt app/src/main`
    no devuelve literales nuevos.
- [x] **T4. Enmienda lorie + `X11SettingsUiState` + `X11SettingsViewModel`.**
    RF-2, RF-3, RF-5, RNF-4
  - Hecho cuando: `IntPreference.put(int)` añadido en `PrefsProto` de lorie
    (simétrico a Boolean/String), commit en el submódulo y pin actualizado en
    el repo principal; el ViewModel obtiene `Prefs` vía
    `LorieApp.getPrefs(context)`, `reload()` rellena el UiState pasando cada
    valor por la policy, y cada setter persiste con el acceso nativo
    correspondiente de `Prefs` (cero claves escritas a mano);
    `./gradlew :app:assembleDebug` compila.
- [x] **T5. `X11SettingsScreen` — sección Salida.** RF-4, RF-5, RF-6, RF-7
  - Hecho cuando: la pantalla (nueva, misma familia que la de Ajustes, botón
    atrás vía callback) muestra: diálogo de modo de resolución (4 valores),
    control condicional de resolución exacta (valores leídos en runtime de
    `displayResolutionExact.getValues()`), campo de texto custom con error
    desde la policy y persistencia solo si es válido, slider de escala 30–300
    paso 10 con commit al soltar, diálogo de filtrado (2), switch de
    fullscreen y diálogo de orientación (5); todos los textos vía recursos;
    `assembleDebug` OK.
- [x] **T6. `X11SettingsScreen` — secciones Puntero y Teclado.** RF-3, RF-6
  - Hecho cuando: Puntero muestra el diálogo de modo táctil (3 valores con
    labels traducidos, default "1") y los switches scaleTouchpad/tapToMove/
    zoomFollowsCursor; Teclado muestra los 5 switches (showAdditionalKbd,
    showIMEWhileExternalConnected, preferScancodes,
    hardwareKbdScancodesWorkaround, filterOutWinkey); cada cambio persiste al
    instante y refleja el valor de la policy; `assembleDebug` OK.
- [ ] **T7. Acceso desde Ajustes.** RF-1
  - Hecho cuando: `SettingsScreen` muestra la entrada "Ajustes X11"
    (`SettingsListTile` + chevron, junto a "Acerca de") con parámetro
    `onX11Click` con default; `SettingsComposeActivity` añade el destino `X11`
    al enum y compone la pantalla nueva con retorno a Ajustes;
    `./gradlew :app:assembleDebug` OK y la entrada navega ida y vuelta.
- [x] **T8. Verificación y cierre.** RF-1, RF-8, RF-9, RNF-5
  - Completada 2026-10-25. Automatizado: `./gradlew test` completo en verde
    (gate de CI); `assembleDebug` produce APK controlado con unzip:
    `lib/arm64-v8a/libXlorie.so` presente y strings ×3 en `resources.arsc`.
    Dispositivo (verificado por el usuario): acceso desde Ajustes ida y vuelta
    (RF-1), primera apertura con defaults (RF-9), persistencia al salir/volver,
    "0x0" rechazado sin persistir (RF-7) y un cambio (`touchMode`/
    `showAdditionalKbd`) reflejado en pestaña X11 conectada sin reiniciar
    (RF-8). `MEMORY.md` y estado de spec/plan anotados.
