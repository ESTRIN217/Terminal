# Spec 013 — Aceleración GPU por VirGL: servidor nativo de renderización + virpipe en el invitado

Estado: **borrador** (2026-10-22)

## Contexto y objetivo

La spec 011 dejó una verdad incómoda: la única vía sin root que soporta (KGSL,
hablando directo con `/dev/kgsl-3d0` de freedreno/turnip) **solo existe en
Qualcomm Adreno**. En Mali / PowerVR — y concretamente en el dispositivo de
trabajo del mantenedor (Unisoc T606, Mali) — el panel dice honestamente que no
hay aceleración y que llvmpipe (CPU) seguirá en uso, aunque la CPU ya corre
multihilo por la spec 012.

Existe una segunda vía sin root, **agnóstica del fabricante**, validada por la
comunidad de Termux (LinuxDroidMaster, ivon, FoxeiZ, termux-desktop-op):
**VirGL**. El modelo es cliente-servidor:

- En la **parte nativa** del app (host Android) corre un pequeño servidor,
  `virgl_test_server`, que renderiza 3D usando el **EGL/GLES del sistema
  Android** — es decir, el driver de GPU del fabricante que la app ya puede
  usar sin root. La app no toca DRM ni el kernel: pide contextos GLES al
  sistema como lo haría cualquier aplicación gráfica (donde el sistema usa
  ANGLE, la traducción a Vulkan la hace el sistema; donde usa el driver
  nativo Mali/Adreno, se usa ese).
- En el **invitado Debian (proot)** no se instala nada raro: la mesa de la
  distro ya incluye el driver gallium `virgl` en modo cliente
  (`GALLIUM_DRIVER=virpipe`), que reenvía por **socket Unix** (la conexión
  vtest) las órdenes 3D al servidor del host y recibe los resultados.

Resultado: el invitado corre OpenGL acelerado por el GPU real del teléfono
(fuera del contenedor), sin root, sin Magisk, sin kernels raros. El coste es
una capa de traducción (virpipe→socket→EGL host), siempre inferior al
rasterizado por software de llvmpipe, y con la CPU libre para la lógica del
programa.

**Decisiones ya tomadas con el mantenedor (2026-10-22):**

1. Alcance: **solo VirGL**. Zink (necesita un driver Vulkan sobre DRM/panthor
   que los kernels Android stock de Mali no exponen a una app) y OpenCL de
   cómputo (el ICD de Mali en `/system/vendor` no es dlopen-reliable sin root)
   quedan documentados como limitación honesta, igual que hicimos con
   libhybris en la 011.
2. El binario del servidor (arm64) **se compila por el propio pipeline del
   fork** (multistage NDK/CMake, patrón proot) y va embebido en el APK — no
   hay descargas de binarios externos para esta pieza (la única pieza
   descargada sigue siendo el rootfs Debian, con su SHA-256).
3. El usuario hoy **no tiene ningún display** para apps gráficas del invitado;
   la spec incluye la **historia mínima de display** (receta validada con VNC
   dentro del invitado y visor externo) como parte del producto documentado.
   El visor VNC integrado en la app queda fuera de alcance.

## Usuarios

- El usuario con **Mali / PowerVR / cualquier GPU no-Qualcomm** (caso del
  mantenedor): hoy no tiene vía alguna; VirGL es su primera aceleración real.
- El usuario **Adreno** que prefiere la vía VirGL (o que la usa cuando la
  versión de mesa del invitado no coincide con la esperada por la 011) — la
  vía KGSL sigue siendo preferente y no se toca.
- Mantenedor: necesita que las dos vías (KGSL y VirGL) **no se pisen** y que
  el panel cuente siempre la verdad.

## Historias de usuario

- HU-1. Como usuario Mali, quiero activar la aceleración VirGL en Ajustes y
  que el invitado renderice 3D por GPU vía el EGL del sistema, para disfrutar
  de apps gráficas/del escritorio sin rasterizar en CPU.
- HU-2. Como usuario Mali, quiero que la app incluya el servidor embebido y lo
  arranque/pare por sí sola con las sesiones, para no tener que gestionar
  procesos a mano ni instalar nada externo.
- HU-3. Como usuario, quiero una receta mínima paso a paso (wiki) para abrir
  un escritorio gráfico del invitado conectado a esa aceleración, para saber
  exactamente qué instalar y qué esperar de rendimiento sin promesas.
- HU-4. Como usuario que activó la aceleración y algo no fue bien, quiero un
  «Desactivar» que pare el servidor y deje el entorno byte a byte como antes,
  para revertir sin rastro.
- HU-5. Como usuario Adreno con aceleración KGSL, quiero que VirGL no se
  active encima ni a la vez, para no terminar en un estado de drivers
  mezclados imposible de depurar.

## Definiciones

- **Servidor VirGL.** El binario arm64 embebido heredero de `virglrenderer`
  ejecutado en modo servidor vtest en la parte nativa; escucha por socket Unix
  local y renderiza con contextos GLES pedidos al sistema Android.
- **Socket de VirGL.** El fichero Unix del servidor (path fijado por las
  constantes estándar del modo vtest de mesa; el valor concreto se fija en el
  plan). El invitado debe poder alcanzarlo: se resuelve con la infraestructura
  de binds del comando de sesión proot que ya existe (spec 010).
- **Vía VirGL en el invitado.** Uso del driver cliente `virpipe` de la mesa de
  la distro (ya presente en el rootfs) mediante variables de entorno estándar:
  `GALLIUM_DRIVER=virpipe` y el override de versión GL que el plan fije. No se
  descarga ni instala nada dentro del invitado para activar VirGL.
- **gpu_solution (ampliado).** La preferencia persistente de la 011 se
  extiende: `NONE` | `ADRENO_KGSL` | `VIRGL`. Mutuamente excluyentes: la
  activación de una limpia la otra.
- **Vía KGSL.** La de la spec 011; no cambia en nada en esta spec.
- **Historia mínima de display.** Documentación reproducible (wiki) para abrir
  un escritorio gráfico del invitado: servidor VNC (X server) dentro del
  Debian + visor VNC externo cualquiera, con la aceleración activada; incluye
  comprobación de renderer (`glxinfo`) y expectativa de rendimiento honesta.

## Requisitos funcionales

- RF-1: EL SISTEMA embebe en el APK el binario arm64 del servidor VirGL,
  producido por el pipeline de compilación del fork (mismo patrón que el
  binario proot: assets por ABI, arm64-v8a), de modo que **cero** binarios de
  servidor se descarguen en runtime. La fuente del código del servidor queda
  fijada (tag/commit) por el plan, y sus dependencias se compilan en el mismo
  pipeline (no se toca el catálogo de dependencias de Gradle).
- RF-2: CUANDO el usuario activa «Aceleración GPU (VirGL)» en Ajustes, EL
  SISTEMA: (a) marca `gpu_solution=VIRGL`; (b) arranca el servidor si aún no
  está vivo; (c) confirma al usuario el estado real (vivo/no vivo) — si el
  servidor no arranca, la preferencia **no** cambia, el error se compone como
  objeto `Error`, va al `Logger` y se muestra; nunca silencio.
- RF-3: MIENTRAS `gpu_solution=VIRGL` y el servidor esté vivo, EL SISTEMA
  inyecta en el entorno de las sesiones proot del rootfs las variables de la
  vía VirGL (`GALLIUM_DRIVER=virpipe` + las del override de versión GL fijadas
  en el plan) y expone al invitado el socket del servidor mediante el
  mecanismo de binds ya existente. Las sesiones ya abiertas conservan el
  entorno con el que arrancaron (patrón acordado en 011/012).
- RF-4: CUANDO no queda ninguna sesión proot activa, EL SISTEMA detiene el
  servidor VirGL (reaprovecha el ciclo de vida de servicios de sesión ya
  existente; el servidor no queda vivo para siempre). Al reactivar sesiones
  con la preferencia activa, se vuelve a arrancar una vez, no por sesión.
- RF-5: CUANDO el usuario desactiva VirGL, EL SISTEMA para el servidor,
  restaura `gpu_solution=NONE` y las sesiones nuevas salen con el entorno
  idéntico byte a byte al de antes de esta spec.
- RF-6: SI el usuario intenta activar VirGL con `ADRENO_KGSL` activo (o al
  revés), ENTONCES la activación requiere desactivar primero la otra: la
  preferencia sostiene a lo sumo una vía, y el panel lo explica en claro.
- RF-7: EL SISTEMA actualiza el panel «Estado GPU» (RF-2 de la 011) para
  reflejar la tercera situación posible: además de la vía KGSL y de la CPU,
  el panel ahora ofrece y reporta la vía VirGL con texto honesto por familia:
  Mali/PowerVR → «aceleración disponible (VirGL: renderiza el GPU del
  teléfono desde la app, sin root)»; Adreno → ambas vías enumeradas con la
  KGSL marcada como preferente; desconocida → la misma recomendación VirGL.
  El panel es la única superficie que promete/describe estados.
- RF-8: EL SISTEMA documenta (wiki + README) la historia mínima de display:
  pasos del invitado para levantar un escritorio gráfico (X server + VNC
  dentro del Debian; paquetes de distro, verificando que existen en Debian
  13), conexión con un visor externo, cómo comprobar que la app está en el
  GPU (`glxinfo`: proveedor del fabricante, no llvmpipe) y la advertencia
  honesta de que la vía es una capa de traducción: no promete FPS.
- RF-9: SI el binario del servidor no está (ABI no soportada, assets ausentes
  o fallo de arranque), ENTONCES el panel y las sesiones funcionan como hoy
  (estado `NONE` efectivo, entorno sin las variables), con el motivo
  registrado; ninguna otra función cambia.

## Requisitos no funcionales

- NFR-1: Sin root, sin Magisk, sin kernel personalizado y sin permisos extra.
  El servidor usa las mismas capacidades que cualquier app gráfica del sistema
  (EGL/GLES), y no toca `/dev/dri` ni `/dev/kgsl-3d0`.
- NFR-2: Constitución #5: no hay descargas nuevas (el binario va embebido; el
  código del servidor se fija por tag/commit en el pipeline). Cero secretos.
- NFR-3: Cero regresiones con VirGL apagado: comando proot, entorno del
  invitado, sesiones y el panel byte a byte iguales a hoy (`gpu_solution=NONE`
  o `ADRENO_KGSL`).
- NFR-4: Sin dependencias nuevas en `gradle/libs.versions.toml` (constitución
  #1): todo lo que necesita el servidor se compila en su propio pipeline CMake
  a partir de fuentes fijadas; la UI usa el stack Compose ya presente.
- NFR-5: Reversibilidad total (RF-5); no se escribe fuera del espacio de la
  app; el socket vive bajo las carpetas de la app.
- NFR-6: El servidor y su ciclo de vida son de **bajo consumo** cuando no hay
  sesiones: inactivo de verdad (proceso terminado), no «vivo pero dormido».

## Casos límite

- **Servidor muerto con la preferencia activa** (crash/OOM del sistema): al
  crear sesión se comprueba y se revive si procede (RF-2/RF-4); si el revive
  falla, la sesión arranca sin aceleración y se informa (no silencio).
- **Varias sesiones proot simultáneas** con VirGL: un solo servidor compartido
  por todas; se detiene al último cierre.
- **`gpu_solution=ADRENO_KGSL` en un Mali** (estado imposible por el gate de
  la 011, pero p. ej. cambiado el hardware): el panel lo detecta por familia
  y degrada el estado con el motivo; el env KGSL no se inyecta si la familia
  no lo justifica (el gate de la 011 se re-evalúa, no se toca).
- **Socket colgado sin servidor (fichero rancio tras un crash):** al arrancar
  el servidor se limpia el fichero rancio antes de re-vincular; la receta
  explica cómo distinguir el error «socket sin servidor» del «no hay
  driver».
- **ABI no arm64** en un dispositivo teórico x86: el pipeline solo produce
  arm64 como el resto del fork («Solo AArch64»); sin binario, aplica RF-9.
- **Reinstalar rootfs con VirGL activo:** la preferencia persiste pero el
  guest ya no existe; cuando el rootfs vuelve a estar instalado, el panel
  vuelve a ofrecer VirGL. A diferencia de la vía KGSL, la vía VirGL no
  depende de la versión de mesa del invitado (usa el driver `virpipe` de la
  mesa de distro tal cual, sin paquetes recompilados), así que no existe gate
  de versión.

## Fuera de alcance

- **Zink / Vulkan en el invitado** (panvk necesita DRM/panthor, ausente en
  kernels Android stock Mali; en Adreno ya lo cubre mejor la 011 vía KGSL
  directa): solo documentado como limitación.
- **OpenCL / cómputo (IA, Ollama):** el ICD del host no es dlopen-reliable sin
  root en Mali; documentado como limitación honesta, sin placebo.
- **Visor VNC dentro de la app** (pantalla integrada): el display story usa
  un visor externo; una vista integrada queda para una spec futura.
- **termux-x11 externo:** puede funcionar (receta alternativa en la wiki) pero
  no es el camino principal ni se integra en la app; queda documentado, sin
  código del fork involucrado.
- **Benchmarks embebidos, tuning de rendimiento, promesas de FPS:** la medida
  real en dispositivo corresponde al mantenedor, como en 011/012.

## Criterios de finalización

- [ ] `./gradlew test` verde, con tests para: la pureza del gate de
  activación VirGL/KGSL (RF-6), la ampliación de la preferencia a `VIRGL`
  sin tocar ninguna clave existente del bloque persistido (gotcha 008), la
  construcción del env vía VirGL (RF-3, variables y orden) y la limpieza o
  ausencia del env cuando está apagado (RF-5/NFR-3) — sobre la base de los
  tests ya existentes de `ProotShellEnvironment`.
- [ ] Pipeline de compilación del servidor (RF-1): el binario está en el APK
  arm64 y se verificó con `unzip` (lección de memoria: `BUILD SUCCESSFUL` no
  garantiza el APK usable); el arranque real con EGL del sistema en el
  dispositivo queda para la parte en-dispositivo.
- [ ] Validación en dispositivo (Mali T606, stock, `enforcing`): activar
  VirGL → el servidor arranca y, vía la receta del escritorio, `glxinfo` en
  el invitado no reporta llvmpipe; desactivar → todo vuelve al estado previo
  byte a byte; htop/overlay de la spec 010 sin regresión.
- [ ] Documentación (RF-8): wiki limitaciones + README actualizados con la
  tercera vía, la receta de display mínimo paso a paso y las limitaciones
  honestas (Zink/OpenCL).

## Dudas abiertas

- [NECESITA ACLARACIÓN] **Versión y tag exactos del servidor:** el código base
  (tag/commit de `virglrenderer` y de sus dependencias menores) se fija en el
  plan; hasta fijarlo no se cierra el pipeline (espíritu de la constitución
  #5: nada sin pin).
- [NECESITA ACLARACIÓN] **Path del socket y variable de conexión del cliente**
  (`VIRGL_TEST_SERVER_URI` vs path estándar de la era vtest): depende de la
  mesa `virpipe` del rootfs (`25.0.7-2+deb13u1`); se confirma con prueba real
  dentro del invitado en la primera tarea de dispositivo y queda escrito en
  las constantes.
- ¿Arranque del servidor: al crear la primera sesión con la preferencia
  activa vs. al activar el switch en Ajustes? El borrador asume «al activar
  el switch + al crear sesión si no está vivo» (RF-2/RF-4).
- ¿La receta VNC del escritorio se promueve a «oficial» del mantenedor tras
  validarla (comprobar también que el paquete del servidor VNC existe y es
  instalable en la Debian 13 del rootfs)?
