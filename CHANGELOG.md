# Changelog

Todos los cambios relevantes de Terminal. El formato sigue
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el versionado es
[SemVer](https://semver.org/lang/es/).

Este proyecto es un fork independiente de [termux/termux-app](https://github.com/termux/termux-app)
y no está afiliado al equipo de Termux.

## [Sin publicar]

### Añadido

#### Terminal
- **`DISPLAY` pre-exportado en el guest Debian** (spec 015): todas las sesiones proot
  arrancan con `DISPLAY=:0` (el display del companion X11), de forma que las apps
  gráficas no requieren `export DISPLAY=:0` manual en cada sesión; un export
  manual posterior (p. ej. `DISPLAY=:1`) lo sobrescribe como antes.
- **Teclado completo en pestañas X11** (spec 016): las teclas hardware, enter/borrar
  del teclado virtual y la barra de teclas extra inyectan vía el procesador de
  teclas del standalone (lorie `InputEventSender` reutilizado), con macros de
  modificadores, paste del portapapeles y zoom del renderer.
- **Pestañas X11 con el companion lanzado desde la app** (spec 014): entrada X11 en
  el drawer con pestaña de escritorio; el estado *«No X server is running»* arranca el
  companion ejecutando `app_process` directamente (APK en `CLASSPATH`) y el servidor
  se anuncia a la pestaña por broadcast; los sockets viven en el `TMPDIR` del rootfs
  (keymaps XKB de `xkb-data`) y los extras/teclado/touch usan el pipeline de entrada
  de lorie.
- **Aceleración GPU vía VirGL** (spec 013): segunda vía sin root agnóstica del
  fabricante para GPUs sin `KGSL` (Mali, PowerVR, Adreno sin KGSL, gráficas
  integradas genéricas): `virgl_test_server` compilado en el pipeline del fork y embebido en el APK, activado
  desde Ajustes → «Estado GPU» → «Aceleración GPU vía VirGL»; el socket por sesión
  se limpia al cerrarla (reversible). Ver ruta y validación en la wiki.
- **Ajustes X11 en Compose** (spec 017): pantalla `Ajustes → «Ajustes X11»` con
  resolución/escala, filtrado, orientación y modo de touch de las pestañas; persiste
  en la misma capa de preferencias de lorie (`Prefs`) y los cambios llegan a la
  pestaña conectada al instante, sin reiniciarla.

### Cambiado

#### Terminal
- **Companion X11: retirada la vía del *guest*** (spec 018-A): el arranque del servidor X
  desde Debian/proot ya no existe (el `app_process` del guest está bloqueado por el
  kernel de los dispositivos objetivo); el companion se lanza **solo desde la app** y la
  pantalla «No X server» ya no muestra comando copiable, solo el botón
  «Iniciar el servidor X».
- **Los binarios embebidos se actualizan con el APK** (spec 018-A): si un
  proot/virgl test server embebido difiere del asset del APK en tamaño, el instalador
  lo reemplaza (antes solo lo copiaba si no existía, quedándose la versión antigua).

### Corregido

#### Terminal
- **El gestor de archivos no detectaba neovim** (enmienda spec 009, RF-2): la lista
  `DEBIAN_EDITOR_CANDIDATES` no incluía `nvim`, así que tras `apt install neovim` la
  acción "Abrir con… › Rootfs" avisaba "no hay editor" (o el selector no lo ofrecía)
  aunque el binario existiera en `usr/bin/nvim`. Ahora se sondea junto al resto.
- **`can't sanitize binding "/linkerconfig"`** (spec 018): proot (`new_binding()`)
  acepta el binding `/linkerconfig` con `EACCES` en fase CLI (espejo de canon.c,
  termux/proot#254); desaparece el warning al arrancar Debian.
- **`btop` no arrancaba bajo SELinux en stock** (spec 019): overlays de `/proc/cpuinfo`
  (snapshot verbatim + línea `model name` desde `SOC_MODEL`) y `/proc/uptime`
  (snapshot vivo del sampler que avanza por tick) servidos por el mismo punto de binds
  de `/proc`; btop resuelve la CPU y el uptime sin leer `/sys/devices` (readdir denegado).

### Notas
- **Limitación conocida:** `btop` exige ≥80 columnas × 24 líneas; en ventanas más
  estrechas muestra `Terminal size too small` (rotar / reducir fuente).


## [2.1.0] — 2026-10-21

### Añadido

#### Terminal
- **URLs impresas clicables en ambos renderers**: la detección de renglones con URL se ha extraído
  a `TerminalUrlScanner` (en `terminal-emulator`, compartida por los dos renderers y el menú
  «Más»), la vista legada subraya y abre por toque, y el canvas Compose hace lo propio, con
  paridad de esquemas con los hipervínculos OSC 8.
- **Identidad de proceso del terminal**: establece `comm` de una TUI propia con `prctl(PR_SET_NAME)`
  vía JNI (`TerminalProcessName`), de forma que herramientas como `fastfetch`/`neofetch` detectan
  el terminal.
- **Kitty Graphics**: colocaciones múltiples y recorte del origen de la imagen, con los
  placeholders visibles en ambos renderers.

#### Gestor de archivos
- **Iconos por tipo de fichero**: coincidencia por extensión con variants por tinte para
  documentos de Groovy DSL (elefante de Gradle en azul), Kotlin, scripts, imágenes, vídeos,
  texto plano, código fuente, etc.
- **Topbar con breadcrumb clicable y menú «Organizar»**: título con carpeta y recuento
  (n ficheros / n carpetas), tren de carpetas navegable, cajón de marcadores (favoritos
  persistentes), orden y modo de vista con preferencias por carpeta, y atajos del launcher
  para carpetas y archivos (`ACTION_OPEN_FILE_MANAGER`).
- **Selección en la topbar**: segundo estado de la barra con «n seleccionados» y acciones
  copiar / cortar / copiar ruta / compartir / editar / borrar. La `BottomAppBar` se retira.
- **Menú por ítem con acciones de riqueza**: abrir con el sistema (FileProvider) o con un editor
  del *guest* Debian si existe (`nano`/`vim` detectados), **Comprimir…** con zip/tar.xz/7z y
  soporte de contraseña en `.7z`, y **Propiedades** en dos pestañas Básico/Permisos, con el
  modo POSIX en formato de texto, usuario/grupo y etiqueta SELinux.

### Corregido
- **Crash al abrir un hipervínculo con la fila fuera de la cuadrícula** del terminal.
- **Runs fantasma en el renderer legado** tras celdas saltadas; los placeholders kitty ya no
  se pintan como glifo (clips respetados en la geometría).
- **La hora del *guest* Debian salía en UTC**: `date` pasa a informar en hora local.
- **El pegado fallido informaba «0 pegados» como éxito**: si el origen ya no existe aparece
  «Error al pegar», y un pegado por cortar solo vacía el portapapeles si alguna fila se movió.
- El gestor de archivos ya no hace *auto-scroll* al abrir una carpeta; el cursor del teclado
  se limpia y el desplazamiento mínimo usa `ensureItemVisible`.
- Cazadas claves faltantes en `values-pt-rBR` del gestor de archivos: 28 claves caían al
  inglés en runtime.

### Documentación
- Wiki de documentación estática (`wiki/`) servida en el hosting, enlazada desde Acerca de:
  uso, personalización, y `RUN_COMMAND`/`Termux:API` documentados.
- Banner, capturas y badges en el README.

## [2.0.0] — 2026-10-03

### Añadido

#### Terminal
- **Renderer nativo pintado con Compose** (`ComposeTerminalCanvas`), sustituto del
  `TerminalView` legado. Va detrás del feature-flag `native_compose_renderer`
  (Ajustes → Experimental), con la vista legada como fallback.
  - Selección de texto nativa por long-press con handles arrastrables, barra flotante
    Copiar/Pegar/Más, pinch-zoom e inercia de scroll.
  - Gestos con paridad con `TerminalView`: swipe abajo = transcript, swipe arriba = live,
    rueda del ratón físico, botón central para pegar.
  - Respeta `terminal-cursor-blink-rate`, `?25l` (cursor oculto) y auto-scroll a live.
- **Hipervínculos OSC 8** con subrayado y apertura por toque (`terminal_hyperlinks`).
  Allowlist de esquemas: `http`, `https`, `ftp`, `file`, `gemini`, `mailto`.
- **Imágenes en línea**: protocolo **Kitty Graphics** (KGP) y **OSC 1337** (iTerm2),
  con placements, chunks, budget LRU de 24 MiB y renderizado en ambos renderers.
  Kill-switch `terminal_images`. Sixel no está soportado.
- **Autodetección de TUI**: consultas de capacidades de kitty, *Feature Reporting* de
  iTerm2, variable `TERM_FEATURES` y respuesta XTVERSION.
- **Colores de 24 bits** desde `~/.termux/colors.properties` (toggle en Ajustes).
- **Fuentes y ligaduras**: selector de monospace (Fira Code, Cascadia Code, JetBrains Mono,
  D2 Coding, Hack y `~/.termux/font.ttf`), Nerd Fonts v3.5.1, toggle de ligaduras e
  importación de fuentes vía SAF. La fuente elegida se aplica a toda la app.
- **Multipaneleo nativo**: dos sesiones simultáneas (terminal o gestor de archivos) en
  pantallas ≥ 600 dp.
- **Prompt coloreado** `root@localhost:~#` mediante `/etc/profile.d/10-termux-ps1.sh`.
- **Tasa de refresco máxima** (90/120 Hz) y tile «Forzar 60 Hz (batería)».
- **Defaults por tier de hardware** (`DeviceTierResolver`): scrollback, blink del cursor y
  renderer nativo según RAM, núcleos y tipo de dispositivo. Solo se siembran en
  instalaciones limpias.
- **Informes de crash restaurados** al reabrir la app.
- **Copiar ruta** en el diálogo de detalles y en la barra de selección del gestor de archivos.
- **Menú «Más» de la terminal** como `ModalBottomSheet` en MD3 Expressive.
- **Editar en la terminal** desde el gestor de archivos (lanza `nano`/`vim`/el editor
  detectado, o muestra cómo instalarlo).
- **Prompt de shell, licencias de proot y Debian** en la pantalla «Acerca de».
- **Paridad de extra keys con Termux**: macros, popup de teclas especiales, estilo
  `extra-keys-style` y claves KEYBOARD/DRAWER/PASTE/SCROLL.
- **Navegación por teclado en el gestor de archivos** y peso de las carpetas en detalles.
- Soporte de symlinks en el gestor de archivos.
- **Configuración de firma de release** mediante `key.properties` (fuera del repo).

### Cambiado
- UI completa migrada a **Jetpack Compose con Material 3 Expressive**; la UI clásica de
  `TermuxActivity` se elimina y Compose pasa a ser la única interfaz.
- El gestor de archivos se extrae al módulo `:filemanager` y puede abrirse como pestaña de
  sesión.
- Operaciones del gestor de archivos fuera del hilo principal.
- Hipervínculos e imágenes se anuncian al shell por variables de entorno de proot
  (`KITTY_WINDOW_ID`, `TERM_PROGRAM=kitty`).
- Auditoría de rendimiento: el draw path del renderer nativo pasa de asignaciones por run a
  4 objetos por frame; el listado del gestor de archivos se cachea en vez de re-enumerarse en
  cada pulsación de búsqueda.
- Cero warnings de Kotlin en los módulos de la app.

### Corregido
- **El split mostraba un solo panel con el renderer nativo**: con `native_compose_renderer`
  activo, abrir el split dejaba el subárbol a medias (un pane sin pintar y sin el divisor
  vertical). Mientras el canvas no esté verificado con dos panes, un split activo se pinta
  siempre con el `TerminalView` legado (`TermuxUiState.useNativeCanvasRenderer`), que ya
  soportaba panes; el canvas sigue activo en el pane único.
- **Los toques ya llegan a las TUI**: con *mouse tracking* activo el canvas entrega el press
  al instante y ya no pide el teclado (lo que provocaba un `SIGWINCH` y un reflow de la TUI
  justo después del toque).
- Celdas con imagen ya no dejan ver el glifo o el carácter de fondo debajo.
- El lienzo nativo rellena el fondo por defecto, eliminando la copia fantasma del texto al
  desplazar el transcript.
- Los handles de selección se enganchan en la esquina correcta de su celda y no se cancelan
  al cruzar celdas.
- Middle-click con el portapapeles vacío ya no revienta.
- El botón BACK con selección activa limpia la selección en vez de escribir ESC.
- El IME por software también cierra la selección.
- El blink del cursor se respeta en la ruta legada y respeta `?25l`.
- La escritura de la fuente en `/root/.ssh` con el sensor de huella ya no introduce `~`.
- El bind `/dev/shm` del entorno proot apunta al directorio de la app.
- Atajos de `HOME`/`END` duplicados en la segunda página de extra keys.
- *Shadow lock* con `nlink: 1` en dispositivos Linkfix.
- Modo seguro: el shortcut ya abre la sesión `failsafe`.
- **El APK de `release` se empaquetaba sin manifest ni recursos** y aun así reportaba
  `BUILD SUCCESSFUL`: la tarea `optimizeReleaseResources` de AGP 9.4.1 busca en su
  directorio de entrada un archivo `.ap_`/`.apk`, no encuentra ninguno, se salta sin error y
  deja solo su `output-metadata.json`. Como el manifest compilado viaja dentro de ese `.ap_`,
  el resultado era un APK con `classes.dex` y assets pero sin `AndroidManifest.xml`, sin
  `resources.arsc` y sin `res/` — imposible de instalar. Desactivado con
  `android.enableResourceOptimizations=false` en `gradle.properties`.

### Documentación
- `CHANGELOG.md`, sección de features 2.0 en el `README.md`, plantilla `key.properties.example`
  y planes históricos archivados en `docs/archive/`.

### Notas
- **Limitación conocida:** desde Android 8 (API 26), SELinux deniega a `untrusted_app` leer
  `/proc/stat`, así que `btop` y `htop` no muestran estadísticas de CPU en dispositivos stock
  con SELinux enforcing. El bind `-b /proc` está presente y es correcto.
- La Release `v2.0.0` incluye dos tipos de APK: los `terminal_v2.0.0+github-debug_*` que
  adjunta la CI (firmados con una clave de prueba **no oficial**, no distribuibles) y el
  `terminal_2.0.0_universal.apk` firmado con la clave de release. **Instala este último.**
- Las imágenes del terminal soportan Kitty Graphics y OSC 1337, pero no Sixel ni animación.

## [1.119.0]

### Añadido
- **UI completa en Jetpack Compose** con Material 3 Expressive (`material3 1.5.0-alpha28`)
  y color dinámico en Android 12+.
- **Pantalla de Ajustes en Compose**, cubriendo la configuración de Termux y de las apps
  API/Float/Tasker/Widget.
- **Gestor de archivos en Compose** con orden, búsqueda, archivos ocultos, marcadores,
  papelera con deshacer, portapapeles, selección múltiple y navegación atrás/adelante.

### Cambiado
- Versión `1.119.0` (`versionCode 119`): salto de esquema `0.x` → `1.x`.

### Eliminado
- **Bootstrap de Termux y las referencias a `termux-package`**: variante de `BuildConfig`,
  *gates* de APT, variables de entorno de shell y constantes.
- **Donar**: preferencia, strings y `TERMUX_DONATE_URL`.
- Activities, fragments y layouts XML antiguos de Ajustes y del gestor de archivos.

## [1.118.0]

- Última versión anterior al fork.

[2.0.0]: https://github.com/ESTRIN217/Terminal/releases/tag/v2.0.0
[1.119.0]: https://github.com/ESTRIN217/Terminal/releases/tag/v1.119.0
[1.118.0]: https://github.com/ESTRIN217/Terminal/releases/tag/v1.118.0