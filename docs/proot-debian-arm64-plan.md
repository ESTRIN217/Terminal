# Plan: Fork renombrable con Debian arm64 + proot

## Estado del Proyecto (Actualizado: Septiembre 2026)

- **applicationId fijado:** `com.estrin217.terminal`
- **Fase 0 (Renombre):** COMPLETADA
- **Fase 1 (Eliminar bootstrap Termux):** COMPLETADA
- **Fase 2 (Investigación PRoot):** COMPLETADA — binario compilado con NDK r30, clang 21, Android 28 arm64
- **Compilación y Tests:** `./gradlew test` (PASS) | `./gradlew assembleDebug` (PASS)
- **Tamaño APK ARM64:** Reducido de ~180 MB a 25 MB
- **Próximo hito:** Fase 2 (integración) — Integrar fuentes PRoot/talloc en `app/src/main/cpp/` y escribir CMakeLists.txt

---

## 1. Contexto

Este repo es un fork de Termux renombrado a `com.estrin217.terminal`. El bootstrap clásico de Termux impedía cambiar el nombre de paquete debido a rutas hardcodeadas en los binarios compilados. Migrar la sesión por defecto a Debian arm64 bajo proot permite que el sistema opere de forma agnóstica al nombre del paquete.

### 1.1 Por qué el bootstrap bloqueaba el renombre

- `TERMUX_PACKAGE_NAME = "com.termux"` en `TermuxConstants.java:352` derivaba `FILES = /data/data/<pkg>/files` y `PREFIX = .../files/usr`.
- Todos los binarios de `bootstrap-{aarch64,arm,i686,x86_64}.zip` llevaban ese `$PREFIX` hardcodeado en runtime / dynamic linker.
- Renombrar con bootstrap Termux exigía recompilar bootstrap + paquetes completos (`termux-packages` wiki `For-maintainers#build-bootstrap-archives`).

### 1.2 Por qué Debian + proot lo desbloquea

- Un rootfs Debian arm64 es FHS (`/bin`, `/usr`, `/etc`), no depende de `$PREFIX` ni del package name.
- Al eliminar la dependencia del bootstrap Termux, el renombre es puramente a nivel Java / Kotlin / Manifest / Gradle.

### 1.3 Decisión incompatible descartada

`proot` vía `pkg install` (termux-packages) **no sirve para un fork renombrado**. Su compilación fija:
```sh
PROOT_UNBUNDLE_LOADER=$TERMUX_PREFIX/libexec/proot
TERMUX_PKG_DEPENDS="libandroid-shmem, libtalloc"
```
Con un nuevo `applicationId` ese path apunta al paquete viejo o inexistente.
Se distribuye **proot propio rename-safe** compilado en-repo vía CMake con `libtalloc` vendorizado y resolución dinámica del loader.

---

## 2. Arquitectura objetivo

```text
APK com.estrin217.terminal (~25 MB)
 ├─ sin bootstrap Termux (eliminados zips, termux-bootstrap JNI, SYMLINKS.txt)
 ├─ proot propio arm64 en files/bin/proot (loader path dinámico o relativo)
 └─ Debian trixie arm64 (.tar.xz) → files/debian/ (descarga primer arranque con UI Compose)

Sesión por defecto:
  proot -r <files>/debian -0 -w /root -b /dev -b /proc -b /sys -b /storage /bin/bash --login

Failsafe:
  /system/bin/sh (sin proot)
```

- Solo arquitectura `arm64-v8a`.
- Sin dependencia de plugins oficiales `com.termux.*`.
- No embutir rootfs en el APK (mantiene el APK ligero ~25 MB; el rootfs de ~30 MB se descarga en primer arranque).

---

## 3. Estado de Fases

### Fase 0 — Renombre [COMPLETADA]

- [x] `app/build.gradle.kts`: `applicationId = "com.estrin217.terminal"`, `manifestPlaceholders["TERMUX_PACKAGE_NAME"] = "com.estrin217.terminal"`.
- [x] `TermuxConstants.java:352`: `TERMUX_PACKAGE_NAME = "com.estrin217.terminal"` (rutas `/data/data/com.estrin217.terminal/...` derivadas automáticamente).
- [x] Constantes añadidas: `DEBIAN_ROOTFS_DIR_PATH`, `DEBIAN_STAGING_ROOTFS_DIR_PATH`, `APP_BIN_DIR_PATH`, `PROOT_BIN_PATH`.
- [x] `app/src/main/res/xml/shortcuts.xml`: `targetPackage` actualizado a `com.estrin217.terminal`.
- [x] `termux-shared/.../strings.xml` (default, `values-es-rVE`, `values-pt-rBR`): entidades `TERMUX_PACKAGE_NAME` y `TERMUX_PREFIX_DIR_PATH` actualizadas.

### Fase 1 — Eliminar bootstrap Termux [COMPLETADA]

- [x] Borrados los 4 archivos ZIP de bootstrap (`app/src/main/cpp/bootstrap-*.zip`, ~120 MB en disco) y fuentes JNI (`termux-bootstrap-zip.S`, `termux-bootstrap.c`).
- [x] Borradas las tareas redundantes `downloadBootstrap` y `downloadBootstraps` en `app/build.gradle.kts`.
- [x] Actualizado `app/src/main/cpp/CMakeLists.txt` (eliminado target `termux-bootstrap`).
- [x] Simplificado `TermuxInstaller.java`: eliminada extracción ZIP, lectura de `SYMLINKS.txt`, llamadas nativas `getZip()` y carga de biblioteca `termux-bootstrap`. Se conserva la verificación y creación de directorios base de la app.
- [x] Corregido `FileReceiverActivityTest` para ejecutarse como pure JUnit con `TermuxUrlUtils`, resolviendo incompatibilidad de Conscrypt/Robolectric en entornos Linux ARM64.
- [x] Verificado con `./gradlew test` (éxito) y `./gradlew assembleDebug` (éxito, APK 25 MB).

### Fase 2 — proot propio rename-safe (solo aarch64)

#### 2A — Investigación y compilación de prueba [COMPLETADA]

Compilación de prueba exitosa en `scratch/proot/` con NDK r30 (clang 21.0.0, `aarch64-linux-android28-clang`).

**Resultado del binario:**
```
ELF 64-bit LSB shared object, ARM aarch64, for Android 28, built by NDK r30-beta1
proot --help → Version 5.1.107
Loader → ELF 64-bit static, arm64, base address 0x2000000000
```

**Modo de loader elegido: bundled** (sin `PROOT_UNBUNDLE_LOADER`)

- El loader se compila con `objcopy` (`loader-wrapped.o`) y se embebe en el binario de proot.
- En cada ejecución, proot extrae el loader a un archivo temporal y lo ejecuta.
- No hay ruta hardcodeada — el binario es rename-safe por construcción.
- En Android, `/tmp` suele ser `noexec`. Se debe pasar `PROOT_TMP_DIR=<filesDir>` al lanzar proot para que el loader temporal sea ejecutable. `filesDir` = `/data/data/com.estrin217.terminal/files`.

**3 patches necesarios sobre fuentes upstream `termux/proot`:**

| Archivo | Patch | Razón |
|---|---|---|
| `talloc-2.4.3/config.h` | Comentar `#define uint_t unsigned int` | Conflicto con `sys/types.h` de Bionic |
| `talloc-2.4.3/config.h` | Comentar `#define HAVE_CRYPT_H 1` | `crypt.h` no existe en Bionic (Android NDK) |
| `proot/src/extension/ashmem_memfd/ashmem_memfd.c` | Añadir `#include <string.h>` | `strcmp`/`memset` no declaradas con NDK clang estricto |

**Flags de compilación verificados:**
```
CFLAGS:  -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -D__STDC_WANT_LIB_EXT1__=1
LOADER:  -fPIC -ffreestanding -static -nostdlib
         -Wl,--build-id=none,-Ttext=0x2000000000,--rosegment,-z,noexecstack
```

**Nota sobre `loader-info.c` (ARM64):**
El archivo `loader/loader-info.c` requiere los símbolos `_start` y `pokedata_workaround` del loader compilado. En ARM64 son `0x2000000000` y `0x200000093c` respectivamente. El script `loader-info.awk` necesita **`gawk`** (no awk POSIX — usa `strtonum`).

#### 2B — Integración en el repo [COMPLETADA]

- [x] Copiadas fuentes de `termux/proot` en `app/src/main/cpp/proot/` (142 ficheros, 1.3 MB; sin `.git`/`tests`/`doc`, sin artefactos `*.o`) y `talloc` mínimo en `app/src/main/cpp/talloc/` (`talloc.c/h`, `replace.h`, `config.h`, `win32_replace.h`, 188 KB).
- [x] Verificados los 3 patches sobre las copias (venían aplicados del scratch): `config.h` sin `uint_t` ni `HAVE_CRYPT_H`, `ashmem_memfd.c` con `#include <string.h>`.
- [x] Escrito `app/src/main/cpp/CMakeLists.txt` modo bundled (espejo del `GNUmakefile`):
  - Loader estático ARM64 (`-ffreestanding -static -nostdlib -Wl,-Ttext=0x2000000000,--rosegment,-z,noexecstack`)
  - `llvm-strip` → `loader.exe`, `llvm-objcopy -I binary` (nombres relativos) → `loader-wrapped.o` con `_binary_loader_exe_start/_end/_size`
  - `loader-info.c` generado con `proot/loader/gen_loader_info.py` (sustituye `loader-info.awk`: solo hay `mawk`, sin `strtonum`; offset verificado = 2364)
  - Target `proot` con 61 fuentes + `talloc.c` + `loader-info.c` + `loader-wrapped.o`, **sin** `PROOT_UNBUNDLE_LOADER`; `FATAL_ERROR` si `ANDROID_ABI != arm64-v8a`
  - `POST_BUILD` publica el binario en `src/main/assets/arm64-v8a/proot` (gitignored)
- [x] `app/build.gradle.kts`: `ndk { abiFilters += "arm64-v8a" }`, `ANDROID_PLATFORM=android-28` solo para nativo (`getifaddrs` es API 24+; igual que Fase 2A), `merge*Assets` con `dependsOn(buildCMake*)`.
- [x] Verificado: `assembleDebug` OK, binario `ELF arm64 Android 28 NDK r30` (1.6 MB) en `assets/arm64-v8a/proot` dentro del APK (~26 MB), sin `com.termux/files` hardcodeado, `./gradlew test` PASS.
- [ ] Pendiente Fase 3: `TermuxInstaller` copiará `assets/arm64-v8a/proot` → `files/bin/proot` con `0700`; runtime con `PROOT_TMP_DIR=<filesDir>`.

### Fase 3 — Rootfs Debian arm64 (descarga diferida) [COMPLETADA]

- [x] `TermuxConstants`: `DEBIAN_ROOTFS_TARBALL_URL` (Debian oficial OCI, debuerreotype trixie arm64, pin commit `f73bd08…`), `DEBIAN_ROOTFS_TARBALL_SHA256` (`ae72a46c…`, digest del layer = autoverificable), `DEBIAN_ROOTFS_TARBALL_SIZE` (49704853), `DEBIAN_ROOTFS_TARBALL_FILE_PATH` (`files/debian.tar.gz`), `PROOT_ASSET_PATH`.
- [x] Deps `commons-compress` + `xz` (ya estaban en el catálogo, sin usar) en `app/build.gradle.kts`.
- [x] `TermuxInstaller.installProotIfNeeded()`: copia `assets/arm64-v8a/proot` → `files/bin/proot` con `0700`; nuevo `TermuxFileUtils.isTermuxAppBinDirectoryAccessible()`.
- [x] Creado `app/.../DebianInstaller.java` (patrón `Error`, `Logger`):
  - Descarga con progreso real (bytes/total) + SHA-256 en streaming; reanuda sin descargar si el tarball local ya es válido.
  - Extracción `.tar.gz` (capa OCI) con commons-compress a `files/debian-staging`, con `sanitizeEntryName()` (rechaza absolutos y `..`), filtro de whiteouts OCI (`isWhiteoutEntry`, como proot-distro v5.1.7), symlinks vía `Os.symlink`, hardlinks vía `link(2)` con fallback a copia, bit ejecutable preservado, especiales ignorados.
  - Gate post-extracción: si falta `bin/bash` en staging → `Error` inmediato con localidad (no se renombra).
  - Post-config: `/etc/resolv.conf` (1.1.1.1, 8.8.8.8) y `/etc/apt/apt.conf.d/01norestrict` (`APT::Sandbox::User "root"`).
  - Instalación atómica: staging → `rename` a `files/debian`, borra el tarball; verifica `bin/bash` + `etc/debian_version`.
  - Listener reatachable (`setListener`) para sobrevivir rotación.
- [x] UI Compose (`TermuxComposeActivity` + `DebianInstallerScreen` + estado en `TermuxUiState`/`TermuxViewModel`):
  - Overlay a pantalla completa con `LinearProgressIndicator` (determinado en descarga, indeterminado en extracción), texto de estado, error + botones Reintentar / Failsafe.
  - Strings en en/es/pt.
- [x] Test `DebianInstallerTest` (4 tests PASS): sanitizador, constantes oficiales, `isInstalled()` falso en host.
- [x] Verificado: `./gradlew test` PASS, `assembleDebug` OK (APK arm64 ~28 MB con asset proot).

### Fase 4 — Sesión Debian por defecto [COMPLETADA]

- [x] `termux-shared/.../ProotShellEnvironment extends AndroidShellEnvironment` (no toca `TermuxSession`):
  - `buildProotCommand()`: `proot -r <debian> -0 -w /root -b /dev -b /proc -b /sys /bin/bash --login` (+ args extra).
  - Env invitado: `HOME=/root`, `USER/LOGNAME=root`, `SHELL=/bin/bash`, `PATH` FHS puro, `TMPDIR=/tmp`, `PROOT_TMP_DIR=<files>` (loader bundled); jamás `LD_PRELOAD`/`LD_LIBRARY_PATH`.
  - `setupShellCommandEnvironment()` sin `createHomeDir()` (`/root` no existe en el host); `setupShellCommandArguments()` passthrough sin `$PREFIX`.
  - `writeEnvironmentToFile()` → `files/debian.env`, invocado al final de `DebianInstaller`.
- [x] `TermuxService.createTermuxSession()`: si no failsafe, no plugin, executable nulo, Debian instalado y proot ejecutable → sesión proot con `ProotShellEnvironment`. Failsafe y plugins conservan `TermuxShellEnvironment` (`/system/bin/sh`).
- [x] Test `ProotShellEnvironmentTest` (5 tests PASS); `./gradlew test` PASS, `assembleDebug` OK.

### Fase 5 — Limpieza y Pulido [COMPLETADA]

- [x] `TermuxBootstrap.java` marcado `@Deprecated` (se conserva por compatibilidad de `termux-shared` como librería: lo usan `TermuxApplication`, reportes de errores y ramas legacy; eliminarlo rompería la API pública).
- [x] `TermuxFileUtils.getTermuxFilesStatMarkdownString()`: el script de diagnóstico ya no referencia `$PREFIX`, `usr-staging` ni `$PREFIX/bin/login`; ahora inspecciona `bin/proot`, `debian/`, `debian-staging`, `debian.tar.xz`.
- [x] `TermuxInstaller`: Javadoc actualizado al flujo sin bootstrap; el reintento ya no borra el inexistente `$PREFIX`.
- [x] Storage en invitado: añadidos `-b /sdcard -b /storage` al comando proot (modo por defecto de proot-distro; fuente inexistente = inerte, el kernel sigue exigiendo el permiso de almacenamiento). `setupStorageSymlinks()` (`~/storage`) se conserva para el lado host.
- [x] `./gradlew test` PASS, `assembleDebug` OK.

---

## 4. Validación

- [x] `./gradlew test` (CI gate) — pasando al 100%.
- [x] `./gradlew assembleDebug` en `arm64-v8a` — pasando, APK funcional de 25 MB.
- [ ] Validación en dispositivo arm64 (al completar Fases 2-4):
  - Descarga y extracción de Debian trixie.
  - `cat /etc/os-release` reporta Debian GNU/Linux 13 (trixie).
  - `apt update` y resolución DNS funcional.
  - `id -u` devuelve `0` (root simulado).
  - Modo Failsafe ejecuta `/system/bin/sh`.
  - Manejo de desconexión / reintento de descarga.

---

## 5. Riesgos y Mitigaciones

1. **Ruta del loader de proot:** ✅ **Resuelto.** Modo bundled elegido y verificado en Fase 2A — el loader se embebe en el binario vía `objcopy` (`loader-wrapped.o`). No hay ruta hardcodeada. En runtime se pasa `PROOT_TMP_DIR=<filesDir>` para que el loader extraído temporalmente sea ejecutable (Android monta `/tmp` como `noexec`).
2. **targetSdk 28:** Se mantiene en `28` en `app/build.gradle.kts` de forma intencional para preservar permisos de ejecución (`execve`) en el almacenamiento privado de la app (W^X en Android 10+).
3. **Restricción de arquitectura:** Se enfoca exclusivamente en `arm64-v8a` (`splits.abi.include("arm64-v8a")`), simplificando el mantenimiento de binarios nativos y reduciendo drásticamente el peso del APK.
4. **Permisos y sandbox de APT:** Mitigado preconfigurando `APT::Sandbox::User "root"` en `/etc/apt/apt.conf.d/01norestrict` durante la inicialización del rootfs.

---

## 6. Decisiones Tomadas

- **Application ID:** Confirmado y aplicado `com.estrin217.terminal`.
- **Estrategia de PRoot:** Confirmada Opción A (compilación CMake en-repo en `app/src/main/cpp/proot/` con `libtalloc` vendorizado).
- **Modo loader de PRoot:** **Bundled** (sin `PROOT_UNBUNDLE_LOADER`). Loader embebido en el binario mediante `objcopy`. Verificado en Fase 2A — binario 100% rename-safe. Runtime requiere `PROOT_TMP_DIR=<filesDir>`.
- **Formato del Rootfs:** Tarball pre-aplanado comprimido (`.tar.xz`) Debian trixie arm64 alojado en GitHub Releases con validación de hash SHA-256.
- **Experiencia de Usuario (UX):** Pantalla/diálogo en Jetpack Compose (`TermuxComposeActivity`) con barra de progreso real (MB/total, porcentaje), estado de descompresión y botón de reintento.
