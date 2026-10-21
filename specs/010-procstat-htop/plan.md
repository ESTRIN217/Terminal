# Plan — Spec 010: htop con estadísticas de CPU reales

## Archivos y responsabilidades

| Archivo | Responsabilidad |
|---|---|
| `termux-shared/.../termux/proot/ProcStatOverlayLibrary.java` (nuevo) | Lógica **pura**: decisión directa/sintética por dato, construcción del texto de cada snapshot (`/proc/stat`, `/proc/meminfo`, `/proc/loadavg`), cálculo de carga propia desde deltas y lista de binds del overlay. Todo determinista: el tiempo entra como parámetro (`nowMs`, `elapsedMs`). |
| `termux-shared/.../termux/proot/ProcStatOverlaySampler.java` (nuevo) | Pegamento Android: lee `/proc/<pid>/stat` del UID propio, usa `SystemClock`/`ActivityManager`, sondea legibilidad de `/proc/stat`, `/proc/meminfo` y `/proc/loadavg`, llama a la librería pura y escribe los snapshots de forma atómica. Lo ejecuta `TermuxService`. |
| `termux-shared/.../environment/ProotShellEnvironment.java` | `buildProotCommand()` gana un overload con `overlayBinds` (pares `host guest`) añadidos tras `-b /proc`. Sin overlay → comando idéntico al de hoy (NFR-6). |
| `termux-shared/.../utils/TermuxConstants.java` | Constantes del directorio overlay: `DEBIAN_PROC_OVERLAY_DIR_PATH` (`files/proc-stat-overlay/`) y ficheros `_STAT/_MEMINFO/_LOADAVG`. |
| `app/.../termux/app/TermuxService.java` | Dueño del ciclo de vida del muestreo: contador de sesiones proot activas → inicia/para el muestreador (1 s). En `createTermuxSession`: sondea vías, escribe snapshot inicial y pasa los binds. En la retirada de sesión: decrementa y para al llegar a 0 (buscando los puntos de retirada existentes, línea ~688 y bucle de cierre masivo ~276). |
| `termux-shared/src/test/java/...` (tests nuevos) | `ProcStatOverlayLibraryTest` y ampliación de `ProotShellEnvironmentTest`. JUnit plano, sin Robolectric (patrón del repo). |
| Doc RF-8 | `README.md` (§ Limitaciones), `docs/proot-debian-arm64-plan.md` (§ 7), `wiki/limitaciones.html`; `CHANGELOG` se toca en el release. |

## Funciones puras (todas con el tiempo explícito como parámetro)

1. **`advanceCpu(prev, cur, elapsedMs, cores, clockTicks)`**
   - entrada: `{pid → [utime, stime]}` de la muestra anterior y la actual de
     `/proc/<pid>/stat` (pids del UID), ms transcurridos desde la muestra previa,
     nº de núcleos, `CLK_TCK`.
   - salida: `busyJiffies` (suma de deltas utime+stime) + mapa para el siguiente
     ciclo. Pid nuevo → contribuye 0 este ciclo (evita el salto inicial); pid
     muerto → se descarta del mapa.
   - `idleJiffies = max(0, elapsedMs·clockTicks/1000·cores − busyJiffies)`: lo que
     no es nuestra carga se reporta como idle (el resto del sistema no es
     observable sin root; decisión de RF-4, documentada en RF-8).
2. **`renderStat(cores, bootSec, busy, idle, running, blocked, processesTotal)`** →
   texto exacto del snapshot:
   - `cpu user nice system idle iowait irq softirq steal guest guest_nice` con
     `user=busy`, `system=0`, `idle=idle`, resto `0`.
   - `cpuN` = línea `cpu` dividida **uniformemente** entre núcleos (RF-11), y los
     `busy%cores`/`idle%cores` sobrantes añadidos a los primeros núcleos, para que
     la suma de `cpuN` cuadre exacta con `cpu`.
   - `btime <bootSec>`, `processes <processesTotal>`, `procs_running <r>`,
     `procs_blocked <b>`. Sin líneas `intr`/`ctxt`/`softirq`: el parser de htop
     las trata como opcionales (verificado contra `LinuxMachine.c` de htop 3.x).
3. **`renderMeminfo(totalKb, availableKb)`** → `MemTotal`, `MemFree=MemAvailable`,
   `MemAvailable`, `Buffers 0`, `Cached 0`, `SwapTotal 0`, `SwapFree 0`,
   `Shmem 0`, `SReclaimable 0` (en kB). Solo para `/proc/meminfo` bloqueado.
4. **`renderLoadavg(l1, l5, l15, running, total)`** → `x.xx y.yy z.zz r/t` para el
   snapshot de `/proc/loadavg` bloqueado.
5. **`decideVias(statRead, memRead, loadRead, filesDirPath)`** (RF-3): por cada
   dato: vía directa si es legible; si no, snapshot sintético + bind
   `host:guest` necesario. Devuelve la lista de binds y los ficheros a escribir.
   Nada legible-bloqueado → lista vacía (NFR-5).
6. **`btimeFromBoot(nowMs, uptimeMs)`**: `bootSec = (nowMs − uptimeMs) / 1000`
   redondeado (error ≤ ±1 s; NFR-2 pide ±2 s).

## Algoritmo (pseudocódigo)

### Ciclo de vida (en `TermuxService`)
```
createTermuxSession (ramo proot, tras el repair ya existente):
    statRead  = puedeAbrir("/proc/stat")
    memRead   = puedeAbrir("/proc/meminfo")
    loadRead  = puedeAbrir("/proc/loadavg")
    plan      = ProcStatOverlayLibrary.decideVias(...)        # puro
    si plan.traeBinds:
        asegurarDirectorioOverlay(0700)
        escribir snapshot inicial de cada fichero sintético (atómico)
        contadorProotActivo++
        si !muestreadorActivo: iniciarMuestreador(1000 ms)   # ScheduledExecutor, hilo daemon MIN_PRIORITY
    buildProotCommand(guestCwd, args, withLinkfix, plan.binds)

retiradaDeSesion proot (cada punto que elimina de mTermuxSessions):
    contadorProotActivo--
    si contador == 0: pararMuestreador()                      # RF-6

tick del muestreador (1 s):
    pids = listar /proc numerado; leer /proc/<pid>/stat (EACCES → saltar)
    busy, mapaSiguiente = advanceCpu(prev, cur, elapsedMs, cores, CLK_TCK)
    bootSec = btimeFromBoot(currentTimeMillis, elapsedRealtime)
    si !memRead: totalKb/availableKb = ActivityManager.MemoryInfo
    r = loadRead ? parseLoadavgRunning() : contarEstados(R) de pids propios
    b = loadRead ? parseLoadavgBlocked() : contarEstados(D) de pids propios
    escribir cada snapshot: fichero temporal + rename (RF-7)
```

### Orden de los binds (punto de verificación)
proot registra `-b /proc` y después `-b <fichero>:/proc/stat`. En proot el bind
más específico (`/proc/stat`, ruta más larga a nivel de componentes) debe ganar
sobre el directorio `/proc`. Se valida con `head -c 32 /proc/stat` dentro del
invitado (T5). Si el orden resultara invertido, se mueven los binds de fichero
a una posición anterior a `-b /proc` (cambio de una línea) y se revalida.

## Interfaz

```java
public final class ProcStatOverlayLibrary {   // pura, sin Android
  public static final class CpuDelta { public long busy; public Map<Integer,long[]> next; }
  public static CpuDelta advanceCpu(Map<Integer,long[]> prev, Map<Integer,long[]> cur,
                                    long elapsedMs, int cores, long clockTicks);
  public static String renderStat(int cores, long bootSec, long busy, long idle,
                                  int running, int blocked, long processesTotal);
  public static String renderMeminfo(long totalKb, long availableKb);
  public static String renderLoadavg(double l1, double l5, double l15, int running, int total);
  public static List<String[]> overlays(boolean statRead, boolean memRead, boolean loadRead,
                                        String overlayDirPath); // pares {host, guest}
  public static long btimeFromBoot(long nowMs, long uptimeMs);
}
```

`ProotShellEnvironment.buildProotCommand(String guestCwd, String[] extraArgs,
boolean withLinkfix, List<String[]> overlayBinds)` — overload nuevo; los
existentes delegan con `null` → sin cambio de comando (NFR-6).

## Decisiones y alternativas descartadas

1. **Overlay en proot (bind de fichero) vs parchear y recompilar htop.**
   Parchear htop obligaría a distribuir un binario arm64 propio y sustituir el
   htop de Debian en cada instalación del rootfs: frágil y rompe `apt`. El bind
   es una capa del comando proot que ya generamos: coste bajo y reversible.
   (Tampoco vale la muestra fija de instalación de Termux: dato no vivo.)
2. **Carga propia como `busy` vs derivarla de `loadavg` del sistema.**
   `loadavg` solo da promedios de nº de procesos en runqueue (no user/system,
   no idle), y nuestro htop solo lista procesos del UID propio: la carga propia
   es el único dato exacto y coherente con lo que htop muestra. `loadavg` solo
   alimenta `procs_running/blocked` reales (RF-4).
3. **Escritura atómica con rename por tick vs escribir el bind en sitio.**
   El rename imprime atomicidad (RF-7). htop reabre el fichero en cada tick (no
   guarda fd), y proot resuelve el bind Open por ruta, así que el rename es
   seguro. La alternativa de truncar+y reassaltar en sitio puede dejar el
   fichero a medias al proceso lector.
4. **Snapshot de `/sys/.../scaling_cur_freq`** fuera de alcance: htop no lo
   necesita y el parser varía por dispositivo; no aporta criterio de cierre.

## Estrategia de tests (`./gradlew test`, JUnit plano)

| Test | RF/NFR |
|---|---|
| `renderStat`: 1/4/8 núcleos; reparto uniforme; suma `cpuN == cpu`; `btime` presente; `procs_running/blocked` | RF-2, RF-11, NFR-2 |
| `renderMeminfo`: formato kB + campos que usa htop | RF-4 |
| `renderLoadavg`: 2 decimales + `r/t` | RF-4 |
| `advanceCpu`: pid nuevo → 0; pid muerto → descartado; idle negativo → clamp 0; `clockTicks=100` | RF-4, NFR-4 |
| `btimeFromBoot`: redondeo correcto | NFR-2 |
| `overlays`: todo legible → lista vacía; parcial → solo los necesarios; par `host guest` correcto | RF-3, NFR-5 |
| `ProotShellEnvironmentTest` (ampliación): con overlay → binds añadidos tras `-b /proc`; sin overlay → comando idéntico al vigente | RF-1, NFR-6 |

Validación en dispositivo (RF-5/RF-9, T6 en `tasks.md`, mano del mantenedor):
stock `enforcing` → htop vivo; bucle de carga → barra sube; Ctrl-C → baja;
así como gates del orden de binds (`head -c 32 /proc/stat`) y vía directa.
