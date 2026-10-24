# Tareas — Spec 013: VirGL (servidor nativo embebido + vía virpipe en el invitado)

- [x] **T1. `VirglLibrary` puro: gate de exclusión mutua + env + args del servidor.** RF-6, RF-3.
  - Hecho cuando: tests en rojo primero (`resolveSolutionChange` con las cinco
    transiciones del plan, motivo i18n estable en `NEEDS_OFF`; `applyVirglEnv`
    activo → las tres variables presentes y, inactivo, byte a byte idéntico;
    `buildServerArgs`);
    luego verde; `./gradlew :termux-shared:test` en verde.

- [x] **T2. `Solution.VIRGL` en `GpuDetectionLibrary` + prefs round-trip.** RF-2, RF-6, gotcha 008.
  - Hecho cuando: añadir el valor del enum NO altera ninguna constante ni clave
    existente; test en rojo→verde que persiste `VIRGL` y lo lee; el bloque
    persistido de la app conserva todas sus claves (matriz existente del test
    de prefs, y la del get/set de gpu_solution).

- [x] **T3. Cableado del env proot: `getEnvironment` + `isVirglSolutionActive` (glue).** RF-3, RF-5, NFR-3.
  - [x] Variante documentada: la señal de «servidor vivo» no la pasa `TermuxService`
    porque el propio builder la obtiene al comprobar la existencia del socket vtest
    en el TMPDIR del rootfs (`isVirglSocketPresent`, mismo directorio compartido del
    companion X de 014) — menos plomería y la sesión nueva siempre ve el estado real.
    Además el guard de llvmpipe multihilo (012) ahora se apaga con VirGL activo
    (`shouldInjectLlvmpipeThreads`), coherente con «llvmpipe no aplica si hay GPU».
  - Hecho cuando: test en rojo: pref `VIRGL`+servidor vivo según el glue → env
    con la vía; pref `VIRGL`+servidor muerto o pref `NONE`/`ADRENO_KGSL` → mapa
    byte a byte idéntico al vigente; `TermuxService` pasa el estado del servidor
    al builder como ya hace con el env KGSL (011 T7) sin tocar su implementación.

- [x] **T4. `VirglServerManager` (glue `app`): extracción, spawn, ciclo por sesiones.** RF-2, RF-4, RF-9.
  - Hecho cuando: lógica del estado (deseado vs vivo, contaje de sesiones proot
    tipo sampler 010, limpieza de socket rancio, timeout de READY) va en
    funciones puras testadas con el proceso abstracto; spawn con
    `ProcessBuilder` + `buildServerEnv` (TMPDIR = `<rootfs>/tmp`); fallos
    siempre como `Error`+`Logger`, nunca throw ni silencio; tests JUnit del
    extracto purificado (rojo→verde). Sin tocar el ciclo de vida de
    `mTermuxService` ni el spawn del companion X (014).

- [x] **T5. Panel «Estado GPU»: tercera vía VirGL + strings ×3 locales.** RF-2, RF-6, RF-7.
  - Hecho cuando: switch «Aceleración GPU (VirGL)» con el gate visual frente a
    KGSL (deshabilitado con motivo cuando `ADRENO_KGSL` activo, RF-6), estado
    real (vivo/no vivo/fallo) desde `VirglServerManager`, estados por familia
    del panel actualizados (Mali ya no dice solo «sin aceleración: dice la vía
    VirGL disponible»); cero literales en Compose (strings `values/`,
    `values-es-rVE/`, `values-pt-rBR/`); `./gradlew test` verde.

- [x] **T6. Pipeline nativo: compilar `virglrenderer` arm64 y embeber en assets.** RF-1, NFR-2, NFR-4.
  - [x] Variantes documentadas: (a) el lado servidor del socket se fijó ya — los
    fuentes pineados revelan que el nombre default `/tmp/.virgl_test` es ABSOLUTO
    (TMPDIR no lo mueve), así que el binario recibe siempre
    `--socket-path <rootfs>/tmp/virgl_test`, más `--no-fork --multi-clients
    --use-egl-surfaceless --use-gles` (Android: EGL del sistema, GLES, sin GBM);
    la URI que la mesa del guest acepta sigue pegada a T7. (b) virglrenderer
    exige gbm (de Mesa) para EGL: parche mínimo de 2 archivos
    (`scripts/virgl-patches/001/002`) + shims NDK (`scripts/ndk-shims/include/`)
    contra los headers internos ausentes (log/log.h, cutils/properties.h).
    (c) el pipeline completo vive en `scripts/build-virglrenderer.sh` (pins +
    SHA-256 + patches + stubs, idempotente, re-ejecutado de cero) y verifica con
    readelf que el binario solo depende de libc/libm/libdl/liblog. El binario
    quedó embebido y verificado con `unzip -l` en el APK debug arm64; queda como
    limitación honesta registrada: sin GBM no hay import de imágenes EGL/dmabuf
    (`VREND_STORAGE_EGL_IMAGE`), sin efecto sobre el render por protocolo.
  - Hecho cuando: fuentes pineadas (tag/commit escritos en el script y en las
    constantes del plan; nada sin pin, constitución #5); dependencias
    estáticas en el mismo multistage; binario en
    `app/src/main/assets/arm64-v8a/` y su extracción en `TermuxInstaller`
    (una línea más del flujo proot); `assembleDebug` produce APK y se
    verifica con `unzip` (lección de memoria); tarea explicitada en la wiki
    de compilación del mantenedor.

- [ ] **T7. Validación en dispositivo (Mali T606) + cierre de constantes.** RF-2..RF-5, RF-9.
  - Hecho cuando: activar VirGL → el servidor (proceso nativo) está vivo y el
    socket está en el rootfs `/tmp`; dentro del guest, con la pestaña X11 de la
    014 abierta, una app con el env del invitado conecta al servidor; además se
    confirma el path/variable real del socket que la mesa `virpipe`
    (`25.0.7-2+deb13u1`) acepta y se fija la constante final (se quita el
    placeholder documentado); desactivar → sin proceso, sin socket, env de
    sesión nueva idéntico al pre-spec; overlay 010 y tab X11 sin regresión;
    el resultado de la medición de renderer se anota en la wiki sin promesas.

- [ ] **T8. Documentación: wiki + README (RF-8) y MEMORY.** RF-8.
  - Hecho cuando: `wiki/limitaciones.html` y README describen la tercera vía
    (receta sobre la pestaña X11: activar VirGL, abrir companion/LorieView,
    salir con el env en el invitado, comprobar con `glxinfo` que NO es
    llvmpipe), la alternativa VNC marcada como secundaria, y las limitaciones
    honestas de Zink/OpenCL en Mali; el panel de estado GPU queda descrito
    con las tres situaciones (KGSL/Adreno, VirGL/cualquiera, CPU).

## Notas

- Cada tarea de código: test en rojo → implementación → `./gradlew test` en
  verde → marcar → parar (constitución #4). Nada de builds Gradle en paralelo.
- Dependencias: T2 depende de T1 (enum del gate); T3 depende de T1+T2; T4
  depende de T1+T3 (launcher del manager usa las mismas funciones); T5
  depende de T1+T4; T6 es independiente del código Java (pipeline nativo) y
  puede correrse T6 en paralelo al resto de tareas Java — pero el APK solo
  integra en T6→T7; T7 depende de todo lo anterior; T8 al final.
- Sin dependencias nuevas en `libs.versions.toml` (constitución #1); nada de
  Kotlin en módulos librería (`VirglLibrary`, constantes y glue puro en
  `termux-shared` en Java; UI solo en `app/com.termux.terminal.compose`).
- Al tocar el bloque persistido (prefs, gotcha 008): conservar cada clave
  existente; solo se añaden valores nuevos.
