# Spec 019 — btop arrancable: model name en `/proc/cpuinfo` para no listar `/sys/devices`

Estado: **cerrada** (2026-10-25; T1–T7 implementadas, validada en dispositivo
por el usuario: btop arranca sin errores de SELinux/procfs con los overlays
cpuinfo + uptime activos. Residual documentado: el requisito de btop de
≥80 columnas — «Terminal size too small» con terminal de 72 — es control
propio de btop, no del sistema; ver RF-10).

## Contexto y objetivo

`btop` muere a su arranque en el rootfs Debian de este fork:

```
root@localhost:~# btop
ERROR: Exception in Shared::init() -> filesystem error: directory iterator
cannot open directory: Permiso denegado [/sys/devices]
```

La cadena exacta (verificada contra el código fuente de btop, `src/linux/btop_collect.cpp`):

1. `Shared::init()` llama a `Cpu::get_cpuName()`, que abre `/proc/cpuinfo`
   (ese fichero **sí es legible** por el UID de la app) y escanea línea a línea
   buscando una cuyo token antes de `:` empiece por `model name`.
2. En Android arm64 el kernel no emite ninguna línea `model name`, así que el
   bucle termina en EOF y btop entra en su fallback: iterar
   `fs::directory_iterator("/sys/devices")` buscando una entrada `arm*`.
3. `readdir` de directorios sysfs está **denegado** por las políticas SELinux
   OEM del dominio de la app (limitación ya documentada en
   `docs/proot-debian-arm64-plan.md` § 7: se permite `search`/`open` de nodos
   concretos, no enumerar directorios). La excepción sube sin capturar
   (`get_cpuName` es la única ruta pro-sysfs sin guard de btop; batería y
   sensores están blindados con `try`/`access`) y btop aborta.

Objetivo: que `btop` arranque en el rootfs Debian sin root. El mecanismo es el
mismo de la spec 010: un **snapshot sintético servido por bind de proot**, esta
vez para `/proc/cpuinfo` — copia **verbatim** del fichero host (el contenido
real, que es legible) con **una línea `model name` añadida**. Con esa línea,
btop encuentra el nombre y sale por la rama de `model name` antes de llegar al
fallback de `/sys/devices`; el listing denegado deja de ejecutarse.

Diferencia clave con la spec 010: allí el overlay sustituía lecturas
**bloqueadas**; aquí el fichero original es legible y el overlay existe por
**semántica** (el fichero Android carece de un campo que las herramientas de
escritorio asumen). No reemplaza lecturas denegadas: publica el mismo fichero
enriquecido.

## Usuarios

- El usuario del terminal que ejecuta `btop` (o cualquier herramienta cuyo
  arranque liste `/sys/devices` solo como fallback cuando cpuinfo carece de
  `model name`) en el rootfs Debian, sin root ni ajustes.
- Validación en dispositivo: el mantenedor, en hardware stock SELinux
  `enforcing`.

## Historias de usuario

- HU-1. Como usuario, quiero que `btop` arranque sin el error «directory
  iterator cannot open directory: Permiso denegado [/sys/devices]», para usar
  su UI completa (procesos, CPU, memoria, red, disco).
- HU-2. Como usuario, quiero que btop muestre un modelo de CPU identificable
  (el SoC del dispositivo), en lugar de una cabecera vacía o el nombre
  truncado del arm de Android.
- HU-3. Como usuario, quiero que el resto del contenido de `/proc/cpuinfo`
  sea el real del dispositivo, para no perder datos que otras herramientas
  ya parsean hoy (nº de procesadores, `BogoMIPS`, `Features`, `CPU part`...).

## Definiciones

- **Snapshot cpuinfo.** Fichero normal con el contenido del `/proc/cpuinfo`
  host tal cual, más una línea `model name : <nombre>` añadida al final.
- **Nombre del modelo.** Valor presentable del SoC/fabricante si el dispositivo
  lo expone, sin espacios ni saltos raros; se extrae de las etiquetas Android
  (`Build.SOC_MODEL` API 31+; degradación a `Build.HARDWARE`); vacío o sin
  valor útil → **no se añade línea y no hay overlay** (vía nativa pura).
- **Vía nativa pura.** Situación en la que NO se publica snapshot, porque el
  arranque de la herramienta no depende de `model name`: host cpuinfo con
  `model name` presente (kernels x86/otra bases) o sin nombre utilizable.
- **Fichero estático.** `/proc/cpuinfo` es estático por arranque del kernel
  (a diferencia de `/proc/stat` de la spec 010): no hay muestreo periódico.

## Requisitos funcionales

- RF-1: CUANDO el usuario lanza `btop` en el terminal del rootfs Debian en un
  dispositivo donde `readdir` de `/sys/devices` está denegado y el SoC tiene
  nombre utilizable, EL SISTEMA lo arranca sin ningún mensaje fatal de
  `/sys/devices` y muestra su UI normal.
- RF-2: MIENTRAS se arranca una sesión proot Debian y el `/proc/cpuinfo` host
  carece de línea `model name` siendo el nombre del SoC utilizable, EL SISTEMA
  publica (regenerando en el arranque de cada sesión, idempotente) un snapshot
  cpuinfo = contenido host verbatim + línea `model name : <nombre>`, y lo
  bindea a `/proc/cpuinfo`.
- RF-3: SI el `/proc/cpuinfo` host ya contiene un token `model name` (o no
  existe nombre utilizable), ENTONCES EL SISTEMA no publicará snapshot ni bind
  para cpuinfo: vía nativa pura, fichero original intacto.
- RF-4: SI el `/proc/cpuinfo` host deja de ser legible directamente (dispositivo
  más restrictivo que lo deniega), ENTONCES se registra el motivo en el log y
  la sesión sigue funcionando vía nativa (sin snapshot): btop vuelve a fallar
  por lo mismo que hoy, pero nada se rompe de más.
- RF-5: EL SISTEMA añade la línea al **final** del fichero: el resto del
  contenido queda byte a byte idéntico al host (parseadores por bloque como
  htop/lscpu no pierden claves; la línea extra se ignora o se adopta como en
  un core más).
- RF-6: EL MECANISMO es genérico a nivel «/proc/cpuinfo con model name» y no
  conoce a btop: btop es la herramienta de validación; otras (`top`,
  `lscpu`, ...) se benefician sin criterios propios de cierre.
- RF-7: LA ESCRITURA del snapshot nunca deja contenido a medias visible al
  binding (fichero temporal completo + `rename`), igual que la spec 010 RF-7.
- RF-8: EL SISTEMA documenta la limitación resuelta donde hoy se describe sin
  código (docs/proot-debian-arm64-plan.md § 7; actualizando README/wiki/
  changelog cuando toque release) indicando la vía del snapshot cpuinfo.
- RF-9 (enmienda 2026-10-25 en dispositivo): SI el `/proc/uptime` host no es
  legible directamente (misma familia de `/proc/stat` bloqueado), ENTONCES EL
  SISTEMA lo cubre con un snapshot vivo del sampler (mismo period de 1 s):
  `<uptime> <idle>`, con uptime exacto derivado de `SystemClock.elapsedRealtime()`
  e idle estimado como el presupuesto de idle de spec 010 (uptime × núcleos;
  el segundo campo no lo consume btop). MIENTRAS hay sesión y la vía directa
  es posible (proxy directo/regresión), el bind no se emite (vía nativa).
  El snapshot entra en el sampler vivo (no hay un contador estático: btop
  espera que los segundos avancen entre lecturas).
- RF-10 (validación en dispositivo): CUANDO la terminal activa tiene menos
  columnas de las que la configuración de btop pide (80 por defecto), EL
  SISTEMA no interviene: btop muestra su mensaje propio
  («Terminal size too small») y el remedio es del usuario (rotar el
  dispositivo a apaisado o reducir el tamaño de letra de la terminal). EL
  SISTEMA deja dicho requisito documentado (README + wiki) para que nadie lo
  confunda con una limitación del rootfs.

## Requisitos no funcionales

- NFR-1: Sin root, sin Magisk y sin permisos extra.
- NFR-2: Coste acotado del arranque de sesión: regenerar el snapshot es leer
  `/proc/cpuinfo` (unos KB) y escribir un fichero; sin muestreo en background
  ni schedulers nuevos (no abre hilos: cpuinfo es estático por boot).
- NFR-3: Nombre del modelo correcto: `Build.SOC_MODEL` (API 31+) es la fuente
  prioritaria; el módulo mantiene compatibilidad desde API 24 sin crash
  (degradación a `Build.HARDWARE` o a «sin overlay»).
- NFR-4: Fidelidad del snapshot: el texto adicional no altera otras claves ni
  el orden de bloques; los parseadores ya existentes no cambian de
  comportamiento (contenido equivalente).
- NFR-5: Compatible con las variantes de sesión (failsafe, plugins, rootfs
  Debian): solo afecta a sesiones proot del rootfs; el resto queda intacto.
- NFR-6: Lógica de decisión y de generación del texto en funciones puras con
  tests JUnit sin Android (constitución #3). El glue Android (lectura host,
  escritura del snapshot) es mínimo.

## Casos límite

- **Lectura host bloqueada** (RF-4): registrar por log y seguir — btop volverá
  a fallar igual que hoy, pero la sesión y el resto de herramientas no se
  tocan.
- **Snapshot residual de sesiones anteriores:** no hay estado caduco; el
  fichero se reescribe completo en cada arranque de sesión.
- **`Build.SOC_MODEL` vacío u «unknown»:** no se añade línea; vía nativa pura
  (mitigación: btop seguiría sin arrancar en esos dispositivos — caso
  prácticamente nulo en hardware real).
- **El fichero host cambia bajo la lectura** (CPU hotplug entre líneas): la
  lectura es una foto coherente de un instante; comportamiento estándar del
  kernel y sin consecuencia aquí.
- **Varias sesiones a la vez:** comparten el mismo snapshot; las escrituras
  atómicas (RF-7) garantizan lecturas íntegras.

## Fuera de alcance

- Hacer legible `/sys` (root, Magisk, SELinux permissive, kernel custom).
- Sintetizar `getdents` para binds dentro de proot (más invasivo; no se toca
  el módulo proot de `app/`).
- Cubrir `ls /dev` / `ls /sys`, o herramientas cuyo arranque liste `/sys` por
  caminos distintos del fallback «cpuinfo sin model name».
- Binds adicionales de ficheros sysfs concretos (cpufreq etc.): si el
  dispositivo deniega esos `open()`, btop pierde las frecuencias por núcleo
  (degradación no fatal e igual a la situación actual aceptada).

## Criterios de finalización

- [x] `./gradlew test` verde, con: tests de `needsModelNameLine`
  (presente/ausente/fichero vacío), tests de `buildSyntheticCpuInfo`
  (verbatim + línea al final; sin nombre utilizable → sin línea), y test del
  ensamblado del comando proot (bind cpuinfo solo cuando toca, posicionado
  junto al resto de overlays).
- [x] En dispositivo stock (SELinux enforcing, sin root): `btop` arranca sin
  el ERROR de `/sys/devices`, muestra el SoC como nombre de CPU y es navegable;
  sin regresión en `htop` ni otras herramientas.
- [x] `cat /proc/cpuinfo` dentro del guest contiene el contenido host verbatim
  + la línea `model name` (implícito: el arranque requiere que el scan de btop
  encontró la línea; `cat` no volcado en la validación).
- [x] RF-9: en dispositivo con `/proc/uptime` bloqueado, `btop` arranca sin
  «Failed to get uptime» y el snapshot `uptime` avanza cada ~1 s (el arranque
  ya exige el uptime inicial; el refresco por tick quedó cubierto por el
  sampler de spec 010) ; con la vía directa posible no se emite bind de
  uptime.
- [x] Failsafe/plugins sin el bind extra (cobertura en tests del ensamblado;
  `TermuxService` no toca la rama failsafe/plugin).
- [x] Doc RF-8 actualizada (plan doc §7, README, wiki; CHANGELOG para release).

## Decisiones cerradas (2026-10-25)

- **Glue junto al sampler de la spec 010:** `prepareSessionOverlayBinds`
  también decide y devuelve el bind de cpuinfo (único punto de decisión de
  binds `/proc`); `TermuxService` no cambia. Lógica pura en una
  `ProcCpuInfoOverlayLibrary` nueva.
- **RF-8 solo sync de docs:** `docs/proot-debian-arm64-plan.md` § 7, README y
  wiki; CHANGELOG queda para la release (estilo resto del repo).
