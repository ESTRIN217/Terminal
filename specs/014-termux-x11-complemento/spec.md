# Spec 014 — Complemento termux:x11 como librería y pestañas con acceso desde el drawer

Estado: completada (T1–T13 + enmiendas A/B; verificada en dispositivo: spawn del companion, carga de lib nativa desde nativeLibraryDir, socket X en TMPDIR del rootfs, display en pestaña con apps gráficas)

## Contexto y objetivo

La app Terminal (fork ESTRIN217) solo ofrece terminal y gestor de archivos; correr
aplicaciones gráficas del entorno Debian (proot) exige un servidor X11 externo. El
repositorio https://github.com/ESTRIN217/termux-x11 (Ya clonado con sus submódulos)
contiene la reorganización de Termux:X11 como librería Android: el módulo `lorie`
incluye el servidor X completo (fuentes C/C++ de Xorg + submódulos compilados por
NDK/CMake), el cliente `LorieView`, preferencias (`Prefs` generado desde
`preferences.xml`) y un `build.gradle` que ya resuelve el `APPLICATION_ID` desde la
app host que lo dependa.

Objetivo: integrar `lorie` como librería del proyecto Terminal y mostrar el display
X11 en pestañas de sesión dentro de la app (patrón de la pestaña de filemanager),
con entrada en el drawer.

## Usuarios

- Usuario de Terminal que ejecuta aplicaciones gráficas del entorno Debian y quiere
  verlas en la propia app.
- Mantenedor del fork, que compila y publica la app.

## Historias de usuario

- HU-1. Como usuario, quiero abrir una pestaña X11 desde el drawer para ver mi
  display gráfico sin salir de la app.
- HU-2. Como usuario, quiero que la pestaña X11 muestre un estado claro cuando no
  hay servidor X corriendo, para saber cómo arrancarlo.
- HU-3. Como usuario, quiero usar teclado, gestos táctiles y la barra de teclas
  extra de lorie sobre el display X11 desde la pestaña.
- HU-4. Como usuario, quiero poder cerrar la pestaña X11 sin que se afecten las
  sesiones de terminal ni el servidor X en ejecución.

## Definiciones

- **Servidor X (lorie nativo):** binario embebido `libXlorie`; corre lanzado desde
  la fila de comandos mediante `CmdEntryPoint` (vía `app_process` con el CLASSPATH
  del propio APK) en un proceso del mismo UID com.termux.
- **Companion:** el proceso servidor lanzado desde la shell; emite el broadcast
  `com.termux.x11.CmdEntryPoint.ACTION_START` con un token binder
  (`ICmdEntryInterface`) para que la pestaña cliente se conecte.
- **Pestaña X11:** sesión de UI polimórfica dentro de `TermuxSessionUiModel`, igual
  que la de filemanager; nunca toca `TermuxService`.
- **LorieView:** `SurfaceView` cliente que recibe un fd del servidor X y dibuja el
  display; gestiona entrada, IME y portapapeles.
- **Drawer:** menú de navegación lateral de la pantalla principal Compose
  (`NavigationDrawerContent.kt`).

## Requisitos funcionales

- RF-1: EL SISTEMA compila `lorie` como librería Android consumida por `app`
  (`implementation project(...)`) sin romper el build existente
  (`./gradlew assembleDebug` con arm64-v8a). *Verificable:* el APK resultante
  contiene `lib/*/libXlorie.so`.
- RF-2: CUANDO el usuario pulsa la entrada X11 del drawer, EL SISTEMA crea una
  pestaña X11 de tipo polimórfico con su propio id, queda activa y no toca
  `TermuxService`. *Verificable:* cerrarla luego no mata sesiones terminales; reproducir en logs/estado.
- RF-3: MIENTRAS la pestaña X11 está activa y no hay servidor conectado, EL SISTEMA
  muestra un estado "no server" con texto alineado a los recursos de strings
  (EN/ES/PT) que indica cómo arrancarlo. *Verificable:* sin literales en Compose.
- RF-4: CUANDO un companion emite `ACTION_START` con token binder hacia el paquete
  de la app, EL SISTEMA conecta el `LorieView` embebido de la pestaña (de la
  etiqueta/tag correspondiente si aplica) al fd entregado por el servidor.
*Verificable:* pestaña muestra el display cuando hay clients conectados.
- RF-5: CUANDO la pestaña X11 recibe eventos de touch o teclado, EL SISTEMA los
  envía al display X mediante el pipeline de entrada de lorie
  (`TouchInputHandler`/`InputStub`), respetando sus modos de emulación.
- RF-6: MIENTRAS la pestaña X11 está activa con el teclado extra habilitado en las
  preferencias de lorie, EL SISTEMA muestra y dispara la barra de teclas extra.
- RF-7: CUANDO el usuario cierra la pestaña X11, EL SISTEMA la elimina del estado
  de sesiones y desconecta el cliente sin detener al companion ni a `TermuxService`.
- RF-8: SI no existe aún servidor X corriendo cuando el usuario abre la pestaña,
  ENTONCES EL SISTEMA ofrece el comando de arranque del companion listo para
  copiar al portapapeles (derivado del APPLICATION_ID instalado) y una
  instrucción visible con los pasos resumidos.
  - **Enmienda A (loader guest, T11/T12):** la app inyecta en el env del guest
    proot `TERMUX_X11_APK = applicationInfo.sourceDir` y escribe de forma
    idempotente un wrapper en el rootfs (`/usr/bin/termux-x11`, shebang bash,
    `exec /system/bin/app_process / --nice-name=termux-x11
    com.termux.x11.CmdEntryPoint "$@"` con `CLASSPATH=${TERMUX_X11_APK:?}`),
    replicando el mecanismo del paquete deb upstream sin deb ni loader aparte.
    Las sesiones proot enlazan además `/apex` (intérprete ELF de `app_process`
    en Android 11+ y libs de ART) y `/linkerconfig` (config de namespaces del
    linker generada en el arranque; sin ella `app_process` muere con
    CANNOT LINK por `libnativeloader.so`) para que el companion corra dentro
    del guest.
    EL SI el wrapper existe ENTONCES el comando primario del overlay es
    `termux-x11 :0 [-tag t]`; SI NO, se conserva el one-liner con rutas
    absolutas `/system/bin/*` como fallback. La escritura del wrapper es barata
    (comparación de contenido) y se ejecuta en el arranque de sesión.
  - **Enmienda B (spawn lado app, T13):** verificado en dispositivo (Unisoc,
    Android 12, app uid): el guest proot no puede leer `/linkerconfig` ni
    `/apex` en claro (`can't sanitize binding: Permission denied`), así que el
    exec de `app_process` dentro del guest no consigue namespaces y muere con
    CANNOT LINK. EL SISTEMA respeta la vía upstream Termux: la pestaña ofrece
    un botón que **inicia el companion desde la propia app** (exec directo de
    `/system/bin/app_process` con `CLASSPATH = applicationInfo.sourceDir`,
    argv idéntico al one-liner, salida descartada, sin proot). EL SI el spawn
    falla (p. ej. SELinux) ENTONCES el overlay muestra el error y conserva el
    comando pegable como fallback. El botón es re-disparable: `CmdEntryPoint`
    re-anuncia un servidor vivo.

## Requisitos no funcionales

- RNF-1: La lógica nueva determinista (parseo del comando companion, mapeo de tag,
  estado de conexión) va en funciones puras con tests JUnit que corren sin Android.
- RNF-2: Todo texto visible vive en `strings.xml` (EN, `values-es-rVE/`,
  `values-pt-rBR/`).
- RNF-3: Constantes nuevas (`ACTION_START`, extras, nombres de comando, paths)
  van a `TermuxConstants.java`.
- RNF-4: Sin dependencias nuevas fuera del catálogo aunque las que exige `lorie`
  (kotlin-stdlib, `androidx.preference`, `lifecycle-viewmodel-ktx`) se justifican
  en el plan como requeridas por la librería.
- RNF-5: `./gradlew test` es el único gate de CI; el build nativo de lorie no
  añade flakiness (compilación reproducible arm64-v8a; ABI splits según env).
- RNF-6: `targetSdk 28`/`minSdk 24` intactos; lorie se compila como librería
  (su `targetSdkVersion 34` declarado no gobierna al app).

## Casos límite

- Servidor X corriendo ANTES de abrir la pestaña (companion ya activo): la pestaña
  se reconoce y conecta al abrirse (heartbeat / broadcast pendiente o recordatorio
  re-registrado).
- Companion muerto en plena sesión: la pestaña vuelve al estado "no server" con
  estado de conexión explícito, sin crash.
- Varias pestañas X11 (una por display con `-tag`): cada pestaña se conecta a su
  display correspondiente; el cambio de tag se resuelve en función pura.
- Rotación/cambio de configuración con la pestaña X11 activa: el estado se
  conserva como con el resto de pestañas.
- Instalación donde el binario nativo no existe (APK antiguo/ABI distinta): estado
  de error explícito en vez de crash al cargar `libXlorie`.

## Fuera de alcance

- App standalone `lorie-app` y loader `shell-loader` como APK separado.
- Duplicar el icono launcher de Termux:X11 en el launcher del sistema (se anula el
  intent-filter LAUNCHER de lorie en la fusión de manifiesto, salvo decisión en contrario).
- Pantalla completa/PiP, botones auxiliares de stylus/ratón refinados, sync de
  clipboard avanzado: se cede a las capacidades existentes de lorie sin prometer ajustes.
- Integración con `termux-x11-preference` (herramienta companion).
- Pantalla de preferencias X11 embebida en la app (`LoriePreferences`), dejada para
  una iteración posterior.

## Criterios de finalización

- Spec aprobada, plan y tareas redactados e implementados en orden.
- Tests `./gradlew test` en verde (Junit para nuevas funciones puras).
- `./gradlew assembleDebug` produce APK con `libXlorie.so` y las activity/host
  integrados; inspección del APK (unzip) para confirmar.
- Pestaña X11 accesible desde el drawer y conectable a companion en dispositivo
  (checklist de dispositivo análogo a specs previas).
- `MEMORY.md` actualizado.

## Decisiones tomadas (aclaración del usuario, 2026-10-22)

- **Integración:** submódulo git en la raíz (`termux-x11/`, submódulo del fork
  ESTRIN217 cuyo árbol ya está clonado), enlazando `:termux-x11:lorie` y
  `:termux-x11:shell-loader:stub` desde `settings.gradle`.
- **Pestaña:** tercer tipo polimórfico de sesión que **embebe `LorieView`** en la
  pestaña Compose (host por sesión como `FileManagerSessionHost`); requiere un
  client surrogate que satisfaga las dependencias que `LorieView` resuelve hoy vía
  `MainActivity` (prefs, teclado extra, `handleKey`, `InputHandler`).
- **Arranque del companion:** botón en el estado "no server" que copia el comando
  listo para pegar en una sesión de terminal (`CLASSPATH=$(pm path ...) app_process
  ... com.termux.x11.CmdEntryPoint :0`, derivado del APPLICATION_ID real);
  instrucciones en la wiki de la app.
- **Extras:** sin icono launcher standalone (se anula el intent-filter LAUNCHER de
  lorie en la fusión de manifest con `tools:node`); `MainActivity` se mantiene sin
  LAUNCHER para soportar ventanas `-tag` separadas; `LoriePreferences` queda
  **fuera de alcance** en esta iteración.
