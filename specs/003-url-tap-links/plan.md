# Plan — Spec 003 Links de texto plano clicables y subrayados

## Mapa de módulos (lo que condiciona todo)

```
terminal-emulator  ←  terminal-view (api)  ←  termux-shared (impl)  ←  app (+ filemanager)
```

- El reconocedor de URLs vive hoy en `termux-shared` (`TermuxUrlUtils.URL_MATCH_REGEX`),
  que **no es visible** desde `terminal-view`; consumirlo desde ahí sería un ciclo de
  dependencias o una copia igualada a mano (riesgo de divergencia, viola la paridad
  del RNF-4). → La lógica nueva vive en `terminal-emulator`, visible (vía `api()`) para
  los dos renderers, y `TermuxUrlUtils` delega en ella para conservar **una sola regex**.
- El builder de la regex se mueve a `terminal-emulator`; los llamadores actuales del
  diálogo (`TermuxComposeActivity`, `FileReceiverActivity`, `TermuxActivityTest`) no cambian.

## Archivos y responsabilidades

| Archivo | Responsabilidad | RF |
|---|---|---|
| `terminal-emulator/.../TerminalUrlScanner.java` (nuevo) | Regex pura + mapeo celdas↔caracteres + caché por renglón lógico | RF-1,3,6,7,8; RNF-1,2 |
| `terminal-emulator/.../TerminalUrlScannerTest.java` (nuevo) | Tests JUnit sin Android | RNF-2 |
| `termux-shared/.../TermuxUrlUtils.java` | `getUrlMatchRegex()` delega en el scanner (regex única) | RNF-4 |
| `terminal-view/.../TerminalRenderer.java` | Corte de runs + subrayado cuando la celda cae en un span | RF-3,6 |
| `terminal-view/.../TerminalView.java` | Probe de URL en tap tras el miss de OSC 8; abre con `ACTION_VIEW` | RF-1,2,5,6 |
| `app/.../compose/ComposeTerminalFrame.kt` | Corte de runs + subrayado en el canvas Compose | RF-3,6 |
| `app/.../compose/ComposeTerminalCanvas.kt` | Probe de URL en tap tras el miss de OSC 8 (antes de `activateSession`) | RF-1,2,5,6,9 |
| `app/.../res/values*/strings.xml` (3) | La descripción del ajuste menciona también las URLs impresas | RF-11 |

No cambian: `TermuxUrlUtils.extractUrls` (firma y semántica), el diálogo del menú More
(RF-10), el flujo OSC 8 existente, el reporte de features ("H").

## Funciones puras del scanner

`TerminalUrlScanner` (clase final, Java, sin APIs de Android; las funciones de mapeo
son puras y el "estado" es solo la caché):

- `static Pattern getUrlPattern()` — builder de la regex, movido verbatim de
  `TermuxUrlUtils`.
- `LogicalLine collectLogicalLine(TerminalBuffer buffer, int externalRow)` — reconstruye
  el renglón lógico de una fila externa:
  1. retrocede mientras `buffer.getLineWrap(row - 1)` sea true, con tope de seguridad
     (no pasar de `-transcriptRows` ni retroceder más de N=100 filas);
  2. avanza mientras `buffer.getLineWrap(row)` sea true (no pasar de la última fila de
     pantalla);
  3. por fila appendea `TerminalRow.mText` desde `findStartOfColumn(0)` hasta
     `findStartOfColumn(columns)` (sin recortar espacios: hacen falta para mapear
     offsets);
  - devuelve `LogicalLine { String text; int[] rowStartOffset; int firstRow }`.
- `List<LinkSpan> findUrlSpans(LogicalLine line)` — `matcher.find()` de la regex sobre
  el texto; para cada match guarda `LinkSpan { int startCharIndexInJoined; String uri }`.
- `boolean cellContains(LinkSpan span, LogicalLine line, int externalRow, int column)` /
  `int[] columnRangeFor(LinkSpan span, LogicalLine line, TerminalBuffer buffer, int row)`
  — mapean offsets de caracteres a celdas recorriendo la `TerminalRow`
  (`findStartOfColumn` + `WcWidth.width`): un glifo ancho ocupa 2 celdas y el tap en
  cualquiera de las dos abre el link (caso límite).
- `String getUriAt(TerminalBuffer buffer, int externalRow, int column)` — puerta única
  para los taps de los dos renderers: busca el renglón lógico y devuelve la URL
  completa del span que cubre la celda, o `null`.
- **Caché (RNF-1)**: `IdentityHashMap<TerminalBuffer, LruCache<Fingerprint, Entry>>` —
  la huella de cada renglón lógico es la concatenación de `hash(texto fila) + wrap +
  columns` de sus filas; calcularla solo lee caracteres (barato), el regex solo corre
  en miss. LRU de 64 entradas. Se toca en la UI thread únicamente (dibujo y tap); sin
  reloj ni tiempo en la lógica — la caducidad es por huella, no por `now`/microrreloj,
  así ninguna función necesita el parámetro "hoy".

## Algoritmo de consumo

### Pintado con subrayado (los dos renderers)

```
al empezar la fila Y (con mHyperlinksEnabled):
  entry = scanner.entryFor(buffer, filaExternaY)      // caché descrita arriba
  inUrl = entry.columnRangesParaFila(Y)               // null si no hay spans (caso común)
en el bucle de runs, junto a style/cursor/selección/hyperlink:
  insideUrl = (inUrl != null) && inUrl[column]
  si cambia → cortar el run                            // igual que cambiar hyperlink
subrayado efectivo = underline del estilo || (hyperlink != 0 && enabled) || insideUrl
```

Lección de la Spec 002 (aplicable): al duplicar el algoritmo en dos renderers,
comparar también los centinelas de estado de los cortes de run, no solo las ramas
nuevas.

### Tap (los dos renderers; el orden respeta RF-2)

```
1. selección activa / pinch / mouse-tracking → camino normal (sin links)
2. OSC 8: getHyperlinkUriAt(row, col) + isAllowedHyperlinkUri → abrir (ya existe)
3. NUEVO: getUriAt(buffer, row, col)                   // TerminalUrlScanner
          + isAllowedHyperlinkUri (misma allowlist)    // RF-5
          → ACTION_VIEW, tap consumido
4. miss → camino normal del tap (activar pane / teclado / callback del cliente)
```

`TerminalView.openHyperlinkAt` gana un segundo paso tras su lookup OSC 8;
`ComposeTerminalCanvas` lo mismo continuación de su bloque OSC 8 (el orden en ambos
es idéntico). La defensa contra fila fuera de la rejilla ya existe en los dos taps y
el scanner la hereda devolviendo `null` para filas fuera de
`[-transcriptRows, mScreenRows)` sin tirar (memoria del proyecto: las lecturas por
fila externa fuera de la rejilla no lanzan, pero validar `emulator.mRows` antes).

## Decisiones justificadas (alternativa descartada)

1. **Regex y detección movidas a `terminal-emulator`; `TermuxUrlUtils` delega** — una
   sola fuente de verdad, paridad automática con el diálogo (RNF-4). *Alternativa
   descartada*: mantener la regex en `termux-shared` e inyectar un "probe" de URLs por
   `TerminalViewClient` — exige plomería view→renderer→app para pintado y tap, y el
   renderer legacy no está cableado al cliente; más riesgo que mover un builder de
   regex interno con delegación para no romper a nadie.
2. **Caché por huella de contenido del renglón lógico** (RNF-1). *Alternativa
   descartada*: cachear por índice de fila — se invalida con cada scroll (el ring
   reordena las external rows, misses constantes) y cachear por "generación del
   buffer" exigiría tocar escrituras del core del emulador.
3. **Subrayar cortando runs** (como con los OSC 8) en vez de una segunda pasada de
   pintado: reutiliza el mecanismo probado de runs; sin estado extra entre pasadas.
4. **El scanner como clase aparte que lee el buffer, no como métodos dentro de
   `TerminalEmulator`/`TerminalBuffer`**: el core del emulador queda sin conocimiento
   de "URLs"; solo usa `getLineWrap`, ya pública. Menos superficie de acoplamiento
   para `resize()` y sus validaciones.

## Estrategia de tests (`./gradlew test`)

- `TerminalUrlScannerTest` (terminal-emulator, JUnit puro):
  - la regex delegada de `TermuxUrlUtils` es la misma instancia/patrón (paridad);
  - link http/https/ftp/gemini/mailto: URI y rango correctos; URL sin esquema
    reconocible → no link (RF-5, límite del reconocedor documentado);
  - renglón lógico partido (lineWrap) → un único span que cubre ambas filas; el tap
    en el tramo de la segunda fila devuelve la URL completa (RF-8);
  - glifo ancho dentro del URL → las 2 celdas son tappable (caso límite);
  - dos links en el mismo renglón (RF-1);
  - fila fuera de rejilla / por debajo del transcript → `null` sin lanzar;
  - hit de caché: segunda petición con contenido sin cambios no re-ejecuta el
    `find()` (contador de rescans expuesto para el test).
- `ComposeTerminalFrameTest` (app, patrón ya existente de tests de runs): el corte de
  run por `insideUrl` y el subrayado forzado del tramo (RF-3 en el canvas).
- `TerminalRenderer` legacy no tiene tests de Canvas: validación en dispositivo
  (checklist manual).

## Orden de tareas

Ver `tasks.md`.
