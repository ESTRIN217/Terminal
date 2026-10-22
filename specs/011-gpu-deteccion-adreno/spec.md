# Spec 011 — GPU: detección honesta del dispositivo y aceleración Adreno por KGSL

Estado: **aprobada** (2026-10-21)

## Contexto y objetivo

El rootfs Debian 13 (trixie, minbase OCI) del fork ve la GPU **sin aceleración**:
Debian compila los drivers de mesa (freedreno/turnip) **solo contra DRM**
(`/dev/dri`), que el kernel Android stock no expone a una app sin root. El
resultado en el invitado es que toda la renderización GPU (GL, GLES, Vulkan)
cae en **llvmpipe / lavapipe**, el renderizador de software de mesa versión
25.0.7-2+deb13u1 (LLVM 19.1.7): correcto pero lento, porque usa CPU.

Hoy se ha verificado binariamente que la vía **KGSL** funciona sin root y sin
nada especial en el kernel: el backend KGSL del driver gallium freedreno
(`kgsl_pipe_new`, símbolos `kgsl_bo_*` en `libgallium-25.0.7-2+deb13u1.so`) y el
Vulkan de turnip (`libvulkan_freedreno.so` con KGSL, ICD
`freedreno_icd.json`, API 1.4.305) hablan directamente con el nodo
`/dev/kgsl-3d0` que el kernel de Qualcomm expone. El proyecto externo
`lfdevs/mesa-for-android-container` publica para Debian 13 los 6 `.deb` de
mesa recompilados con ese backend, exactamente a la versión de distro
(`25.0.7-2+deb13u1`, tag `debian/25.0.7-2-adreno`), con SHA-256 publicados en
las notas de la release; eso permite instalarlos con `apt install --reinstall
./fichero.deb` **sin tocar el gestor de paquetes** y desinstalar limpio
(`apt-mark unhold` + `--reinstall` desde el mirror Debian).

Esta spec define: (1) **detección honesta** de la familia GPU del dispositivo
en funciones puras, sin root; (2) un **panel de estado** en settings que
escribe en qué escenario está el usuario sin promesas falsas; (3) el
**pipeline de instalación** de la vía Adreno (descarga fijada por SHA-256,
instalación en el invitado, holds, preferencia persistente) disponible **solo**
en los dispositivos donde realmente aplica; y (4) que en dispositivos Mali
(como el del validador) **no cambie nada** salvo el panel de información.

## Usuarios

- El usuario con un dispositivo **Adreno (Qualcomm)** que hoy renderiza en CPU
  y no sabe que existe una vía de aceleración sin root.
- El usuario con **Mali / PowerVR / otro** que merece una explicación honesta
  de por qué su renderizado es por CPU, no un botón roto.
- Validación en dispositivo: el mantenedor, con hardware **Mali (MediaTek)**,
  donde la validación es necesariamente **parcial** (todo excepto el pipeline
  Adreno se valida de verdad; el pipeline queda implementado y documentado,
  a validar con hardware Adreno del mantenedor o de la comunidad).

## Historias de usuario

- HU-1. Como usuario Adreno, quiero que la app detecte mi GPU y KGSL y me
  ofrezca instalar la aceleración, para jugar/renderizar por GPU y no por CPU.
- HU-2. Como usuario Adreno, quiero desinstalar la aceleración y volver a la
  mesa de distro en un paso, para revertir si algo falla.
- HU-3. Como usuario Mali, quiero un panel que me diga claramente «sin
  aceleración GPU disponible sin root; llvmpipe (CPU) seguirá en uso», para no
  tener expectativas falsas.
- HU-4. Como usuario, quiero que si el rootfs no está instalado o su mesa no
  coincide con la versión esperada, la app no intente nada, para no romper el
  guest.

## Definiciones

- **Familia GPU.** Clasificación del dispositivo GPU como
  `ADRENO_CON_KGSL` (sysfs `kgsl-3d0` y/o nodo `/dev/kgsl-3d0` presentes),
  `ADRENO_OTRO`, `MALI`, `POWERVR` o `DESCONOCIDA`.
- **Vía KGSL.** Hablar con la GPU Qualcomm por `/dev/kgsl-3d0` (ioctl del
  kernel, sin root, sin DRM). Es la única vía soportada por esta spec.
- **gpu_solution.** Preferencia persistente del pipeline: `NONE` (por
  defecto) o `ADRENO_KGSL` (instalación de los 6 `.deb` pinneds completada).
- **Modo solo información.** Estado del panel en el que no se ofrece ninguna
  acción de instalación (RF-6).
- **Validación parcial.** Situación en la que parte de la spec (el pipeline
  Adreno completo) no puede ejercitarse en el hardware disponible del
  validador (Mali); se documenta el proceso y los artefactos listos para
  validarlo en un Adreno sin exigirlo para cerrar la spec.

## Requisitos funcionales

- RF-1: CUANDO la app consulta el estado GPU del dispositivo (arranque del
  panel o antes de construir el entorno del invitado), EL SISTEMA determina la
  familia GPU en funciones puras sobre datos inyectados, con estas entradas (en
  este orden de prioridad, sin root): (a) `/sys/class/kgsl/kgsl-3d0/gpu_model`
  legible → Adreno con KGSL y su modelo; (b) existencia de `/dev/kgsl-3d0`;
  (c) sysprops `ro.hardware.egl` y `ro.board.platform`; (d) `Build.SOC_MODEL`
  (solo API ≥ 31; minSdk 24 y targetSdk 28 intencional). Familias resultantes:
  `ADRENO_CON_KGSL`, `ADRENO_OTRO`, `MALI`, `POWERVR`, `DESCONOCIDA`.
  Cualquier ausencia de dato no devuelve una familia «adivinada»: cae en
  `DESCONOCIDA` con el motivo anotado.
- RF-2: EL SISTEMA muestra en settings un panel «Estado GPU» con texto honesto
  por familia: `ADRENO_CON_KGSL` → «aceleración GPU disponible (vía KGSL)»;
  `MALI`, `POWERVR`, `ADRENO_OTRO` y `DESCONOCIDA` → «sin aceleración GPU
  disponible sin root; llvmpipe (CPU) seguirá en uso».
- RF-3: SI la familia es `ADRENO_CON_KGSL` Y el rootfs está instalado Y la
  versión de mesa de los 6 paquetes del invitado coincide con la esperada
  (`25.0.7-2+deb13u1`, verificada por `dpkg-query` en el invitado), ENTONCES el
  sistema ofrece la acción «Instalar aceleración GPU», que: descarga los 6
  `.deb` pinneds de `lfdevs/mesa-for-android-container` (URL base + nombre
  exacto + SHA-256 de cada uno, fijados en `TermuxConstants`) con verificación
  SHA-256 en streaming (patrón `DebianInstaller`), los instala con
  `apt install --reinstall ./fichero.deb`, los marca (`apt-mark hold` de los 6)
  y persiste `gpu_solution=ADRENO_KGSL` solo si todo terminó con éxito.
  SI cualquier paso falla, ENTONCES el error se compone como objeto `Error`, se
  registra vía `Logger` y se muestra al usuario (toast); la
  preferencia no cambia (nunca silencio).
- RF-4: MIENTRAS `gpu_solution=ADRENO_KGSL`, EL SISTEMA inyecta
  `MESA_LOADER_DRIVER_OVERRIDE=kgsl` en el entorno de los procesos del invitado
  (solo sesiones proot del rootfs; el comando proot del resto del sistema no
  cambia).
- RF-5: CUANDO el usuario pide «Desinstalar aceleración GPU», EL SISTEMA
  ejecuta en el invitado `apt-mark unhold` de los 6 paquetes +
  `apt install --reinstall` de los mismos (descarga desde el mirror Debian) y,
  si tiene éxito, limpia `gpu_solution` a `NONE`. SI falla, ENTONCES se informa
  el error y la preferencia queda como estaba.
- RF-6: SI el dispositivo NO es `ADRENO_CON_KGSL`, O el rootfs no está
  instalado, O la versión de mesa del invitado diverge de la esperada, ENTONCES
  el panel lo indica con el motivo concreto y la acción de instalación NO se
  ofrece (modo solo información). En esos dispositivos el resto del sistema
  (comando proot, entorno del invitado, sesiones) no cambia en absoluto.
- RF-7: SI la descarga de un `.deb` se interrumpe, o su SHA-256 no coincide con
  el fijado, ENTONCES el fichero se descarta (no se ejecuta `apt`, no queda
  artefacto parcial utilizable) y se informa el error.

## Requisitos no funcionales

- NFR-1: Sin root, sin Magisk, sin kernel personalizado y sin permisos extra.
- NFR-2: Constitución #5: toda descarga con SHA-256 fijado en
  `TermuxConstants`; los 6 hashes se extraen de las release notes el día de
  implementación (placeholder documentado hasta entonces).
- NFR-3: Cero regresiones en dispositivos no-Adreno: ni el comando proot ni el
  entorno del invitado cambian un byte cuando `gpu_solution` no está activo.
- NFR-4: Sin dependencias nuevas en `gradle/libs.versions.toml`: descarga con
  `HttpURLConnection` (ya usado por `DebianInstaller`); apt corre dentro del
  invitado, no tocamos paquetes en el host.
- NFR-5: Reversibilidad total: la desinstalación (RF-5) devuelve el guest al
  estado de distro; los `.deb` descargados viven en el directorio de ficheros
  de la app y pueden borrarse.

## Casos límite

- **Adreno sin nodo KGSL visible** (kernel sin `/dev/kgsl-3d0` o sysfs sin
  `kgsl-3d0`): familia `ADRENO_OTRO` → modo solo información (RF-6).
- **minSdk 24 (API < 31):** sin `Build.SOC_MODEL`; la detección se apoya solo
  en sysfs/nodo/sysprops (cada fuente es opcional, RF-1 lo permite).
- **Rootfs reinstalado o actualizado con otra mesa:** la verificación de
  versión del gate (RF-3/RF-6) re-evalúa en el panel; si diverge, la acción de
  instalar se retira aunque la preferencia siga en `ADRENO_KGSL` (el gate manda
  sobre la pref; la pref no fuerza nada fuera de la verificación).
- **`apt` ocupado (lock) por otro proceso del guest:** el comando falla con
  código distinto de 0 → `Error` + toast; la pref no cambia; se puede reintentar.
- **Instalación a medias (cancelada tras 3 de 6):** los ficheros parciales se
  descartan (RF-7), `apt` no se lanzó; el estado `gpu_solution` sigue `NONE`.
- **Desinstalar cuando nunca se instaló:** la acción solo se ofrece con
  `gpu_solution=ADRENO_KGSL` y gate vigente; en el resto queda oculta.

## Fuera de alcance

- Servidores gráficos/remotos (X11, termux-x11, wlroot, etc.): esta spec solo
  instala drivers y fija variables; el cómo se renderiza el display es otra
  spec.
- OpenCL, kernels DRM (panfrost/panthor para Mali, rockchip, etc.), root y
  libhybris: sin vías sin root hoy; se documentan como limitación honesta.
- zink-sobre-turnip y demás combinaciones alternativas del stack Mesa (el
  alias `kgsl_dri` queda documentado pero no expuesto como opción).
- Validación formal de rendimiento en hardware Adreno de terceros (se
  documenta el proceso, no el número).
- Tuning de rendimiento y «benchmark suites» empaquetadas en la app:
  benchmarks solo como herramientas de validación, no como producto.

## Criterios de finalización

- [ ] `./gradlew test` verde, con tests unitarios para: la familia GPU sobre
  entradas inyectadas (todas las combinaciones RF-1), el gate de acción
  (RF-3/RF-6 con cada condición fallida), la construcción del comando apt
  (RF-3/RF-5) y la inyección/ausencia de `MESA_LOADER_DRIVER_OVERRIDE` (RF-4),
  incluido el caso SHA-256/URL correcto en forma (64 hex).
- [ ] En dispositivo Mali (stock, `enforcing`): el panel dice la verdad
  («sin aceleración… llvmpipe»), la acción de instalación no se ofrece, el
  comando proot byte a byte igual que antes del cambio y el invitado sigue en
  llvmpipe (si `glxinfo`-equivalente disponible) — validación parcial
  documentada.
- [ ] Pipeline Adreno: descargas con SHA-256 fijados verificadas en test de
  forma (URL con `+` codificado `%2B`); flujo install/uninstall revisado con
  el pseudocódigo del plan; la validación real exige hardware Adreno
  (mantenedor o comunidad) — no bloqueante para cerrar la spec, pero
  documentada en la wiki.
- [ ] Documentación: wiki (GPU/limitaciones) y README describen las tres
  situaciones (Adreno acelera, Mali/PowerVR no, invitado en llvmpipe) y el
  mecanismo KGSL con su referencia a `lfdevs/mesa-for-android-container`.

## Decisiones cerradas (2026-10-21)

- **Única vía soportada:** KGSL sobre `/dev/kgsl-3d0` (binariamente verificada
  en los `.deb` de `lfdevs/mesa-for-android-container`, release
  `debian/25.0.7-2-adreno`). Mali queda en honestidad/informativa, no placebo.
- **Pinned a la versión de distro** (`25.0.7-2+deb13u1`): mismo nivel de
  punto-release que trixie, `apt install --reinstall` la acepta y la
  desinstalación con `--reinstall` desde mirror es limpia.
- **`MESA_LOADER_DRIVER_OVERRIDE=kgsl` como conmutador** (variable estándar de
  mesa 25; `kgsl_dri` es un alias del driver gallium freedreno):
  conservadora, no toca el ICD de Vulkan ni otros ajustes.
- **Validación parcial aceptada** por el hardware del validador (Mali): el
  pipeline Adreno queda implementado y documentado como confiable hasta poder
  probarlo en un Adreno.

## Dudas abiertas

- [NECESITA ACLARACIÓN] Los **6 SHA-256 exactos**: el placeholder de
  `TermuxConstants` debe rellenarse desde las release notes de la tag
  `debian/25.0.7-2-adreno` el día de implementar la descarga; sin ese paso no
  debe compilarse el pipeline (constitución #5).
- ¿Es la prioridad de fuentes de RF-1 la correcta para OEMs raros donde
  `ro.hardware.egl` no es «adreno»? Criterio: si aparece un caso real, se
  ajusta en la spec, no en silencio.
- Confirmar en el dispositivo el orden de gate anti-confusión cuando el
  usuario instala rootfs nuevo con la pref aún `ADRENO_KGSL` (RF-3/RF-6 ya lo
  cubren por diseño; verificar que el panel comunica «aceleración activa pero
  verificando versión…» en vez de prometer).
