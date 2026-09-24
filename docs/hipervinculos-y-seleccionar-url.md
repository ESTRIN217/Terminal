# Hipervínculos (OSC 8) y «Seleccionar URL»

Dos mecanismos independientes para llegar a una URL desde la terminal. Este documento describe cómo funciona cada uno, sus límites y dónde vive el código.

---

## 1. Seleccionar URL (Menú Más)

### Qué es

Una acción del menú «Más» de la terminal que extrae **todas las URLs en texto plano** del transcript y ofrece copiarlas o abrirlas. No depende de OSC 8: funciona con cualquier URL que haya aparecido en pantalla, aunque la app que la imprimió no emita hipervínculos.

### Dónde está

Entradas unificadas vía `TerminalViewClient.onShowMoreMenu()`:

- top-bar (overflow `MoreVert`, solo pestaña terminal)
- toolbar de selección (botón «Más…»)
- right-click del ratón

La hoja es `TerminalMoreMenu` (`ModalBottomSheet` + `DropdownMenuItem` bajo `MaterialExpressiveTheme`). El ítem es `TerminalMoreAction.SELECT_URL` (`TerminalMoreMenu.kt`).

### Flujo

```
Menú Más → SELECT_URL
    → TermuxComposeActivity.showUrlSelection()
        → ShellUtils.getTerminalSessionTranscriptText(session, true, true)
        → TermuxUrlUtils.extractUrls(text)          // LinkedHashSet, orden de aparición
        → urlSet.toTypedArray().reversedArray()     // más reciente primero
        → AlertDialog con la lista
```

- **Tap en la URL** → `ShareUtils.copyTextToClipboard(...)` + toast `msg_select_url_copied_to_clipboard`
- **Long-press en la URL** → `ShareUtils.openUrl(...)` (cierra el diálogo antes)
- **Sin URLs** → diálogo `title_select_url_none_found`

El título del diálogo (`title_select_url_dialog`) explica las dos acciones: *«Click URL to copy or long press to open»*.

### Extracción de URLs

`TermuxUrlUtils.extractUrls(String)` (`termux-shared/.../data/TermuxUrlUtils.java`) barre el texto plano con un patrón de URL y devuelve un `LinkedHashSet<CharSequence>` sin duplicados, en orden de aparición. Cubre esquemas habituales (`http`, `https`, `ftp`, …) sobre el **transcript ya renderizado**, no sobre el estado interno del emulador.

### Strings

| Clave | EN | ES | PT |
|---|---|---|---|
| `action_select_url` | Select URL | Seleccionar URL | Selecionar URL |
| `title_select_url_dialog` | Click URL to copy or long press to open | Toque la URL para copiar o mantenga presionado para abrir | Toque na URL para copiar ou pressione longo para abrir |
| `title_select_url_none_found` | No URL found in the terminal. | No se encontró ninguna URL en el terminal. | Nenhuma URL encontrada no terminal. |
| `msg_select_url_copied_to_clipboard` | URL copied to clipboard | URL copiada al portapapeles | URL copiada para a área de transferência |

### Código

| Pieza | Archivo |
|---|---|
| Ítem del menú | `app/.../compose/TerminalMoreMenu.kt` (`SELECT_URL`) |
| Dispatch | `app/.../app/TermuxComposeActivity.kt` → `onMoreMenuAction` |
| Diálogo | `app/.../app/TermuxComposeActivity.kt` → `showUrlSelection()` |
| Extracción | `termux-shared/.../data/TermuxUrlUtils.java` |
| Tests | `app/src/test/java/com/termux/app/TermuxActivityTest.java` |

---

## 2. Hipervínculos OSC 8

### Qué es

[OSC 8](https://gist.github.com/egmontkob/eb114294efbcd5adb1944c9f3cb5feda) es una secuencia de escape que marca **celdas concretas** como enlace:

```text
ESC ] 8 ; params ; URI BEL    texto    ESC ] 8 ; ; BEL
└──── abre el link ────┘      └─celdas─┘  └── cierra ──┘
```

Ejemplo:

```bash
printf '\e]8;;https://example.com\e\\Haz clic aquí\e]8;;\e\\'
```

- `params` puede llevar `id=…`; en v1 se **ignora** (solo se usa la URI).
- Terminador: `BEL` (`\007`) o `ST` (`ESC \`).
- URI vacía (`ESC ] 8 ; ; BEL`) **cierra** el link activo.

Las apps embebidas (lynx, w3m con soporte, prompts modernos, etc.) emiten esta secuencia para que el emulador subraye el texto y lo abra al tocarlo.

### Almacenamiento en el emulador

| Pieza | Detalle |
|---|---|
| Registry | `TerminalEmulator.mHyperlinkUris` — `ArrayList<String>`, índice **1-based** (0 = «sin link») |
| Side-band | `TerminalRow.mHyperlinkIds` — `int[]` paralelo a `mStyle`, se reserva perezosamente |
| Sellado | `emitCodePoint` → `setChar` + `setHyperlink(column, mCursorRow, id)` |
| Ancho 2 | Un CJK de ancho 2 sella **ambas columnas** |
| Borrado | Sobrescribir la celda (`setChar`) pone el id a 0; `clear()` rellena el array |

Lookup público:

```java
String uri = emulator.getHyperlinkUriAt(row, column); // row external, col 0-based
boolean activo = emulator.isHyperlinkActive();
```

### Render (ambos renderers)

La agrupación en runs rompe cuando cambia el id de hyperlink, igual que rompe por estilo/cursor/selección:

- **Legacy** (`terminal-view/.../TerminalRenderer.java`): el bucle de runs trackea `lastRunHyperlink`; al cambiar, cierra el run y pasa `isHyperlink` a `drawTextRun`, que fuerza `underline`.
- **Compose** (`app/.../compose/ComposeTerminalFrame.kt`): `TextRun.hyperlinkIndex` (0 = no-link); `buildLineRuns` añade `hyperlink != lastHyperlink` a la condición de break; `drawComposeRun` fuerza `paint.isUnderlineText`.

### Tap → abrir

Guards comunes (en este orden):

1. Pref `terminal_hyperlinks` **ON** (kill-switch experimental).
2. **Sin mouse-tracking** (`isMouseTrackingActive()`): las TUIs con mouse-tracking (vim, opencode…) son dueñas del botón 1; el tap se reporta como click de ratón en su lugar.
3. Sin selección de texto activa.
4. URI resuelta en la celda tocada.
5. Scheme en allowlist — `TerminalEmulator.isAllowedHyperlinkUri`:
   - ✅ `http`, `https`, `ftp`, `file`, `gemini`, `mailto`
   - ❌ `javascript:` y cualquier otro esquema exótico

Apertura:

| Renderer | Código |
|---|---|
| Legacy | `TerminalView.onSingleTapUp` → `openHyperlinkAt` → `startActivity(ACTION_VIEW)` |
| Compose | `ComposeTerminalCanvas.onTap` → `ShareUtils.openUrl(context, uri)` |

Si alguno de los guards falla, el tap sigue el camino normal (focus, `onClientTap`, mouse-event al TUI si aplica).

### Pref experimental `terminal_hyperlinks`

Receta de 5 capas, igual que `native_compose_renderer`:

| Capa | Archivo |
|---|---|
| Constantes | `TermuxPreferenceConstants.TERMUX_APP.KEY_TERMINAL_HYPERLINKS = "terminal_hyperlinks"` (default **`true`**) |
| Acceso | `TermuxAppSharedPreferences.isTerminalHyperlinksEnabled()` / `setTerminalHyperlinksEnabled()` |
| State | `SettingsUiState.terminalHyperlinks` ← `SettingsViewModel` |
| UI | `SettingsScreen` → `SettingsSwitchTile` con `[Experimental]` |
| Runtime | `TermuxUiState.hyperlinksEnabled` → `TermuxViewModel.setHyperlinksEnabled` → `TermuxComposeActivity` (`onCreate`/`onResume`) → `TerminalViewHost` / `ComposeTerminalCanvas` |

Default **ON**: es un kill-switch, no una feature opt-in. Apagar sube el flag a ambos renderers y desactiva underline **y** tap a la vez.

### Strings

| Clave | EN | ES |
|---|---|---|
| `terminal_hyperlinks` | Terminal hyperlinks [Experimental] | Hipervínculos de terminal [Experimental] |
| `terminal_hyperlinks_desc` | Underline OSC 8 links and open them on tap | Subraya los enlaces OSC 8 y ábrelos al tocarlos |

(PT no lleva estas cadenas; cae al default del recurso EN.)

### Código

| Pieza | Archivo |
|---|---|
| Parseo OSC 8 + registry + allowlist | `terminal-emulator/.../TerminalEmulator.java` |
| Side-band por celda | `terminal-emulator/.../TerminalRow.java` |
| Lookup / resize | `terminal-emulator/.../TerminalBuffer.java` |
| Underline + tap legacy | `terminal-view/.../TerminalRenderer.java`, `TerminalView.java` |
| Underline + tap Compose | `app/.../compose/ComposeTerminalFrame.kt`, `ComposeTerminalCanvas.kt` |
| Cableado pref | `TermuxPreferenceConstants`, `TermuxAppSharedPreferences`, `Settings*`, `TermuxUiState`, `TermuxViewModel`, `TermuxComposeActivity`, `TermuxMainScreen`, `TerminalViewHost` |
| Tests emulador | `terminal-emulator/src/test/.../HyperlinkTest.java` (8 tests) |
| Tests runs Compose | `app/src/test/.../ComposeTerminalFrameTest.java` → `testHyperlinkChange_splitsRuns` |

---

## 3. Relación entre ambos

Son **caminos independientes** que no se pisan:

| | Seleccionar URL | Hipervínculo OSC 8 |
|---|---|---|
| Fuente | Texto plano del transcript (regex) | Celdas marcadas con escape OSC 8 |
| Requiere app cooperativa | No | Sí |
| Dónde se activa | Diálogo del Menú Más | Tap directo en la celda |
| Acción | Tap = copiar, long-press = abrir | Tap = abrir |
| Mouse-tracking | N/A (es un diálogo) | El tap **no** abre; se reporta al TUI |
| Pref | Ninguno | `terminal_hyperlinks` |

Una URL impresa sin OSC 8 solo es alcanzable vía **Seleccionar URL** (o seleccionando el texto a mano). Una URL marcada con OSC 8 se abre con un toque **y** también aparece en el diálogo si está en el transcript.

---

## 4. Limitaciones conocidas (v1)

- **Imágenes en línea**: **OSC 1337** (iTerm2 `File=`) + **kitty graphics** (`a=q`/`a=t`/`a=T`/`a=p`/`a=d`, chunks `m=0/1`, PNG/RGB/RGBA, `q=`). **Sixel**, virtual placements y animación fuera de alcance. Kill-switch: pref `terminal_images` (default ON). Buffer OSC 1337 ampliado a 4 MiB (overflow traga hasta terminador); registry budget 24 MiB (LRU null-in-place).
- **`id=` de OSC 8**: se parsea y se ignora; no hay deduplicación visual por id de sesión.
- **Mouse-tracking + OSC 8**: con mouse-tracking activo el tap **no** abre el link (protege a las TUIs); usar Seleccionar URL o long-press como alternativa.
- **Registry en memoria**: las URIs viven en un `ArrayList` por sesión; `reset()` del emulador lo limpia. No hay límite práctico, pero una sesión muy larga con miles de URIs únicas crece acotadamente.
- **Allowlist de schemes**: cualquier scheme nuevo (p. ej. `ssh://`) requiere ampliar `isAllowedHyperlinkUri` explícitamente.

---

## 5. Autodetección de protocolos (TUIs)

| Canal | Secuencia / var | Respuesta | Kill-switch |
|---|---|---|---|
| **kitty graphics** | `ESC _ G i=31,s=1,v=1,a=q,t=d,f=24;AAAA ESC \` | `ESC _ G i=31;OK ESC \` | Responde `OK` **aunque** `terminal_images=OFF` |
| **iTerm2 Feature Reporting** | `ESC ] 1337;Capabilities ESC \` | `ESC ] 1337;Capabilities={FeatureString} ESC \` | `F` (FILE) y `H` (hyperlinks) omitidos si sus prefs están OFF; la query siempre responde |
| **`TERM_FEATURES`** (env al arrancar shell) | — | Misma `{FeatureString}` | Misma lógica que Capabilities al spawn; en caliente usar la query OSC |
| **XTVERSION** | `ESC [ > 0 q` | `ESC P > \| Terminal(ver) ESC \` | Siempre |

`{FeatureString}` (prefijo alfanumérico; ignora el resto): `T3` = 24-bit, `B` = bracketed paste, `M` = mouse, `H` = OSC 8, `F` = OSC 1337 FILE. **No** se anuncia Sixel (`Sx`). **No** hay code para kitty en `TERM_FEATURES` — usar `a=q`.

Ejemplo ON: `T3BMHF`. Con imágenes OFF: `T3BMH`.

```sh
# kitty (yazi, kitten icat, …)
printf '\033_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA\033\\'

# Feature Reporting (iTerm2)
printf '\033]1337;Capabilities\033\\'

# XTVERSION
printf '\033[>0q'

# env (solo local; no viaja por SSH)
echo "$TERM_FEATURES"
```

Código: `TerminalEmulator.buildFeatureString` / `handleInlineImageOsc(Capabilities)` / `doCsiBiggerThan('q')` en `terminal-emulator`; `TerminalFeatureReport` + `TermuxShellEnvironment.putTermFeatures` en `termux-shared`. Tests: `FeatureReportTest`, `TerminalFeatureReportTest`.
