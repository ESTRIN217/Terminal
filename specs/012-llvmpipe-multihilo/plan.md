# Plan — Spec 012: llvmpipe multihilo (`LP_NUM_THREADS`)

Depende de la spec 011 solo en un punto: el flag «¿está acelerado?» llega
inyectado como booleano (`gpuAccelerated`), de modo que 012 es implementable e
independiente (con `gpuAccelerated=false` constante en tests), y al integrarse
con 011 el booleano sale de `gpu_solution` + gate.

## Archivos y responsabilidades

| Archivo | Responsabilidad | RF cubiertos |
|---|---|---|
| `termux-shared/.../termux/proot/LlvmpipeThreadsLibrary.java` (nuevo, **puro**) | Cálculo del valor de hilos y de las entradas de env: `computeThreads(coresRaw, N)` = `min(clamp(coresRaw,1,32), clamp(N,1))`; `llvmpipeEnvEntries(threads)` = mapa `{LP_NUM_THREADS: t, GALLIUM_NUM_THREADS: t}` o vacío (t = 0/«unset»). Sin Android. | RF-1 |
| `termux-shared/.../shell/command/environment/ProotShellEnvironment.java` | Cableado en `getEnvironment(...)`: lee pref `enabled`/`N` (glue, prefs), obtiene `availableProcessors()`, decide con la librería pura y añade las entradas. **Nada cambia si off o si acelerado** (RF-2, RF-4). | RF-1, RF-2, RF-4 |
| `termux-shared/.../termux/TermuxConstants.java` | Claves de las dos preferencias nuevas (nombres de clave como constantes, aquí, no dispersas). | RF-1, RF-2 |
| `termux-shared/.../settings/preferences/TermuxAppSharedPreferences.java` | `isLlvmpipeThreadsEnabled()` (default **on**, marcado explícito) y `setLlvmpipeThreadsEnabled(...)`; `getLlvmpipeThreadsCount()` (default 8) / `setLlvmpipeThreadsCount(...)`. **Gotcha spec 008:** solo añadir claves, reescribir ninguna. | RF-1, RF-2, NFR-3 |
| `app/src/main/java/com/termux/terminal/compose/settings/` (`SettingsScreen.kt`, `SettingsViewModel.kt`, `SettingsUiState.kt`) | Ajustes: Switch «Hilos llvmpipe» + Slider N (1..16, step 1) en la sección que corresponda (la de terminal/rendimiento); strings ×3 locales; nota inline («aplica a las próximas sesiones») como string. | RF-1, RF-2 |
| `app/src/main/java/com/termux/app/TermuxService.java` | Nada de lógica propia: el entorno se compone en `ProotShellEnvironment`; si `applyGpuEnv` (spec 011) ya entrega el flag acelerado aquí, se pasa como booleano al cálculo (integración). | RF-4 |
| Tests puros (`termux-shared/src/test/java/...`) + ampliación `ProotShellEnvironmentTest` | Tabla abajo; JUnit plano. | todos |

## Funciones puras (con datos igual que en 010: lo variable entra como parámetro; el tiempo no participa)

1. **`computeThreads(int coresRaw, int prefN) -> int`** — RF-1.
   - `cores = clamp(coresRaw, 1, 32)` (ROMs que mienten al alza), `N =
     clamp(prefN, 1, 16)`; resultado `min(cores, N)`. Con `prefN` en `1`,
     resultado `1` (fijado a propósito: el punto de comparación del benchmark).
2. **`llvmpipeEnvEntries(int threads) -> Map<String,String>`** — RF-1.
   - `threads < 1` → mapa vacío (no inyectar valores absurdos);
     `threads >= 1` → `LP_NUM_THREADS` + `GALLIUM_NUM_THREADS` con el mismo
     valor como String.
3. **`shouldInject(boolean enabled, boolean gpuAccelerated) -> boolean`** — RF-1, RF-2, RF-4.
   - `enabled && !gpuAccelerated`. Con `gpuAccelerated=true` (Adreno activo)
   el resto de cálculo no se lee (cortocircuito).
4. En el glue (`ProotShellEnvironment.getEnvironment`): `threads =
   computeThreads(Runtime.getRuntime().availableProcessors(),
   prefs.getCount())` → `llvmpipeEnvEntries(threads)` si `shouldInject(...)`,
   `putAll` al mapa del invitado.

## Algoritmo (pseudocódigo)

### Entorno del invitado (RF-1/RF-2/RF-4)
```
getEnvironment(ctx, isFailSafe):
    ... mapa base ya existente ...
    enabled  = prefs.isLlvmpipeThreadsEnabled()          # glue
    accelerated = prefs.getGpuSolution() == ADRENO_KGSL  # glue (spec 011; false si no existe aún)
    si !shouldInject(enabled, accelerated):              # puro; RF-2/RF-4 aquí
        fin (mapa tal qual, byte a byte idéntico a hoy)
    threads = LlvmpipeThreadsLibrary.computeThreads(availableProcessors(), prefs.getCount())
    mapa.putAll(LlvmpipeThreadsLibrary.llvmpipeEnvEntries(threads))  # RF-1
```

### Settings (RF-1/RF-2)
```
Switch off  → prefs.setEnabled(false)      # nada más: el próximo getEnvironment no inyecta
Slider N    → prefs.setCount(N)            # rango 1..16, step 1; default 8 ⇒ min(cores, 8)
```

## Decisiones y alternativas descartadas

1. **Env global del invitado vs en cada comando gráfico.** Global: una línea,
   sin bisagra de «cuándo es un proceso gráfico» (un `htop` no es gráfico y da
   igual que herede la var). Descartadas: (a) envolver con
   `LP_NUM_THREADS=n cmd` en cada invocación gráfica — requiere clasificar
   comandos, frágil; (b) wrapper del binario por fichero — invasivo y
   desinstalable a medias.
2. **Fijar las 2 variables (`LP` + `GALLIUM`) vs solo `LP_NUM_THREADS`.** Las
   dos: `GALLIUM_NUM_THREADS` rige para la ruta gallium moderna y
   `LP_NUM_THREADS` para la clásica de llvmpipe; según el binario y su compilado
   de mesa una u otra se respeta, así que fijar solo una es frágil.
3. **Default on vs off.** On (N=8): la comparación con «hoy» es el propio
   benchmark de RF-3 y el off queda como el seguro de regresión. Con off por
   defecto la spec quedaría inerte por defecto, lo que no justifica el trabajo.
4. **Booleano `gpuAccelerated` inyectado, no re-detectado.** El flag se
   reutiliza de la spec 011 (pref + gate); mientras 011 no esté integrada, es
   `false` (hoy todos los dispositivos son llvmpipe: valor truthful).
   Descartado re-sensar el sysfs en cada `getEnvironment`: I/O por sesión sin
   dato nuevo.

## Estrategia de tests (`./gradlew test`)

| Test | RF/NFR |
|---|---|
| `computeThreads`: (8,8)=8; (4,8)=4; (16,8)=8; (1,16)=1; clamp bajo (0 → 1 como suelo); coresRaw=99 → clamp 32 antes de min; prefN fuera de rango (0, 99) → clamp 1..16 antes de min | RF-1 |
| `llvmpipeEnvEntries`: t≥1 → ambas vars con el mismo valor; t<1 → mapa vacío | RF-1 |
| `shouldInject`: off → false; on+no gpu → true; on+gpu → false | RF-1, RF-2, RF-4 |
| `ProotShellEnvironmentTest` (ampliación): on+llvmpipe → mapa con las 2 vars (`8`); off → mapa byte a byte idéntico al vigente; pref N=4 → `4` | RF-1, RF-2 |
| Prefs: defaults on/N=8; set/get round-trip; el resto de claves del bloque persistido intactas (test de no-regresión de serializador, gotcha 008) | RF-2, NFR-3 |

Validación en dispositivo (RF-3, tarea T4): mantenedor con Mali; on →
`echo $LP_NUM_THREADS` = `min(núcleos, N)`; off → vacío; benchmark con el
comando elegido (duda abierta 1) documentado en la wiki o el motivo de no
poder.

## Dudas abiertas del plan

- [NECESITA ACLARACIÓN] Herramienta de benchmark headless exacta (idem spec,
  duda 1). La validación del env (`echo`) es incondicional y suficiente para
  cerrar RF-3 en su parte garantizada; el try de `glmark2-es2 --off-screen`
  se documenta con su resultado aunque sea negativo.
- Si la integración con `applyGpuEnv` (011) ordena de otra forma la lectura de
  la pref (dos lecturas de prefs por getEnvironment es aceptable; no
  micro-optimizar sin dato).
