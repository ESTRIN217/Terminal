# MEMORY.md — Terminal
## Estado actual
- **v3.0.0 publicada (2026-10-26)**: tag en `74333562`, release con APK universal
  firmado + `sha256sums`; CI adjunta los `+github-debug` al publicarse. Sitio y wiki
  desplegados (`update-site.py` + `firebase deploy --only hosting`, dos targets).
- Rama `3.0` = `master` (ff-merge `74b4ac75 → 74333562`). Specs 010–020 CERRADAS.
- **Spec 020 (Soporte USB sin root: lsusb + bridge UsbManager)**: implementada
  y verificada en dispositivo (commits `87aeaaf2`, `3ea23f96`, verificación
  2026-10-26). Puente socket abstracto `@terminal_usb` + snapshots
  `/tmp/usb_devices.*` + `lsusb`/`termux-usb` instalados en `/usr/local/bin`.
  
- Residuales aceptados y documentados (wiki `limitaciones.html`): btop ≥80×24,
  Adreno sin validar en hardware, glxinfo BadMatch rootless, Zink/OpenCL sin vía,
  Sixel/animaciones no soportadas. 017 fuera de alcance: ekbar completo,
  `extra_keys_config`, `userActions`. + **zutty congelado bajo VirGL**
  (2026-10-26): intermitente en ráfagas de texto; fastfetch es espectador
  (bloqueado escribiendo al pty, parecía que era él quien colgaba); stack
  VirGL sano (`glxgears` ~170 FPS con zutty abierto). Decisión del
  mantenedor: **solo documentar** (`limitaciones.html` #virgl-zutty), sin
  fix ni spec. Warnings OSC 8 de zutty: NO de fastfetch (su fuente no emite
  OSC 8), de los dotfiles; inofensivos.

## Guía Android Studio ARM64 (2026-10-27)
- **Verificado en el guest Debian del dispositivo (5 GB RAM)**: AS Rabbit 1
  2026.2.1 + IDEA Community 2025.3 aarch64 (build 253) + **skiko 0.144.5** de
  Maven Central + OpenJDK 25 del guest (jbr → symlink). Funciona en X11
  (DISPLAY=:0 inyectado por la app), avisos de rendimiento aparte.
- **Descubrimiento clave**: los natives de la última IDEA Community (línea 253)
  son VIEJOS frente a plataforma 262 → splash muere con
  `RenderException`/`UnsatisfiedLinkError _nSetFontEdging`. Fix: remplazar
  `libskiko-linux-arm64.so` por `skiko-awt-runtime-linux-arm64` de Maven.
  Versión del skiko readose del jar: `unzip -p lib/intellij.libraries.skiko.jar
  org/jetbrains/skiko/Version.class | grep -aoE '[0-9]+\.[0-9]+\.[0-9]+'` (sin
  binutils). IUA: IDEA 2025.3 Community extrae como `idea-IU-…` (nombre unified).
- **Docs**: página nueva `wiki/android-studio.html` (sin commits, pendiente
  descongelar con transporte update-site); nav actualizada en las 9 páginas +
  filas/cross-links en portada y `x11.html`. Pendiente: adaptar repos externos
  `android-studio-termux-arm64*` (rename a terminal-arm64 por el usuario en
  GitHub) con el script sin sudo + parche skiko; fix del wget blob→raw.

## Spec 021 — Almacenamiento USB (CERRADA 2026-10-28)

- Todos los criterios de finalización verificados. Implementación: librería
  pura `UsbBlockDeviceFormatLibrary` + tests, constantes 021, sección
  `blockDevices` aditiva en snapshot de `UsbBridgeServer` (mountpoints desde
  `/proc/mounts` del host, parseo puro `extractVolumeMountpoints`, refresco
  JIT en cada conexión al bridge) y wrappers guest `lsblk` (filas LIVE de
  `/proc/mounts` del guest: pendrive + microSD, `type=volume`, tamaño
  `statvfs`, exit 0 siempre) y `terminal-usb-cp` (descubrimiento en vivo,
  selector ID cualquier posición, exit 3 destino existente, anti-`..`),
  instalados vía `usbToolsNeedRefresh`. Round-trip ≥10 MB verificado vía
  wrapper (md5 origen = md5 leído). Spec/plan/tasks sincronizados
  (`52e2dbbc`), wiki ampliada (`4f2e2d8e`).
- **Reglas permanentes:** la vía es el bind `-b /storage:$HOME/storage` ya
  existente (SELinux deniega readdir de `/storage`, no el hijo); el puente de
  ficheros de sockets está descartado (`USB_FILE_BRIDGE_ABSTRACT_ADDRESS`
  queda sin uso/documentada); `lsblk` NO leerá sysfs real NUNCA (SELinux
  untrusted_app) — no reintentar fix; no usar `ACTION_MEDIA_MOUNTED`
  (restringido).

## Spec 022 — Navegador GeckoView (CERRADA 2026-10-29 — implementada y verificada)

- Objetivo: pestañas de navegador in-app (quinto caso sealed `Browser`, patrón X11 014)
  para abrir los enlaces del terminal (hipervínculos/URL 003 + More-menu) sin salir de la app.
- Motor pin `159.0.20261022150525` (release, nunca el universal 232 MiB — RNF-1);
  compileSdk 37.2, minSdk 26 (enmienda RNF-0). Pin manual por release; los
  `maven-metadata.xml` de maven.mozilla.org están desincronizados — verificar
  por POM/AAR directo. APK debug universal: 140.8 MB (libxul.so 154 MB dentro).
- **Verificación RNF-3 (2026-10-29, mantenedor):** `ps -A` muestra el multiproc
  de Gecko (`:gpu`, `:media`, 4 `:sandboxed_tab_*`) todos con el UID del app
  (`u0_a235`) en el Unisoc/A12 — sin conflicto con shared UID (spike T1
  POSITIVO). Principal ≈230 MB PSS con ≈104 MB swap; pestañas 50–240 MB RSS
  (buena parte es libxul mapeado compartido). Sin caídas de proot/X11.
  Veredicto: uso aceptable, sin reescopo. También probado: entrada en drawer,
  ruteo de enlaces tap → pestaña, ajuste intern/extern («magnífico»).
- **Excepción:** R8/`assembleRelease` NO verificado (3 intentos con OOM local);
  pospuesto a la CI/release 3.1. No dar por sana la ofuscación del AAR de GV
  hasta entonces. El tamaño con ofuscación se medirá con la release.
- Commits: `a3d53f26` (build+spec), `be451b10` (lógica+tests), `824c013f`
  (prefs/ajustes/licencia), `efad182f` (motor/UI/drawer) + bookkeeping.
- Sin changelog/update-site: quedan para la release 3.1 general.

## Doc limitaciones udev (2026-10-29)
- Documentado en `wiki/limitaciones.html` + README § Limitaciones conocidas:
  `udevadm control --reload-rules` (`No such file or directory`) y
  `systemd-hwdb update` (`Bad file descriptor`) son **esperados en proot**:
  no hay `udevd` (socket `/run/udev/control` no existe), sin uevents del kernel
  en el guest, Android gestiona `/dev` con `ueventd`, y `hwdb.bin` solo lo
  consume udev. Son no-op: no reintentar ni "arreglar". Si un tutorial de
  Linux con systemd pide "recargar reglas udev", ese paso no aplica al guest.
- Origen real de esos mensajes: el `postinst` de `udisks2` (aparece como
  `Failed to scan devices: Permission denied` + `dpkg: error processing
  package udisks2` en `apt upgrade`). Remedio documentado (wiki + README):
  `apt remove --purge udisks2`, o neutralizar su postinst
  (`echo 'exit 0' > /var/lib/dpkg/info/udisks2.postinst && dpkg --configure -a`).
  Mismo patrón para cualquier paquete cuyo postinst llame a `udevadm`/
  `systemd-hwdb`.

## Arquitectura y reglas prácticas
- `terminal-emulator` → `terminal-view` → `termux-shared` → `app`; `filemanager` →
  `termux-shared`. No invertir dependencias. Librerías Java puro; Kotlin solo en `app`
  y `filemanager`.
- Lógica determinista en puros con tests JUnit (rojo→verde); UI despacha.
- Prefs persistidas: añadir claves, no perder las existentes.
- `Error`/`null` como éxito, `Logger`, capturas específicas.
- Strings ×3 locales; sin literales Compose; docs/memoria en español.
- Constantes en `TermuxConstants`; Hungarian `m`; Conventional Commits
  capitalizados; semver.

## Learnings vigentes
- **CI del repo NO inicializa submódulos por defecto**: desde que
  `settings.gradle.kts` incluye `:termux-x11-lorie`/`:termux-x11-stub`, TODOS los
  `actions/checkout` que ejecutan Gradle llevan `submodules: recursive` (fix en 4
  workflows, commit `3ae3fabe`).
- **`attach_debug_apks_to_release.yml` borra release+tag al fallar** (`--cleanup-tag`)
  y su checkout usa `ref: ${{ github.ref }}` = el TAG. Consecuencia: el tag de una
  release debe apuntar a un commit que YA tenga los fixes de CI, o el fallo se come
  la release (ocurrió con v3.0.0: se re-creó el tag sobre el tip).
- Submódulo `termux-x11`: fork `ESTRIN217/termux-x11`, pin `1e5b56b` subido (README
  ya documenta su condición de fork: aviso de fork, setup vía Terminal, paquete host
  `com.estrin217.terminal` en los comandos `am`/CLASSPATH — clases/acciones/comando
  siguen con nombre upstream). About de GitHub del fork editado vía `gh repo edit`.
  El `origin` va por SSH. Los submódulos anidados
  (`xserver`, `libx11`…) son upstream con `ignore=dirty`; los parches de xserver van
  sin commit — no limpiar.
- `sha256sums` de release: solo el APK universal de release; los `+github-debug` llevan
  su propio fichero generado por CI.
- lorie: IMEs entregan enter/borrar vía `InputConnection.sendKeyEvent` →
  `LorieView.dispatchKeyEvent`; reusar `InputEventSender` en vez de duplicar quirks.
  Al quitar símbolos Java de lorie, grep del espejo en `cpp/` (JNI abort).
- Extra keys puros: pasos con NOMBRE de tecla; mapeo en `PRIMARY_KEY_CODES_FOR_STRINGS`.
- Entorno guest: vars inofensivas sin servicio van sin gate (DISPLAY 015); el guard
  VirGL (socket) es excepción por coste de driver.
- **Puente USB (020)**: `LocalServerSocket(String)` de Android SOLO bindea en el
  namespace abstracto (`@terminal_usb`); los scripts guest conectan con
  `s.connect(b"\x00terminal_usb")` (bytes con `\0` inicial, Python 3 lo soporta).
  El socket de filesystem del plano original era inviable por API pública.
  El repair refresca tools USB stale/ausentes por byte-compare
  (`usbToolsNeedRefresh`, patrón `linkfixNeedsRefresh`).
- CI del repo en Java 17: `gradle/gradle-daemon-jvm.properties` (toolchain 25,
  generado por `updateDaemonJvm`) está en `.gitignore` — NO commitarlo; cierra
  con borrado local si vuelve a aparecer.
- **pkill/kill de shell**: `pkill -f "<patrón>"` se mata a sí mismo si el patrón
  literal ya está en la propia cmdline del shell. Usar `kill $(cat pid)`.
- `app_process` dentro del proot guest no funciona (ESTRIN Unisoc/A12): companion
  SOLO desde la app (`X11CompanionSpawner` + CLASSPATH + `TERMUX_X11_LIBDIR`);
  `TMPDIR=<rootfs>/tmp` activa keymap XKB chroot. Loader guest RETIRADO (018-A).
- Host aarch64: aidl/aapt2 x86_64 rotos; `BUILD SUCCESSFUL` no garantiza APK
  utilizable: `unzip -l | grep AndroidManifest.xml` antes de publicar.
- VirGL: socket ABSOLUTO `--socket-path <rootfs>/tmp/virgl_test`; mesa stock lee
  `VTEST_SOCKET_NAME=/tmp/virgl_test` (path crudo, NO la URI); validar con
  `glxgears`/`glmark2` (`glxinfo` aborta BadMatch rootless).
- Semánticas parecidas no se unifican sin spec; tests en rojo primero.
