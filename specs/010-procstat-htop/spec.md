# Spec 010 — htop con estadísticas de CPU reales en `/proc/stat` bloqueado

Estado: **aprobada** (2026-10-21; dudas cerradas: alcance genérico, per-core
uniforme, cadencia 1 s — ver Decisiones cerradas)

## Contexto y objetivo

Desde Android 8 (API 26) SELinux bloquea que una app (`untrusted_app`) lea
`/proc/stat`; Google lo cerró como «Won't Fix (Intended Behavior)» porque el
archivo filtra canales laterales entre apps. En dispositivos stock
(`enforcing`, sin root) cualquier herramienta que abra `/proc/stat` desde el
terminal muere a su arranque:

```
root@localhost:~# htop
Cannot open /proc/stat: Permission denied
```

`btop` falla en la misma pieza. Este fork tenía la limitación **documentada**
(`README.md` § Limitaciones conocidas, `CHANGELOG` 2.0.0,
`wiki/limitaciones.html`, `docs/proot-debian-arm64-plan.md` § 7) y sin código.
La primera spec de la serie 3.0 la pasa de documentada a **resuelta sin root**:
si el kernel no quiere darnos `/proc/stat`, el terminal fabrica la vista de
`/proc/stat` que htop necesita, con **datos reales obtenidos por las vías que
Android sí permite** (procesos propios vía `/proc/<pid>/stat`, `SystemClock`,
`ActivityManager.MemoryInfo`, recuento de núcleos, y lectura directa de
cualquier fichero que el dispositivo sí deje accesible).

Como referencia de la industria: el paquete htop de Termux lo «resuelve» con
el mismo truco de redirección, pero alimenta el fichero destino con una
muestra **fija de instalación** (los medidores no son datos vivos). Esta spec
es más ambiciosa: htop arranca y muestra **actividad que responde al uso real
del dispositivo**.

## Usuarios

- El usuario del terminal que ejecuta `htop` en el rootfs Debian y espera
  medidores de CPU con la semántica de un GNU/Linux de sobremesa, sin root ni
  ajustes.
- Validación en dispositivo: el mantenedor, en hardware stock con SELinux
  `enforcing`.

## Historias de usuario

- HU-1. Como usuario, quiero que `htop` arranque sin el error «Cannot open
  /proc/stat: Permission denied», para usarlo igual que en cualquier GNU/Linux.
- HU-2. Como usuario, quiero que el medidor de CPU refleje actividad real y se
  mueva en vivo, para diagnosticar cargas de mi entorno.
- HU-3. Como usuario, quiero que la RAM que htop muestra («Mem»/
  «MemTotal/MemAvailable») sea coherente con el dispositivo, para valorar cuánta
  memoria queda libre.
- HU-4. Como usuario, quiero que si el dispositivo **sí** permite leer
  `/proc/stat` (root, SELinux permissive, kernels sin restricción), htop use
  esos datos directos, para tener la vía nativa intacta.

## Definiciones

- **Fichero de estadísticas (stat-snapshot).** Fichero normal (no procfs) con
  el formato de `/proc/stat` que htop espera leer, escrito por el lado Android
  y actualizado mientras haya sesión de terminal activa.
- **Fuente directa.** Situación en la que el proceso puede abrir el fichero
  original (`/proc/stat`, `/proc/meminfo`, `/proc/loadavg`, …) en lectura. Se
  sondea con un intento de apertura al construir la sesión; no se asume nunca.
- **Carga propia.** Suma de tiempo usuario+sistema de los procesos visibles
  para la app (UID propio). En este fork **todos los procesos del
  GNU/Linux del usuario corren sobre el UID de la app**, así que la carga propia
  es exactamente lo que htop lista: es el dato que Android expone sin root y
  el modelo lo reporta honestamente (vía synthetic = «lo medible», no invención).
- **Datos fijos de instalación.** Muestras estáticas tipo el paquete de Termux;
  **prohibidos** por esta spec (inservibles como diagnóstico).

## Requisitos funcionales

- RF-1: CUANDO el usuario lanza `htop` en el terminal del rootfs, EL SISTEMA lo
  arranca sin ningún mensaje fatal de `/proc/stat` y muestra su UI normal
  (procesos, medidores, teclas de función).
- RF-2: MIENTRAS hay una sesión de terminal proot activa, EL SISTEMA mantiene
  un fichero de estadísticas con, como mínimo: línea `cpu` (total), líneas
  `cpuN` por núcleo, `btime`, `procs_running`, `procs_blocked`.
- RF-3: SI un fichero fuente (`/proc/stat`, y en general cada dato: memoria,
  btime, running/blocked) es directamente legible en el dispositivo, ENTONCES
  el valor usado es el del fichero original leído a cada ciclo (vía directa,
  la síntesis solo cubre los datos con la vía bloqueada).
- RF-4: SI un dato no es legible directamente, ENTONCES el sistema lo sintetiza
  con las fuentes accesibles: nº de núcleos del sistema
  (`availableProcessors`/`sysconf`), btime derivado de
  `System.currentTimeMillis() − SystemClock.elapsedRealtime()`, memoria vía
  `ActivityManager.MemoryInfo` si `/proc/meminfo` está bloqueado, carga propia
  por delta de `/proc/<pid>/stat` de los procesos del UID, y nº de procesos
  corriendo/bloqueados desde `/proc/loadavg` (`corriendo/total`) si es legible,
  o contado sobre los procesos propios si no.
- RF-5: MIENTRAS htop está visible, los medidores responden: con un proceso del
  terminal cargando la CPU la barra sube y, al parar la carga, baja
  (verificación en dispositivo con un bucle tipo `while true; do sha256sum
  <ficherito>; done` y Ctrl-C).
- RF-6: CUANDO no queda ninguna sesión de terminal activa, EL SISTEMA deja de
  refrescar el fichero de estadísticas (cero muestreo en background con la app
  sin sesiones).
- RF-7: LA ESCRITURA del fichero de estadísticas nunca deja contenido a medias
  que htop pueda parsear mal (escritura atómica: fichero temporal completo +
  `rename`).
- RF-8: EL SISTEMA documenta la limitación resuelta: se actualizan las
  secciones que hoy dicen «sin código/sin workaround» (README, changelog
  cuando toque release, `wiki/limitaciones.html` y
  `docs/proot-debian-arm64-plan.md`), con el modelo exacto de datos (qué es
  real, qué es sintético y cómo).
- RF-9: EL SISTEMA valida en dispositivo los dos caminos: (a) dispositivo
  stock `enforcing` → htop funciona por la vía citada en RF-2/RF-4;
  (b) fuente directa disponible → se usan los datos originales sin alterar el
  comportamiento.
- RF-10: EL MECANISMO es genérico a nivel «/proc/stat como fichero», sin
  conocer la herramienta cliente: htop es la herramienta de validación de
  aceptación y se espera que `btop`/`top` se beneficien por sí solos, sin
  criterios formales de cierre específicos de ellas.
- RF-11: CUANDO solo se dispone de carga agregada sin atribución por núcleo,
  EL SISTEMA reparte la carga total a partes iguales entre las líneas `cpuN`
  (todos los núcleos muestran el mismo %; la suma cuadra con la línea `cpu`).

## Requisitos no funcionales

- NFR-1: Sin root, sin Magisk y sin permisos extra en el dispositivo.
- NFR-2: Corrección del dato: btime con error ≤ ±2 s frente al real;
  `MemTotal` coherente con el hardware (margen ±0.5 GB por zonas reservadas del
  kernel); la suma de las líneas `cpuN` es consistente con la línea `cpu` total.
- NFR-3: Refresco del fichero cada 1 s mientras hay sesión activa (tolerancia
  hasta 2 s bajo carga del hilo principal); htop refresca por defecto cada
  1.5 s, así el medidor «vive».
- NFR-4: Coste del muestreo despreciable: un muestreador de baja frecuencia
  (no bucle contiguo), sin escribir nada si no hay sesión activa.
- NFR-5: Con vía directa disponible en TODOS los datos (RF-3), no se escribe
  ningún fichero sintético (comportamiento nativo puro).
- NFR-6: Compatible con las variantes de sesión existentes (failsafe, plugins,
  rootfs Debian): el mecanismo solo toca sesiones proot del rootfs; failsafe y
  plugins quedan intactos.

## Casos límite

- **Rootfs recién instalado.»** htop no está en la imagen mínima; tras
  `apt install htop` funciona. El mecanismo es de entorno de sesión, no de
  contenido del rootfs.
- **Sesión larga que cruza muchas escrituras:** el bind es sobrefichero
  (file→file), y el contenido se reemplaza de forma atómica en el mismo inode
  (o se nota la implicación del `rename` en el plan, según se elija).
- **`/proc/loadavg` también bloqueado:** el contador running/blocked se saca de
  los procesos propios (RF-4); htop solo usa esos contadores para el pie de
  pantalla.
- **Varias sesiones con htop a la vez:** comparten el
  mismo snapshot; ninguna vista lo corrompe por las escrituras atómicas (RF-7).

## Fuera de alcance

- Root, Magisk, kernel personalizado o SELinux permissive como requisitos.
- Ver estadísticas de procesos ajenos al UID de la app (Android lo impide sin
  root; no hay dato que sintetizar).
- Reescribir los binds de `/proc` del proot integrado: el overlay es una capa
  añadida, no una sustitución.
- Herramientas cuyo formato de `/proc/stat` no estándar (parches raros de
  distro aparte de Debian).

## Criterios de finalización

- [ ] `./gradlew test` verde, con tests unitarios para: selección de vía
  (directa/sintética por dato), generación del texto del snapshot (`btime`,
  `cpuN` coherentes, `procs_*`), y argumentos de proot añadidos solo cuando
  toca.
- [ ] En dispositivo stock (`enforcing`, sin root): `htop` arranca sin errores;
  medidor de CPU sube con carga y baja al reposo (RF-5); memoria razonable.
- [ ] Con vía directa disponible: los medidores coinciden con el fichero
  original (RF-9b) y no se escribe snapshot sintético.
- [ ] Sin sesiones activas: el fichero de estadísticas deja de actualizarse
  (mtime congelado tras cerrar todo).
- [ ] Documentación de limitación actualizada en los 4 sitios de RF-8.

## Decisiones cerradas (2026-10-21)

- **Alcance:** genérico a nivel `/proc/stat`; aceptación en htop, btop/top como
  beneficiarios sin garantía formal (RF-10).
- **Per-core sin atribución:** reparto uniforme entre núcleos (RF-11).
- **Cadencia de refresco:** 1 s (NFR-3).
