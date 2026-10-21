# Tareas 005 — Identidad de proceso visible para apps CLI

- [x] **T1. Facade + native en terminal-emulator. Test JUnit en rojo, luego clase/JNI/C y verde.** RF-1, RF-2, RF-5
  - Hecho cuando: `TerminalProcessNameTest` rojo primero con `TerminalProcessName` y `PROCESS_IDENTITY` ausentes; tras crear la clase (constant + `applyProcessIdentity()` con catch), `JNI.setProcessName` declarado y `termux.c` con implementación `prctl(PR_SET_NAME)`, `./gradlew :terminal-emulator:test` en verde (incluido el test nuevo).
- [x] **T2. Cableado en app: `TermuxApplication.onCreate`.** RF-2, RF-4, RF-5
  - Hecho cuando: `onCreate` llama a `TerminalProcessName.applyProcessIdentity()` en el hilo principal y registra warn con `Logger` si devuelve false; `./gradlew test` en verde.
- [x] **T3. Validación en dispositivo + cierre.** RF-1, RF-4
  - Hecho cuando: APK instalado; en sesión host y guest `fastfetch` muestra `Terminal: Terminal`; `cat /proc/<pid>/comm` del app da `Terminal`; tasks marcadas, spec movida a estado `implementada` (tras validación) y `MEMORY.md` actualizado.
