# MEMORY.md — Terminal
## Estado actual
- Rama `3.0`. **Spec 014 (termux-x11 complemento): T1–T9 hechas y committeadas;**
  T10 doc+memoria al día. Pendiente mantenedor: probar en dispositivo real
  (conexión companion, touch, hardware keys) y cerrar la spec.
- Cerradas antes: 010 (overlay /proc), 011/012 (GPU llvmpipe; doc Mali pendiente),
  001–009; 2.1.0 publicada.
- **Spec 013 (VirGL) borrador en `specs/013-virgl-aceleracion-gpu/`:** solo VirGL,
  binario NDK/CMake como proot, VNC dentro del invitado, `gpu_solution` =
  NONE|ADRENO_KGSL|VIRGL. Pendiente aprobación → plan → tareas. Dudas pinned: tag
  de virglrenderer, path del socket con mesa 25.0.7.

## Spec 014 — decisiones y estado
- Submódulo `termux-x11/` (pin `c82664b0` = `65a9a06` + fix CMake). Pins internos lorie
  (xserver, xorgproto…) ahora inicializados (`--init --recursive`). El fork envía
  ~17 parches de xserver **sin commit** (working tree del submódulo anidado);
  conservar, no limpiar.
- Cadena pura→UI: `X11CompanionCommand` (comando copy-paste) → `X11Sessions` →
  `X11SessionBus` (tag→binder, untagged a primera pestaña sin tag) →
  `X11TabController` (surrogate `LorieView.Client`, reconnector 250 ms,
  `bindView()` para rebind tras rotación) → `X11SessionRegistry` (controller por
  sessionId, vive rotaciones) → `X11SessionHost` (Compose). El cierre de pestaña
  libera en `removeSession` de la Activity; no toca TermuxService.
- Touch embebido: forwarder propio en el host (`sendTouchEvent` 18/19/20 XI);
  gestos completos quedan en la app standalone. HW keys vía `handleKey` →
  `view.sendKeyEvent` (down solo con repeatCount 0).
- `TermuxConstants.TERMUX_X11`, strings ×3 (`x11_*`, `action_open_x11`). Overlay
  "no server" con comando + copiar; estados NO_SERVER/CONNECTED/DISCONNECTED.

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
- El native de lorie (`lorie/src/main/cpp/lorie/activity.cpp`) referenciaba el
  campo `activity` (tipo MainActivity) que la T2 retiró de `LorieView`:
  `GetFieldID` falla y el JNI aborta = crash nativo al abrir pestaña X11 (y
  rompía también la standalone). Fix: hooks vía `LorieView.Client` (defaults
  `clientConnectedStateChanged`/`onServerHeartbeat`), lookup lazy del campo
  `client`. Regla: al quitar símbolos Java de lorie, grep del espejo en `cpp/`.
- Host build aarch64: binarios x86_64 del SDK rotos: aidl (wrapper a /usr/bin/aidl),
  aapt2, **cmake/3.22.1 (usar 3.31.6, pin en lorie/build.gradle)**. BISON y FLEX
  necesarios para el build nativo de lorie (apt-get). Pull/push del submódulo:
  inicializar submódulos anidados antes de compilar nativo.
- NDK 30 + API<29 con `__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__`: malloc.h inyecta
  `reallocarray` static inline → el fallback de xserver choca. Fix aplicado:
  `#include <malloc.h>` antes del `#define reallocarray xreallocarray` en
  `xserver/include/os.h` (working tree anidado; `libfontenc.a` aporta el símbolo).
- `BUILD SUCCESSFUL` no garantiza APK utilizable: inspeccionar con unzip
  (libXlorie.so, manifests fusionados en intermediates — aapt2 no corre en aarch64).
- Glifos multicolor Compose requieren `tint = Color.Unspecified`.
- Semánticas parecidas no se unifican sin spec; cuido tests en rojo primero.
- Conventional Commits capitalizados (`Added`, `Changed`, `Fixed`…); semver.
