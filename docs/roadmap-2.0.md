# Plan de trabajo — Terminal 2.0

> Objetivo: arrancar la 2.0 sin romper la capacidad de **dogfooding** (compilar la app dentro de la propia app). Mitigación acordada: **backup instalable funcional** (APK v1.119.0 guardado + segundo dispositivo como rescatador).

Fecha: septiembre 2026 · Base: commit `f8ce27e2` (v1.119.0) · Rama de trabajo: `release/2.0` (último: `8b40ef17`)

---

## 1. Flujo de trabajo seguro en git

- **`master` = única fuente de builds estables.** Primero se crea un *punto de restauración*: tag `v1.119.0-dogfood` (commit `f8ce27e2`) y se guarda su APK firmado de debug como respaldo inmutable.
- **Ramas por feature:** `feature/2.0-<nombre>` (glob `feature/**`). La CI ya corre tests en PRs a `master` (`run_tests.yml`) — se aprovecha tal cual.
- **Checklist de integración (antes de mergear a `master`):**
  1. `./gradlew test` en CI ✓
  2. `./gradlew assembleDebug` local ✓
  3. Instalar el APK en el dispositivo principal y probar **solo esa feature** ✓
  4. Si toca rendering/sesión, verificar que el flujo de compilación dentro de la app sigue OK ✓
- **Convención:** un commit por feature con Conventional Commits (`Added:`, `Fixed:`, ...). Features atómicas (nada de mega-branches meses sin mergear).
- **Recovery si `master` se llegara a romper:** `git checkout v1.119.0-dogfood && git checkout -b hotfix/restore` y recompilar desde ahí. `master` nunca se reescribe con `reset`/`force-push`.

## 2. Backup instalable funcional (mitigación)

- **Inmediato:** descargar el APK v1.119.0 (de la Release de GitHub o de `assembleDebug`) y guardarlo en el segundo dispositivo **y** en un sitio fuera de ambos (PC/Drive). El segundo dispositivo ya tiene la app instalada = respaldo que no compila pero sí abre terminal.
- **Checkpoint por feature:** script `scripts/checkpoint.sh` (o task de Gradle) que hace `git tag` + `assembleDebug` + copia `terminal_<version>+checkpoint.apk` a carpeta compartida. Permite volver a cualquier punto probado.
- **Toolchain en el segundo dispositivo (paso pendiente, bajo riesgo):** script bootstrap que instale en él JDK 17, Android SDK + NDK `30.0.14904198` y el wrapper de Gradle. Así, si el principal se queda muerto a mitad de una feature, el segundo compila el último tag checkpoint.
- **Regla de oro:** nunca mergear una feature de rendering sin un **feature-flag** (ver Fase 3).

## 3. Roadmap 2.0 (ordenado por riesgo)

### Fase 0 — Cimientos (hacer antes que nada)

- Punto de restauración `v1.119.0-dogfood` + APK guardado + script de checkpoint.
- Framework de **feature-flags** para features de alto riesgo (si no existe: validar en `TermuxViews`/`Settings`).
- Decidir la estrategia de ramas: `master` con bugs-solo + rama `release/2.0` acumulando features (recomendado) **o** features mergeadas directo a `master` con el checklist.

### Fase 1 — Quick wins de bajo riesgo (aisladas, `Added:`) — ítems 1–3 ✅ · 8–10 ✅

1. **Mostrar licencia de proot y Debian** — ✅ hecho (`d78ff9ba`): entradas GPLv2/PRoot y Debian en `LicensesScreen.kt`, strings EN/ES. Proot es GPL-2.0+ y Debian enlaza a `debian.org/legal/licenses/` (las carpetas `assets/` solo contienen binarios, no textos de licencia).
2. **Copiar ruta de archivos/carpetas** — ✅ hecho (`1155a17e`): botón "Copiar ruta" en el diálogo de detalles y en la barra de selección múltiple (modo multi-selección), strings EN/ES/PT.
3. **Colores verdaderos 24-bit** — ✅ hecho (`43648445`): toggle "Esquema de colores personalizado" en Ajustes. `TerminalColorSchemeLoader` lee `~/.termux/colors.properties` (claves `foreground/background/cursor/color0..15` con `#RRGGBB`) reutilizando `TerminalColorScheme` del emulador; `TerminalPalette` acomoda un `scheme` completo de 259 colores y `applyPalette` lo copia al emulador. Recarga automática al volver de Ajustes. El emulador ya soportaba SGR `38;2;r;g;b`/`48;2;...`.
8. **Restaurar informes de crash al reabrir la app** — ✅ hecho: `TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG)` en `onResume()` y `ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false)` en `onCreate` de `TermuxComposeActivity` (paridad con el `TermuxActivity` borrado en `a0da79f3`). El pref `crash_report_notifications_enabled` se respeta dentro de `TermuxCrashUtils` (doble check: cached + file). Sin UI nueva.
9. **Pintar el prompt `root@localhost:~#`** — ✅ hecho: constantes `DEBIAN_PS1_PROFILE_RELATIVE_PATH` (`etc/profile.d/10-termux-ps1.sh`) + `DEBIAN_PS1_SHELL_SCRIPT` (PS1 256-color `\u@\h:\w\$` con `\[`/`\]`) en `TermuxConstants`; escrito por `DebianInstaller.writePs1Config()` desde `writePostInstallConfig()` (install fresco) y `repairInstalledRootfsPermissions()` (existe → reescribe en la próxima pestaña, patrón welcome). Test `testPs1Constants_pathAndScript_consistent` en `DebianInstallerTest`. Verificado contra el rootfs OCI: `/etc/profile` carga `bash.bashrc` (PS1 plano) **y después** `profile.d/*.sh`; `/root/.bashrc` tiene el PS1 comentado → el script gana.
10. **Reducir warnings** — ✅ hecho: 0 warnings Kotlin (antes 9: deprecaciones M3 `ScrollableTabRow`/`Slider`/`android.R.string.yes|no`, override sin `@Deprecated`, `!!` innecesario); fixes de lint accionables (`new Handler()`, `FLAG_IMMUTABLE` en PendingIntents, `DefaultLocale`, `ExtraTranslation` de `termux_keyboard_header`, `tools:ignore`/label redundantes, `AutoboxingStateCreation`, `UseKtx` `toUri`, `NewApi` de `java.nio` en filemanager con `@SuppressLint` documentado). **No** se añade lint al gate de CI (sigue siendo solo `./gradlew test`).

### Fase 2 — UI Compose (medio riesgo) — ítems 4–5 ✅ · 11–12 ✅

4. **Multipaneleo nativo (tiling)** — ✅ hecho (`9f6e7363`): dos paneles simultáneos de cualquier sesión (terminal o file manager) en pantallas ≥ `600dp`. Se descartó `ListDetailPaneScaffold`/`material3-adaptive` (el BOM no mapeaba `androidx.compose.material3:material3-adaptive:`) en favor de un `Row` + `Box`/`onSizeChanged` — el `onSizeChanged` evita el crash `performMeasureAndLayout called during measure layout` que provocaba `BoxWithConstraints` al eliminar un `TerminalView` dentro de su medida. Modelo: `SplitState(paneOneId, paneTwoId)` fija los paneles por posición y `activeSessionIndex` solo marca **foco** — tocar un panel lo enfoca sin moverlo, una sesión tercera vía tabs reemplaza la sesión del panel enfocado, y cerrar/rotar sanea el split. El botón de split se oculta sin espacio salvo cuando hay un split activo (para poder cerrarlo). Extra keys y back se enrutan al panel enfocado (`isActivePane`), y `TerminalViewRegistry` enruta los updates por sesión compuesta (vista enfocada + vista secundaria). Se corrigieron además el doble-swap por pérdida de foco y la re-adjunción de sesión al cambiar de tab. Tests unitarios de la lógica en `TermuxViewModelSplitTest`.
5. **Fuentes y ligaduras tipográficas** — ✅ hecho (`8b40ef17`): selector de fuente monospace en Ajustes (predeterminada, Fira Code, Cascadia Code, JetBrains Mono, D2 Coding, Hack, y `~/.termux/font.ttf` si existe) con toggle de ligaduras. Las 5 fuentes empaquetadas en `app/src/main/assets/fonts/` se actualizaron a Nerd Fonts v3.5.1 (~15MB): `FiraCodeNerdFontMono-Regular` (upstream v6.2), `HackNerdFontMono-Regular` (v3.003), `JetBrainsMonoNerdFontMono-Regular` (v2.304, antes v1.0.3 sin Nerd), `CascadiaMonoNF-Regular` (v2407.024, antes Code PL v2009.022) y D2Coding `v1.3.3-20260725` (antes v1.3.2); `.txt` de atribución actualizados. `TerminalFontLoader` resuelve el id → `Typeface` con fallback a `Typeface.MONOSPACE`; `TerminalRenderer` recién creado al cambiar tipoletra/tamaño y `mEnableLigatures` fuerza una ruptura de run por code point para inhibir ligaduras cuando está apagado (Android no permite desactivar GSUB `liga` en un `Paint`; hay que shapear cada code point aparte). El `TerminalView.setTypeface()` que estaba muerto ahora se alimenta vía `TermuxComposeActivity` (revisión `mFontRevision` en resume/reload-styling) → `TermuxMainScreen` → `SessionPane` → `TerminalViewHost`. El ítem "Estilo" del menú "Más" ya no lanza la app compañera `Terminal:Styling`: ahora abre un diálogo de fuente del terminal equivalente al de Ajustes (`showTerminalFontDialog`), y se eliminaron todas las referencias de código a `termux-styling` (constantes, strings, `TermuxStylingAppSharedPreferences`, placeholder del manifest); quedan solo la atribución histórica (changelog del javadoc, licencias de las fuentes) y el mecanismo genérico de recarga `ACTION_RELOAD_STYLE`. **Importar fuente del almacenamiento:** `TerminalFontImporter` copia el .ttf/.otf elegido con el picker SAF (`OpenDocument`, sin permisos) a `~/.termux/font.ttf` con escritura atómica, tope 50MiB y validación de magia sfnt + `Typeface.createFromFile`; entrada en Ajustes (tile "Importar…") y botón neutral en el diálogo del menú Más, strings EN/ES, tests en `TerminalFontImporterTest`. **Toda la app espeja la fuente del terminal:** `TermuxExpressiveTheme(terminalTypeface)` aplica `FontFamily(typeface)` a los 15 estilos M3; lo pasan `TermuxComposeActivity` (cubre instalador Debian), `SettingsComposeActivity` y `FileManagerComposeActivity` (con revisión en `onResume`). Tests unitarios del catálogo en `TerminalFontCatalogTest`.

11. **Menú "Más" de la terminal en MD3 Expressive** — ✅ hecho (`4db8bacf`): el `ContextMenu` legado (`onCreateContextMenu`/`onContextItemSelected`) se reemplaza por `TerminalMoreMenu` (`ModalBottomSheet` + `DropdownMenuItem` bajo `MaterialExpressiveTheme`) con paridad 1:1 de ítems y condiciones (share selected text, autofill, kill-process enable/check, keep-screen-on check). Entradas unificadas vía `TerminalViewClient.onShowMoreMenu()` (toolbar de selección, right-click del ratón y `MoreVert` de la top bar — este último solo cuando la pestaña activa es terminal); los hosts ya no hacen `registerForContextMenu`. El activity conserva `showMoreMenu`/`dismissMoreMenu`/`onMoreMenuAction` y captura el texto seleccionado **antes** de cerrar la hoja (share selected text).
12. **Filemanager — Editar en la terminal** — ✅ hecho: acción «Editar» en la barra de selección (exactamente 1 archivo) y en el diálogo de detalles, con `onEditFile: ((File) -> Unit)?` en `FileManagerScreen` (solo archivos, vía `resolveForOpen`) + strings EN/ES/PT (`action_edit`, `filemanager_no_editor`, `filemanager_choose_editor`, `filemanager_unmappable_path`). Cableado `FileManagerSessionHost` → `TermuxMainScreen` → `TermuxComposeActivity.openFileInEditor(hostPath)`: guards MAX_SESSIONS + rootfs instalado + `isHostPathMappable` (prefijo exacto rootfs/`/sdcard`/`/storage`, rechaza `/sdcardfoo`) + probe de `TermuxConstants.DEBIAN_EDITOR_CANDIDATES` en `usr/bin` con `canExecute()`; 0 → diálogo con hint `apt install nano`, 1 → lanza, N → chooser. Sesión: `createTermuxSession(null, arrayOf("-c", "<editor> '<guest>'; exec bash"), …)` (escape `'` → `'\''`, `; exec bash` tras salir del editor); nombre de pestaña `editor: nombre`. **No** se usa RUN_COMMAND/`ACTION_SERVICE_EXECUTE`. `FileManagerComposeActivity` legado deja `onEditFile = null`. Cierra la promesa de fastlane *"Edit files with nano and vim"*.

### Fase 2.5 — Rendimiento de plataforma (medio riesgo) ✅

13. **Tasa de refresco máxima (90/120Hz)** — ✅ hecho (`9813d0c8`): `TermuxComposeActivity.applyPreferredRefreshRate()` elige en `Display.getSupportedModes()` el modo de **mayor `refreshRate` a la resolución actual** (nunca cambia la resolución — el texto sigue nítido), aplica `window.attributes.preferredDisplayModeId` + `preferredRefreshRate` en `onCreate` y `onResume`, con fallback silencioso (`try/catch` + `Logger`). Candado de batería: tile «Forzar 60 Hz (batería)» en Ajustes (pref `force_60hz`, strings EN/ES) que selecciona el modo más cercano a 60 Hz. Hardware acceleration ya estaba ON por defecto (sin `android:hardwareAccelerated` en el manifest).
14. **Beneficio del hardware por dispositivo** — ✅ hecho (`b2756579`): `DeviceTierResolver` (Java puro en `termux-shared`) clasifica el dispositivo en `LOW`/`MEDIUM`/`HIGH` por RAM total (`ActivityManager`), cores y `Build.HARDWARE`/`PRODUCT` (emulador → `LOW`). `HardwareDefaultsSeeder` corre en `TermuxApplication.onCreate` **antes de cualquier escritura de prefs** y solo siema fresh (prefs vacías + rootfs no instalado + sin `termux.properties`; las instalaciones existentes solo reciben el marker `hardware_defaults_seeded` → **cero cambio de comportamiento**). Seeds por tier: scrollback 1000/2000/5000, blink 0/0/500 ms y `native_compose_renderer` false/false/**true** (medio = defaults actuales). Los seeds de `termux.properties` se inyectan en `TermuxSharedProperties.preProcessPropertiesOnReadFromDisk` solo si la clave está ausente del archivo del usuario (cadena: archivo → seed → constante); el archivo jamás se escribe. Tests `DeviceTierResolverTest` + `HardwareDefaultsSeederTest`. **Límites de listas del filemanager — descartado (decisión registrada):** LazyColumn con `key` ya virtualiza el render y el cuello de botella es `listFiles()` del sistema; un cap artificial ocultaría archivos al usuario.
15. **btop: `Failed to parse /proc/stat` — limitación documentada (sin código)** — ✅ hecho (`e3237e1c`): nota añadida en `README.md` (§ Limitaciones conocidas) y `docs/proot-debian-arm64-plan.md` (§ 7): desde Android 8/API 26 SELinux deniega a `untrusted_app` leer `/proc/stat`, los monitores CPU (`btop`, `htop`) no muestran stats en stock enforcing, el bind `-b /proc` está presente y correcto (`ProotShellEnvironment.buildProotCommand()`); sin workaround sin root/SELinux permissive.

### Fase 3 — Rendering del terminal (alto riesgo, el corazón del 2.0)

6. **Terminal nativa pintada con Compose** — spike operativo (commit `cee30ff5`): `ComposeTerminalCanvas` pinta la sesión con un `Canvas` Compose en lugar de la vista legada, con la lógica pura de runs en `ComposeTerminalFrame` (agrupación por estilo, reverse video, cursor block/underline/bar, caracteres anchos y combining, escala de glifos no-mono). Detrás del **feature-flag** `native_compose_renderer` (Ajustes → Experimental) con la vista legada como fallback, tal como exige el roadmap. El input vive en `HiddenTerminalInputHost` (vista oculta full-size que posee IME/teclas/foco; el offset de scroll lo posee el canvas), ambos con la misma geometría (`updateSize`). Gestos con paridad `TerminalView.doScroll`: swipe abajo = volver (transcript), swipe arriba = live; en alt-buffer manda flechas UP/DOWN; con **mouse-tracking activo** (TUIs como opencode) manda eventos de rueda anclados a la posición del dedo. **Iteración blink:** el cursor parpadea cuando la propiedad `terminal-cursor-blink-rate` es válida, solo en el pane activo (`LaunchedEffect` + fase propia del canvas; el blinker de la vista oculta queda inerte en rate 0). **Iteración ratón físico (SOURCE_MOUSE):** rueda → `doScroll` (±3/fila), botón izq → `sendMouseEvent` en mouse-tracking (drag = transcript sin tracking), medio → pegar portapapeles, clic → foco/teclado; todo consumido para no interferir con el touch. **Iteración selección/gestos (commit `11ef4864`):** selección de texto por long-press (expansión a palabra), handles draggables con clamping y scroll en el borde, toolbar flotante Copiar/Pegar/Más, pinch-zoom (±2px por paso, igual que el legacy) y fling con inercia (`Scroller.fling` × 0.25). **Cierre de brechas de paridad:** grid mínimo 4×4 (`Math.max(4, ...)` de `updateSize`), auto-scroll a live con salida nueva (paridad `onScreenUpdated`: snap a `mTopRow = 0` salvo selección activa o auto-scroll deshabilitado), flechas UP/DOWN en alt-buffer respetando cursor/keypad application mode (DECCKM/DECKPAM), vetos de long-press (escala en curso + consumido por el client), cursor visible al instante tras input (blink reset), botón Pegar deshabilitado sin clip en el portapapeles y guardia de 300 ms al cancelar la selección con un tap. Tests de la lógica en `ComposeTerminalFrameTest` (Kotlin + Java), `TermuxViewModelSplitTest` y `TerminalKeyHandlerTest`. **Paridad de selección (cierre):** una tecla hardware cierra el modo de selección como el legacy `onKeyDown` (hook `onUserKeyInput` del `HiddenTerminalInputHost`, con BACK delegado al `BackHandler`), la toolbar flotante se oculta mientras se arrastra un handle y reaparece al soltarlo (`updateFloatingToolbarVisibility`) y el pane notifica `viewClient.copyModeChanged` al iniciar/cerrar la selección y al descomponerse (paridad `start/stopTextSelectionMode` y `onDetachedFromWindow`). **Cierre del fantasma de scroll:** el canvas nativo rellena el fondo por defecto en cada frame (las celdas de fondo por defecto no se pintaban por run y dejaban ver la capa oculta `HiddenTerminalInputHost` en `mTopRow=0` como una copia estática del texto mientras el canvas scrolleaba), el `TerminalView` oculto pasa a `alpha = 0` (su único aporte era el color de fondo, que ahora pinta el canvas) y el pane pone `palette.background` de respaldo para los frames iniciales. **Paridad de gotas (handles):** cada gota se dibuja desplazada por el hotspot legacy (`0.75W` inicio / `0.25W` fin, `mHotspotX`) para que la punta caiga exacto en la esquina inferior de su celda ancla; el arrastre agarra en el touch-down de cero-slop (consume el `DOWN`, sigue el dedo hasta `UP/CANCEL` sin cancelarse al cruzar celdas — teclas estables + lecturas vía `rememberUpdatedState` — y un tap sobre la gota no limpia la selección); el drawable vuelve a voltearse cerca de los bordes de pantalla (`checkChangedOrientation` con throttle de 50 ms durante el drag y chequeo forzado al mostrarse, paridad `show()` con force=true).
7. **Hipervínculos (OSC 8) e imágenes** — toca `terminal-emulator` (protocolo) y el view (render). Necesita los tests del emulador (`./gradlew test`) verdes antes de mergear.

### Fase 3.6 — Paridad legacy vs nativa (cierre de brechas) ✅

Auditoría `TerminalView` (legacy) vs `ComposeTerminalCanvas` + hosts (nativa). Todo se arregla en `app` (más pruebas); el input ya está a paridad porque el host oculta un `TerminalView` real.

**P0 (crash) — Fixed**
1. Middle-click paste con portapapeles vacío: guard null/empty antes de `paste` (paridad `TerminalView.onTouchEvent` BUTTON_TERTIARY); `TerminalEmulator.paste` no tolera null.

**P1 (estado visible) — Fixed**
2. BACK con selección: el pane activo registra hooks en `TerminalViewRegistry`; `shouldBackButtonBeMappedToEscape` fuerza la rama de escape para que `onKeyDown` del client limpie la selección y consuma BACK sin escribir ESC (paridad `onKeyPreIme`). El key-up pareado también se consume.
3. IME de software limpia la selección en `onCodePoint` → `dismissActivePaneSelection` (paridad `sendTextToTerminal` → `stopTextSelectionMode`); el comentario del host que decía que el IME no lo hacía era incorrecto.
4. Blink del cursor: el override del canvas hace AND con `emulator.isCursorEnabled` (respeta `?25l`); la fase solo se resetea en input (`blinkResetTick`), no en cada frame de output.
5. `terminal-cursor-blink-rate` en la ruta legacy: `TerminalViewHost` llama `setTerminalCursorBlinkerRate` al montar (antes el blinker se quedaba en rate 0 y nunca parpadeaba al alternar el flag).

**P2 (paridad fina) — Fixed**
6. Snap a live al cambiar el grid en `updateSize` del canvas (`mTopRow = 0` solo si cambió columns/rows).
7. Selección + auto-scroll deshabilitado: `shiftSelectionForNewOutput(..., isAutoScrollDisabled)` ancla a `-transcript` al fin del historial (paridad `onScreenUpdated`).
8. Tap del canvas invoca `onSingleTapUp` del client (`onClientTap`).
9. Salir de touch mode (teclado hardware) cierra la selección (paridad `TextSelectionCursorController.onTouchModeChanged`).
10. Métricas de fuente: `measureCanvasMetrics` y el `Paint` del canvas usan `fontSize.toInt()` igual que `TerminalView.setTextSize(int)` (evita drift si llegara un tamaño fraccional).
11. Scrollbar vertical en el canvas: thumb con la fórmula `activeRows + mTopRow - mRows`, visible solo con scrollback (`topRow < 0`), paridad `computeVerticalScroll*` + `awakenScrollBars`.

**Aceptado / fuera de alcance**
- Right-click → menú legacy (out of scope, ya anotado en el canvas).
- Fling: `exponentialDecay` ≈ `Scroller` (sensación aproximada, documentado).
- Handles de selección: la nativa usa matemática exacta; la legacy tiene offset hard-coded de 40 px — no se “paridad” hacia atrás.

**Pendiente (auditoría en dispositivo / no bloquea 2.0)**
- Accesibilidad/TalkBack (semantics del canvas vs `contentDescription` en la view alpha-0), contrato `onLongPress(event)` con el `MotionEvent` real.
- Tests: branching `useNativeRenderer` sin cobertura de UI; extraer más lógica de composables a `ComposeTerminalFrame` para unit tests. La lógica de abort/pin de selección ya tiene tests (`ComposeTerminalFrameTest`).

### Fase 3.7 — Auditoría de rendimiento Compose (análisis, no bloquea 2.0) ✅

Análisis metodológico del renderer nativo y de la UI Compose; entrega = este informe con hallazgos priorizados (P0 crash/jank → P2 micro-opt). Solo los P0 de jank severo se arreglan en esta fase; P1/P2 quedan post-2.0.

**P0 — Fixed (jank severo)**

1. **I/O de disco en `setContent` sin `remember`** — `TerminalFontLoader.resolve` + `TerminalColorSchemeLoader.load()` se re-ejecutaban en cada recomposición del scope raíz (gatillada por `mFontRevision`/`mPaletteRevision` en `onResume` y por estado leído en ese scope, p.ej. `mMoreMenuState`). Fix: `remember(mFontRevision, fontId)` / `remember(mPaletteRevision, useCustomColorScheme)` en `TermuxComposeActivity`, `SettingsComposeActivity` y `FileManagerComposeActivity`. El bump de revisión sigue forzando recarga tras Ajustes; recomposiciones ajenas no re-leen assets ni `colors.properties`.
2. **Sort + symlink scan en `Dispatchers.Main`** — `FileManagerViewModel.refresh()` filtraba, ordenaba (`FileSortOption.getComparator` → stats por comparación) y escaneaba `readSymlinkTargetRaw`/`isBrokenSymlink` dentro de `withContext(Main)` tras `listFiles`. Fix: pipeline completo (filter → sort → scan → publish) en `Dispatchers.IO` vía `applyListing()`; en Main solo `_uiState.update`.
3. **`setSearchQuery` re-listeaba el directorio por tecla** — cada keystroke llamaba `refresh()` → `listFiles` completo. Fix: cache `lastListed: Array<File>?`/`lastListedDir`; `setSearchQuery`/`toggleSort`/`toggleHidden` usan `reapplyCachedListing()` (re-filtro en memoria sobre IO, sin enumerar). El cache se invalida al iniciar un `refresh()` full (mutaciones de archivo, navegación) para no servir listings previos a una mutación; si no hay cache válida se cae a `refresh()`. Sin debounce (el filter en IO por tecla es barato).

**P1 — Pendiente (post-2.0)**

4. **Stats en bodies de composable (filemanager)** — `file.length()`/`file.isDirectory` en filas del `LazyColumn` (`FileManagerScreen.kt` ~477/456), `bookmarkDirs()` re-alloca lista+Pairs por recomposición del diálogo (~706), DETAILS `f.length()` en composition (~600). Mover a state ya resuelto en el ViewModel o `remember`.
5. **`object : ExtraKeysCallback` nuevo por recomposición** — `TermuxMainScreen.kt` ~358: identidad anónima nueva cada recomposición → el slot de `ExtraKeysBar` nunca es skippable. Extraer a `remember`/`rememberUpdatedState`.
6. **Sin `@Stable`/`@Immutable` ni `derivedStateOf`** — `TermuxUiState`/`List`/`TerminalSession`/`ExtraKeysConfig` inestables; cero `derivedStateOf` en todo el repo. Strong skipping está ON por default (Kotlin/Compose Compiler 2.4.x, sin bloque `composeCompiler` explícito); fui huecos puntuales (puntos 4–5, state monolítico).
7. **UI state monolítico del filemanager** — un solo `FileManagerUiState.collectAsState` recompose la pantalla entera ante cualquier cambio (`busy`, `focusedIndex`, status…). Considerar selectors o estado particionado.
8. **`licenses()` reconstruido 3×** — `LicensesScreen.kt` ~108/116 rehace la lista en el mismo loop. `remember`/`val` único.
9. **`crash_log.md`/prefs en `onResume`** — `TermuxCrashUtils.notifyAppCrashFromCrashLogFile` en cada resume (lifecycle, no frame; ya en background thread con pref cached). Baja prioridad; no es hot path de dibujo.
10. **Ktor** — declarado en `gradle/libs.versions.toml` pero **sin** `implementation` en ningún `*.kts` ni imports: catálogo muerto. No hay fetch de releases/issues → no hay cache HTTP que añadir hoy. Si se cablea Ktor, diseñar cache desde el inicio. *(Errata del item 17 original: "Ktor ya en el stack" era incorrecto.)*
11. **Imágenes** — el item 17 decía "no hay imágenes en UI": **falso**. `AboutScreen` usa Coil 3 `AsyncImage` (`coil-compose` + okhttp). Coil ya cachea en memoria/disco y decodifica off-main; riesgo residual bajo. *(Errata corregida.)*

**P2 — Pendiente (post-2.0, draw path / micro-opt)**

12. **Asignaciones por frame en el draw path** (confirmado; escala rows×runs×fps):
    - `ComposeTerminalFrame.buildLineRuns()` — `ArrayList<TextRun>` + un `TextRun` por run, por row, por frame (`ComposeTerminalFrame.kt` ~312/352/378).
    - `resolveRunColors()` — `ResolvedRunColors` por run, por frame (~560).
    - `selectionBoundsForRow()` — `Pair<Int,Int>` por row (~102–106), consumido en `ComposeTerminalCanvas.kt` ~767.
    - Scrollbar — `Paint()` nuevo cada frame (`ComposeTerminalCanvas.kt` ~668).
    - Lambda `hasWidthMismatch` + `String(Character.toChars(...))` por code point non-ASCII, por row (~768–777).
    - Plan: pools/reutilización de `TextRun`, prealloc del Paint de scrollbar, devolver longs/índices en vez de `Pair`, lambda fuera del loop de rows.
13. **Cero `GraphicsLayer`/`drawWithCache`/`rememberGraphicsLayer`/`clipRect`/`clipPath`** — evaluar capas estáticas (scrollbar, cursor fijo) y clipping hardware para selección/scrollbar.
14. **Overdraw posible** — canvas hace `drawColor` full-frame de fondo (~749) encima del `Box.background` del Surface padre (`TermuxMainScreen.kt` ~518). Medir con Debug GPU overdraw.
15. **Path legacy** (`TerminalView`/`TerminalRenderer`): reutiliza `mTextPaint` + cache `asciiMeasures[127]` — no priorizar.
16. **`CanvasFontMetrics`** es `data class` con `FloatArray` → equals estructural deficiente; puede romper skip si se re-mide. Marcar `@Immutable` o igualdad manual.

**Correcciones a los ítems 16–17 originales**
- Item 17 "LazyColumn + key": **verificado OK** — `FileManagerScreen.kt` ~416 ya usa `key = { _, file -> file.absolutePath }`. El punto real de RAM es el `List<File>` completo de `FileManagerUiState.files` (listado de dir entero en memoria; la UI solo virtualiza el render). Cap artificial de listas ya **descartado** en Fase 2.5 (ítem 14).
- Item 17 "Ktor en el stack": **errata** — solo en version catalog (ver P1.10).
- Item 17 "no hay imágenes": **errata** — Coil en About (ver P1.11).
- Geometría de split: barata (`roundToPx` + comparaciones); `SessionSplitPicker` ya usa `remember(sessions, …)`. No es candidato P0/P1.

**Verificado como ya correcto (no priorizar)**
- Strong skipping default-on (Kotlin 2.4.x); `key` en LazyColumn del filemanager y del split picker; `remember` del Paint principal/métricas/blink en el canvas; `blinkRate` leído una vez con `remember`; `folderSizes` cacheado; prefs solo en init/ops; crash_log solo en resume/broadcast; `rememberUpdatedState` en gestures del overlay; path legacy de asignaciones bajas; tests de runs/colores (`ComposeTerminalFrameTest` + Java) sólidos.

### Fase 4 — Release 2.0

- Bump `versionName` → `2.0.0` y `versionCode` → `120` (el validador semver de `app/build.gradle.kts` ya acepta `2.0.0`).
- Tag `v2.0.0` → la CI ya adjunta APK de debug a la Release automáticamente.
- Verificar migración de datos existentes (rootfs Debian, ajustes) intacta.
18. **Crear clave de release** — hoy solo existe signing de **debug** (`app/testkey_untrusted.jks`, contraseñas en claro en `build.gradle.kts`); `release` está **sin firmar** (`signingConfig` no asignado) y ningún workflow corre `assembleRelease`. Plan: generar keystore fuera del repo (`.signing/` ya está en `.gitignore`), añadir `signingConfigs.release` leyendo `keystore.properties` (no commiteado; plantilla `.example`), cablear `release.signingConfig = signingConfigs.release`. Decidir firma local vs CI (secrets de GitHub) antes del tag `v2.0.0`; si CI, añadir paso de firma a `attach_debug_apks_to_release.yml` o workflow paralelo de release APK. **Nunca commitear el keystore ni las contraseñas.**
19. **Actualizar documentos para 2.0** — `README.md`: añadir features 2.0 (renderer nativo `native_compose_renderer`, multipane, fuentes/ligaduras, colores 24-bit, importar fuente, editar con nano/vim) y versión objetivo; crear `CHANGELOG.md` ligado al flujo de releases de CI; rellenar o eliminar stub `docs/en/index.md` (scaffolding basura de termux.dev); renombrar/archivar `120.md` (plan de 1.119.0 con nombre de versionCode 120 = 2.0.0, colisiona); corregir header desactualizado de `docs/proot-debian-arm64-plan.md` ("Próximo hito: Fase 2" vs fases completadas); actualizar tabla de Estado de este archivo al tag `v2.0.0`. Mantener attribution Termux (requerido).

**Descartado (decisión registrada):** `applicationIdSuffix ".debug"` + `resValue "string" "app_name"` para builds debug — cambiaría el id de paquete de los builds con los que se dogfoodea, rompería los paths hardcodeados de `TermuxConstants` (`/data/data/com.estrin217.terminal/...`) y el APK de respaldo v1.119.0. Se opta por no tocar `applicationId` ni `app_name` por ahora.

---

## 4. Estado

| Fase | Estado |
|---|---|
| Fase 0 — Cimientos | Completada |
| Fase 1 — Quick wins | Completada (ítems 1–3 y 8–10) |
| Fase 2 — UI Compose | Completada (ítems 4–5, 11 menú "Más" terminal y 12 editar en filemanager) |
| Fase 2.5 — Rendimiento de plataforma | Completada (13–15) |
| Fase 3 — Rendering | Completada (canvas Compose nativo tras el flag; paridad principal cerrada — ver Fase 3.6 para brechas restantes) |
| Fase 3.7 — Auditoría perf Compose | Completada (informe 16–17 + fixes P0: remember font/palette, IO filemanager, cache de listado) |
| Fase 4 — Release 2.0 | Pendiente (incluye 18 clave release, 19 docs) |
