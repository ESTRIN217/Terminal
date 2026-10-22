# Plan — Spec 011: GPU, detección honesta y aceleración Adreno (KGSL)

## Archivos y responsabilidades

| Archivo | Responsabilidad | RF cubiertos |
|---|---|---|
| `termux-shared/.../termux/proot/GpuDetectionLibrary.java` (nuevo, **puro**) | Clasificación de familia GPU desde datos inyectados; gate de acción «instalar/permitido+motivo»; construcción del comando apt de install/uninstall; parse de la salida de `dpkg-query -W`; builders de URL con `%2B`. Sin Android, sin reloj (no hay tiempo en esta spec). | RF-1, RF-3, RF-5, RF-6 |
| `termux-shared/.../termux/proot/GpuDeviceReader.java` (nuevo, glue Android) | Lee las fuentes reales: `/sys/class/kgsl/kgsl-3d0/gpu_model`, existencia de `/dev/kgsl-3d0`, sysprops `ro.hardware.egl`/`ro.board.platform` (vía `SystemProperties.get` reflection, como el resto del repo), `Build.SOC_MODEL` solo si `Build.VERSION.SDK_INT >= 31`. Devuelve el DTO inyectado a la librería pura; errores a `Error`+`Logger`, nunca throw ni silencio. | RF-1, RF-6 |
| `termux-shared/.../shell/command/environment/ProotShellEnvironment.java` | Método **puro** nuevo `applyGpuEnv(map, gpuSolutionActive)` + cableado en `getEnvironment(...)`: si `gpu_solution=ADRENO_KGSL` añade `MESA_LOADER_DRIVER_OVERRIDE=kgsl`; si no, el mapa sale idéntico a hoy (NFR-3). | RF-4, NFR-3 |
| `termux-shared/.../termux/TermuxConstants.java` (~1350 líneas, constantes aquí, no dispersas) | `DEBIAN_GPU_DEBS_DIR_PATH` (`files/debian/gpu-debs`), las 6 constantes `_MESA_DEB_URL` + `_MESA_DEB_SHA256` + `_MESA_DEB_PACKAGE` (una tripleta por paquete), `DEBIAN_MESA_EXPECTED_VERSION = "25.0.7-2+deb13u1"` y las claves de preferencia. SHAs como placeholder documentado hasta la implementación (duda abierta 1). | RF-3, NFR-2 |
| `termux-shared/.../settings/preferences/TermuxAppSharedPreferences.java` | Nueva pref `gpuSolution` (`NONE`/`ADRENO_KGSL`) con getter/setter. **Gotcha spec 008:** al tocar el bloque persistido, conservar TODAS las claves existentes; solo se añaden, no se reescribe el serializador. | RF-3, RF-5 |
| `app/src/main/java/com/termux/app/GpuAccelerationInstaller.java` (nuevo, Java, glue) | Análogo de `DebianInstaller`: descarga streaming de los 6 `.deb` con SHA-256 al vuelo (`HttpURLConnection`, `MessageDigest`), descarte del fichero si el hash no cuadra (RF-7) y ejecución de los comandos apt **dentro del invitado** mediante el runner de sesiones proot de `TermuxService` (una ejecución one-shot de fondo; el resultado — código de salida — decide si se persiste la pref). | RF-3, RF-5, RF-7 |
| `app/src/main/java/com/termux/terminal/compose/settings/` (`SettingsScreen.kt` + `SettingsViewModel.kt` + `SettingsUiState.kt`) | Panel «Estado GPU»: línea de estado por familia (RF-2), acción «Instalar aceleración GPU» / «Desinstalar aceleración GPU» solo cuando el gate lo permite (RF-3/RF-5/RF-6), estado de progreso desde el ViewModel (colector con el resultado del ejecutor). Nada de literales: strings en `values/`, `values-es-rVE`, `values-pt-rBR` (constitución #6). | RF-2, RF-3, RF-5, RF-6 |
| `app/src/main/java/com/termux/app/TermuxService.java` | Punto de arranque del uso de la librería: al crear sesión proot, leer `gpu_solution` (glue, prefs) y pasarla a `getEnvironment`/`applyGpuEnv`. No gestiona ciclo de vida de muestreo (no hay muestreo aquí; el spawn one-shot lo protagoniza `GpuAccelerationInstaller`). | RF-4 |
| `termux-shared/src/test/java/...` y `app/src/test/java/...` (tests nuevos) | `GpuDetectionLibraryTest` (JUnit plano), ampliación de `ProotShellEnvironmentTest`, `GpuPureCommandsTest` (comandos/parse/URL), `GpuAccelerationInstallerTest` (state machine de descarga con Robolectric si hace falta; la lógica del gate es pura y va en JUnit). | todos |

## Funciones puras (todas con entradas inyectadas; aquí el tiempo no participa)

1. **`detectFamily(GpuInputs in) -> GpuFamily`** — RF-1.
   - entrada: `gpuModel` (String|null de `/sys/class/kgsl/kgsl-3d0/gpu_model`),
     `kgslDevNode` (bool de `/dev/kgsl-3d0`), `hwEgl` (String|null),
     `boardPlatform` (String|null), `socModel` (String|null, ya filtrado por
     API en el glue).
   - lógica: prioridad (a) `gpuModel` o `kgslDevNode` → `ADRENO_CON_KGSL`
     (devuelve también el modelo bruto saneado — trim, sin saltos);
     (b-c-d) sin KGSL, si cualquier identificador menciona
     `adreno|qcom|sc[0-9]` → `ADRENO_OTRO`; `mali|mediatek|mt[0-9]` → `MALI`;
     `powervr|imagination` → `POWERVR`; si no → `DESCONOCIDA` con el motivo
     («sin datos», «sin KGSL e id no reconocido»).
   - por qué orden de prioridad: el sysfs/nodo es la **verdad del kernel**; los
     sysprops son la apariencia de los OEMs y `SOC_MODEL` solo existe en
     API≥31. Sin KGSL nada se «adivina» Adreno con KGSL.
2. **`evaluateAction(family, rootfsInstalled, guestMesaVersion, expectedMesaVersion, currentSolution) -> ActionGate`** — RF-3, RF-5, RF-6.
   - salida: enum {`INSTALL_OFFERED`, `UNINSTALL_OFFERED`, `INFO_ONLY`} +
     `motivo` (String clave i18n). Reglas: `rootfsInstalled=false` →
     INFO_ONLY(«rootfs no instalado»); `mesa diverge` → INFO_ONLY(«versión»);
     `family != ADRENO_CON_KGSL` → INFO_ONLY(«familia»); `solution==NONE` y el
     resto ok → INSTALL_OFFERED; `solution==ADRENO_KGSL` y resto ok →
     UNINSTALL_OFFERED (panel marca «aceleración activa»).
   - el gate manda sobre la pref: si la pref dice `ADRENO_KGSL` pero la mesa
     diverge, la acción de desinstalación sigue ofreciéndose (para poder
     revertir) y el panel nota la divergencia.
3. **`buildInstallCommand(List<String> debGuestPaths, List<String> packages) -> String`** — RF-3.
   - `export DEBIAN_FRONTEND=noninteractive; apt-get update && apt install --reinstall -y ./deb1 ... && apt-mark hold pkg1 ...`
   - Salida: String único fácil de testear: prefijo no interactivo + reinstalar
     por fichero (`./` obligatorio para apt) + holds explícitos de los 6.
4. **`buildUninstallCommand(List<String> packages) -> String`** — RF-5.
   - `apt-mark unhold pkg1 ...; apt install --reinstall -y pkg1 ...` (mirror
     Debian: sin fichero local).
5. **`parseDpkgVersions(String dpkgQueryOutput) -> Map<String,String>`** — RF-3, RF-6.
   - parse de `dpkg-query -W -f='${Package}\t${Version}\n' mesa-libgallium
     libgl1-mesa-dri libgbm1 libglx-mesa0 libegl-mesa0 mesa-vulkan-drivers`
     (salida de formato `paq\tvers`); tolerante a paquetes ausentes (valor
     `null`, el gate lo leerá como «diverge»).
6. **`debUrlFor(String baseReleaseUrl, String packageName) -> String`** — RF-3, NFR-2.
   - `baseReleaseUrl + packageName` con el nombre **codificado**: el `+` de
     `25.0.7-2+deb13u1` es `%2B` (verificado en la release real).
7. **`applyGpuEnv(Map<String,String> map, boolean gpuSolutionActive) -> Map<String,String>`** (en `ProotShellEnvironment`) — RF-4, NFR-3.
   - activo → `MESA_LOADER_DRIVER_OVERRIDE=kgsl`; inactivo → mapa intacto.
8. **`isWellFormedSha256(String s) -> boolean`** — NFR-2: 64 hex; test de forma
   para las 6 constantes (los valores reales llegan el día de implementación).

## Algoritmo (pseudocódigo) — con puntos de error marcados `[E?]`

### Instalar (RF-3) — en `GpuAccelerationInstaller` (glue)
```
instalarAceleracion():
    in      = GpuDeviceReader.read()                          # [E1] sysfs/ioctls abiertos; cualquier fallo → familia DESCONOCIDA+motivo
    familia = GpuDetectionLibrary.detectFamily(in)            # puro
    rootfs  = DebianInstaller.isRootfsInstalled()             # ya existe en el repo
    version = ejecutarEnGuest one-shot dpkg-query             # [E2] Sesión proot falla/timeout → Error+toast, abort
              → parseDpkgVersions (puro)
    gate    = evaluateAction(familia, rootfs, version.expected, ...)   # puro
    si gate != INSTALL_OFFERED: mostrar motivo; fin            # RF-6, jamás intentarlo fuera del gate

    para cada deb en DEBIAN_GPU_DEBS (6):
        url  = debUrlFor(base, nombre)                        # puro, %2B
        stream = HttpURLConnection(url)  ↓ descarga a files/debian/gpu-debs/<nombre>
        sha   = MessageDigest en vuelo                        #          [E3] red interrumpida → borrar fichero parcial, Error+toast, abort
        si sha != TermuxConstants.<deb>_SHA256:               #          [E4] constitución #5: mismatch → borrar, abort SIN apt
            borrarFichero(); Error("SHA-256 mismatch"); toast; fin
    # aquí y solo aquí se toca el guest:
    exit = ejecutarEnGuest(buildInstallCommand(...))          # [E5] apt lock ocupado/dependencias → exit != 0
    si exit == 0: prefs.setGpuSolution(ADRENO_KGSL); toast ok # RF-3: la pref SOLO cambia con éxito
    si no:        Error+Log+toast (la pref queda NONE)        # nunca silencio
```

### Desinstalar (RF-5)
```
desinstalarAceleracion():
    gate = evaluateAction(..., currentSolution=ADRENO_KGSL)   # puro; si diverge la mesa igual se permite (ver función 2)
    si gate != UNINSTALL_OFFERED: fin
    exit = ejecutarEnGuest(buildUninstallCommand(packages))
    si exit == 0: prefs.setGpuSolution(NONE)
    si no: Error+toast; pref queda ADRENO_KGSL                # [E6]
```

### Entorno del invitado (RF-4) — en `ProotShellEnvironment.getEnvironment`
```
... mapa base ya existente (TZ, PATH, ...) ...
gpuSolutionActivo = TermuxAppSharedPreferences.getGpuSolution(ctx) == ADRENO_KGSL   # glue
applyGpuEnv(environmentMap, gpuSolutionActivo)              # puro; inactivo → mapa idéntico a hoy
```

### Panel (RF-2/RF-6) — en `SettingsViewModel`/`GpuAccelerationInstaller`
```
onResume panel:
    familia = detectFamily(reader.read())        # glue lee una sola vez por entrada al panel
    version = dpkg-query si rootfs instalado (ligero, one-shot)
    estado  = evaluateAction(...)                # puro → texto por strings.xml, botón visible o no
```

## Interfaz del runner de comandos en el invitado

`GpuAccelerationInstaller` reutiliza el **mismo camino probado de las
sesiones proot** de `TermuxService` (ExecutionCommand de fondo, salida
capturada a log, callback de fin): alternativa descartada de abrir el binario
proot a pelo con `ProcessBuilder` — duplicaría la infraestructura de sesión,
rompería con el `termux-exec` y perdería las variables/binds ya resueltos por
`buildProotCommand`. Análogo a cómo `ProcStatOverlaySampler` (spec 010) se
apoya en el ciclo de vida de `TermuxService` en lugar de reinventarlo. Si al
implementar el callback de fin no distingue exit code con claridad, se
persiste un marcador en el propio log de salida y se parsea con una función
pura (`parseExitMarker(output)`, mismo patrón que el resto de parses).

## Decisiones y alternativas descartadas

1. **Pinnear los 6 `.deb` vs compilar mesa propia.** Compilar/empaquetar mesa
   con KGSL implicaría mantener una toolchain de mesa en el repo y publicar
   artefactos; el proyecto `lfdevs/mesa-for-android-container` ya publica
   exactamente la versión de distro para Debian 13, misma que nuestros
   `.deb`/mirror, verificado binariamente por nosotros hoy. Desinstalación
   limpia garantizada con `apt install --reinstall`: los mismos paquetes,
   misma versión, descargados otra vez desde el mirror Debian se restituyen
   byte a byte. (Descartado también: parchear paquetes fuera de `apt`.)
2. **`MESA_LOADER_DRIVER_OVERRIDE=kgsl` vs alias `DRI_DRIVER=kgsl_dri` vs tocar
   ICD.** El override es la variable estándar de mesa 25 para forzar el
   driver gallium; `kgsl_dri` es un alias documentado del mismo driver (se
   deja como nota; no se expone). No tocar `XDG_DATA_DIRS` ni el ICD de
   Vulkan: el `freedreno_icd.json` del paquete ya se instala donde el loader
   lo ve (API 1.4.305).
3. **Verificar versión de mesa con `dpkg-query` vs confiar en
   `/etc/debian_version`.** `debian_version=13.6` es demasiado grueso
   (parches de seguridad suben la mesa sin cambiar el release de Debian); el
   `dpkg-query` da la versión exacta de los 6 paquetes, es lo que el gate
   necesita, por una sola vez al abrir el panel (glue, one-shot).
4. **Descartar fichero parcial vs dejarlo y reanudar.** Constitución #5:
   nada queda sin verificar; si un `.deb` no cuadra el SHA, se borra y no se
   toca `apt` (RF-7). La reanudación con `Range:` se descarta (la release es
   pequeña, decenas de MB; la reanudación complica la verificación del hash).
5. **Pref `gpu_solution` en `TermuxAppSharedPreferences` vs fichero aparte.**
   En el fichero de prefs de la app, sin reescribir el bloque persistido
   (gotcha 008): añadir una clave es una operación segregada y la
   desinstalación la limpia con `NONE`. Un fichero nuevo sería una fuente más
   de verdad que se puede desincronizar con el rootfs.

## Estrategia de tests (`./gradlew test`, JUnit plano; Robolectric solo donde pinte Android)

| Test | RF/NFR |
|---|---|
| `detectFamily`: adreno con kgsl (modelo/varios formatos), con solo nodo, solo `hwEgl=adreno`, `SOC_MODEL=` (API), mali/mediatek, powervr, desconocida con motivo — entradas inyectadas, sin disco | RF-1 |
| `evaluateAction`: cada condición del gate falla por su motivo; pref activa + versión diverge → UNINSTALL; pref NONE + versión ok → INSTALL | RF-3, RF-5, RF-6 |
| `buildInstallCommand`: 6 ficheros y 6 holds correctos, `./` presente, frontend noninteractive | RF-3 |
| `buildUninstallCommand`: unhold+reinstall por mirror | RF-5 |
| `parseDpkgVersions`: bien formado, paquete ausente → null, sin crash | RF-3, RF-6 |
| `debUrlFor`: `+` → `%2B`; nombre exacto de la release | RF-3, NFR-2 |
| `isWellFormedSha256`: 64 hex sí; 63/no-hex no — sobre las 6 constantes | NFR-2 |
| `applyGpuEnv`: activo → variable presente `kgsl`; inactivo → mapa byte a byte como el de hoy (test base existente de `ProotShellEnvironmentTest` sin alterar comando) | RF-4, NFR-3 |
| `GpuAccelerationInstaller` (Robolectric o extracto purificado): hash mismatch → no ejecuta apt y borra el fichero; éxito de apt → pref cambia; fallo de apt → pref NO cambia | RF-3, RF-5, RF-7 |

Validación en dispositivo (T9): mantenedor con **Mali** verifica la detección,
el panel honesto, la ausencia de la acción, y que el invitado sigue en
llvmpipe con `env`/`glxinfo`-equivalente; el pipeline Adreno se documenta
(wiki) para validación con hardware Adreno por mantenedor/comunidad, no
bloqueante.

## Dudas abiertas del plan

- [NECESITA ACLARACIÓN] SHA-256 reales: rellenar `TermuxConstants` desde las
  release notes el día de implementar la descarga (ver duda abierta 1 de la
  spec); los tests de forma ya están listos para validarlos.
- El callback de fin del ExecutionCommand: si no expone exit code limpio, usar
  marcador en el log + parse puro (descrito arriba). Mínimo riesgo, se decide
  en T6.
