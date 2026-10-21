# Plan 008 — Topbar del gestor: breadcrumb, organizar, More y cajón de marcadores

Spec: `specs/008-filemanager-topbar/spec.md`. Cubre RF-1 a RF-18 y RNF-1 a RNF-5.

## Archivos y responsabilidades

| Fichero | Cambio | RF |
|---|---|---|
| `filemanager/…/FileSortOption.java` | Overload `getComparator(boolean ascending, boolean foldersFirst)`; el actual delega con `true` | RF-7, RF-8 |
| `filemanager/…/compose/BreadcrumbPolicy.kt` (nuevo) | Funciones puras: `Crumb(label, path)`, `crumbs(path, homePath)` y `ListingCounts.Counts.of(files, showHidden)` | RF-1, RF-2, RF-18 |
| `filemanager/…/compose/FileViewMode.kt` (nuevo) | `enum class FileViewMode { LIST, GRID }` | RF-7 |
| `filemanager/…/compose/FileManagerUiState.kt` | + `viewMode`, `foldersFirst`, `folderScoped`, `folderCount`, `fileCount`, `favorites: List<Crumb>`, `isFavorite`, `canGoUp` | RF-1, RF-5, RF-6, RF-11 |
| `filemanager/…/compose/FileManagerViewModel.kt` | Vista/orden/scope con prefs por carpeta, favoritos (StringSet en prefs), `goTo()`, `canGoUp`, recuentos en `applyListing`, carga de prefs por carpeta al navegar | RF-6..RF-12, RF-18 |
| `filemanager/…/compose/FileManagerScreen.kt` | Topbar nueva (menú→cajón, título+recuento, sin ruta), renglón breadcrumb con back/forward, cajón de marcadores, menú Organizar, menú More completo, diálogo «Ir a…», vista cuadrícula; se retiran los diálogos `SORT` y `BOOKMARKS` y el parámetro `onNavigateUp` | RF-1..RF-9, RF-12..RF-17 |
| `filemanager/…/FileOperationsHelper.java` | (opcional) `shareFolder(Context, File)` reutilizando la URI de FileProvider + `EXTRA_TEXT` de la ruta | RF-14 |
| `filemanager/res/values*/strings.xml` (3 locales) | Textos nuevos listados abajo | RNF-2 |
| `termux-shared/…/TermuxConstants.java` | En `TERMUX_ACTIVITY`: `ACTION_OPEN_FILE_MANAGER`, `EXTRA_OPEN_FILE_MANAGER_PATH` | RF-16 |
| `app/…/compose/TermuxViewModel.kt` | `createFileManagerSession(baseName, initialPath: String? = null)`; `TermuxSessionUiModel.FileManager.initialPath` | RF-10, RF-16 |
| `app/…/compose/FileManagerSessionHost.kt` | Pasa `initialPath` al factory del ViewModel; callbacks `onNewFileManagerTab` y `onCreateShortcut`; caja del `onNavigateUp` | RF-10, RF-16, RF-4 |
| `app/…/compose/TermuxMainScreen.kt` | Cablea los dos callbacks nuevos hacia la activity | RF-10, RF-16 |
| `app/…/TermuxComposeActivity.kt` | `handleIntent`: rama `ACTION_OPEN_FILE_MANAGER` (extra ruta opcional; consume el action); llamada también en `onCreate` para arranque frío del atajo | RF-16 |

## Funciones puras (con «hoy» como parámetro)

```kotlin
// BreadcrumbPolicy.kt
fun crumbs(path: String, homePath: String?): List<Crumb>
// hoy: la ruta absoluta se dibuja tal cual en la topbar, sin segmentos
// (Root → "Root"; cada ancestro intermedio y el final: "Home" en la raíz
// del debian home y "Home" como primer segmento de sus descendientes;
// resto: nombre del segmento; "/" siempre existe como primer segmento)

fun counts(files: List<File>, showHidden: Boolean): Counts  // folders + files
// hoy: no existe; subtítulo nuevo
```

```java
// FileSortOption.java — hoy dirs SIEMPRE primero cableado en cada opción
Comparator<File> getComparator(boolean ascending, boolean foldersFirst);
// foldersFirst=false: los directorios comparan por su criterio normal
// (name/size/date/type) y pueden quedar entre los archivos
```

## Algoritmo en pseudocódigo

### RF-2: breadcrumb
```
crumbs(path, homePath):
  p = path normalizado (sin "/" final, salvo "/")
  if p == "/"  -> [ Crumb("Root", "/") ]
  segs = ancestros en orden de "/" a p (names entre barras)
  for cada ancestro a:
    label = (a == "/") ? "Root" : (a == homePath) ? "Home" : nombre(a)
  # la primera vez que homePath aparece, el resto son nombres de segmento
  return la lista completa
```
El renglón pinta `back | forward | crumb0 > crumb1 > … > crumbN(sin click)`,
`horizontalScroll` con la cola visible (auto-scroll al final al entrar).

### RF-8: ajustes por carpeta
```
al navegar a dir:
  if prefs existe claves "folder:<path>:_*": aplicar view/sort/foldersFirst de ahí
  else: valores globales
cambios del menú Organizar:
  if folderScoped: guardar en claves "folder:<path>:_*"
  else: guardar globales + limpiar el override de la carpeta
activar «Solo esta carpeta»: snapshot de los valores vigentes dentro del override
desactivar: borrar override y aplicar globales
```
Persistencia: `SharedPreferences("file_manager")` igual que hoy (sort_option,
sort_ascending, show_hidden ya viven ahí): + `view_mode`, `folders_first`,
`folder_scoped` y por ruta `folder:<path>:_sort`, `_asc`, `_ff`, `_view`.

### RF-16: atajo del launcher
```
Crear acceso directo (app, vía callback):
  shortcutId = "fm_" + hash(path)
  intent = Intent(ctx, TermuxComposeActivity).setAction(ACTION_OPEN_FILE_MANAGER)
           .putExtra(EXTRA_OPEN_FILE_MANAGER_PATH, path)
  requestPinShortcut(ShortcutInfoCompat(id, label=name amable, icon, intent))
arranque (frío o tibio):
  handleIntent: if action == ACTION_OPEN_FILE_MANAGER ->
    path = extra ?: null
    mViewModel.createFileManagerSession(baseName, path)   # no requiere servicio
    intent.action = null    # consumir, como ACTION_RUN ya hace
```

### RF-11/12: subir e ir a
```
Subir -> viewModel.goUp() (ya existe; empuja atrás, limpia adelante); el
  elemento queda deshabilitado cuando no hay carpeta padre (canGoUp va en state,
  calculado en applyListing; no es un valor nuevo que controlar a mano)
Ir a… -> texto t: f = si es absoluta File(t) si no File(currentDir, t)
  f.isDirectory ? onEnsureStorageAccess(f){ viewModel.navigateTo(f) }
                : statusMessage de carpeta no legible (aviso existente) y no navega
```

## Interfaz (firmas relevantes)

- `FileManagerScreen(viewModel, onOpenFile, onShareFiles, onEnsureStorageAccess,
  onNewFileManagerTab: (() -> Unit)?, onCreateShortcut: ((File) -> Unit)?, …)` —
  **quita** `onNavigateUp` (el back de hardware lo sigue llevando el host).
- `FileManagerSessionHost(sessionId, onCloseSession, onOpenInTerminal, onEditFile,
  initialPath: String? = null, isActivePane, onActivatePane, onNewFileManagerTab,
  onCreateShortcut, modifier)`.
- `ViewModel`: `fun setViewMode(m)`, `fun setFoldersFirst(b)`, `fun toggleSort(o)`,
  `fun toggleSortAscending()`, `fun toggleFolderScope()`, `fun toggleFolderScoped()`,
  `fun addFavorite()`, `fun removeFavorite(path)`, `fun goTo(text: String)`.

## Decisiones (con alternativa descartada)

1. **Per-carpeta en SharedPreferences por ruta** (`folder:<path>:_*`). Descartadas
   una BD (Room/SQLite exige migraciones) y un JSON en `cacheDir` (el cache se
   limpia y se pierde el ajuste); prefs ya es la infraestructura del sort global.
2. **Favoritos como `StringSet` desordenado.** Descartada lista delimitada: el
   orden del cajon no es requisito (RF-5 no fija orden) y `StringSet` evita fugas
   con rutas que contienen separadores.
3. **El atajo vive en la app, la biblioteca solo expone callback.** Descartado
   `ShortcutManagerCompat` dentro de `filemanager`: obligaría a la biblioteca a
   conocer la class de la actividad del host (rompe RNF-5).
4. **Grid con `LazyVerticalGrid(GridCells.Adaptive(minSize = 112.dp))`.** Descartadas
   2 columnas fijas (mal en tablets) y `LazyGridCells.Fixed(3)` (idem); el mosaico
   repite la misma detección de iconos de la fila.
5. **El breadcrumb auto-scroll a la cola** con un `LaunchedEffect(currentPath)` que
   hace `scrollBy` el desbordamiento real (o `BringIntoView` del último crumb al
   entrar); sin animación de lista (no es lista perezosa, es un `Row` scrolleable).
6. **El icono de menú de la topbar abre `ModalNavigationDrawer` envolviendo el
   `Scaffold`** — descartado `PersistentNavigationDrawer` (come ancho en tablet y
   el gestor es una pestaña), descartado reusar el diálogo BOOKMARKS (lo sustituye).
7. **Recuento va en el UiState, no en la composición:** única fuente para el
   subtítulo y todo se recalcula en `applyListing` (RF-18 y validez si cambia `showHidden`).
8. Los nombres visibles del orden se siguen obteniendo de
   `FileSortOption.getDisplayName()` (deuda existente, no añadimos literales
   nuevos; crear los nuestros duplica el texto).

## Estrategia de tests (`./gradlew test`)

- `BreadcrumbPolicyTest.kt` (JUnit, sin Android): `/`, home exacto, home/hijo,
  `/sdcard`, `/storage/emulated/0`, ruta profunda, cadena vacía, sobra barras.
- `ListingCountsTest.kt` (JUnit): visibles/ocultos mixtos, todo oculto,
  lista vacía, symlink a dir cuenta como carpeta (isDirectory).
- `FileSortOptionFoldersFirstTest.java` (JUnit): subida/bajada por nombre/tamaño/fecha
  con carpetas primero on/off; carpetas y archivos mezclados; orden estable.
- Compose no va bajo test (coherente con el repo: no existe runtime Robolectric en
  CI; la pantalla se valida en dispositivo).

## Strings nuevos (los tres locales, EN base)

`filemanager_counts` (“%1$d folders · %2$d files”), `action_organize`, `view_list`,
`view_grid`, `sort_ascending`, `folders_first`, `only_this_folder`,
`action_new_tab`, `action_go_to`, `action_refresh`, `action_share_folder`,
`action_add_favorite`, `action_remove_favorite`, `action_create_shortcut`,
`msg_shortcut_not_supported`, `title_section_view`, `title_section_sort`,
`filemanager_favorites`, `filemanager_go_to_title` (título del diálogo Ir a),
`filemanager_go_to_hint`. Los textos exactos se pulen al volcar a cada locale sin
duplicar keys presentes.
