# Tareas 002 — Celdas placeholder nunca se pintan como glifo

Plan: `specs/002-kitty-placeholder-blanking/plan.md`. Una tarea cada vez: **tests primero
(rojo) → código → `./gradlew test` en verde → marcar**. Gate único: `./gradlew test`.

- [x] **T1. Decodificador: reportar celdas placeholder no resolubles.** RF-1, RF-4, RF-7
  - **Hecho** (2026-10-19): `KittyPlaceholderBlankTest` (4 casos) escrito antes y en rojo
    (error de compilación por la sobrecarga inexistente); `KittyPlaceholderDecoder` gana
    `UnresolvedCellVisitor` y la sobrecarga de 5 argumentos; `decode` sigue sin tocar
    `RowState` cuando no resuelve. `:terminal-emulator:test` verde, 350 tests (346 + 4).
  - Hecho cuando: `KittyPlaceholderBlankTest` se escribe antes y está en rojo (la sobrecarga
    no existe); `KittyPlaceholderDecoder` expone `UnresolvedCellVisitor` y
    `collectRow(..., unresolvedVisitor)` reportando cada celda que empieza por `U+10EEEE`
    y no decodifica, sin tocar `RowState`; la sobrecarga de 4 argumentos sigue igual y
    `KittyPlaceholderTest`/`KittySuiteReplicationTest` pasan sin cambios.
    `./gradlew :terminal-emulator:test` verde.

- [x] **T2. Renderer legado: ocultar el glifo y blanquear la celda.** RF-1, RF-3, RF-4, RF-5, RF-6, RF-8
  - **Hecho** (2026-10-19): `mUnresolvedColumns` (un `int[]` dimensionado a `columns`, crece
    solo si cambia el ancho) + `mUnresolvedCount`; las columnas no resolubles entran en
    `skipCells` y se blanquean tras el texto con el nuevo `blankPlaceholderCell`;
    `drawPlaceholderCell` blanquea antes de resolver. `:terminal-view:compileDebugJavaWithJavac`
    verde.
  - Hecho cuando: las columnas no resolubles se recogen en un `int[]` por frame, se añaden a
    `skipCells` y se blanquean tras el texto con el fondo efectivo; `drawPlaceholderCell`
    blanquea antes de resolver, de modo que una colocación ausente, una imagen desalojada o
    un destino fuera de `c×r` dejan la celda limpia; con imágenes desactivadas no cambia
    nada. `./gradlew :terminal-view:compileDebugJavaWithJavac` verde.

- [x] **T3. Renderer Compose: misma regla.** RF-1, RF-3, RF-4, RF-5, RF-6, RF-8
  - **Hecho** (2026-10-19): `IntArray(columns)` por frame + `unresolvedCount` local;
    `collectRow` de 5 argumentos; `blankComposePlaceholderCell` extraído y llamado también al
    principio de `drawComposePlaceholderCell`. `:app:compileDebugKotlin` verde.
  - Hecho cuando: `ComposeTerminalCanvas` recoge las columnas no resolubles en un
    `IntArray(columns)` por frame, las añade a `skipCells` y las blanquea tras el texto y
    las pasadas de imagen; `drawComposePlaceholderCell` blanquea antes de resolver; el
    comentario de `drawPlaceholderCell` ya no habla de "fallback glyph".
    `./gradlew :app:compileDebugKotlin` verde.

- [x] **T4. Cierre.** RF-8, RNF-1, RNF-2, RNF-3, RNF-5
  - **Hecho** (2026-10-19): `CopilotDiagTest.java` borrado; `./gradlew test` → BUILD SUCCESSFUL,
    523 tests (terminal-emulator 348, app 149, filemanager 21, termux-shared 5), 0 fallos;
    `git diff --stat` no toca `gradle/libs.versions.toml`, `strings.xml` ni `values-*`; la spec
    queda `Estado: aprobada` con nota de implementación (validación en dispositivo pendiente);
    `MEMORY.md` actualizado. Falta que el usuario confirme en el móvil (copilot sin símbolos,
    opencode/yazi igual).
  - Hecho cuando: se borra el test temporal `CopilotDiagTest.java`, `./gradlew test` queda en
    verde, `git diff` no toca `gradle/libs.versions.toml`, `strings.xml` ni `values-*`, la
    spec pasa a `Estado: implementada` (validación en dispositivo pendiente de confirmar por
    el usuario) y `MEMORY.md` resume la causa, el arreglo y lo que queda por validar.

- [x] **T5. Corrección del renderer legado: runs fantasma tras celdas saltadas.** RF-1, RF-8
  - **Hecho** (2026-10-19): el usuario reportó que la corrección funcionaba en Compose nativo
    pero no en el legado. Causa raíz, distinta del T2, en `TerminalRenderer.render()`: al
    saltar celdas (`skipCells`) el estado del run anterior quedaba con `lastRunStartColumn
    = -1`, pero el bucle solo usaba `column == 0` como centinela de "no hay run vivo".
    Consecuencia: al reanudar texto tras las celdas saltadas (o al acabar una fila toda
    saltada) se lanzaba `drawTextRun` con `startColumn = -1` e índice de texto obsoleto;
    con `mes = 0` el escalado de anchura dividía entre cero y estiraba los glifos
    placeholder por la fila — exactamente los símbolos que veía el usuario con copilot.
    Compose lo tiene bien (`hasRun` en `forEachRun`); el legado quedó desalineado desde
    que el salto de celdas existe (espec 001). Fix: `lastRunStartColumn < 0 ||` al frente
    de la condición de corte (fuerza run nuevo tras salto), guardas
    `lastRunStartColumn >= 0` en el flush interno y en el flush final de fila — mismas
    reglas que `forEachRun`. El pintado no es verificable con JUnit puro (Canvas es
    Android; sin Robolectric) → validación en dispositivo, igual que RNF-3.
    `./gradlew test` verde (523).
  - Hecho cuando: el bucle de runs del legado nunca lanza `drawTextRun` sin run vivo
    (condición de corte con `lastRunStartColumn < 0`, flushes guardados con
    `lastRunStartColumn >= 0`), paridad con `ComposeTerminalFrame.forEachRun`;
    `./gradlew test` verde; el usuario confirma en dispositivo que copilot se ve limpio
    en el render legado y Compose nativo, y opencode/yazi sin cambios.
