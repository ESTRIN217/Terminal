# Changelog

Todos los cambios relevantes de Terminal. El formato sigue
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el versionado es
[SemVer](https://semver.org/lang/es/).

Este proyecto es un fork independiente de [termux/termux-app](https://github.com/termux/termux-app)
y no está afiliado al equipo de Termux.

## [2.0.0] — sin fecha

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
- Los APK adjuntos a las Releases de GitHub están firmados con una clave de prueba **no
  oficial** y no son distribuibles.
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