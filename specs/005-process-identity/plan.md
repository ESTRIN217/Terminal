# Plan 005 — Identidad de proceso visible para apps CLI

## Archivos y responsabilidades

| Archivo | Módulo | Responsabilidad | RF |
|---|---|---|---|
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalProcessName.java` | terminal-emulator | Constante de identidad + `applyProcessIdentity()` pública (facade de JNI), con log de fallo | RF-1, RF-2, RF-5 |
| `terminal-emulator/src/main/java/com/termux/terminal/JNI.java` | terminal-emulator | Nueva native `setProcessName(String)` (package-private, como el resto) | RF-1 |
| `terminal-emulator/src/main/cpp/termux.c` | terminal-emulator | JNI que ejecuta `prctl(PR_SET_NAME, name)` y devuelve éxito/fallo | RF-1, RF-2 |
| `terminal-emulator/src/test/java/com/termux/terminal/TerminalProcessNameTest.java` | terminal-emulator | Test JUnit puro: identidad fija `Terminal`, ≤ 15 chars (límite `TASK_COMM_LEN-1`) | RF-5 |
| `app/src/main/java/com/termux/app/TermuxApplication.java` | app | Llama a la identidad en `onCreate` (hilo principal) y registra el fallo con `Logger` | RF-2, RF-4, RF-5 |

## Interfaz

```java
// TerminalProcessName (public final, terminal-emulator)
public static final String PROCESS_IDENTITY = "Terminal";
/** @return true si prctl(PR_SET_NAME) fijó la identidad; false en fallo (sin excepción). */
public static boolean applyProcessIdentity();
```

- Identidad y límite viven en terminal-emulator porque el límite de 15 chars es una propiedad
  del syscall nativo (y terminal-emulator es el módulo del JNI); app solo orquesta.
- No se usa `android.system.Os.prctl` (Java): no permite pasar un `char*` (solo args numéricos).
- Log en terminal-emulator vía su `Logger` interno (el módulo es autónomo, no puede usar el de
  termux-shared); la app además registra el fallo con `com.termux.shared.logger.Logger`.

## Algoritmo (pseudocódigo)

```
applyProcessIdentity():
  try:
    return JNI.setProcessName(PROCESS_IDENTITY)   // prctl(PR_SET_NAME, name) == 0
  catch Throwable:
    log error; return false                        // RF-2: degradar sin bloquear
```

```
TermuxApplication.onCreate():        // hilo principal del proceso, RF-4/RF-5
  if !TerminalProcessName.applyProcessIdentity():
    Logger.logWarn(..., "of failure comm identity ...")
```

Identidad en el arranque: `Application.onCreate` corre en el hilo principal; el `PR_SET_NAME`
del hilo principal es el `comm` visible como `/proc/<pid>/comm` del proceso. Las sesiones se
crean después y por herencia el PPID de la cadena termina en ese PID.

## Decisiones justificadas (alternativa descartada)

1. **prctl nativo** vs escribir `/proc/self/comm` desde Java: la escritura procfs puede estar
   mediada por SELinux en según qué ROMs; `prctl` es exactamente la vía que usa Android para
   fijar el nombre de proceso (y no está bloqueada por su filtro SECCOMP).
2. **prctl nativo** vs `android.system.Os.prctl` (Java): la firma de libcore solo acepta
   argumentos numéricos, no cabe pasar un `char*`.
3. **`android:process="terminal"` en el manifest**: descartado — mueve componentes a otro
   proceso/rescribiro el modelo de ciclo de vida; riesgo alto sin beneficio.
4. **Cambiar `applicationId` a algo ≤ 15 chars**: descartado — rompe `TermuxConstants`
   (paths derivados), las actualizaciones sobre el APK publicado y el respaldo v1.119.0.
5. **Pedir caso especial upstream (fastfetch)**: descartada — no depende de nosotros y
   no resolvería neofetch/scripts propios.

## Estrategia de tests (`./gradlew test`)

- `TerminalProcessNameTest` (JUnit plano, sin Android, sin JNI): fija el contrato puro —
  identidad `Terminal`, longitud ≤ 15 (límite de `comm`) y sin separadores de ruta; falla
  hasta que existan la clase y la constante (rojo → código → verde).
- Hook nativo (`prctl`, herencia a sesiones) se valida en dispositivo según RF-1/RF-4
  (verificación manual: `fastfetch` host/guest + `cat /proc/<pid>/comm`).
- Gate: `./gradlew :terminal-emulator:test` durante la tarea y `./gradlew test` al final.
