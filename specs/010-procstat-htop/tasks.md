# Tareas — Spec 010: htop con estadísticas de CPU reales

- [x] **T1. Librería pura `ProcStatOverlayLibrary` (render + deltas + `btime`).** RF-2, RF-4, RF-11, NFR-2.
  - Hecho cuando: tests en rojo primero (`renderStat` 1/4/8 núcleos con suma `cpuN == cpu`, `renderMeminfo`, `renderLoadavg`, `advanceCpu` con pid nuevo/muerto/clamp, `btimeFromBoot`) y luego verde; `./gradlew :termux-shared:test` verde.

- [x] **T2. Decisión de vías y lista de overlays.** RF-3, NFR-5.
  - Hecho cuando: tests `overlays()` en rojo (todo legible → vacía; parcial → solo necesarios) y luego verde.

- [x] **T3. Overload de `buildProotCommand` con binds + constantes del directorio overlay.** RF-1, NFR-6.
  - Hecho cuando: `ProotShellEnvironmentTest` en rojo (con overlay: binds tras `-b /proc` con formato `host:guest`; sin overlay: comando idéntico al vigente) y luego verde; constantes en `TermuxConstants.java`.

- [x] **T4. `ProcStatOverlaySampler` + ciclo de vida en `TermuxService`.** RF-2, RF-4, RF-5, RF-6, RF-7, NFR-3, NFR-4.
  - Hecho cuando: la sesión proot escribe snapshot inicial y pasa binds; tick de
    1 s con escritura atómica; contador de sesiones a 0 para el muestreador (en
    las tres rutas de retirada); parser de `/proc/<pid>/stat` y conteo de
    estados testeados en JUnit; la verificación del tick en runtime queda en
    T5/T6.
  - Hecho cuando: la creación de sesión proot escribe snapshot inicial y pasa binds; el tick de 1 s actualiza snapshots con escritura atómica; al quedar 0 sesiones proot el muestreador se para; los puntos de retirada de sesión (remoción individual y cierre masivo) están cubiertos; Smoke unitario del hilo (sin Robolectric) ejecutado con `./gradlew test` integro en verde.

- [ ] **T5. Ajuste fino del orden de binds si proot los resuelve al revés.** RF-1.
  - Hecho cuando: en el emulador o dispositivo, `head -c 32 /proc/stat` dentro del invitado devuelve nuestro snapshot (no EACCES ni el nuevo contenido del guest), o se documenta y aplica el reorden de una línea si hiciera falta.

- [ ] **T6. Validación en dispositivo (mantenedor).** RF-5, RF-9, NFR-2.
  - Hecho cuando: htop arranca sin errores; con carga (`while true; do :; done`) la CPU sube, tras Ctrl-C baja; `MemTotal` coherente (±0.5 GB); sin sesiones activas el fichero de snapshot queda congelado (mtime); con `/proc/stat` legible (root/permissive si disponible) usa la vía directa y no escribe snapshot actualizado.

- [x] **T7. Documentación de la limitación resuelta.** RF-8.
  - Hecho cuando: `README.md` (§ Limitaciones), `docs/proot-debian-arm64-plan.md`
    (§ 7), `wiki/limitaciones.html` y `docs/en/index.md` describen el mecanismo
    (qué es real, qué sintético y el modelo de datos); `CHANGELOG` se toca en el
    release; `public/index.html` sigue describiendo 2.1.0 y se ajusta en el
    release de 3.0.

## Notas

- Cada tarea de código: test en rojo → implementación → `./gradlew test` en verde → marcar → parar (constitución #4).
- Commits Conventional Commits con tipo capitalizado (`Added`, `Fixed`…).
- `docs/` y especificación técnica en inglés; texto para el usuario en español (constitución #6).
