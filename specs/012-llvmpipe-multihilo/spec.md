# Spec 012 — llvmpipe multihilo: `LP_NUM_THREADS` en el invitado

Estado: **implementada** (2026-10-22)

## Contexto y objetivo

Con la vía real de GPU cerrada en la mayoría de dispositivos (Adreno+KGSL es la
excepción, spec 011), el renderizador de facto del invitado Debian 13 es
**llvmpipe** (software, mesa 25.0.7-2+deb13u1 / LLVM 19.1.7). El problema
concreto: **llvmpipe por defecto usa pocos hilos** en nuestro invitado, así que
un `glmark2` o una app GPU-ish se fragmenta en un solo núcleo mientras el resto
del SoC duerme.

la palanca estándar de mesa es la variable de entorno **`LP_NUM_THREADS`** (y su
hermana gallium **`GALLIUM_NUM_THREADS`**): mesa 25 lee ambas para el nº de
hilos del rasterizador de software; fijamos las dos para no depender de cuál
respeta el renderizador de turno.

Esta spec no «acelera» un driver: reparte el trabajo de CPU que ya se hace.
El efecto debe ser medible hoy mismo en el hardware del validador (Mali,
medido sobre el invitado con `htop` del propio sistema — spec 010 — y un
benchmark documentable) y **anulable en un toggle** (valor por defecto
activado, N configurable, sin regresión: apagar la pref ⇒ el entorno del
invitado vuelve byte a byte a hoy).

## Usuarios

- El usuario cuya GPU no es acelerable (Mali, PowerVR, Adreno sin aceleración
  activa): todas sus apps gráficas (GTK, renderizado en canvas, mapas…) corren
  por llvmpipe y hoy dejan núcleos sin usar.
- El mantenedor: necesita un criterio medible de «funciona» para validar la
  spec en dispositivo.

## Historias de usuario

- HU-1. Como usuario Mali, quiero que llvmpipe use mis 8 núcleos en vez de uno,
  para que las apps con aceleración de mesa por CPU vayan visiblemente mejor.
- HU-2. Como usuario, quiero controlar el nº de hilos y poder apagar el
  comportamiento, para ajustar si mi dispositivo/batería lo prefiere.
- HU-3. Como usuario Adreno con la aceleración (spec 011) activa, quiero que
  estas variables NO se inyecten, para que el driver real no las herede.

## Definiciones

- **Thread-pool de llvmpipe.** Los `LP_NUM_THREADS` threads del rasterizador;
  más hilos ⇒ primitivas repartidas entre más núcleos.
- **Hipótesis activa.** Estado de compose settings: on (por defecto) = inyectar
  `LP_NUM_THREADS/GALLIUM_NUM_THREADS` cuando toca; off = no inyectar nada.
- **Contador de hilos N.** Valor configurado por el usuario (default 8, límite
  16); el valor real inyectado es `min(núcleos, N)`.

## Requisitos funcionales

- RF-1: SI el renderizador del invitado es llvmpipe (i.e. la GPU del dispositivo
  NO está acelerada por KGSL — consecuencia del gate de spec 011), ENTONCES el
  sistema inyecta en el entorno de los procesos del invitado
  `LP_NUM_THREADS=min(núcleos host, N)` y `GALLIUM_NUM_THREADS` con el mismo
  valor, donde núcleos = `Runtime.availableProcessors()` saneado y N es el
  valor corregible en settings (por defecto 8, límite 16).
- RF-2: SI la preferencia está desactivada (off), ENTONCES el entorno del
  invitado NO contiene esas dos variables y es, byte a byte, idéntico al de hoy
  (regresión cero).
- RF-3: EL SISTEMA valida la hipótesis en dispositivo (Mali) con un criterio
  medible: dentro del invitado `echo $LP_NUM_THREADS` devuelve
  `min(núcleos, N)` cuando está on y vacío cuando está off, y un benchmark
  documentable (p. ej. `glmark2-es2 --off-screen` con `LP_NUM_THREADS=1` vs `N`)
  muestra mejora apreciable o el motivo documentado de por qué no.
- RF-4: MIENTRAS la aceleración Adreno (spec 011) esté activa (`gpu_solution=
  ADRENO_KGSL`, gate vigente), EL SISTEMA no inyecta estas variables (no aplica
  a driver real).

## Requisitos no funcionales

- NFR-1: Sin root; sin permisos; una sola dependencia de mesa existente
  (nada nuevo en `libs.versions.toml`, constitución #1).
- NFR-2: Con la preferencia off, ni el comando proot ni el entorno del
  invitado cambian (igual de fuerte que la NFR-3 de la spec 011).
- NFR-3: El valor inyectado se guarda en las preferencias de la app SIN perder
  ninguna clave existente del bloque persistido (gotcha spec 008).

## Casos límite

- **Dispositivo de 1 núcleo reportado** (`availableProcessors()` = 1): hilos
  = 1 (sin crash, sin inyección de valores absurdos).
- **`availableProcessors()` elevado (>32, algunos ROMs mienten):** saneado
  (clamp 1..32) antes de `min(núcleos, N)`.
- **Setting N por encima de núcleos:** N=16 en un equipo de 8 → `min` baja a 8
  (N solo es techo).
- **Setting N=1:** `1` explícito (comparable con el estado off en el benchmark
  de RF-3).
- **Adreno acelerado (spec 011) + preferencia on:** sin inyección (RF-4); el
  panel de settings puede anotarlo, nada se rompe.
- **Update de la preferencia con sesión live:** el nuevo valor aplica a las
  próximas sesiones; las ya abiertas conservan su entorno (documentación del
  panel, no aceptación silenciosa).

## Fuera de alcance

- «Cómo se renderiza el display» (X11, termux-x11, wlroot): sigue siendo otra
  spec.
- Otros renderizadores (Vulkan lavapipe, swrast): las variables les puede
  ayudar colateralmente, pero sin criterio formal de cierre.
- Tuning fino per-app (valores distintos para determinadas aplicaciones),
  perfilado de batería, etc.

## Criterios de finalización

- [x] `./gradlew test` verde con tests unitarios: cálculo puro de
  `min(núcleos, N)` y saneo; inyección (variables con valor correcto cuando
  toca, mapa inalterado cuando off, ausencia total cuando Adreno activo).
- [x] En dispositivo Mali: `echo $LP_NUM_THREADS` dentro del invitado devuelve
  `min(núcleos,N)` con la preferencia on; vacío con la preferencia off; y
  `echo $GALLIUM_NUM_THREADS` responde al mismo valor que `LP_NUM_THREADS` en
  operativa on.
- [x] Benchmark documentado en la wiki (o motivo documentado si la herramienta
  headless no es ejecutable — dudas abiertas) con mejora ≥25% de score con
  `N` vs `1` como objetivo; si se mide, publicar el número con el contexto.
- [x] El panel de settings no ha tocado ninguna clave persistida preexistente
  (gotcha 008).

## Decisiones cerradas (2026-10-21)

- **Env global en el invitado vs per-process/per-app:** global (barato,
  predecible; llvmpipe es quien lo llama). Descartado per-app: complejidad de
  configuración sin beneficio medible hasta ahora.
- **Doble variable:** fijar `LP_NUM_THREADS` + `GALLIUM_NUM_THREADS` (mesa 25
  valora ambos; fijar solo uno es frágil entre binaries).
- **Sin setting separado para el caso Adreno:** el gate de RF-1 ya excluye el
  caso (la condición «llvmpipe» es la misma que «no acelerado»), un toggle
  adicional duplicaría control con la misma señal.
- **Default: on con N=8.** Por defecto activado porque el estado actual
  (llvmpipe con pocos hilos) es el peor escenario medible en el hardware del
  validador, y la NFR-2 garantiza una salida limpia. El juicio de valores
  (cuánto mejora el usuario medio) queda para el benchmark de RF-3.
  Alternativa descartada (default off): es exactamente el estado actual,
  no aporta.

## Dudas abiertas

- [NECESITA ACLARACIÓN] Benchmark headless: ¿`glmark2-es2 --off-screen` corre
  sin servidor gráfico (EGL surfaceless) en el rootfs minbase? Si no, definir
  qué comando medible llega al renderizador (candidato documentado: proxy con
  `htop` — spec 010 — comparando hilos activos bajo carga gráfica; o instalar
  un benchmark headless si existe en Debian 13). La validación mínima
  garantizada es la del env (`echo $LP_NUM_THREADS`), que RF-3 cubre siempre.
- Confirmar el efecto exacto de `GALLIUM_NUM_THREADS` en mesa 25.0.7 (mesa
  docs): no es crítico porque se fija igual que `LP_NUM_THREADS` y sobra si el
  driver ignora una, pero el plan de implementación debe citar el fuente.
- Persistir el valor N como int vs string en las prefs (detalle de
  implementación; sin impacto en los RF).
