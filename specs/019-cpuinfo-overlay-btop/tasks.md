# Tareas 019 — btop: overlay `/proc/cpuinfo`

Orden de dependencia. Cada tarea: tests primero (rojo) → código →
`./gradlew test` verde → marcar. Máx. 20–30 min por tarea.

- [x] **T1. Librería pura `ProcCpuInfoOverlayLibrary` con sus tests.**
  `modelNamePresent` (semántica por segmentos de `:`, como btop),
  `buildSyntheticCpuInfo`, `presentableCpuName`. NFR-6 / RF-2 / RF-5.
  - Hecho cuando: `ProcCpuInfoOverlayLibraryTest` pasa con los casos del plan
    (x86-like, arm64, vacío, sin `:`, nombres raros, SOC_MODEL/HARDWARE,
    >64 chars) y el resto de `./gradlew test` sigue verde.

- [x] **T2. Constante + glue en `ProcStatOverlaySampler`.**
  `DEBIAN_PROC_OVERLAY_CPUINFO_FILE_PATH` en `TermuxConstants`; en
  `prepareSessionOverlayBinds`: lectura host, guard SOC_MODEL (idéntico patrón
  a `GpuDeviceReader`), decisión `cpuInfoOverlayNeeded` (fn estática pura),
  escritura atómica del snapshot y bind añadido a la lista; early-return solo
  cuando nada aplica (NFR-5). Sesión solo-cpuinfo no arranca sampler ni
  cuenta sesión de sampler. RF-2/RF-3/RF-4/RF-7.
  - Hecho cuando: `cpuInfoOverlayNeeded` testada (cpuText null / con
    model name / sin nombre → false; sin model name + nombre → true), lectura
    fallida loggea y no produce bind; `./gradlew test` verde.

- [x] **T3. Test de ensamblado del comando proot.**
  En `ProotShellEnvironmentTest` (app): con `overlayBinds` que incluya el par
  cpuinfo, el comando contiene `-b <cpuinfoPath>` `-b /proc/cpuinfo`
  posicionados tras el bloque `-b /proc` (antes de `-b /sys`), y el resto del
  comando queda idéntico al caso de lista de hoy. RF-2.
  - Hecho cuando: test nuevo pasa por índice de lista y los asserts previos
    del fichero no cambian.

- [x] **T4. Build completo.**
  `./gradlew :termux-shared:test :app:test` (y `assembleDebug`) para descartar
  regresión de wiring; logs sin cambios cuando no aplica cpuinfo.
  - Hecho cuando: build y tests completos verdes.

- [x] **T5. Sync de docs (RF-8).** `docs/proot-debian-arm64-plan.md` § 7: en
  la tabla/limitación de sysfs registrar que btop queda resuelto vía snapshot
  `/proc/cpuinfo` (patrón spec 010); misma mención en README (§ Limitaciones)
  y `wiki/limitaciones.html`. CHANGELOG: queda para la release.
  - Hecho cuando: los tres sitios mencionan la resolución sin contradicciones
    con el estado actual.

- [x] **T7 (enmienda RF-9). Snapshot vivo de `/proc/uptime`.**
  `DEBIAN_PROC_OVERLAY_UPTIME_FILE_PATH` en `TermuxConstants`;
  `renderUptime` puro en `ProcStatOverlayLibrary` + test; sampler con flag
  `mUptimeOverlayNeeded` (probe directo `/proc/uptime`, vía nativa si legible),
  bind solo cuando toca, y arranque de sampler aunque stat/mem/load estén bien
  (uptime es el único bloqueado). T6 seguirá validando el conjunto.
  - Hecho cuando: `renderUptime` testado (formato 2 decimales, dos campos);
    `./gradlew test` verde con la decisión incluida en el ensamblado si el
    probe cambia entre tiendas (stat bien + uptime mal → sampler y bind
    uptime).

- [x] **T6. Validación en dispositivo + cierre.**
  `btop` sin el ERROR de `/sys/devices`, cabecera con el SoC, navegable;
  `cat /proc/cpuinfo` = contenido host + línea `model name`; `htop` sin
  regresión. Actualizar esta spec (estado: implementada), `MEMORY.md`.
  - Hecho cuando: los criterios de finalización de la spec marcados y el
    estado de la spec/MEMORY actualizado.
  - **Cerrada (2026-10-25):** btop arranca en dispositivo (validación del
    usuario); tras el arranque lo único que queda es el control de tamaño de
    btop (`Terminal size too small: Width = 72` con config pidiendo 80
    columnas) — propio de btop, resuelto por el usuario rotando el dispositivo
    o reduciendo el tamaño de letra de la terminal. Documentado en RF-10 y en
    README/wiki.
