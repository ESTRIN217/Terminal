# MEMORY.md — Terminal
## Estado actual
- **v3.0.0 publicada (2026-10-26)**: tag en `74333562`, release con APK universal
  firmado + `sha256sums`; CI adjunta los `+github-debug` al publicarse. Sitio y wiki
  desplegados (`update-site.py` + `firebase deploy --only hosting`, dos targets).
- Rama `3.0` = `master` (ff-merge `74b4ac75 → 74333562`). Specs 010–020 CERRADAS.
- **Spec 020 (Soporte USB sin root: lsusb + bridge UsbManager)**: implementada (T1–T6), `./gradlew test` en verde. Puente socket `terminal_usb` + snapshots `/tmp/usb_devices.*` + `lsusb`/`termux-usb` instalados en `/usr/local/bin`.
- Residuales aceptados y documentados (wiki `limitaciones.html`): btop ≥80×24,
  Adreno sin validar en hardware, glxinfo BadMatch rootless, Zink/OpenCL sin vía,
  Sixel/animaciones no soportadas. 017 fuera de alcance: ekbar completo,
  `extra_keys_config`, `userActions`.

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
