# MEMORY.md — Terminal
## Estado actual
- Rama `3.0`. **Spec 014 (termux-x11 complemento): T1–T10 + enmienda A (loader
  guest T11/T12) hechas.** Pendiente: probar en dispositivo real (wrapper
  guest, companion, touch, HW keys) y cerrar la spec. Cerradas antes: 010–012,
  001–009; 2.1.0 publicada.
- **Spec 013 (VirGL) borrador en `specs/013-virgl-aceleracion-gpu/`:** pendiente
  aprobación → plan → tareas.

## Spec 014 — decisiones y estado
- Submódulo `termux-x11/` (pin `c82664b0`); parches xserver **sin commit**
  (working tree anidado), conservar; submódulos anidados con `--init --recursive`.
- Cadena: `X11CompanionCommand` → `X11Sessions` → `X11SessionBus` (tag→binder,
  untagged a primera pestaña) → `X11TabController` (surrogate `LorieView.Client`,
  reconnector 250 ms, `bindView()`) → `X11SessionRegistry` (por sessionId, vive
  rotaciones) → `X11SessionHost` (Compose). Cierre de pestaña no toca TermuxService.
- Touch: forwarder propio (XI 18/19/20); gestos completos en standalone;
  HW keys `view.sendKeyEvent` (down solo repeatCount 0).
- `TermuxConstants.TERMUX_X11`, strings ×3; overlay "no server" con comando
  primario/fallback e ícono copiar; estados NO_SERVER/CONNECTED/DISCONNECTED.

## Arquitectura y reglas prácticas
- `terminal-emulator` → `terminal-view` → `termux-shared` → `app`;
  `filemanager` → `termux-shared`. No invertir dependencias.
- Librerías Java puro; Kotlin Compose solo en `app` y `filemanager`.
- Lógica determinista en puros con tests JUnit; UI despacha y presenta.
- Prefs persistidas: añadir claves, no perder las existentes (filemanager ver AGENTS).
- `Error`/`null` como éxito, `Logger`, capturas específicas.
- Strings ×3 locales; sin literales Compose; docs/memoria en español.
- Constantes en `TermuxConstants`; Hungarian `m` en ambos lenguajes.
- `targetSdk 28`, `minSdk 24`, arm64-v8a. Java 17/NDK 30/CMake 3.31.6.
  Tests `./gradlew test`; ahí: `--tests 'com.termux.terminal.x11.*'`.

## Learnings vigentes
- lorie/`activity.cpp` referenciaba símbolos Java retirados (`MainActivity`) →
  JNI abort = crash al abrir pestaña. Fix: hooks vía `LorieView.Client`;
  regla: al quitar símbolos Java de lorie, grep del espejo en `cpp/`.
- En el proot guest `/system` no visible y PATH sin `pm`: bind `-b /system`,
  `-b /apex`, `-b /linkerconfig`, `-b /data/app` en `buildProotCommand` +
  rutas absolutas.
  `/apex` imprescindible: intérprete ELF de `app_process64` en Android 11+
  (`/apex/com.android.runtime/bin/linker64`); sin bind el exec da ENOENT
  («required file not found»). `/linkerconfig` imprescindible para el linker
  de APEX (namespaces; sin ella CANNOT LINK por libnativeloader.so).
  `/data/app` imprescindible: proot traduce lo
  no enlazado al rootfs y el `CLASSPATH=/data/app/…` no resolvería.
- Loader guest (enmienda A RF-8, replicando el deb upstream): env
  `TERMUX_X11_APK` (sourceDir per-sesión) + wrapper idempotente
  `files/debian/usr/bin/termux-x11` escrito en `TermuxService.onStartCommand`;
  overlay elige primario `termux-x11 :0` vs one-liner por `File.isFile`.
  Upstream resuelve por `Loader.java` (PathClassLoader + firma) sin `pm`.
- Host build aarch64: aidl/aapt2 x86_64 rotos; cmake 3.31.6 (pin en
  lorie/build.gradle); BISON/FLEX vía apt. Submódulos anidados inicializados
  antes de compilar nativo; parches xserver sin commit, no limpiar.
- NDK 30 + API<29 con `__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__`: fix en
  `xserver/include/os.h` (`#include <malloc.h>` antes del rename reallocarray).
- `BUILD SUCCESSFUL` no garantiza APK utilizable: inspeccionar con unzip
  (libXlorie.so, manifests fusionados en intermediates — aapt2 no corre en aarch64).
- Glifos multicolor Compose requieren `tint = Color.Unspecified`.
- Semánticas parecidas no se unifican sin spec; cuido tests en rojo primero.
- Conventional Commits capitalizados (`Added`, `Changed`, `Fixed`…); semver.
