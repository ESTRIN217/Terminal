# MEMORY.md — Terminal
## Estado actual
- Rama `3.0`. Cerradas 001–016; 2.1.0 publicada.
- **Spec 016 CERRADA (2026-10-24):** teclado en pestañas X11 verificado en
  dispositivo: HW keys, enter/borrar del IME y barra extra keys inyectan vía
  lorie `InputEventSender` reutilizado, y touch queda sin regresión. La pestaña
  ya replica el `setOnKeyListener` standalone y `sendExtraKey` refleja la
  semántica de `TermuxX11ExtraKeys` sin tocar `TouchInputHandler`.
- **Spec 015 CERRADA (2026-10-24):** guest proot inyecta `DISPLAY=:0`
  siempre (`X11_DEFAULT_DISPLAY`); residual: export manual como override.
- Specs 013/014 verificadas en dispositivo; 014 pendientes de verificación fina
  en más apps (touch/HW/extra keys — con 016 el teclado va por la vía
  standalone). 013: `glxgears` ≈140 FPS, reversibilidad OK.

## Arquitectura y reglas prácticas
- `terminal-emulator` → `terminal-view` → `termux-shared` → `app`;
  `filemanager` → `termux-shared`. No invertir dependencias.
- Librerías Java puro; Kotlin solo en `app` y `filemanager`.
- Lógica determinista en puros con tests JUnit (rojo→verde); UI despacha.
- Prefs persistidas: añadir claves, no perder las existentes.
- `Error`/`null` como éxito, `Logger`, capturas específicas.
- Strings ×3 locales; sin literales Compose; docs/memoria en español.
- Constantes en `TermuxConstants`; Hungarian `m`; Conventional Commits
  capitalizados; semver.

## Learnings vigentes
- Lorie: IMEs entregan enter/borrar vía `InputConnection.sendKeyEvent` →
  `LorieView.dispatchKeyEvent`; sin `OnKeyListener` se caen (el texto va por
  `commitText`→text event). Reusar `InputEventSender` (separable de
  MainActivity con `Consumer<Boolean>` de captura) en vez de duplicar quirks.
- Extra keys puros: pasos con NOMBRE de tecla (no keycodes Android); el
  mapeo vive en `PRIMARY_KEY_CODES_FOR_STRINGS` (lorie).
- Entorno guest: vars inofensivas sin servicio van sin gate (DISPLAY 015);
  el guard VirGL (socket) es excepción por coste de driver.
- lorie: al quitar símbolos Java de lorie, grep del espejo en `cpp/`
  (JNI abort: caso `MainActivity` retirado, hooks vía `LorieView.Client`).
- `app_process` dentro del proot guest no funciona (ESTRIN Unisoc/A12):
  companion se inicia desde la app (`X11CompanionSpawner` + CLASSPATH +
  `TERMUX_X11_LIBDIR`); `TMPDIR=<rootfs>/tmp` activa keymap XKB chroot.
- Loader guest: env `TERMUX_X11_APK` + wrapper idempotente
  `files/debian/usr/bin/termux-x11` (reescrito en `TermuxService`).
- Host aarch64: aidl/aapt2 x86_64 rotos; submódulos anidados `--init
  --recursive` antes de nativo; parches xserver sin commit, no limpiar.
- `BUILD SUCCESSFUL` no garantiza APK utilizable: unzip (libXlorie.so,
  manifest fusionados en intermediates).
- VirGL: socket ABSOLUTO `--socket-path <rootfs>/tmp/virgl_test`; mesa
  stock lee `VTEST_SOCKET_NAME=/tmp/virgl_test` (path crudo, NO la URI);
  validar con `glxgears`/`glmark2` (`glxinfo` aborta BadMatch rootless).
- Semánticas parecidas no se unifican sin spec; tests en rojo primero.
