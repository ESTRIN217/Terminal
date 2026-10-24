# MEMORY.md — Terminal
## Estado actual
- Rama `3.0`. **Spec 014 (termux-x11 complemento) COMPLETADA y verificada en
  dispositivo** (T1–T13 + enmiendas A/B): spawn del companion desde la app,
  display X11 en pestaña, apps gráficas Debian funcionando. Pendiente de
  verificación fina (no bloquea): touch/HW keys/extra keys en más apps, GPU.
  Cerradas antes: 010–012, 001–009; 2.1.0 publicada.
- **Spec 013 aprobada (2026-10-23): `specs/013-virgl-aceleracion-gpu/`** con
  spec+plan+tareas T1–T8. **T1–T6 implementadas (2026-10-23):** `VirglLibrary`
  puro (gate exclusión KGSL/VirGL, `applyVirglEnv`, args/env del servidor,
  `decideServerAction`, `shouldKeepWaiting`, `parseSolutionName`), `Solution.VIRGL`,
  env virgl en `ProotShellEnvironment` vía existencia del socket en
  `DEBIAN_ROOTFS_TMP_DIR_PATH`, guard llvmpipe extendido a VirGL,
  `VirglServerManager` (assets→bin, socket rancio, READY window, stop), hooks
  en `TermuxService`, panel con switch VirGL + strings ×3, y **T6: binario
  virgl_test_server arm64 compilado por `scripts/build-virglrenderer.sh`**
  (pins SHA-256, patches `scripts/virgl-patches/`, shims `scripts/ndk-shims/`)
  embebido en assets y verificado con `unzip -l`. GREEN `./gradlew test`.
  Falta: T7 device (Mali T606; URI del guest por confirmar contra mesa
  25.0.7) y T8 wiki/README. Server args fijados: `--no-fork --multi-clients
  --use-egl-surfaceless --use-gles --socket-path <rootfs>/tmp/virgl_test`.
  Limitación sin GBM registrada: sin import de dmabuf/EGL-image.
  **T8 hecha (2026-10-23):** wiki limitaciones (receta X11 + excluyente + estado
  de validación pendiente + VNC secundaria + Zink/OpenCL), x11.html remite a
  VirGL, README tercera vía. Solo queda **T7**: validación en el Mali T606
  (activar switch, comprobar `/tmp/virgl_test`, pestaña X11, `glxinfo` ≠
  llvmpipe, desactivar → env byte a byte) y anotar medición en la wiki.

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
- Ejecutar `app_process` DENTRO del proot guest no funciona (probado Unisoc/A12:
  exec→ENOENT sin `/apex`; con `/apex`, CANNOT LINK sin `/linkerconfig`; y el
  uid de app no puede leer/enlazar `/linkerconfig`). Conclusión: companion se
  inicia desde la app (`X11CompanionSpawner`, ProcessBuilder + CLASSPATH
  sourceDir + `TERMUX_X11_LIBDIR` env). Sockets y keymaps: `TMPDIR=<rootfs>/tmp`
  (`buildCompanionEnv`), que además activa la heurística chroot de XKB.
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
- Pipeline VirGL (spec 013, `scripts/build-virglrenderer.sh`): GitLab
  freedesktop anon archives redirige a sign-in → epoxy va vía mirrors GitHub
  (kraj/anholt) con SHA-256 igual; el default del socket vtest es `/tmp/.virgl_test`
  ABSOLUTO (TMPDIR no lo mueve) → `--socket-path` explícito siempre; virglrenderer
  exige gbm para EGL (no existe sin root en Android) → parches 001/002 +
  shims `scripts/ndk-shims/` (log/log.h y cutils/properties.h no vienen en el
  NDK); deps del binario solo libc/libm/libdl/liblog.
- Glifos multicolor Compose requieren `tint = Color.Unspecified`.
- Semánticas parecidas no se unifican sin spec; cuido tests en rojo primero.
- Conventional Commits capitalizados (`Added`, `Changed`, `Fixed`…); semver.
