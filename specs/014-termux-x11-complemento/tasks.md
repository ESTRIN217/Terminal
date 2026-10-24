# Tareas — Spec 014 (termux:x11)

Nota de orden: el build nativo de lorie (xserver) es largo; en T1 solo se verifica
la configuración del grafo, y el build completo con `libXlorie.so` se hace en T9.

- [x] **T1. Enlazo build del submódulo.** RF-1
  Submódulo git (`termux-x11/` desde el clon ya hecho, pin del SHA actual),
  `settings.gradle` con mapeo `:termux-x11:lorie` y alias `:shell-loader:stub`,
  `app/build.gradle.kts` con `implementation project(":termux-x11:lorie")`,
  adaptaciones AGP 9 en `lorie/build.gradle` (`compileSdk 34`, `packaging`,
  kotlin 2.4.20, `abiFilters arm64-v8a`) y alias `androidx-lifecycle-viewmodel-ktx`
  en el catálogo.
  - Hecho cuando: `./gradlew help` configura todo el grafo sin errores y
    `./gradlew projects` muestra `:termux-x11:lorie` y `:shell-loader:stub`.
- [x] **T2. Decoplo LorieView.** RF-4..RF-7
  En el submódulo: nueva interfaz `LorieView.Client`, refs a MainActivity
  re-canalizadas por ahí, `MainActivity implements Client` (delegación),
  manifest de lorie sin `<application>` attrs ni LAUNCHER/SECONDARY_HOME y
  sin `LorieApp$Receiver`, commit del submódulo en su repo (+pin nuevo).
  - Hecho cuando: `classes` de lorie compila en el build de parent y
    `grep '@mipmap\|MAIN\|LAUNCHER\|android:name=".LorieApp"'` del manifest
    no deja nodos viejos.
- [x] **T3. Constantes y strings ×3.** RNF-2/RNF-3
  `TermuxConstants` (acciones, extras, nombre de comando) y
  `strings.xml` EN/`values-es-rVE`/`values-pt-rBR` con: item X11, overlay no server,
  botón copiar, instrucciones, error de lib no cargada. Los strings de vistas de
  lorie se reutilizan tal cual (no se duplican).
  - Hecho cuando: `grep -rnP '(Text|contentDescription)\(\s*"' --include=*.kt app/src/main`
    no añade literales y los strings nuevos están en los 3 locales.
- [x] **T4. Aplicación y receiver.** RF-4
  `TermuxApplication extends LorieApp`, manifest del app con `tools:replace` para
  `android:name`/`allowBackup`, receiver `X11BroadcastReceiver` (Kotlin) con los
  filter de acciones y pasamanos a `X11SessionBus`.
  - Hecho cuando: `./gradlew :app:mergeGeneralDebugManifest` produce manifest con
    receiver nuevo, sin icono LAUNCHER X11 y Application names coherentes.
- [x] **T5. Funciones puras de companion/tag.** RF-8/RNF-1
  `X11CompanionCommand.kt` (build/parse display/tag) + tests JUnit en rojo → verde
  (`X11CompanionCommandTest`).
  - Hecho cuando: `./gradlew :app:test` queda verde y los casos borde están
    cubiertos (sin display, display no válido, tag vacío, tag con espacios).
- [x] **T6. Modelo de sesión X11.** RF-2/RNF-1
  `X11Sessions.kt` puro (ids/nombre sin colisión, resolución tag→sesión) +
  `TermuxSessionUiModel.X11` en sealed + factory/cierre en `TermuxViewModel`
  (no toca TermuxService) + tests JUnit.
  - Hecho cuando: tests JUnit en verde y el tipo queda funcional sin depender de
    Android.
- [x] **T7. Bus y controller.** RF-4..RF-7
  `X11SessionBus` (tag→binder, linkToDeath, deduplicate + reportFatalError),
  `X11TabController` (client surrogate; loop de conexión, listeners de prefs, extra keys).
  - Hecho cuando: `./gradlew test` sigue en verde (nuevos tests puros si aparecen) y
    el controller compila sin referenciar MainActivity.
- [x] **T8. Host Compose y drawer.** RF-2..RF-6, RF-8
  `X11SessionHost` (AndroidView(LorieView) + extra keys de lorie + overlay no server
  con botón copiar), integración en `SessionTabs`/`TermuxMainScreen`, ítem en
  `NavigationDrawerContent` con callback a ViewModel.
  - Hecho cuando: `./gradlew assembleDebug` tiene éxito, sin literales Compose y
    con la entrada X11 dibujada en el drawer.
- [x] **T9. Build completo + inspección APK.** RF-1
  `./gradlew assembleDebug` con build nativo de lorie (`libXlorie.so`) arm64-v8a;
  inspección `unzip -l` (so, receiver, manifest, sin icono duplicado).
  - Hecho cuando: `lib/arm64-v8a/libXlorie.so` aparece en APK y tests siguen verde.
- [x] **T10. Documento y memoria.**
  Página wiki con instrucciones del companion (comando real y casos proot),
  `MEMORY.md` actualizado con estado de la spec y aprendizajes (build nativo largo).
  - Hecho cuando: página wiki enlazada y memoria ≤ ~50 líneas.
- [x] **T11. Loader guest (enmienda A, RF-8).**
  `ProotShellEnvironment` inyecta `TERMUX_X11_APK` (sourceDir de la app, null-safe);
  `TermuxX11GuestLoaderLibrary` (Java, `com.termux.shared.termux.x11`) con partes
  puras (`buildWrapperScript`, `applyApkPathEnv`) y `ensureWrapperScript(context)`
  idempotente escribe `<rootfs>/usr/bin/termux-x11` (chmod +x); constantes
  `X11_GUEST_WRAPPER_HOST_PATH` en `TermuxConstants.TERMUX_X11`; gancho de
  escritura en `TermuxService.onStartCommand`. Tests JUnit en termux-shared
  en rojo → verde.
  - Hecho cuando: tests de librería en verde y el script existe tras abrir la app.
- [x] **T12. Comando primario del overlay (enmienda A, RF-8).**
  `X11CompanionCommand.guestCommand(display, tag)` → `termux-x11 :0 [-tag t]`;
  `chooseCommand` decide primario (wrapper presente) vs one-liner fallback
  (rutas absolutas); `X11TabController.companionCommand` usa
  `File(X11_GUEST_WRAPPER_HOST_PATH).isFile()`; tests en
  `X11CompanionCommandTest` en rojo → verde. Wiki `x11.html` actualizada
  (primario `termux-x11`, one-liner como avanzado).
  - Hecho cuando: `./gradlew test` verde, `assembleDebug` OK y wiki revisada.
- [x] **T13. Spawn lado app del companion (enmienda B, RF-8).**
  `X11CompanionCommand.buildSpawnArgs(display, tag)` (puro, argv para
  `app_process`, tests primero); `X11CompanionSpawner.spawn(context, display,
  tag)` (ProcessBuilder con `CLASSPATH = sourceDir`, argumentos igual al
  one-liner, salida descartada en hilo daemon, `Error` con fallo);
  `X11TabController.startCompanion()` y botón primario en el overlay
  `NoServerOverlay` (en fallo, toast con error y el comando pegable queda
  como fallback). Strings ×3. Wiki actualizada (spawn en el dispositivo,
  pegar solo como avanzado).
  - Hecho cuando: tests de spawn args en verde, `./gradlew test` pasa,
    `assembleDebug` OK y el botón arranca el companion en dispositivo.
