# Tasks 008 — Topbar del gestor de archivos

Spec: `specs/008-filemanager-topbar/spec.md` · Plan: `plan.md`.
Cada tarea con tests primero (rojo), luego código, `./gradlew test` en verde.

- [x] **T1. Comparador «carpeta primero» en `FileSortOption`.** RF-7, RF-8
  - Envoltura: overload `getComparator(boolean ascending, boolean foldersFirst)`; los 4
    comparadores respetan `foldersFirst=false` (dirs por su criterio normal, sin bloque).
  - Hecho cuando: `FileSortOptionFoldersFirstTest` (2×4 combinaciones: asc/desc ×
    name/size/date/type con dirs on/off) en rojo → código → `./gradlew :filemanager:test` verde;
    la firma antigua sigue compilando (delega con `true`).
- [x] **T2. `ListingCounts` puro.** RF-1, RF-18
  - Hecho cuando: `ListingCountsTest` en rojo (mezcla visible/oculta, todo oculto,
    vacía, symlink→dir cuenta como carpeta) → código → tests unitarios sin Android en verde.
- [x] **T3. `BreadcrumbPolicy` puro.** RF-2
  - Hecho cuando: `BreadcrumbPolicyTest` en rojo (`/`→Root, home exacto→Home, home/hijo,
    `/sdcard`, `/storage/emulated/0`, ruta profunda, cadena vacía, barras sobrantes)
    → código → tests en verde.
- [x] **T4. Estado y ViewModel: vista, orden, scope, favoritos, goUp/goTo, recuentos.** RF-1, RF-6..RF-12, RF-18
  - UiState + FileViewMode/foldersFirst/folderScoped/recuentos/favorites/isFavorite/canGoUp;
    prefs globales + por carpeta (`folder:<path>:_*`) con snapshot al activar scope y
    borrado al desactivar; favoritos StringSet; `goTo()` relativa/absoluta.
  - Hecho cuando: compila; navegación entre carpetas mantiene/limpia per-carpeta
    según scope; `./gradlew test` verde (no suben tests nuevos: las partes puras ya
    quedaron en T1-T3; esta tarea es cableado del ViewModel).
- [x] **T5. Topbar + breadcrumb + drawer de marcadores.** RF-1, RF-2, RF-3, RF-4, RF-5, RF-15
  - Título/sección recuento, icono menú→`ModalNavigationDrawer` (fijos + favoritos con
    quitar), back/forward junto al tren clicable (auto-scroll a la cola), fuera la ruta
    y la fila antigua; los diálogos SORT/BOOKMARKS se retiran;
    «Copiar ruta» de la carpeta en el More provisional (T6 lo coloca en orden).
  - Hecho cuando: sin ruta absoluta en topbar; tren clicable navega; drawer añade/quita
    favoritos visibles tras reiniciar el proceso; compila también
    `FileManagerComposeActivity` (obra: quita `onNavigateUp` ahí).
- [x] **T6. Menú «Organizar».** RF-7, RF-8
  - Icono a la izquierda de More; vista/orden/asc/foldersFirst/scope se aplican
    al momento y quedan reflejados al reabrir; reordenación en memoria (caché)
    sin releer disco.
  - Hecho cuando: cambiar vista/orden no relanza listado de disco (caché paga),
    los selects parecen marcados al reabrir el menú, y el ajuste queda guardado.
- [x] **T7. Menú More completo + diálogo «Ir a…».** RF-9..RF-13, RF-15
  - Orden RF-9: Nuevo tab, Subir, Ir a…, Actualizar, Seleccionar todo, Ocultos,
    Compartir carpeta, Copiar ruta, Abrir en terminal, Añadir/Quitar favoritos,
    Crear acceso directo; Pegar se conserva al final (condicional).
  - Hecho cuando: cada elemento activa su acción (tab/subir/ir a/actualizar se
    verifican con los hosts reales en T9; lo dependiente de permisos va detrás del
    guard de acceso al almacenamiento); diálogo Ir a navega a ruta relativa y
    absoluta, vacía deshabilita confirmar.
- [x] **T8. Vista cuadrícula.** RF-17
  - `LazyVerticalGrid(Adaptive(112.dp))` con tile icono+nombre+badge de enlace,
    selección por tap largo, cursor por índice con desplazamiento mínimo (nudge:
    anything ya visible no se mueve, cortado abajo se empuja lo justo; fuera de
    pantalla se alinea al borde más cercano), igual política que la lista de la
    spec 007.
  - Hecho cuando: la cuadrícula muestra la lista igual de ordenada que la vista de
    lista; cursor/focus funciona con teclado; selección y barra inferior intactas.
- [x] **T9. Cableado de app: pestaña nueva + atajo del launcher.** RF-10, RF-16
  - Constantes `ACTION_OPEN_FILE_MANAGER`/`EXTRA_OPEN_FILE_MANAGER_PATH` en
    `TermuxConstants.TERMUX_ACTIVITY`; `handleIntent` rama nueva (y llamada en
    `onCreate`, consumo del action); `createFileManagerSession(baseName, initialPath)`
    + `FileManagerSessionHost` pasa el destino inicial; requestPinShortcut con id
    estable, label amable e icono de carpeta; fallback aviso si no se admite.
  - Hecho cuando: «Nuevo tab» abre una pestaña de gestor nueva y activa; pedir
    acceso directo fija icono en el launcher y al tocarlo abre la app en esa carpeta;
    rotación no procesa el intent otra vez (action consumida).
- [x] **T10. Strings en 3 locales + cerrar.** RNF-2
  - Todas las cadenas nuevas en `values/`, `values-es-rVE/`, `values-pt-rBR/`;
    sin literales en Compose (`grep -rnP '(Text|contentDescription)\(\s*"'`
    filemanager sigue vacío); `./gradlew test` completo en verde.
  - Hecho cuando: el grep no devuelve literales y `./gradlew test` verde.
