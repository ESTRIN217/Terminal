# Tasks 009 — Menú More por ítem y diálogo de propiedades

Spec: `specs/009-filemanager-item-menu/spec.md` · Plan: `plan.md`.
Cada tarea que añade tests es test-primero (rojo), luego código, `./gradlew test` verde.

- [x] **T1. Políticas puras: nombre de compresión y resultado de pegado.** RF-7, RF-4
  - `ArchiveNamePolicy.kt` (`ArchiveFormat`, `buildCompressionPlan`: Ready/Invalid/Collision)
    y `PastePolicy.kt` (`outcome(moved, total, isCut)`: limpiar solo si cortó algo, aviso si todo falló).
  - Hecho cuando: `ArchiveNamePolicyTest` (Ready/vacío/espacios//`/`/colisión/ext por formato)
    y `PastePolicyTest` (todo movido, cero movido, parcial, copiar-mode) en rojo → código →
    `./gradlew :filemanager:test` verde.
- [x] **T2. Políticas puras: editor de rootfs y texto de modo.** RF-2, RF-12
  - `EditorPolicy.kt` (`chooseEditor`: primer disponible o `null`) y `PosixModeText.kt`
    (`format(mode, perms)` → simbólico de `FilePermissions.toString` + octal).
  - Hecho cuando: `EditorPolicyTest` (nano, nano caído→vim, ninguno, lista vacía, orden)
    y `PosixModeTextTest` (644/755/000/777) en rojo → código → `./gradlew :filemanager:test` verde.
- [x] **T3. Portapapeles por ítem y arreglo del pegado en el ViewModel.** RF-3, RF-4
  - `cutItem(file)`/`copyItem(file)` usan `setClipboard([item], …)` sin tocar selección ni
    modo; `paste()` aplica `PastePolicy`: aviso de error al fallar todo y limpieza del
    portapapeles de cortar solo si count>0.
  - Hecho cuando: portapapeles queda con un solo origen y la selección no cambia;
    pegar con fuentes borradas muestra el aviso de error (y no «0 pegados») y el
    portapapeles de cortar sobrevive a un pegado fallido; compila y `./gradlew test` verde.
- [x] **T4. Dependencias del catálogo + `ArchiveCreator` + `compressItem`.** RF-7, RNF-1, RNF-4
  - Alta de `implementation(libs.commons.compress)` y `implementation(libs.xz)` en
    `filemanager/build.gradle.kts` (delta citado en plan/RNF-1);
    `ArchiveCreator.create(out, src, format, password)` con `.zip`, `.tar.xz` (backend
    `org.tukaani:xz`) y `.7z` con contraseña `SevenZOutputFile(File, char[])`; el
    ViewModel encola en IO con busy + refresh y snack bar de resultado según el plan.
  - Hecho cuando: `./gradlew :filemanager:assembleDebug` integra las dependencias; comprimir
    un archivo y una carpeta pequeños genera el fichero correcto en la carpeta abierta
    (verificación manual en dispositivo o con `file`); colisión/vacío no llegan a tocar disco.
- [x] **T5. Menú More por ítem en la fila de lista.** RF-1, RF-3, RF-5, RF-6, RF-8..RF-10, RF-13
  - Sustituye el icono «Info» de cada fila por «More» con `DropdownMenu` anclado; ítems en el
    orden de RF-1 con estados busy (`enabled = !busy` en Pegar/Eliminar/Renombrar/Comprimir,
    resto habilitado); «Pegar» condicional a `hasClipboard`; favorito solo carpetas con
    su variante añadir/quitar; Eliminar/Renombrar reutilizan los diálogos vigentes con
    `dialogFile`; todas las acciones sobre el enlace sin resolver (RF-13); la selección no se toca.
  - Hecho cuando: el menú se abre por fila y en orden; cortar/copiar/pegar/eliminar/
    renombrar/compartir/copiar ruta/favoritos desde un ítem funcionan como sus homólogos
    de selección; con ocupación en curso el menú respeta los estados habilitado/
    deshabilitado; `./gradlew test` verde (sin tests nuevos: las partes puras ya están en T1-T3).
- [x] **T6. Submenú «Abrir con…» con Sistema/Rootfs.** RF-2, RF-13
  - Visible solo para archivos (y enlaces cuyo destino no sea carpeta); «Sistema» =
    `onOpenFile(resolveForOpen(item))`; «Rootfs» = `chooseEditor` con los
    `DEBIAN_EDITOR_CANDIDATES` en rootfs (`usr/bin`, canExecute): disponible →
    `onEditFile` (callback opcional ya existente; `null` oculta el elemento),
    indisponible/rootfs ausente → snack bar `msg_no_rootfs_editor` y no abre nada.
  - Hecho cuando: `Rootfs` abre sesión con nano (o vim si nano falta); sin binarios el aviso
    aparece y no hay sesión; sin cableado de `onEditFile` el elemento Rootfs no se ve;
    enlaces: Sistema/Rootfs actúan sobre el destino; `./gradlew test` verde.
- [x] **T7. Diálogo «Comprimir…».** RF-7
  - Nombre por defecto = nombre del ítem (aceptar deshabilitado si queda vacío, como los
    demás diálogos de nombre); radios `.zip`/`.tar.xz`/`.7z` (una sola activa); campo de
    contraseña visible solo con `.7z`; al confirmar `compressItem` con el plan de T1/T4:
    Invalid/Collision → snack bar de error sin tocar disco (RNF-5).
  - Hecho cuando: el diálogo abre/cierra sin literales (datos en strings); zip/tar.xz/7z
    y 7z con contraseña generan el archivo del nombre elegido; colisión y nombre con `/`
    muestran aviso y no sobrescriben; `./gradlew test` verde.
- [x] **T8. Diálogo de propiedades en dos pestañas.** RF-12, RF-13
  - `DialogKind.PROPERTIES` (sustituye DETAILS): pestaña Básico (nombre, carpeta superior
    amable de crumbs, MIME, contenido = tamaño o «N carpetas · M archivos» por ocultos
    vigente, última modificación) y pestaña Permisos (propietario/grupo vía
    `FileAttributes`+`UserUtils`, modo simbólico+octal vía `PosixModeText`,
    contexto SELinux vía `SELinuxUtils`), botón aceptar cierra; «—» en dato ilegible;
    enlaces muestran destino + estado roto y se leen con lstat (sin seguir); el mosaico
    de cuadrícula redirige su «Info» a este diálogo (fuera de alcance el menú en cuadrícula).
  - Hecho cuando: el diálogo abre para archivo, carpeta y enlace; los datos ilegibles no
    bloquean (fila «—»); el mosaico ya no muestra el diálogo de detalles con acciones;
    `./gradlew test` verde.
- [x] **T9. Atajo de archivo + cableado de app.** RF-11, RNF-6
  - `onCreateFileShortcut: ((File) -> Unit)?` opcional en Screen y Host (`null` ⇒ ocultar);
    `TermuxConstants`: `ACTION_OPEN_FILE`/`EXTRA_OPEN_FILE_PATH`; `TermuxMainScreen`
    propaga; `TermuxComposeActivity`: `createFileLauncherShortcut` (id estable, icono de
    archivo) y rama en `handleIntent` que abre el archivo con la app Android o, si no
    existe ya, el gestor en su carpeta por defecto sin error; consume el action.
  - Hecho cuando: «Crear acceso directo» de un archivo fija un icono que abre el archivo
    (y si se borra antes de abrir el atajo, la app abre el gestor sin error); sin cableado el
    elemento desaparece del menú; rotación no reprocesa el intent (action consumida);
    `./gradlew test` verde.
- [x] **T10. Strings en 3 locales + cerrar.** RNF-2
  - Todas las cadenas nuevas del plan en `values/`, `values-es-rVE/`,
    `values-pt-rBR/`; sin literales en Compose.
  - Hecho cuando: `grep -rnP '(Text|contentDescription)\(\s*"' --include=*.kt app/src/main
    filemanager/src/main` no devuelve literales nuevos y `./gradlew test` completo verde.
