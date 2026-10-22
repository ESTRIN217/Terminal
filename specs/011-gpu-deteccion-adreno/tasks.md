# Tareas — Spec 011: GPU, detección honesta y aceleración Adreno (KGSL)

- [x] **T1. Librería pura `GpuDetectionLibrary` (familia GPU + gate).** RF-1, RF-3, RF-5, RF-6.
  - Hecho cuando: tests en rojo primero (`detectFamily` con todas las combinaciones de
    entradas inyectadas, motivo en `DESCONOCIDA`; `evaluateAction` con cada condición
    fallida por su motivo y los dos caminos de oferta) y luego verde;
    `./gradlew :termux-shared:test` verde.

- [x] **T2. Glue `GpuDeviceReader` (sysfs, `/dev/kgsl-3d0`, sysprops, `Build.SOC_MODEL` API-guarded).** RF-1, RF-6.
  - Hecho cuando: el glue devuelve el DTO inyectable y ningún fallo de lectura finaliza
    el proceso ni se traga en silencio (Error+Logger); el filtrado por API de
    `Build.SOC_MODEL` es code-reviewed y su saneo del `gpu_model` (trim) testado en JUnit.

- [x] **T3. Constantes y preferencia.** RF-3, NFR-2.
  - Hecho cuando: `TermuxConstants` con `DEBIAN_GPU_DEBS_DIR_PATH`, las 6 tripletas
    (URL + SHA-256 placeholder documentado + nombre de paquete),
    `DEBIAN_MESA_EXPECTED_VERSION` y claves de pref; `TermuxAppSharedPreferences`
    con `getGpuSolution/setGpuSolution` **sin tocar ninguna clave existente** del
    bloque persistido (gotcha spec 008); test de forma `isWellFormedSha256` sobre las
    6 constantes en rojo→verde tras el placeholder/documentation.

- [x] **T4. Comandos apt, parse de `dpkg-query` y URLs (puros).** RF-3, RF-5, RF-6.
  - Hecho cuando: tests en rojo (`buildInstallCommand` con `./` + frontend + 6 holds;
    `buildUninstallCommand` unhold+reinstall por mirror; `parseDpkgVersions` con paquete
    ausente → null sin crash; `debUrlFor` con `+` → `%2B`) y luego verde.

- [x] **T5. `GpuAccelerationInstaller` (glue, patrón `DebianInstaller`): descarga+SHA.** RF-3, RF-7.
  - Hecho cuando: descarga streaming de los 6 `.deb` a `files/debian/gpu-debs` con
    `HttpURLConnection` + `MessageDigest` en vuelo; mismatch o conexión cortada → fichero
    borrado, Error+toast, abort SIN ejecutar apt; test (JUnit/Robolectric del extracto
    purificado) que cubre los dos fallos y el éxito de forma.

- [x] **T6. Ejecución en el invitado + persistencia de la pref (install y uninstall).** RF-3, RF-5.
  - Hecho cuando: los comandos apt corren dentro del guest vía el runner de sesiones
    proot de `TermuxService` (one-shot de fondo, salida a log); exit 0 → pref
    `gpu_solution=ADRENO_KGSL` (install) / `NONE` (uninstall); exit !=0 → Error+Log+toast
    y la pref NO cambia (test de la transición con el extracto purificado).

- [x] **T7. Inyección del entorno: `applyGpuEnv` + cableado en `getEnvironment`.** RF-4, NFR-3.
  - Hecho cuando: test en rojo (activo → `MESA_LOADER_DRIVER_OVERRIDE=kgsl` presente;
    inactivo → mapa byte a byte idéntico al vigente) y luego verde; `TermuxService`
    lee la pref y la pasa al construir el entorno de sesión proot.

- [x] **T8. Panel «Estado GPU» en settings + strings ×3 locales.** RF-2, RF-3, RF-5, RF-6.
  - Hecho cuando: en `compose/settings` el panel muestra el texto honesto por familia
    (incluido Mali: «…llvmpipe (CPU) seguirá en uso»), el motivo de modo solo-info
    cuando aplique, y los botones «Instalar/Desinstalar aceleración GPU» solo cuando
    el gate los permite; barra de progreso en operación; cero literales en Compose
    (strings en `values/`, `values-es-rVE`, `values-pt-rBR`); `./gradlew test` verde.

- [ ] **T9. Validación en dispositivo (Mali) + documentación.** RF-2, RF-4, RF-6.
  - Hecho cuando (dispositivo Mali stock): el panel dice la verdad y no ofrece la acción;
    nueva sesión proot no lleva `MESA_LOADER_DRIVER_OVERRIDE` (verificado dentro del
    guest) y el comando proot es el mismo que antes del cambio; el guest sigue en
    llvmpipe; cero regresiones en sesiones/htop (spec 010 intacta).
  - Hecho cuando: wiki (GPU/limitaciones) y README describen las tres situaciones y la
    vía KGSL con su origen; el proceso de validación del pipeline Adreno (hardware
    requerido) queda documentado en la wiki.

## Notas

- Cada tarea de código: test en rojo → implementación → `./gradlew test` en verde →
  marcar → parar (constitución #4). Nada de builds Gradle en paralelo (lección del IC
  envenenado, MEMORY).
- Dependencias: T2 depende de T1 (DTO); T4 depende de T3 (constantes); T5/T6 dependen de
  T3+T4; T7 depende de T3; T8 depende de T1+T6; T9 al final.
- Sin dependencias nuevas en `libs.versions.toml` (constitución #1); nada de Kotlin en
  módulos librería (constantes/glue puros en `termux-shared` en Java; UI solo en
  `app/com.termux.terminal.compose`).
