# Plan — Spec 013: VirGL (servidor nativo embebido + vía virpipe en el invitado)

## Archivos y responsabilidades

| Archivo | Responsabilidad | RF/NFR cubiertos |
|---|---|---|
| `termux-shared/.../termux/proot/VirglLibrary.java` (nuevo, **puro**) | Gate de activación/desactivación con exclusión mutua frente a `ADRENO_KGSL` (RF-6); construcción del env del invitado (`applyVirglEnv`); estado del panel por familia con la tercera vía (RF-7); composición del env de arranque del servidor (TMPDIR = `/tmp` del rootfs). Sin Android, sin reloj, sin disco. | RF-3, RF-6, RF-7 |
| `GpuDetectionLibrary.java` | Ampliación del enum `Solution` con `VIRGL` (sin tocar ninguna constante existente). | RF-2, RF-6 |
| `termux-shared/.../shell/command/environment/ProotShellEnvironment.java` | Cableado en `getEnvironment(...)`: si `gpu_solution=VIRGL` y el servidor está vivo → añade el env de la vía VirGL; si no, el mapa sale idéntico a hoy (NFR-3). Reutiliza el patrón `applyGpuEnv` (011) y el gate de hilos de 012. | RF-3, RF-5, NFR-3 |
| `termux-shared/.../TermuxConstants.java` | `VIRGL_ASSET_PATH` (`arm64-v8a/<bin>`), `VIRGL_BIN_PATH`, `VIRGL_SOCKET_NAME` (valor placeholder documentado; se cierra en la tarea de dispositivo) y los nombres de variables env. | RF-1, RF-3 |
| `app/src/main/java/com/termux/app/VirglServerManager.java` (nuevo, glue) | Ciclo de vida del servidor: extraer el binario de assets a `PREFIX/bin` (patrón `TermuxInstaller`), levantar proceso nativo con `ProcessBuilder` + env TMPDIR del rootfs, estado vivo deseado según sessions de proot activas, limpieza del socket rancio antes de re-vincular, parada ordenada. Un solo servidor para todas las sesiones (contado como en el sampler de la 010, ligado a `TermuxService`). | RF-2, RF-4, RF-5, RF-9 |
| `termux-shared/.../settings/preferences/TermuxAppSharedPreferences.java` | El getter/setter existente de `gpu_solution` ya persiste el nombre del enum: añadir `VIRGL` no toca claves (gotcha 008 verificado con test). | RF-2, RF-5 |
| `app/src/main/java/com/termux/terminal/compose/settings/` (`SettingsScreen.kt`, `SettingsViewModel.kt`, `SettingsUiState.kt`) | Panel «Estado GPU»: tercera situación con el switch «Aceleración GPU (VirGL)», gate visual frente a KGSL (RF-6), estado real (vivo/no vivo/fallo) y progreso. Cero literales: strings en `values/`, `values-es-rVE/`, `values-pt-rBR/`. | RF-2, RF-6, RF-7 |
| `scripts/build-virglrenderer.sh` + `app/src/main/assets/arm64-v8a/<bin>` (pipeline, **NDK como proot**) | Compilación multistage del servidor para arm64: fuentes pineadas (tag/commit) y una sola vez por build. Está fuera del catálogo Gradle (NFR-4); se garantiza el binario en el APK en el primer merge de assets (mismo orden que `buildCMake` del proot). | RF-1, NFR-2, NFR-4 |
| `app/src/main/java/com/termux/app/TermuxInstaller.java` | Extraer el binario VirGL de assets junto al proot (una línea más del mismo flujo). | RF-1, RF-9 |
| `wiki/limitaciones.html`, `README.md` | RF-8: receta sobre la pestaña X11 (companion + `LorieView`, env del invitado, `glxinfo` de comprobación), advertencia honesta, alternativa VNC secundaria, limitaciones Zink/OpenCL. | RF-8 |

## Funciones puras (todas con entradas inyectadas)

1. **`resolveSolutionChange(current, requested) -> SolutionGate`** — RF-6.
   - current ∈ {NONE, ADRENO_KGSL, VIRGL}; requested ∈ {VIRGL, NONE, ADRENO_KGSL}.
   - Reglas: NONE→VIRGL ok; NONE→ADRENO_KGSL ok (vía KGSL 011); VIRGL→ADRENO_KGSL
     requiere paso intermedio por NONE → gate `NEEDS_OFF:claves` (motivo i18n);
     ADRENO_KGSL→VIRGL igual; cualquiera→NONE ok. El gate es puro y la UI lo
     explica (RF-6); nada de escribir la pref fuera del resultado puro.
2. **`applyVirglEnv(map, virglActive) -> Map`** — RF-3, RF-5.
   - activo (servidor vivo) → añade `GALLIUM_DRIVER=virpipe`, `MESA_GL_VERSION_OVERRIDE=<valor del plan>`
     y `VIRGL_TEST_SERVER_URI=unix:<rootfs>/tmp/<socket>` (path exacto placeholder
     hasta validar en dispositivo; el builder ignora `null` y no marca nada).
   - inactivo o servidor muerto → mapa intacto byte a byte (NFR-3).
3. **`buildServerEnv(rootfsTmpPath) -> Map<String,String>`** — RF-2/RF-4.
   - El servidor nace con `TMPDIR=<rootfs>/tmp` (mismo directorio que usa el
     companion X de la 014), de modo que su socket vtest quede visible en el
     guest como `<rootfs>/tmp/...` sin binds adicionales. Input null/blank →
     `Error` en notación del repo (null éxito + objeto `Error` en el glue).
4. **`virglPanelState(family, rootfsInstalled, solution, serverAlive) -> PanelState`** — RF-7.
   - Salida: clave i18n del estado + visibilidad del switch y de los avisos de KGSL.
     Con `ADRENO_KGSL` activo el switch VirGL sale deshabilitado con motivo (RF-6).
5. **`buildServerArgs(permanent) -> List<String>`** — RF-2/RF-4: argumentos
   del servidor (`--no-fork`) para que el proceso vivo dependa de la app y
   no haga fork por su cuenta.

## Algoritmo (pseudocódigo) — con puntos de error `[E?]`

### Activar (RF-2)
```
activarVirgl:
    gate = resolveSolutionChange(current, VIRGL)          # puro; NEEDS_OFF si KGSL activo
    si gate != OK: motivo en panel; abort (sin tocar pref) # RF-6
    binario = extraerAsset(VIRGL_ASSET_PATH→VIRGL_BIN_PATH) # [E1] entrada/salida IO → Error+Logger
    limpiarSocketRancio(rootfsTmpPath)                     # [E2] IO suave: si falla, Error+continuar (RF-9)
    proceso = ProcessBuilder(binario --no-fork).environment().putAll(buildServerEnv(rootfsTmp))
    lanzar; esperar READY (socket existe o stdout del servidor) con timeout corto
    si vive: prefs.setGpuSolution(VIRGL); panel ok
    si no: Error+toast; pref NO cambia                       # [E3] nunca silencio
```

### Desactivar (RF-5)
```
    matar proceso (si vivo); limpiar socket; prefs.setGpuSolution(NONE) (siempre éxito en desactivar)
    sesiones nuevas: env sin la vía (independiente de constantes)
```

### Ciclo de vida por sesión (RF-4) — `TermuxService` repite el patrón del sampler 010
```
    al crear sesión proot: manager.ensureRunning(prefs == VIRGL, rootfsTmp)   # revive silenciosa por sesión
    al terminar sesión: si 0 sesiones proot activas → manager.stopRequested()    # NFR-6
                 (el stop respeta: si el usuario reabre, se revive en ensureRunning)
```

### Env del invitado (RF-3) — en `getEnvironment`
```
    gpuActive   = pref==ADRENO_KGSL                                    # (011, intacto)
    virglActive = pref==VIRGL y servidor vivo (el glue lo lleva, decisión 3)
    applyVirglEnv(map, virglActive)                                    # puro
    el orden KGSL/virgl no se pisa: pref exclusiva (gate RF-6)         # garantía estructural
```

## Interfaz del servidor y decisiones del pipeline nativo

- **Binarios pineados:** `virglrenderer` upstream (tag final se marca en el
  repos y en el script el día de implementar T5 — nada sin pin), con sus
  dependencias menores compilando estáticas en el mismo multistage. La UI no
  consume ninguna de esas librerías (NFR-4).
- **Binario en assets** `arm64-v8a/…` y extracción por `TermuxInstaller` como el
  proot (consistencia del fork y con su flujo ya probado en todos los
  dispositivos). El .so alternativo vía `nativeLibraryDir` (vía lorie/014) se
  descarta: el binario proot ya usa assets y evita conflictos de nombres de
  libs y el wrapper `packagingOptions`.
- **Socket en el TMPDIR del rootfs** (igual que el companion X de la 014): cero
  binds nuevos; el guest lo ve como `/tmp/<socket>`.
- **Variable de conexión del cliente:** placeholder documentado;
  `VIRGL_TEST_SERVER_URI` es la vía moderna de mesa (≥ 20.2); el path va tras
  probar en dispositivo (T6). Orden de prueba: primero la URI documentada; si
  la mesa del rootfs la ignora, fallback al path vtest clásico (el resultado
  se registra y se fija la constante).

## Decisiones y alternativas descartadas

1. **Compilar `virglrenderer` en el fork vs paquete TUR (`virglrenderer-android`).**
   El paquete externo depende del prefijo y del gestor de apt de Termux main, que
   este fork no usa; también obligaría a una descarga nueva con SHA-256 dependiente
   de un tercero para una pieza crítica (constitución #5 se agrava). El pipeline
   propio es más trabajo pero reproduce y evita cadenas de terceros → elegido
   (decisión del mantenedor, 2026-10-22).
2. **Assets vs `nativeLibraryDir` (libXlorie del 014).** Consistencia con proot
   (assets ya probados) y menor colisión con el paquete de lorie; el coste es
   extraer el binario en un primer arranque (ya existe ese flujo).
3. **`ProcessBuilder` nativo del app vs lanzar el servidor dentro de una sesión.**
   La sesión es del guest Debian; el servidor es pieza nativa del host y debe
   sobrevivir al shell de sesiones: proceso con el uid del app, sal probada en
   el patrón del sampler 010 (lifecycle ligado a sesiones, no shell).
4. **`MESA_GL_VERSION_OVERRIDE` mínimo y documentado** (el valor exacto se
   convierte en constante tras la prueba del T6; los excesos de 4.5COMPAT se
   evitan por defecto: el env fija lo mínimo que convierte en GPU no «vuelta»).

## Estrategia de tests (`./gradlew test`, JUnit plano)

| Test | RF/NFR |
|---|---|
| `resolveSolutionChange`: NONE→VIRGL ok; KGSL→VIRGL NEEDS_OFF; VIRGL→KGSL NEEDS_OFF; VIRGL/NONE ok; KGSL→NONE ok | RF-6 |
| `Solution` con `VIRGL` persiste y lee sin alterar claves existentes (pref round-trip) | RF-2, gotcha 008 |
| `applyVirglEnv`: activo+vivo → tres variables presentes; inactive/muerto → mapa idéntico byte a byte al vigente (extiende `ProotShellEnvironmentTest`) | RF-3, RF-5, NFR-3 |
| `buildServerEnv`: TMPDIR del rootfs presente; blank → error puro | RF-2/RF-4 |
| `virglPanelState`: cada familia y por pref: KGSL deshabilitando el switch VirGL con motivo; VirGL activo reporta el estado real (vivo/no vivo/fallo) | RF-7 |
| gate KGSL 011 intacto: el gate `evaluateAction` no cambia cuando VirGL existe (prueba contra la matriz ya escrita en `GpuDetectionLibraryTest`) | NFR-3 |
| APK: tras `assembleDebug` (tarea manual/dispositivo, no CI) inspección `unzip` del asset embebido (RF-1) — igual que en 011/014 | RF-1 |

Validación en dispositivo (T6): mantenedor con **Mali T606**: activa VirGL (servidor
vivo + socket en el rootfs /tmp), abre la pestaña X11 (014), corre una app
del invitado con el env virpipe y revisa el renderer con `glxinfo` (no llvmpipe,
proveedor Mali) y el benchmark de partida solo como comprobación. Después,
desactivar → byte a byte como antes; overlay 010 y tab X11 sin regresión.

## Dudas del plan (resueltas sobre la marcha o quedan pendientes de T5/T6)

- [NECESITA ACLARACIÓN] `virglrenderer` tag/commit y valor real de
  `VIRGL_TEST_SERVER_URI`/path del socket: se fijan en T5 (constantes) y se
  verifican en T6 (dispositivo). Los placeholders están documentados y los
  tests los validan por forma; sin pin no compila el pipeline (const. #5).
- El plan cubre también el reversible: si al implementar la mesa `virpipe` del
  rootfs pide otro env (p. ej. más overrides de versión), se corrige aquí con
  nota y test, nunca en silencio.
