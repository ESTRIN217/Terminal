# Plan 019 — btop: overlay `/proc/cpuinfo` con `model name`

Spec manda: `specs/019-cpuinfo-overlay-btop/spec.md` (aprobada).
Decisiones cerradas: glue junto al sampler spec 010 (único punto de decisión
de binds `/proc`); doc sync sin CHANGELOG (queda para la release).

## Archivos y responsabilidades

| Archivo | Cambio | RF |
|---|---|---|
| `termux-shared/.../termux/proot/ProcCpuInfoOverlayLibrary.java` (NUEVO) | Funciones puras: detección de `model name` al estilo exacto de btop, construcción del texto sintético, selección del nombre presentable del SoC | RF-2/3/5, NFR-6 |
| `termux-shared/.../termux/proot/ProcStatOverlaySampler.java` | `prepareSessionOverlayBinds` también decide el overlay de cpuinfo (RF-2/3/4/7) y el overlay vivo de `/proc/uptime` (RF-9): probe, snapshot y bind | RF-2/3/4/7/9 |
| `termux-shared/.../termux/TermuxConstants.java` | Constantes `DEBIAN_PROC_OVERLAY_CPUINFO_FILE_PATH` y `DEBIAN_PROC_OVERLAY_UPTIME_FILE_PATH` | RF-2, RF-9 |
| `app/src/test/...ProotShellEnvironmentTest.java` | Test de ensamblado del comando con el bind cpuinfo | RF-2, criterio test |
| `termux-shared/src/test/...ProcCpuInfoOverlayLibraryTest.java` (NUEVO) | Tests de las fns puras | NFR-6 |
| `docs/proot-debian-arm64-plan.md` § 7, `README.md`, `wiki/limitaciones.html` | Sync de la limitación (RF-8; CHANGELOG para la release) | RF-8 |
| `ProcStatOverlayLibrary.java` | `renderUptime(upSec, idleSec)` puro → `<up> <idle>` con 2 decimales | RF-9 |

`TermuxService` y el módulo `proot` C **no** cambian.

## Funciones puras

En `ProcCpuInfoOverlayLibrary`:

- ` needsModelNameLine(String? hostCpuInfoText) -> bool `
  Réplica exacta del scan de btop (`getline(..., ':')` + `ignore(..., '\n')`):
  por cada tramo, texto desde la posición actual hasta el próximo `:`, partido
  en línea; tras un `:` se salta al fin de la línea (una línea sin `:` entra en
  el siguiente tramo). Hay línea si algún tramo empieza por
  `model name`. Casos: texto vacío → sin línea; x86-like → la halla;
  arm64 Android → no; `model name` sangra/tras texto en la misma línea → se
  pierde (bug-por-bug).

- ` buildSyntheticCpuInfo(String hostText, String? modelName) -> String `
  hostText verbatim; si `modelName` no utilizable → hostText intacto
  (sin línea); si no, `hostText` + `\nmodel name : <nombre>\n`
  (se garantiza exactamente un `\n` entre el cuerpo y la línea nueva).
  Nombre saneado: trim, colapsar whitespace interno a espacio, sin `:`
  ni `\n`.

- ` presentableCpuName(socModel?, hardware?) -> String? `
  Prioridad: `socModel` (trim) si no null/blank y ≠ `unknown` (valor
  default AOSP en algunos dispositivos/«indefinido»); si no `hardware`
  (trim); null si ninguna utilizable. Anti-basura: máx 64 chars → truncar
  o rechazar (decisión: rechazar → null, evita líneas corruptas).

En el sampler, decisión (fn `static` puro testeable):

- ` cpuInfoOverlayNeeded(cpuText?, presentableName?) -> bool `
  = cpuText legible && needsModelNameLine(cpuText) && presentableName != null.
  (cpuText null → false: RF-4 vía nativa.)

`/** "hoy" */` — no hay parámetro temporal: cpuinfo es estático por boot.

## Algoritmo (pseudocódigo de `prepareSessionOverlayBinds`)

```
statRead, memRead, loadRead, uptimeRead  ← probes (spec 010 + RF-9 enmienda)
cpuText    ← leer /proc/cpuinfo (silencioso, con RF-4 log si IOException/SecurityException)
cpuName    ← presentableCpuName(socModelGuardado(), Build.HARDWARE)
cpuOverlay ← cpuInfoOverlayNeeded(cpuText, cpuName)

SI (statRead && memRead && loadRead && uptimeRead && !cpuOverlay) → RETURN null // NFR-5

asegurar directorio overlay (como hoy, log-error y seguir)
SI CPUINFO: buildSyntheticCpuInfo → snapshot estático atómico → bind /proc/cpuinfo
SI !statRead || !memRead || !loadRead || !uptimeRead:   // datos vivos → sampler
    contar sesión + arrancar (o mantener) sampler
    binds ← overlays(statRead, memRead, loadRead) + ({uptimePath,"/proc/uptime"} SI !uptimeRead)
RETURN binds
```

Sampler (tick, RF-9): si `mUptimeOverlayNeeded`, escribe
`renderUptime(elapsedRealtime()/1000.0, elapsedRealtime()/1000.0 * availableProcessors)`
con la escritura atómica común. El snapshot es **vivo**: btop compara uptimes
entre lecturas (per-process CPU y actividad de disco), congelarlo degradaría
los % sin ser exacto.

Nota: sesión con SOLO cpuinfo overlay no arranca sampler ni toca contadores;
`onProotSessionRemoved()` (servicio) es no-op seguro con `sSampler == null`
(los guards existentes lo toleran).

## Interfaz

- Bind añadido como `String[]{hostPath, "/proc/cpuinfo"}` a la lista que ya
  recibe `buildProotCommand(..., overlayBinds)`: se inserta tras `-b /proc`
  y el bind de fichero sombra al bind de directorio (mecanismo igual que
  `/proc/stat`, verificado en dispositivo en la spec 010).
- `GpuDeviceReader` tiene su guard privado de `Build.SOC_MODEL` (API 31+,
  spec 011). Sin exponerlo: el guard se replica en el glue del sampler
  (2 líneas, mismo patrón `Build.VERSION.SDK_INT < Build.VERSION_CODES.S`),
  evitando tocar el API público de la spec 011.

## Decisiones con su alternativa descartada

- **Copiar cpuinfo + añadir línea** frente a «no tocar cpuinfo y bindear
  nodos sueltos de /sys»: proot no sintetiza `getdents` para binds (§7 docs /
  path/glue.c), así que bindear entradas bajo `/sys/devices` no haría listable
  el directorio; el fallback de btop exige listarlo. Descartada por
  inservible.
- **Glue en el sampler frente a pieza nueva autónoma:** decisión del usuario
  (2026-10-25): único punto de decisión de binds `/proc`, `TermuxService`
  intacto. La pieza nueva queda solo en la librería pura.
- **Réplica del guard SOC_MODEL frente a exponer el de GpuDeviceReader:**
  evita ensanchar el API de spec 011; guard de 2 líneas replicado con su
  comentario cross-ref. Alternativa descartada por acoplamiento entre specs.
- **Rechazar nombres >64 chars frente a truncar:** un nombre truncado
  presentado como modelo es peor que mostrar vacío.
- **Reescritura por sesión (idempotente) frente a caché por mtime:** coste
  trivial (~KB, síncrono en el arranque de sesión ya síncrono); más simple y
  sin estados residuales.

## Estrategia de tests (`./gradlew test`)

- `ProcCpuInfoOverlayLibraryTest` (JVM puro):
  - `needsModelNameLine`: x86-like (false), arm64 Android (true), texto
    vacío/null (true), fichero sin `:`, «model name» sangrado/tras texto en
    línea (true, semántica bug-por-bug), al inicio de línea (false).
  - `buildSyntheticCpuInfo`: verbatim completo + única línea final; con
    `null`/«unknown»/blank → texto intacto; saneado (espacios, `:`, salto).
  - `presentableCpuName`: SOC_MODEL win, unknown → HARDWARE, ambos vacíos →
    null, trim/whitespace interno, >64 chars → rejected pass al HARDWARE.
- `ProcStatOverlaySamplerTest`: si cubre hoy la decisión (mirar), extendemos
  con `cpuInfoOverlayNeeded` (RF-2/3/4); el sampler solo queda con su prueba
  funcional de dispositivo (sin tests de I/O Android).
- `ProotShellEnvironmentTest`: GIVEN lista con bind cpuinfo → el comando
  contiene `-b <cpuinfoPath>` + `/proc/cpuinfo` inmediatamente después del
  bloque `-b /proc` (antes de `-b /sys`) asserts por índice de lista; orden
  del resto de binds intacto.

Cada tarea: tests primero, rojo→código→verde, `./gradlew test` completo antes
de marcarla (constitución #4).
