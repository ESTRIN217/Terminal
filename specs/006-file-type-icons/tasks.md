# Tasks 006 — Iconos distintivos por tipo de archivo (rev 2)

Spec: `specs/006-file-type-icons/spec.md` · Plan: `specs/006-file-type-icons/plan.md`
Gate: `./gradlew :filemanager:test` (y `./gradlew test` al cerrar). Tests primero.

- [x] **T1. Tests de `FileTypeDetector` en rojo.** RF-2, RF-3, RF-5
  - Hecho cuando: existe `filemanager/src/test/java/com/estrin217/filemanager/FileTypeDetectorTest.java`
    (JUnit 4) cubriendo: una extensión al menos por familia (primeras de la RF-2), desconocida →
    `null`, `null`/`""` → `null`/`""`, `Makefile` → sin tipo, `file.` → sin tipo,
    `app.tar.gz` → `gz`, `.bashrc` → sin tipo, `APP.PY`/`IMG.Jpg` reconocidos,
    `Mi documento final.txt` → `txt`; y `./gradlew :filemanager:test` falla por
    clase inexistente (rojo).
- [x] **T2. Implementar `FileTypeDetector.java`.** RF-2, RF-3, RF-5
  - Hecho cuando: clase `final` en `com.estrin217.filemanager` con `enum FileType`
    (CODE, BINARY, ARCHIVE, IMAGE, AUDIO, VIDEO, TEXT, PDF, DOCUMENT),
    `getFileExtension(String)` y `familyFor(String)` según el plan; Javadoc con
    `@param`/`@return`; `./gradlew :filemanager:test` en verde.
- [x] **T3. Pintado en la UI.** RF-1, RF-3, RF-4
  - Hecho cuando: nuevo `FileTypeIcons.kt` en `compose` (mapping `FileType` → icono/color
    según la tabla del plan, sin lógica) y el bloque del `Icon` en `FileManagerScreen.kt`
    mantiene la precedencia roto > link > dir > familia > genérico (con `remember` por
    `absolutePath`); `./gradlew :filemanager:test` sigue en verde y `grep -rnP
    '(Text|contentDescription)\(\s*"'` no devuelve literales nuevos en `filemanager/src/main`
    (C.6).
- [x] **T4. Contrato de orden de `FileSortOption` (regresión).** (sin RF; anti-duplicación)
  - Hecho cuando: existe `FileSortOptionExtensionTest.java` con ordenación completa asc/desc
    fija en verde sobre el código actual; la delegación propuesta en el plan se descartó tras
    demostrar con el test que cambiaba el grupo de ficheros ocultos (fuera de spec), el
    `FileSortOption` no se modifica y el test queda como documentación del contrato.
- [x] **T5. Cerrar la spec.** (criterios de finalización)
  - Hecho cuando: `./gradlew test` completo en verde (565 tests, 0 fallos); `spec.md` pasa a
    `Estado: implementada`; `MEMORY.md` actualizado; pendiente de validar visualmente en
    dispositivo la lista de familias (no bloquea: gate = tests).
- [x] **T1-T5 rev 1**: implementadas (2026-10-20), con la enmienda documentada de no delegar
  `FileSortOption` (ver plan). La numeración repite T1-T4 en rev 2 tras este bloque.
- [x] **T1 (rev 3). Tests GIT en rojo.** RF-13
  - Hecho cuando: `FileTypeDetectorTest` incluye: `.gitignore`/`GITIGNORE`/`.GITMODULES` → GIT,
    `gitattributes`/`gitconfig`/`gitkeep`/`gitmodules` (sin punto) → GIT, y no-GIT confusable
    (`gitui.cfg`, `.profile`) → NONE; `./gradlew :filemanager:test` en rojo.
- [x] **T2 (rev 3). `IconVariant.GIT` en el detector.** RF-13
  - Hecho cuando: match por nombre base (sin punto inicial, lowercase) en el set de 5 ficheros,
    testimonde en verde.
- [x] **T3 (rev 3). Glifo git + tinte brand + fila.** RF-13
  - Hecho cuando: `GitGlyph` vendido en `FileTypeGlyphs.kt` (viewport 78, URL origen el XML
    adjunto/materia), `IconVariant.icon` → glifo y `IconVariant.tint` → brand `#F03C2E` (solo
    GIT; el resto hereda familia), y `FileManagerScreen` pinta `variant?.tint ?: family?.tint`;
    `./gradlew :filemanager:test` en verde; `./gradlew test` global en verde al cerrar;
    `MEMORY.md` aggiornado. Nota: el test de cuasi-muchos `github_notes.md` se ajustó a
    MARKDOWN (el match git es exacto de nombre base, nunca substring).
- [x] **T1 (rev 5). Tests de KT en rojo.** RF-15
  - Hecho cuando: `FileTypeDetectorTest` incluye `MainActivity.kt`/`script.kts`/`NOTES.KT` →
    IconVariant.KT y `a.kt.bak`/`test.ktsx` → NONE (la familia CODE no cambia); test en rojo.
- [x] **T2 (rev 5). `IconVariant.KT` en el detector.** RF-15
  - Hecho cuanto: extensións `kt`/`kts` → KT (la familia sigue CODE para el resto del sistema,
    solo cambia el icono); test en verde.
- [x] **T3 (rev 5). Glifo Kotlin + tinte brand.** RF-15
  - Hecho cuando: `KotlinGlyph` vendido en `FileTypeGlyphs.kt` (el path del XML adjunto,
    viewport 48), `IconVariant.icon` → glifo y `IconVariant.tint` → `#7F52FF`; test global
    `./gradlew test` en verde; `MEMORY.md` aggiornado.
- [x] **T1 (rev 6). Tests JAVA en rojo.** RF-16
  - Hecho cuando: `Main.java`/`Main.JAVA` → `IconVariant.JAVA` y `Main.javac`/`a.java.bak` →
    NONE; test en rojo.
- [x] **T2 (rev 6). `IconVariant.JAVA` en el detector.** RF-16
  - Hecho cuando: extensión `java` → JAVA (la familia sigue CODE); test en verde.
- [x] **T3 (rev 6). Glifo local_cafe vendido + mapping + rojo Java.** RF-16
  - Hecho cuando: `JavaCoffeeGlyph` vendido en `FileTypeGlyphs.kt`
    (endpoint `local_cafe.kt`, URL citada), `IconVariant.icon` → glifo y `IconVariant.tint` →
    rojo Java `#EA2D2E` (variante con color propio); `./gradlew test` global en verde;
    `MEMORY.md` aggiornado.
- [x] **T1 (rev 7). Tests PY en rojo.** RF-17
  - Hecho cuando: `main.py`/`Main.PY`/`v.poli.py` → `IconVariant.PY` y `a.py.bak`/`p.pyw` →
    NONE; test en rojo.
- [x] **T-cierre. Validación en dispositivo y cierre definitivo** (criterios de finalización)
  - Hecho cuando: la lista del gestor muestra las 9 familias y las 10 variantes con sus
    tintes (git rojo, json amarillo, kotlin morado, java rojo, python bicolor) y no rompe con
    ficheros sin tipo; validada en dispositivo 2026-10-20 por el usuario; `spec.md` →
    `cerrada`; `MEMORY.md` aggiornado.
- [x] **T2 (rev 7). `IconVariant.PY` en el detector.** RF-17
  - Hecho cuando: extensión `py` → PY (familia CODE intacta); test en verde.
- [x] **T3 (rev 7). Glifo Python bicolor + mapping.** RF-17
  - Hecho cuando: `PythonGlyph` vendido en `FileTypeGlyphs.kt` con los DOS paths del drawable
    (azul `#3776AB` y amarillo `#FFD43B` bakeados, viewport 17.06), `IconVariant.icon` → glifo
    y `IconVariant.tint` → `Color.Unspecified` para PYTHON (sin ColorFilter, RF-17);
    el tinte de la fila lo propaga (no lo consume); `./gradlew test` global en verde;
    `MEMORY.md` aggiornado.
- [x] **T1 (rev 2). Tests de `iconVariantFor` en rojo.** RF-7 a RF-12
  - Hecho cuando: `FileTypeDetectorTest` incluye los tests de variante: `README.md`/`readme.md`
    → INFO (no MARKDOWN), `CHANGELOG.MD` → HISTORY, `a.md`/`a.markdown`/`Notas.MD` → MARKDOWN,
    `a.json` → JSON, `a.properties`/`build.prop` → SETTINGS, `app.js` → JS, `app.ts`/`a.md.txt`
    → NONE, variantes insensible a mayúsculas en ext; y `./gradlew :filemanager:test` en rojo.
- [x] **T2 (rev 2). `IconVariant` en el detector.** RF-7 a RF-11
  - Hecho cuando: `enum IconVariant` + `iconVariantFor(String)` implementados con la
    precedencia readme > changelog > extensión; test en verde.
- [x] **T3 (rev 2). Glifos vendidos + mapping + fila.** RF-10, RF-11, RF-12
  - Hecho cuando: `FileTypeGlyphs.kt` (markdown y file_json, URL de origen en comentario),
    `FileTypeIcons.kt` mapea `IconVariant` → icono (y color sigue siendo el de la familia) y
    `FileManagerScreen.kt` añade la variante tras link/dir en la precedencia;
    `./gradlew :filemanager:test` en verde y sin literales nuevos (C.6).
- [x] **T4 (rev 2). Cierre.**
  - Hecho cuando: `./gradlew test` completo en verde; `spec.md` → `implementada (rev 2)`;
    `MEMORY.md` aggiornado.
- [x] **T1 (rev 4). JSON en amarillo.** RF-14
  - Hecho cuando: `IconVariant.tint` devuelve `#F9A825` (Material Yellow 800) para JSON y
    `null` para el resto; spec y plan actualizados a rev 4 (única variante con color propio
    además de GIT); `./gradlew test` global en verde (sin nuevos tests: el mapping color es
    puro pintado de UI, sin lógica de libría) y `MEMORY.md` aggiornado.
