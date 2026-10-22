# Tareas — Spec 012: llvmpipe multihilo (`LP_NUM_THREADS`)

- [x] **T1. Librería pura `LlvmpipeThreadsLibrary`.** RF-1.
  - Hecho cuando: tests en rojo primero (`computeThreads` con las parejas
    (8,8)(4,8)(16,8)(1,16), clamps coresRaw 0/99, prefN 0/99; `llvmpipeEnvEntries`
    ambas vars con el mismo valor / mapa vacío; `shouldInject` con los tres casos)
    y luego verde; `./gradlew :termux-shared:test` verde.

- [x] **T2. Constantes de claves y prefs persistentes.** RF-2, NFR-3.
  - Hecho cuando: claves nuevas como constantes en `TermuxConstants`;
    `TermuxAppSharedPreferences` con `isLlvmpipeThreadsEnabled` (default **on**
    explícito) y `get/setLlvmpipeThreadsCount` (default 8) — solo claves
    añadidas, **ninguna clave existente del bloque persistido tocada** (gotcha
    spec 008); test de defaults y round-trip en rojo→verde.

- [x] **T3. Cableado en `ProotShellEnvironment.getEnvironment`.** RF-1, RF-2, RF-4.
  - Hecho cuando: ampliación de `ProotShellEnvironmentTest` en rojo (on+llvmpipe →
    mapa con `LP_NUM_THREADS=8` y `GALLIUM_NUM_THREADS=8`; pref N=4 → `4`; off →
    mapa byte a byte idéntico al vigente; gpuAccelerated=true → sin variables) y
    luego verde; lectura de `availableProcessors()` solo en el glue.
    No lanzar builds Gradle en paralelo al validar.

- [x] **T4. UI en settings (Switch + Slider N) + strings ×3 locales.** RF-1, RF-2.
  - Hecho cuando: en `compose/settings` switch «Hilos llvmpipe» y slider N
    (1..16, step 1, default 8) con función pura de descripción del valor (`min(
    núcleos, N)`) mostrada como string de settings; nota «aplica a las próximas
    sesiones» y texto honesto cuando Adreno activo («no aplica: aceleración GPU
    activa»); cero literales en Compose (`values/`, `values-es-rVE`,
    `values-pt-rBR`); `./gradlew test` verde.

- [x] **T5. Validación en dispositivo (Mali) — RF-3 + benchmark.**
  - Hecho cuando: dentro del invitado `echo $LP_NUM_THREADS` = `min(núcleos, N)`
    con la opción on y vacío con off; `echo $GALLIUM_NUM_THREADS` coherente;
    benchmark intentado (p. ej. `glmark2-es2 --off-screen` con 1 hilo vs N) y
    su resultado completo — score o motivo de imposibilidad headless —
    documentado; `htop` (spec 010) sigue intacta (regresión cero comprobada en
    sesiones).
  - Resultado (2026-10-22, Infinix X6525 / Mali T606, 8 núcleos): activo
    `LP=8 GALLIUM=8 CORES=8`; apagado `LP= GALLIUM= CORES=8`; `htop 3.4.1`
    arrancó y mostró los 8 CPU. `glmark2-es2 --off-screen` no pudo ejecutarse:
    el binario no está instalado y la caché apt no ofrece proveedor; sin score.
    Evidencia y contexto en `wiki/limitaciones.html`.

- [x] **T6. Documentación.** RF-3 (parte de benchmark), criterios de finalización.
  - Hecho cuando: wiki (GPU/limitaciones o sección de rendimiento) y README
    describen la palanca, el valor por defecto y cómo se apaga; `CHANGELOG` y
    `public/` se tocan en el release siguiente, no aquí.

## Notas

- Cada tarea de código: test en rojo → implementación → `./gradlew test` en verde
  → marcar → parar (constitución #4).
- Dependencias: T3 depende de T1+T2; T4 depende de T2; T5 tras T3; T6 tras T5.
- Sin dependencias nuevas (constitución #1); sin Kotlin en módulos librería; UI
  solo en `app/com.termux.terminal.compose`.
- Esta spec podría implementarse antes o después de la 011: el flag
  `gpuAccelerated` es inyectable (`false` mientras la 011 no exista); al integrar,
  sale de `gpu_solution` (T7/T3 de la 011).
