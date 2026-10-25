# MEMORY.md — Terminal
## Estado actual
- Rama `3.0`. Cerradas 001–017.
- **Spec 018 (2026-10-25) implementada + Enmienda A, PENDIENTE verific.
  dispositivo:** el warning `can't sanitize binding "/linkerconfig"` seguía
  tras instalar el APK porque el REINSTalador no actualizaba binarios
  embebidos: `installProotIfNeeded` copiaba solo si el binario no existía →
  el proot viejo (con la causa del warning: binding descartado en fase
  CLI, ver abajo) sobrevivía a toda actualización de APK. Fix RF-05:
  `installEmbeddedBinaryIfNeeded` compara longitud asset↔instalado (proot Y
  virgl); helper puro `binaryNeedsUpgrade` con test. La vía guest del
  companion está RETIRADA (RF-06..08): sin comando copiable ni botón copiar
  en el overlay ("no server" solo botón Iniciar), sin wrapper
  `<rootfs>/usr/bin/termux-x11` (eliminación idempotente en `TermuxService`),
  sin `TERMUX_X11_APK` en env guest, constantes `X11_GUEST_WRAPPER_GUEST_PATH`
  y `X11_COMPANION_PM_PATH` borradas. Companion SIEMPRE vía app
  (`X11CompanionSpawner`); binds -b /apex /linkerconfig /data/app /system se
  quedan (decisión usuario). Causa proot del warning: `realpath2(traíba
  ref NULL en CLI)` → realpath(3) crudo → EACCES (termux/proot#254) →
  binding descartado; fix: espejo del hack de canon.c en `new_binding()`
  (binding.c).
- Spec 017 cerrada (2026-10-25): ajustes de X11 en `SettingsScreen` → destino
  `X11` en `SettingsComposeActivity` → `X11SettingsScreen`
  (Salida/Puntero/Teclado). SOLO persiste vía `Prefs` de lorie
  (`LorieApp.getPrefs`); lógica en `X11SettingsPolicy` puro con tests. Verificado
  en dispositivo: RF-1/8/9, cambio de `touchMode` refleja en pestaña conectada
  sin reiniciar. Enmienda lorie: `IntPreference.put(int)` (`PrefsProto`,
  commit `9e0c832`, pin actualizado). Fuera para iteraciones posteriores:
  ekbar completo + `extra_keys_config`, `userActions`.
  - Espejados en policy (mantener en sync con lorie `arrays.xml`):
    filtering bilinear/nearest, 5 orientaciones, touch 1/2/3; las 24
    resoluciones exactas se leen en runtime vía `getValues()`.
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
- `app_process` dentro del proot guest no funciona (ESTRIN Unisoc/A12) y la
  vía guest está retirada (spec 018 A): companion SOLO desde la app
  (`X11CompanionSpawner` + CLASSPATH + `TERMUX_X11_LIBDIR`);
  `TMPDIR=<rootfs>/tmp` activa keymap XKB chroot.
- Loader guest: RETIRADO (spec 018 A): no wrapper, sin `TERMUX_X11_APK`.
- Binarios embebidos (proot/virgl): el instalador ahora ACTUALIZA si el asset
  difiere en tamaño (antes solo copiaba si no existía → stale forever).
- Host aarch64: aidl/aapt2 x86_64 rotos; submódulos anidados `--init
  --recursive` antes de nativo; parches xserver sin commit, no limpiar.
- `BUILD SUCCESSFUL` no garantiza APK utilizable: unzip (libXlorie.so,
  manifest fusionados en intermediates).
- VirGL: socket ABSOLUTO `--socket-path <rootfs>/tmp/virgl_test`; mesa
  stock lee `VTEST_SOCKET_NAME=/tmp/virgl_test` (path crudo, NO la URI);
  validar con `glxgears`/`glmark2` (`glxinfo` aborta BadMatch rootless).
- Semánticas parecidas no se unifican sin spec; tests en rojo primero.
