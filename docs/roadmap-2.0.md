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

### Fase 1 — Quick wins de bajo riesgo (aisladas, `Added:`) ✅

1. **Mostrar licencia de proot y Debian** — ✅ hecho (`d78ff9ba`): entradas GPLv2/PRoot y Debian en `LicensesScreen.kt`, strings EN/ES. Proot es GPL-2.0+ y Debian enlaza a `debian.org/legal/licenses/` (las carpetas `assets/` solo contienen binarios, no textos de licencia).
2. **Copiar ruta de archivos/carpetas** — ✅ hecho (`1155a17e`): botón "Copiar ruta" en el diálogo de detalles y en la barra de selección múltiple (modo multi-selección), strings EN/ES/PT.
3. **Colores verdaderos 24-bit** — ✅ hecho (`43648445`): toggle "Esquema de colores personalizado" en Ajustes. `TerminalColorSchemeLoader` lee `~/.termux/colors.properties` (claves `foreground/background/cursor/color0..15` con `#RRGGBB`) reutilizando `TerminalColorScheme` del emulador; `TerminalPalette` acomoda un `scheme` completo de 259 colores y `applyPalette` lo copia al emulador. Recarga automática al volver de Ajustes. El emulador ya soportaba SGR `38;2;r;g;b`/`48;2;...`.

### Fase 2 — UI Compose (medio riesgo)

4. **Multipaneleo nativo (tiling)** — ✅ hecho (`9f6e7363`): dos paneles simultáneos de cualquier sesión (terminal o file manager) en pantallas ≥ `600dp`. Se descartó `ListDetailPaneScaffold`/`material3-adaptive` (el BOM no mapeaba `androidx.compose.material3:material3-adaptive:`) en favor de un `Row` + `Box`/`onSizeChanged` — el `onSizeChanged` evita el crash `performMeasureAndLayout called during measure layout` que provocaba `BoxWithConstraints` al eliminar un `TerminalView` dentro de su medida. Modelo: `SplitState(paneOneId, paneTwoId)` fija los paneles por posición y `activeSessionIndex` solo marca **foco** — tocar un panel lo enfoca sin moverlo, una sesión tercera vía tabs reemplaza la sesión del panel enfocado, y cerrar/rotar sanea el split. El botón de split se oculta sin espacio salvo cuando hay un split activo (para poder cerrarlo). Extra keys y back se enrutan al panel enfocado (`isActivePane`), y `TerminalViewRegistry` enruta los updates por sesión compuesta (vista enfocada + vista secundaria). Se corrigieron además el doble-swap por pérdida de foco y la re-adjunción de sesión al cambiar de tab. Tests unitarios de la lógica en `TermuxViewModelSplitTest`.
5. **Fuentes y ligaduras tipográficas** — ✅ hecho (`8b40ef17`): selector de fuente monospace en Ajustes (predeterminada, Fira Code, Cascadia Code, JetBrains Mono, D2 Coding, Hack, y `~/.termux/font.ttf` si existe) con toggle de ligaduras. Las 5 fuentes empaquetadas en `app/src/main/assets/fonts/` se actualizaron a Nerd Fonts v3.5.1 (~15MB): `FiraCodeNerdFontMono-Regular` (upstream v6.2), `HackNerdFontMono-Regular` (v3.003), `JetBrainsMonoNerdFontMono-Regular` (v2.304, antes v1.0.3 sin Nerd), `CascadiaMonoNF-Regular` (v2407.024, antes Code PL v2009.022) y D2Coding `v1.3.3-20260725` (antes v1.3.2); `.txt` de atribución actualizados. `TerminalFontLoader` resuelve el id → `Typeface` con fallback a `Typeface.MONOSPACE`; `TerminalRenderer` recién creado al cambiar tipoletra/tamaño y `mEnableLigatures` fuerza una ruptura de run por code point para inhibir ligaduras cuando está apagado (Android no permite desactivar GSUB `liga` en un `Paint`; hay que shapear cada code point aparte). El `TerminalView.setTypeface()` que estaba muerto ahora se alimenta vía `TermuxComposeActivity` (revisión `mFontRevision` en resume/reload-styling) → `TermuxMainScreen` → `SessionPane` → `TerminalViewHost`. El ítem "Estilo" del menú "Más" ya no lanza la app compañera `Terminal:Styling`: ahora abre un diálogo de fuente del terminal equivalente al de Ajustes (`showTerminalFontDialog`), y se eliminaron todas las referencias de código a `termux-styling` (constantes, strings, `TermuxStylingAppSharedPreferences`, placeholder del manifest); quedan solo la atribución histórica (changelog del javadoc, licencias de las fuentes) y el mecanismo genérico de recarga `ACTION_RELOAD_STYLE`. **Importar fuente del almacenamiento:** `TerminalFontImporter` copia el .ttf/.otf elegido con el picker SAF (`OpenDocument`, sin permisos) a `~/.termux/font.ttf` con escritura atómica, tope 50MiB y validación de magia sfnt + `Typeface.createFromFile`; entrada en Ajustes (tile "Importar…") y botón neutral en el diálogo del menú Más, strings EN/ES, tests en `TerminalFontImporterTest`. **Toda la app espeja la fuente del terminal:** `TermuxExpressiveTheme(terminalTypeface)` aplica `FontFamily(typeface)` a los 15 estilos M3; lo pasan `TermuxComposeActivity` (cubre instalador Debian), `SettingsComposeActivity` y `FileManagerComposeActivity` (con revisión en `onResume`). Tests unitarios del catálogo en `TerminalFontCatalogTest`.

### Fase 3 — Rendering del terminal (alto riesgo, el corazón del 2.0)

6. **Terminal nativa pintada con Compose** — spike operativo (commit `cee30ff5`): `ComposeTerminalCanvas` pinta la sesión con un `Canvas` Compose en lugar de la vista legada, con la lógica pura de runs en `ComposeTerminalFrame` (agrupación por estilo, reverse video, cursor block/underline/bar, caracteres anchos y combining, escala de glifos no-mono). Detrás del **feature-flag** `native_compose_renderer` (Ajustes → Experimental) con la vista legada como fallback, tal como exige el roadmap. El input vive en `HiddenTerminalInputHost` (vista oculta full-size que posee IME/teclas/foco; el offset de scroll lo posee el canvas), ambos con la misma geometría (`updateSize`). Gestos con paridad `TerminalView.doScroll`: swipe abajo = volver (transcript), swipe arriba = live; en alt-buffer manda flechas UP/DOWN; con **mouse-tracking activo** (TUIs como opencode) manda eventos de rueda anclados a la posición del dedo. **Iteración blink:** el cursor parpadea cuando la propiedad `terminal-cursor-blink-rate` es válida, solo en el pane activo (`LaunchedEffect` + fase propia del canvas; el blinker de la vista oculta queda inerte en rate 0). **Iteración ratón físico (SOURCE_MOUSE):** rueda → `doScroll` (±3/fila), botón izq → `sendMouseEvent` en mouse-tracking (drag = transcript sin tracking), medio → pegar portapapeles, clic → foco/teclado; todo consumido para no interferir con el touch. **Iteración selección/gestos (commit `11ef4864`):** selección de texto por long-press (expansión a palabra), handles draggables con clamping y scroll en el borde, toolbar flotante Copiar/Pegar/Más, pinch-zoom (±2px por paso, igual que el legacy) y fling con inercia (`Scroller.fling` × 0.25). **Cierre de brechas de paridad:** grid mínimo 4×4 (`Math.max(4, ...)` de `updateSize`), auto-scroll a live con salida nueva (paridad `onScreenUpdated`: snap a `mTopRow = 0` salvo selección activa o auto-scroll deshabilitado), flechas UP/DOWN en alt-buffer respetando cursor/keypad application mode (DECCKM/DECKPAM), vetos de long-press (escala en curso + consumido por el client), cursor visible al instante tras input (blink reset), botón Pegar deshabilitado sin clip en el portapapeles y guardia de 300 ms al cancelar la selección con un tap. Tests de la lógica en `ComposeTerminalFrameTest` (Kotlin + Java), `TermuxViewModelSplitTest` y `TerminalKeyHandlerTest`.
7. **Hipervínculos (OSC 8) e imágenes** — toca `terminal-emulator` (protocolo) y el view (render). Necesita los tests del emulador (`./gradlew test`) verdes antes de mergear.

### Fase 4 — Release 2.0

- Bump `versionName` → `2.0.0` y `versionCode` → `120` (el validador semver de `app/build.gradle.kts` ya acepta `2.0.0`).
- Tag `v2.0.0` → la CI ya adjunta APK de debug a la Release automáticamente.
- Verificar migración de datos existentes (rootfs Debian, ajustes) intacta.

---

## 4. Estado

| Fase | Estado |
|---|---|
| Fase 0 — Cimientos | Completada |
| Fase 1 — Quick wins | Completada |
| Fase 2 — UI Compose | Completada |
| Fase 3 — Rendering | Completada (canvas Compose nativo tras el flag; paridad cerrada con `TerminalView`) |
| Fase 4 — Release 2.0 | Pendiente |
