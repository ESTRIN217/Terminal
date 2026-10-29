# Tasks 023 — Ejecutar en la terminal y Descomprimir

Spec: `specs/023-filemanager-ejecutar-descomprimir/spec.md` · Plan: `plan.md`.
Cada tarea que añade tests es test-primero (rojo), luego código, `./gradlew test` verde.

- [x] **T1. ExtractPolicy pura: formato y destino de extracción.** RF-5, RF-6
  - `ExtractPolicy.kt`: `ExtractFormat` (contenedores + sueltos), `ExtractionTarget`
    (`Folder(name)`/`Single(name)`), `parse(name)` → par formato/destino o `null`.
  - Hecho cuando: `ExtractPolicyTest` (los 7 contenedores con `Folder`, los 3 sueltos
    con `Single`, `doble.tar.gz` → TAR_GZ+`doble`, mayúsculas, `.gz` sin base → null,
    sin extensión → null) en rojo → código → `./gradlew :filemanager:test` verde.
- [ ] **T2. RunPolicy pura: comando de ejecución.** RF-2, RF-3, RF-4
  - `RunPolicy.kt`: `RunPlan` (`Direct`/`Interpreter`/`NotExecutable`) y
    `decide(name, canExecute, interpretersAvailable)` con escapado de apóstrofes.
  - Hecho cuando: `RunPolicyTest` (ejecutable → `./nombre` escapado; `.sh` sin bit →
    `bash <nombre>`; `.py` → `python3` si está / not si falta; `.txt` sin bit →
    NotExecutable) en rojo → código → `./gradlew :filemanager:test` verde.
- [x] **T3. `ArchiveExtractor` en disco.** RF-6, RF-7, RF-8, RNF-5
  - zip, tar (+gz/bz2/xz), 7z (contraseña opcional) y suelto gz/bz2/xz;
    zip-slip aborta y limpia; fallo a mitad elimina el destino parcial.
  - Hecho cuando: `ArchiveExtractorTest` en rojo → código →
    `./gradlew :filemanager:test` verde (sin Android, tmp-dir del test).
- [x] **T4. ViewModel: `extractItem` + notificaciones.** RF-6, RF-7, RF-8
  - `extractItem(file, password?, onResult)` en IO con busy + refresh;
    colisión/formato resueltos con la policy ANTES de tocar disco;
    `PasswordRequiredException` → `ExtractionOutcome.PasswordRequired`;
    notificaciones snack (hecho/colisión/fallo/entrada insegura).
  - Hecho cuando: compila y `./gradlew test` verde; ninguna ruta de extracción
    toca el hilo principal; colisión comprueba `File.exists()` sin leer el archivo.
- [x] **T5. Menú + diálogo de contraseña en `FileManagerScreen`.** RF-1, RF-5, RF-8
  - «Ejecutar en la terminal» (archivos, tras «Abrir con…`, `enabled=!busy`,
    oculto sin callback), «Descomprimir» (tras «Comprimir…`, solo extraíbles);
    `DialogKind.EXTRACT_PASSWORD` con campo oculto y reintentador único (T4).
  - Hecho cuando: el menú se compila sin literales (strings en T7); sin cableado de
    `onRunInTerminal` el elemento no se pinta; `./gradlew test` verde.
- [ ] **T6. Cableado de app: ejecutar.** RF-1..RF-4, RNF-6
  - `onRunInTerminal` en `FileManagerSessionHost` + `TermuxMainScreen`;
    `TermuxComposeActivity.runFileInTerminal` (rootfs/MAX sesiones/ruta
    mapeable → RunPolicy con intérpretes sondeados → `launchRunSession`
    espejo de `launchEditorSession`; not-executable → diálogo `msg_file_not_executable`).
  - Hecho cuando: compila, `./gradlew test` verde y `grep` sin literales nuevos.
- [x] **T7. Strings en 3 locales + cerrar.** RNF-2
  - Cadenas nuevas del plan en `values/`, `values-es-rVE/`, `values-pt-rBR/`.
  - Hecho cuando: `grep -rnP '(Text|contentDescription)\(\s*"' --include=*.kt
    app/src/main filemanager/src/main` sin literales y `./gradlew test` completo verde.
- [x] **T8. Verificación de cierre de spec.**
  - `./gradlew test` verde global (2026-10-29); spec en `implementada`;
    verificación en dispositivo pendiente del mantenedor (se ejecuta contra
    los criterios de finalización de la spec).
  - Hecho cuando: 0 tests caídos; estados marcados; anotado en `MEMORY.md`.
