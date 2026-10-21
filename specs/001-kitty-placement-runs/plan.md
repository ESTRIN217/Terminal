# Plan 001 — Colocaciones múltiples y recorte de origen en kitty graphics

Estado: aprobado

Plan de la spec aprobada `specs/001-kitty-placement-runs/spec.md`. Solo QUÉ técnico: archivos,
funciones puras, pseudocódigo, interfaz, decisiones y tests. Sin dependencias nuevas (RNF-1: no se
toca `gradle/libs.versions.toml`). Todo el estado nuevo es determinista: el desempate de "antigüedad"
de RF-20/RF-37 es un contador monótono asignado por el emulador, **nunca** el reloj del sistema.

Convenciones del repo que aplica este plan: Java puro en `terminal-emulator` y `terminal-view`
(sin Kotlin), Hungarian notation `m` en campos, `LOG_TAG` por clase, javadoc con `@param`/`@return`
en todo método público, identificadores y comentarios técnicos en inglés.

---

## 1. Archivos y responsabilidades

| Archivo | Acción | Responsabilidad | RF |
|---|---|---|---|
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalImagePlacement.java` | nuevo | Una colocación: clave estable, imagen referenciada, rectángulo de celdas, recorte de origen en píxeles, `z`, número de creación, buffer. Métodos `isVisible()`, `intersectsRow`, `coversCell`, `onAltScreen`. | RF-1..4, RF-18, RF-20, RF-21, RF-30..32, RF-36, RF-37 |
| `terminal-emulator/src/main/java/com/termux/terminal/KittyPlacementGeometry.java` | nuevo | Funciones puras de geometría: recorte de origen, resolución de celdas, recorte al área visible, banda de píxeles por celda, comparador de orden y borrados por celda. Sin Android, sin estado. | RF-7..17, RF-20, RF-21, RF-27, RF-37 |
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalImageData.java` | modificado | Se queda **solo como payload** (bytes, `pixelWidth`, `pixelHeight`, `pixelFormat`, `decodeRawArgb`). Se le quitan `startRow`, `startCol`, `cellsW`, `cellsH`, `onAltScreen`, `placeAt`, `isPlaced`, `intersectsRow`, `containsCell`. | RF-36, RNF-7 |
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java` | modificado | `mImageIds` por celda pasa a guardar **clave de colocación** (1-based). Nuevo `getPlacementKeyAt(row, col)`; `getImageAt`/`setImage` siguen existiendo con el mismo tipo y comportamiento (ver §6.1). | RF-21, RF-22, RF-29, RF-30 |
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalRow.java` | modificado | Igual que arriba (`getImage`/`setImage` documentan "placement key"); sin cambios de memoria ni de `copyInterval`. | RF-22, RF-30, RNF-7 |
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java` | modificado | Tabla de colocaciones y mapa `(i=,p=) → clave`; parseo de `x/y/w/h/z`/`c/r` por acción; alta/sustitución (`a=p`), borrados (`d=i/I/p/P/a/A/c/C`), desplazamiento por scroll, tope de 256 y evicción; API de lectura para renderers. | RF-1..6, RF-18..38 |
| `terminal-view/src/main/java/com/termux/view/TerminalImagePainter.java` | nuevo | Pintor compartido de una pasada de imágenes por fila (dos pasadas: debajo/encima de los glifos) para vista legado y canvas Compose. | RF-8, RF-18, RF-19, RF-23, RNF-3, RNF-4 |
| `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` | modificado | Usa `TerminalImagePainter`; construye `skipCells` (z>=0) y `skipBackgroundCells` (z<0); elimina `drawImagesForRow`/`drawImageStrip` locales. | RF-18, RF-19, RF-22, RF-23, RNF-4 |
| `app/src/main/java/com/termux/terminal/compose/ComposeTerminalCanvas.kt` | modificado | Mismas dos pasadas con el mismo pintor (`TerminalImagePainter`); `forEachRun`/`ComposeRowRunPainter` aceptan `skipBackgroundCells`; `drawComposeImages`/`drawComposeImageStrip` desaparecen. | RF-18, RF-19, RF-23, RNF-4 |
| `app/src/main/java/com/termux/terminal/compose/ComposeTerminalFrame.kt` | modificado | `forEachRun` propaga la omisión de fondo por celda (misma semántica que el renderer legado). | RF-19, RF-23 |
| `terminal-emulator/src/test/java/com/termux/terminal/KittyPlacementGeometryTest.java` | nuevo | Tests puros de §3 (sin `TerminalEmulator`, sin Android). | RF-7..17, RF-20, RF-21, RF-27, RF-37 |
| `terminal-emulator/src/test/java/com/termux/terminal/KittyPlacementRunsTest.java` | nuevo | Secuencias APC reales de opencode/OpenTUI sobre `TerminalTestCase`. | RF-1..6, RF-18..38 |
| Tests existentes (ver §7.5) | modificados | Leen geometría desde la colocación, no desde el payload; 4 llamadas `a=p` pasan de `w=/h=` a `c=/r=`. | RNF-6 |

`KittyPlaceholderDecoder` y `KittyVirtualPlacement` **no se tocan** (RF-33).

---

## 2. Modelo de datos

### 2.1 `TerminalImagePlacement` (nuevo)

```
public final class TerminalImagePlacement {                       // javadoc con @param en el ctor
    public final int key;             // 1-based, estable, nunca se reutiliza
    public final int registryId;      // índice 1-based en mImageDataList (payload compartido, RF-36)
    public int startRow, startCol;    // origen external/screen, como hoy (se desplazan con scroll, RF-30)
    public int cellsW, cellsH;        // rectángulo de destino en celdas (RF-7/RF-9)
    public int cropX, cropY, cropW, cropH;  // recorte de origen en PÍXELES de la imagen (0 = imagen completa)
    public final int z;               // RF-18: <0 debajo del texto
    public final long creationSeq;    // desempate de RF-20 y antigüedad de RF-37 (contador, no reloj)
    public boolean onAltScreen;       // RF-32, mismo criterio que hoy
    public boolean isVisible();       // cropW>0 && cropH>0 && cellsW>0 && cellsH>0 (RF-15)
    public boolean intersectsRow(int externalRow);
    public boolean coversCell(int externalRow, int column);
}
```

### 2.2 Tablas del emulador

```
private final ArrayList<TerminalImagePlacement> mImagePlacements = new ArrayList<>();  // índice 0 sin usar
private final HashMap<Long, Integer> mKittyPlacementKeys = new HashMap<>();            // (registryId,p=) -> key
private long mPlacementSeq;                                                            // RF-20/RF-37
private static final int MAX_PLACEMENTS_PER_IMAGE = 256;                                // RF-37, junto a
                                                                                        // MAX_IMAGE_ENCODED_BYTES
```

- Clave del mapa: `((long) registryId << 32) | (placementId & 0xFFFFFFFFL)` → dos imágenes con el mismo
  `p=` no colisionan (RF-4); `p=0` no entra en el mapa (RF-3).
- Las entradas de `mImagePlacements` se ponen a `null` al borrar, **nunca se reindexan** (misma regla que
  `mImageDataList`): una clave vieja en una fila reciclada resuelve a `null` y no colisiona (RF-29).
- `mImageDataBytesTotal` y el presupuesto no cambian: las colocaciones no guardan bytes (RNF-7, RF-36).

### 2.3 La celda

`TerminalRow.mImageIds` sigue siendo `int[mColumns]` (misma memoria, RNF-7). El valor deja de ser el id de
imagen y pasa a ser la **clave de colocación**. Reglas de escritura (RF-21):

- Al estampar una colocación, cada celda visible que cubre se escribe solo si la colocación nueva gana a la
  que ya estaba ahí según `KittyPlacementGeometry.comparePlacementOrder` (menor `z`, y a igual `z` mayor
  `creationSeq`), es decir, el pintor siempre encuentra en la celda la colocación superior.

---

## 3. Funciones puras

Todas en `KittyPlacementGeometry` (final, sin estado, sin Android). Se testean en JUnit y las consumen
tanto el emulador como los dos renderers (RNF-2, RNF-4, RNF-5). No hay parámetro de fecha: lo que haría de
"hoy" aquí es el número de creación, que llega **como parámetro**, nunca se lee de una fuente global.

```java
/** RF-12..15: recorte de origen en píxeles, recortado a la imagen. x/y negativos valen 0 (RF-13).
 *  @return {left, top, width, height} con width/height 0 si la intersección es vacía. */
public static int[] resolveSourceCrop(int imageWidth, int imageHeight,
                                      int cropX, int cropY, int cropW, int cropH);

/** RF-7/RF-9/RF-10: resuelve las celdas de destino.
 *  Ambos pedidos >0 → tal cual (RF-7). Falta uno → se deriva del aspecto del origen en píxeles, ceil, mín. 1.
 *  Faltan los dos → se derivan de srcPxW/srcPxH. Sin píxeles conocidos y sin ambos lados → null (RF-10).
 *  @return {cols, rows} o null. */
public static int[] resolveCells(int requestedCols, int requestedRows,
                                 int srcPxW, int srcPxH, int cellPxW, int cellPxH);

/** RF-8/RF-31: intersección del rectángulo con el área visible, sin reescalar.
 *  @param visibleTopRow fila external de la primera fila visible (negativa si se ha desplazado)
 *  @return {startRow, endRow, startCol, endCol} (final exclusivo) o null si no se ve nada. */
public static int[] clipToVisibleArea(int startRow, int startCol, int cellsW, int cellsH,
                                      int visibleTopRow, int visibleRows, int visibleColumns);

/** RF-11/RF-14/RF-17: banda de píxeles (dentro del recorte) que corresponde a una fila/columnas.
 *  Mapeo por celda, bordes alineados a la rejilla, sin desplazamiento subcelda.
 *  @return {left, top, right, bottom} con al menos 1 px por eje, dentro del recorte. */
public static int[] sourcePixelBand(int cropX, int cropY, int cropW, int cropH,
                                    int cellsW, int cellsH,
                                    int localRow, int localCol, int spanCells);

/** RF-18: true cuando la colocación va por debajo de los glifos. */
public static boolean isBelowText(int z);

/** RF-20: orden de pintado; devuelve negativo/0/positivo. Desempate por antigüedad (seq). */
public static int comparePlacementOrder(int zA, long seqA, int zB, long seqB);

/** RF-21/RF-27/RF-29: una celda está cubierta por la colocación. */
public static boolean coversCell(int startRow, int startCol, int cellsW, int cellsH,
                                 int externalRow, int column);

/** RF-20/RF-21: clave de la colocación superior que cubre la celda, o 0. */
public static int topmostKeyAt(List<TerminalImagePlacement> placements, int externalRow, int column);

/** RF-37: índice (en la lista dada) de la colocación más antigua de una imagen, o -1. */
public static int oldestPlacementIndexForImage(List<TerminalImagePlacement> placements, int registryId);
```

Notas de implementación de las funciones (contrato, no código):

- `resolveSourceCrop`: `left=max(0,x)`, `top=max(0,y)`, `right=min(imageWidth, x+w)` (`w<=0` → hasta el
  borde, RF-12), `bottom` análogo; devuelve `w=0`/`h=0` cuando `left>=right` o `top>=bottom` (RF-15). Si
  `imageWidth`/`imageHeight` son 0 (desconocidos) devuelve el rectángulo tal cual con `w=h=0`.
- `sourcePixelBand` es la **única** fórmula de escalado de origen: se usa en las dos pasadas y en los dos
  renderers, así que RNF-4 se cumple por construcción, no por disciplina. Con `crop` = imagen completa
  (RF-16) y `cellsW/H` = celdas del rectángulo, la fórmula coincide exactamente con el cálculo actual de
  `drawImageStrip`, que es lo que mantiene idéntico el caso yazi (RF-35).
- `resolveCells` recibe `cellPxW/cellPxH` como parámetros (los campos del emulador se pasan explícitamente);
  si valen 0 se usa 1 (mismo criterio que `deriveMissingCells` hoy).

Fuera de las funciones puras se quedan, por dependencia de estado: el alta/sustitución de colocaciones, el
estampado de celdas, el desplazamiento por scroll (`shiftPlacements`) y la evicción por presupuesto.

---

## 4. Algoritmos

### (a) `a=p` — colocación nueva o repetida (RF-1..7, RF-9..17)

```
processKittyCommand(control, data):
  parsea una vez: cols(c=), rows(r), cropX(x), cropY(y), cropW(w), cropH(h), z(z, puede ser negativo),
                  cursorMove(C, se ignora), i, p, q, U, d, t, f, s, v, m
  si acción p/P → handleKittyPlace(i, p, cols, rows, crop{x,y,w,h}, z, q, virtual)

handleKittyPlace(...):
  si virtual → createVirtualPlacement(...) sin cambios; reply OK; return              # RF-33
  regId = mKittyImageIds.get(i); data = getImageData(regId)
  si data == null → reply "ENOENT:Image not found"; return                            # sin cambios
  srcW = data.pixelWidth; srcH = data.pixelHeight
  crop = resolveSourceCrop(srcW, srcH, x, y, w, h)                                    # RF-12..15
  cells = resolveCells(cols, rows, crop.w>0?crop.w:srcW, crop.h>0?crop.h:srcH,
                       mCellWidthPixels, mCellHeightPixels)
  si cells == null → reply INVALID; return                                            # RF-10
  origin = (mCursorRow, mCursorCol)                                                   # RF-6: no se mueve
  si origin fuera de la rejilla de celdas (col>=mColumns || row>=mRows) → INVALID     # como hoy
  key = (p != 0) ? mKittyPlacementKeys.get(mapKey(regId, p)) : null
  si key != null y mImagePlacements[key] != null:
        placement = la misma clave, geometría sustituida (origen, celdas, recorte, z)   # RF-2
        createSeq nuevo (pasa a ser la más reciente); clearCellsOf(key) antes de estampar
  si no:
        enforcePlacementCap(regId)                                                      # RF-37
        key = allocatePlacement(new TerminalImagePlacement(registryId=regId, origin, cells,
              crop, z, ++mPlacementSeq, onAlt = (mScreen == mAltBuffer)))               # RF-1/RF-3/RF-4
        si p != 0 → mKittyPlacementKeys.put(mapKey(regId,p), key)
  si placement.isVisible(): stampCells(placement)                                       # RF-15: si no, no reserva
  reply OK (con p= si venía)                                                            # sin cambios
```

`stampCells(placement)`: recorre las celdas del rectángulo que caen dentro de la rejilla (el resto se
recortará solo al pintar, RF-8/RF-31) y escribe la clave siguiendo la regla de §2.3.

### (b) Pintado por fila en dos pasadas (RF-18, RF-19, RF-20, RF-21, RF-23, RNF-3, RNF-4)

```
por cada fila visible:
   # el emulador entrega las colocaciones de esa fila ya ordenadas por (z, creationSeq) (RF-20)
   emulator.collectPlacementsForRow(row, /*belowText=*/true, out):     # z<0
       painter.drawRowPass(canvas, emulator, row, rowTop, rowBottom, cellWidth,
                           belowText=true, backgrounds, bitmaps, paint)
   glifos(row):                                                        # caminos actuales, sin cambios
       skipCells           = celdas con clave de colocación con z>=0   # la imagen tapa el glifo
       skipBackgroundCells = celdas con clave de colocación con z<0    # el glifo se ve, el fondo no
       dibuja runs de texto + placeholders U=1 (estos últimos igual que hoy, RF-33)
   emulator.collectPlacementsForRow(row, /*belowText=*/false, out):    # z>=0
       painter.drawRowPass(..., belowText=false, ...)
```

`drawRowPass` (en el pintor compartido, §5.2):

```
para cada colocación de la lista:
    rect = clipToVisibleArea(placement.startRow, placement.startCol, cellsW, cellsH,
                             visibleTopRow, visibleRows, columns)          # RF-8/RF-31, sin reescalar
    si rect == null: continua
    para cada columna del rect (o agrupando en tramos contiguos de la misma clave):
        background = backgrounds.colorAt(columna)                          # fondo efectivo de la celda
        paint.setColor(background); canvas.drawRect(celda)                 # RF-23: transparencia
        band = sourcePixelBand(crop, cellsW, cellsH, localRow, localCol, span)
        canvas.drawBitmap(bitmap, band, celda, null)                       # sin reescalado propio (RNF-3)
```

`backgrounds` es un callback que cada renderer implementa: el legado resuelve con
`TextStyle.effectiveBackgroundColor(estilo, palette, reverseVideo||selección)` y el canvas Compose con su
`cellBlankColor(...)`. Así el pintor no necesita ni `TerminalRow` ni la paleta.

### (c) Borrados (RF-24..29)

```
handleKittyDelete(deleteMode, i, p(x=), p(y=), placementId, q):
  modo d/D:
    'a'/'A' → clearAllPlacements()      # pone a 0 las celdas y anula todas las entradas; payloads intactos (RF-28)
    'i'/'I' → removeImage(regId)        # borra payload + TODAS sus colocaciones + celdas (RF-24/RF-38)
    'c'/'C' → key = mScreen.getPlacementKeyAt(mCursorRow, mCursorCol)
              si key != 0 → deletePlacement(key)                          # RF-28 (comportamiento actual)
    'p'/'P' → si placementId != 0:                                        # RF-25/RF-26
                  key = mKittyPlacementKeys.get(mapKey(regId, placementId))
                  si key == null o entrada nula → reply OK; return        # idempotente (RF-26)
                  deletePlacement(key)                                     # solo esa; payload y demás intactas
              si no y x>0 e y>0 → key = topmostKeyAt(celdas,y-1,x-1); deletePlacement(key)   # RF-27
    otro → reply EINVAL (sin cambios)

deletePlacement(key):                                # RF-29
  clearCellsOf(key); mImagePlacements.set(key-1, null); mKittyPlacementKeys quita las entradas con ese valor
clearPlacementsOfImage(regId): mismas dos líneas para todas las colocaciones de esa imagen
```

`clearCellsOf(key)` solo recorre las filas del rectángulo de la colocación (más las de transcript
alcanzables) y pone a 0 **solo** las celdas cuya clave es esa: nunca toca el texto ni los estilos (RF-22).

### (d) Desplazamiento por scroll y cambio de tamaño (RF-30, RF-31, RF-32)

```
shiftPlacements(top, bottom, deltaRows, transcriptShifted):     # sustituye shiftImagePlacements
  onAlt = (mScreen == mAltBuffer)
  para cada placement no nulo con isVisible() y onAltScreen == onAlt:
      si startRow < 0: si transcriptShifted → startRow += deltaRows
      si no, si startRow en [top, bottom) → startRow += deltaRows
  # las claves viajan solas con las celdas (TerminalRow.copyInterval, sin cambios)
  # NO se ancla ni se recorta el rectángulo: se recorta al pintar (RF-8/RF-31)
```

- Los cinco puntos de llamada actuales (`RI`, `IL`, `DL`, `DECSTBM scroll`, `scrollDownOneLine`) pasan a
  llamar a `shiftPlacements` con la misma firma.
- `resize()`: no se toca ninguna colocación (RF-31); un origen que quede fuera de la rejilla se recorta al
  pintar y, si vuelve a entrar, se ve igual (nada se destruye).
- Alt-screen (DECSET 1049/47): se conserva el código actual de `setDecsetInternalBit`, pero por colocación
  (`onAltScreen && isVisible()` → anular); al limpiar el alt buffer se descartan sus colocaciones (RF-32).

### (e) Evicción por presupuesto y tope de colocaciones (RF-37, RF-38, RNF-7)

```
enforcePlacementCap(regId):
  mientras countPlacementsOfImage(regId) >= MAX_PLACEMENTS_PER_IMAGE:
      idx = oldestPlacementIndexForImage(mImagePlacements, regId)      # función pura
      deletePlacement(clave(idx))                                      # libera celdas
evictImagesForBudget(incoming):                                        # ya existe
  al anular una entrada de mImageDataList → clearPlacementsOfImage(regId)   # RF-38
evictPlacementsForBudget(...): NO existe: el presupuesto sigue siendo de bytes de payload (RNF-7)
```

### (f) Parseo de claves (RF-6, RF-7, RF-11, RF-34)

`processKittyCommand` deja de mezclar `w/h` con las celdas. Pasa un bloque de parámetros:

```
cols(c=), rows(r)                 → celdas del rectángulo de destino
x, y, w, h                        → dependen de la acción:
   a=p/a=P                        → recorte de origen en píxeles (RF-11) y origen del borrado por celda (RF-27)
   a=t/a=T                        → w/h siguen siendo celdas (comportamiento actual, RF-34); x/y se ignoran
z                                 → entero con signo (parseKittyInt ya acepta negativos)
C=                                → se parsea y se ignora (fuera de alcance: el cliente mueve el cursor)
```

---

## 5. Interfaz que consumen los dos renderers (RNF-4)

### 5.1 Lectura (en `TerminalEmulator`, javadoc con `@param`/`@return`)

```java
/** Colocación por clave, o null si ya no existe. */
public TerminalImagePlacement getPlacement(int key);
/** Clave de colocación escrita en la celda (lado emisor del side-band). */
public int getPlacementKeyAt(int externalRow, int column);
/** Colocación superior que cubre la celda, o null (RF-21). */
public TerminalImagePlacement getPlacementAt(int externalRow, int column);
/** Rellena `out` con las colocaciones de la fila de esa pasada, ordenadas por (z, creationSeq). */
public void collectPlacementsForRow(int externalRow, boolean belowText, List<TerminalImagePlacement> out);
/** Igual, pero asignando una lista nueva (comodidad para tests). */
public List<TerminalImagePlacement> getPlacementsForRow(int externalRow, boolean belowText);
/** Colocaciones vivas de una imagen, por clave ascendente (tests y d=i). */
public List<TerminalImagePlacement> getPlacementsForImage(int registryId);
/** Payload compartido (sin cambios). */
public TerminalImageData getImageData(int registryId);
/** Igual que hoy: resuelve el payload de la celda a través de la colocación superior. */
public TerminalImageData getImageDataAt(int externalRow, int column);
/** Sin cambios (RF-33). */
public KittyVirtualPlacement resolveVirtualPlacement(int imageId);
```

`collectPlacementsForRow` recibe la lista del llamante a propósito: el pintado no asigna memoria por fila
y fotograma (RNF-3), y el orden lo fija el emulador una sola vez (RNF-4, RNF-5).

### 5.2 Pintado (nuevo, en `terminal-view`)

```java
public final class TerminalImagePainter {                    // LOG_TAG = "TerminalImagePainter"
    /** Fondo efectivo de una celda (estilo + paleta + selección/reverse), resuelto por el renderer. */
    public interface CellBackgroundResolver { int colorAt(int column); }
    /** Bitmap ya decodificado y cacheado por el renderer (cachés actuales: id + identidad del payload). */
    public interface ImageBitmapProvider { Bitmap bitmapFor(TerminalImageData data); }

    public void drawRowPass(Canvas canvas, TerminalEmulator emulator, int externalRow,
                            float rowTop, float rowBottom, float cellWidth,
                            boolean belowText,
                            CellBackgroundResolver backgrounds, ImageBitmapProvider bitmaps, Paint paint);
}
```

- `TerminalRenderer` (vista legado) y `ComposeTerminalCanvas` (app) llaman al **mismo** `drawRowPass` con
  sus propios callbacks: es lo que hace que RNF-4 se cumpla por construcción en vez de "por disciplina", ya
  que ambos usan `android.graphics.Canvas`. El canvas Compose puede importar `terminal-view`
  (`app/build.gradle.kts:41`).
- El pintor no decodifica ni cachea: pide el bitmap por callback (RNF-3: un decode por imagen, identidad
  comprobada, sin bitmap redimensionado por colocación).
- `TerminalRenderer.mImageBitmaps` y `composeImageBitmaps` se quedan donde están; solo cambia quién las
  consume.

---

## 6. Decisiones justificadas y alternativa descartada

### 6.1 Clave de colocación por celda

- **Decisión:** la celda guarda una clave de colocación 1-based (misma tabla de claves que hoy usaba el id
  de imagen: entradas anuladas, nunca reindexadas). `TerminalBuffer.getImageAt`/`setImage` y
  `TerminalRow.getImage`/`setImage` **mantienen nombre, tipo y semántica de escritura**, pero su javadoc
  pasa a decir "placement key"; se añade `TerminalBuffer.getPlacementKeyAt(row, col)` como alias explícito
  para el código nuevo. `TerminalEmulator.getImageDataAt` no cambia de comportamiento: resuelve
  clave → colocación → payload.
  - Las filas siguen llevándose las celdas consigo (`TerminalRow.copyInterval` copia `mImageIds`), así que
    scroll, insert/delete line y `blockCopy` no necesitan ningún cambio para las reservas; el rectángulo
    (que sí es absoluto) lo sigue desplazando `shiftPlacements` (§4d).
  - Los tests que hoy afirman `getImageAt(...) == 1` siguen pasando en los casos de una sola imagen porque
    las claves se asignan 1, 2, 3… en orden de creación; donde la identidad importe, el test compara con la
    clave que devuelve `getPlacementKeyAt`/`getPlacementAt` (§7.5).
- **Alternativa descartada — byte/campo extra en el estilo:** los estilos son `long` empaquetados y
  cubiertos por `TextStyleTest`; hurtar bits colisiona con colores/efectos y rompería RNF-6.
- **Alternativa descartada — lista de colocaciones por celda:** una estructura por celda en un
  `TerminalRow` multiplica la memoria (RNF-7), rompe la copia por `System.arraycopy` de `copyInterval` y
  añade asignación al camino caliente.
- **Alternativa descartada — mapa `(fila, col)` → colocación:** hashing por celda en cada fotograma
  (RNF-3) y, peor, cada scroll/`IL`/`DL` tendría que re-clavar claves porque el ring buffer mueve filas
  enteras.
- **Alternativa descartada — resolver el "más alto" al pintar:** recorrer todas las colocaciones por celda
  da O(celdas × colocaciones) y hace que el resultado dependa del orden de iteración (RNF-5). Se resuelve al
  escribir, con el comparador.

### 6.2 Reparto de la geometría entre el emulador y el renderer

- **Decisión:** el emulador parsea y **recorta** el recorte de origen contra los píxeles conocidos del
  protocolo (`s=`/`v=` o IHDR) y decide si es vacío (RF-15) y cuántas celdas tiene el rectángulo (RF-9/10);
  el renderer mapea ese recorte al espacio de píxeles del **bitmap decodificado** con la misma función pura
  (clamp defensivo contra el tamaño real del bitmap).
- **Alternativa descartada — guardar los valores crudos y decidir todo en el renderer:** RF-15 ("registra
  la colocación pero no reserva celdas") y RF-9 no se pueden decidir sin el recorte, y los dos renderers
  podrían divergir (RNF-4); además los tests puros necesitarían un bitmap.
- **Alternativa descartada — pre-recortar/escalar un bitmap por colocación en el emulador:** introduce
  Android en `terminal-emulator` (constitución 3) y multiplica memoria (RNF-7).

### 6.3 Orden `z` con pintado por filas

- **Decisión:** dos pasadas por fila (`belowText` antes de los glifos, "encima" después) y, dentro de cada
  pasada, lista ordenada por `(z, creationSeq)`. Como la celda ya guarda la colocación superior (RF-21),
  dos colocaciones que comparten celda nunca llegan a pintarse superpuestas: el orden relativo de RF-20 se
  cumple por construcción.
- **Alternativa descartada — display list global del fotograma ordenada por `z`:** exige el área visible
  completa antes de pintar, rompe el recorrido fila a fila (que es lo que mantiene el coste acotado,
  RNF-3) y asigna una lista por fotograma.
- Detalle de los glifos: con `z<0` el glifo debe verse (RF-18/HU-2) y el fondo de la celda no puede
  repintarse encima de la imagen, así que la pasada de texto omite el fondo de esas celdas
  (`skipBackgroundCells`) y sigue omitiendo glifo+fondo en las celdas tapadas por `z>=0` (`skipCells`, como
  hoy). El pintor ya rellena el fondo de cada celda que cubre antes de dibujar (RF-23).

### 6.4 Partir `TerminalImageData`

- **Decisión:** sí. `TerminalImageData` = payload; `TerminalImagePlacement` = geometría. Una imagen tiene
  N colocaciones y una sola copia de bytes (RF-36, RNF-7).
- **Alternativa descartada — mantener el rectángulo "principal" en el payload y añadir la tabla al lado:**
  dos fuentes de verdad; `d=i`, la retransmisión (RF-5) y la evicción (RF-38) tendrían que acordarse de
  limpiar ambas, que es exactamente el fallo que RF-29 prohíbe.

### 6.5 Pintor compartido en `terminal-view`

- **Decisión:** un `TerminalImagePainter` que usan vista legado y canvas Compose, con dos callbacks
  (fondo por celda, bitmap).
- **Alternativa descartada — duplicar el código otra vez como hoy:** RNF-4 exige el mismo resultado visible
  en los dos renderers y no hay test de píxeles (sin Robolectric en este repo: `MEMORY.md`), así que la
  divergencia no se detectaría hasta el dispositivo.
- **Alternativa descartada — meter el pintor en `terminal-emulator`:** arrastraría `android.graphics` a un
  módulo Java puro (constitución 3).

### 6.6 Origen fuera de la rejilla

- **Decisión:** se mantiene el comportamiento actual: si la celda de origen del cursor cae fuera de la
  rejilla, la colocación es INVALID (no se crea). El recorte de RF-8 solo actúa sobre rectángulos que
  empiezan dentro y cruzan el borde derecho/inferior (incluido el caso "empieza en la última columna":
  queda 1 celda de ancho).
- **Alternativa descartada — aceptar orígenes fuera y clipear a vacío:** convertiría un error de cliente en
  una colocación invisible e imposible de diagnosticar, y cambiaría la respuesta actual (RNF-6).

---

## 7. Estrategia de tests con `./gradlew test`

Único gate (constitución 4). Dos suites nuevas en `terminal-emulator` + adaptación de las existentes.

### 7.1 `KittyPlacementGeometryTest` (JUnit puro, sin emulador)

Casos concretos, uno por requisito:

- `resolveSourceCrop`: sin recorte; `w`/`h` ausentes ⇒ resto de la imagen (RF-12); `x`/`y` negativos ⇒ 0
  (RF-13); recorte mayor que la imagen y `x`/`y` fuera ⇒ intersección (RF-14); ancho o alto cero y origen
  más allá del borde ⇒ `w=h=0` (RF-15); imagen de 1×1 px.
- `resolveCells`: `c=`/`r=` exactos, aunque no cuadre el aspecto (RF-7); solo `c=` ⇒ `r` derivado del
  aspecto y mínimo 1 (RF-9); ninguno y píxeles conocidos ⇒ derivado; ninguno y píxeles desconocidos
  (`0/0`) ⇒ `null` (RF-10); `cellPxW=0` ⇒ no divide por cero.
- `clipToVisibleArea`: rectángulo interior; cruce del borde derecho, del inferior y de ambos (RF-8);
  origen en la última columna/fila; rectángulo completamente por fuera ⇒ `null`; `c=`/`r=` enormes;
  fila negativa (transcript) visible.
- `sourcePixelBand`: con recorte y sin recorte (RF-16); las bandas de todas las filas cubren exactamente el
  recorte sin huecos ni solapes y la última llega al borde (RF-17); 1 px mínimo por eje.
- `comparePlacementOrder` e `isBelowText`: `z` ausente/0/positivo ⇒ encima; `z` muy negativo; empate de `z`
  ⇒ gana la creación posterior (RF-20).
- `topmostKeyAt` / `coversCell`: solape de dos colocaciones (RF-21).
- `oldestPlacementIndexForImage`: 257 colocaciones ⇒ la más antigua (RF-37).

### 7.2 `KittyPlacementRunsTest` (secuencias reales, `TerminalTestCase`)

Helpers (mismo estilo que `KittyGraphicsTest.kg`), con `q=2` para no depender de las respuestas:

```java
private static final String PNG_2x2_B64 = /* PNG 2×2 literal, como PNG_1x1_B64 en KittyGraphicsTest */;
private static String transmit(int i) { return "\033_Ga=t,f=100,i=" + i + ",m=0,q=2;" + PNG_2x2_B64 + "\033\\"; }
private static String place(int i, int p, String cells, String crop, int z) {
    return "\033_Ga=p,i=" + i + ",p=" + p + "," + cells + "," + crop + ",C=1,z=" + z + ",q=2\033\\";
}
// uso: enterString("\033[3;5H"); enterString(place(5, 1, "c=6,r=3", "x=0,y=0,w=8,h=4", -1));
```

Casos (RF entre paréntesis):

1. **Un tramo con recorte**: transmitir + `a=p` con `c=/r=` y `x/y/w/h` ⇒ la colocación tiene ese
   rectángulo y ese recorte, el cursor sigue donde estaba (RF-6, RF-7, RF-11) y solo se reservan las celdas
   del rectángulo (RF-22).
2. **Dos tramos de la misma imagen** con `p=1` y `p=2` y recortes distintos ⇒ dos colocaciones vivas, cada
   una con su rectángulo, celdas propias y el mismo `registryId` (RF-1, RF-4, RF-36); `getImageDataAt`
   devuelve el mismo payload en ambos rectángulos.
3. **Mismo `p=` repetido con otro rectángulo** ⇒ sigue habiendo una, con la geometría nueva (RF-2).
4. **`p=0` dos veces** ⇒ dos colocaciones (RF-3).
5. **`z=-1` vs `z=0`** ⇒ `getPlacementsForRow(row, belowText=true/false)` clasifica cada una; con `z`
   ausente ⇒ encima (RF-18, RF-19); dos con el mismo `z` en la misma celda ⇒ la celda queda con la creada
   después (RF-20, RF-21).
6. **Borrados**: `d=p,i=5,p=1` quita solo el tramo 1 y deja el 2 y el payload (RF-25); `d=p` de un `p=`
   inexistente responde OK sin tocar nada (RF-26); `d=i,i=5` quita los dos tramos y el payload (RF-24);
   `d=a` limpia celdas y colocaciones pero permite recolocar (RF-28); `d=p` sin `p=` con `x=`/`y=` borra lo
   que cubre esa celda (RF-27); tras cualquier borrado no queda ninguna celda apuntando a la colocación
   eliminada (RF-29).
7. **Retransmitir el mismo `i=`** con dos tramos activos ⇒ payload nuevo y cero colocaciones (RF-5).
8. **Scroll**: dos tramos en filas 3-5, `\n\n` al pie ⇒ los dos `startRow` se desplazan y las claves viajan
   en las celdas; llevar uno fuera del área visible por arriba y volver ⇒ vuelve a pintarse (RF-30);
   `IL`/`DL`/`RI` como en `ImagePlacementShiftTest` (RF-30).
9. **Resize** con `resize()` a menos columnas que el origen de un tramo ⇒ no hay excepción, la colocación
   no se mueve ni se reescala (RF-31).
10. **Alt-screen**: colocar en el buffer alternativo y volver al principal ⇒ no se ve en el principal; al
    limpiar el alternativo se descartan sus colocaciones (RF-32).
11. **Presupuesto y tope**: 257 colocaciones de la misma imagen ⇒ 256 vivas y la más antigua fuera
    (RF-37); imagen grande que fuerza evicción ⇒ todas sus colocaciones desaparecen y sus celdas quedan
    libres (RF-38, RF-29).
12. **Casos límite de geometría**: recorte vacío ⇒ OK sin celdas reservadas (RF-15); `c=/r=` enormes ⇒
    recorte al pintar sin excepción (RF-8); sin `c/r` ni píxeles ⇒ INVALID como hoy (RF-10); `x/y`
    negativos (RF-13).

Regresiones que deben seguir en verde sin tocarse: `KittyPlaceholderTest` (RF-33), `KittySuiteReplicationTest`
(RF-33), `KittyFileMediaTest`, `ApcTest`, `FeatureReportTest`, `HyperlinkTest`, `ImagePlacementShiftTest`.

### 7.3 Secuencias exactas de opencode/OpenTUI

Las tres que describe la spec, tal como se escriben en el test:

```
# 1) transmitir el PNG (sin mostrar)
\033_Ga=t,f=100,i=<id>,m=0,q=2;<base64 png>\033\\
# 2) colocar el tramo con recorte, tras posicionar el cursor (fila;col 1-based)
\033[<row>;<col>H
\033_Ga=p,i=<id>,p=<pid>,c=<celdas>,r=<celdas>,x=<px>,y=<px>,w=<px>,h=<px>,C=1,z=<z>,q=2\033\\
# 3) segundo tramo: mismo i=, otro p=, otro recorte y otras celdas
#    y después, según haga el cliente, posicionar el cursor otra vez (a=p no lo mueve, RF-6)
# 4) borrados: \033_Ga=d,d=p,i=<id>,p=<pid>,q=2\033\\   y   \033_Ga=d,d=i,i=<id>,q=2\033\\
# 5) z<0 (debajo del texto) vs z>=0 (encima) y z ausente
```

Se replican también con `q=0` en al menos un caso para comprobar que las respuestas (OK/ENOENT/INVALID) no
cambian (RF-26 y casos límite de `q=1`/`q=2`).

**Captura real (T9, 2026-10-19).** Ejecutando el opencode de verdad en un PTY con `TERM=xterm-kitty` y
`OPENTUI_IMAGE_PROTOCOL=kitty`, la vista de diff emite **una colocación por imagen** (no varios tramos) y
con estos valores, que fijan los tests:

```
\033_Ga=t,f=100,i=2401244685,m=1,q=2;<trozo base64>\033\\      # + trozos m=1 … m=0
\033_Ga=p,i=2401244685,p=1,c=19,r=7,x=0,y=0,w=80,h=60,C=1,z=-1073741823,q=2\033\\
\033_Ga=d,d=I,i=2401244685,q=2\033\\                          # al destruir el elemento
```

Tres avisos que no se ven en la spec: los ids `i=` son **secuenciales con base alta** (por encima de
`Integer.MAX_VALUE`: de ahí RF-39), `z` es `-1073741823`/`-1073741822` (no `-1`), y al cambiar el layout el
cliente **retransmite** la imagen y vuelve a colocarla con otro `c=`/`w=` en lugar de mover la existente.

### 7.4 Qué se puede afirmar desde JUnit y qué no

- **Sí (sin Android):** geometría de cada colocación (celdas, recorte, `z`), reservas exactas por fila y
  columna (claves en las celdas), orden de pintado (`collectPlacementsForRow`), clasificación debajo/encima
  del texto, borrados e idempotencia, retransmisión, recorte al área visible, desplazamiento por scroll y
  `IL`/`DL`/`RI`, ausencia de excepciones en todos los casos límite, y contabilidad de memoria (payload
  único + tope de colocaciones).
- **No (checklist manual en dispositivo, criterio 3 de la spec):** el resultado en píxeles — composición
  alfa sobre el fondo, glifo visible sobre una imagen `z<0`, selección/reverse video sobre la imagen, el
  clamp real cuando el tamaño del bitmap decodificado difiere del sniffado, el coste por fotograma (RNF-3)
  y la apariencia de opencode/yazi/OSC 1337 en la app. No hay test de renderer posible en este repo (sin
  Robolectric), y por eso §6.5 comparte el pintor en vez de duplicarlo.

### 7.5 Tests existentes que cambian (y por qué)

- **Geometría leída desde el payload** (`data.startRow`, `data.cellsW`, `data.isPlaced()`,
  `data.intersectsRow`): `ImagePlacementShiftTest`, `KittyGraphicsTest`, `TerminalImagesOsc1337Test`,
  `KittyFileMediaTest`. Las aserciones se reescriben para leer la colocación
  (`mTerminal.getPlacementAt(row, col)` / `getPlacementsForImage(regId)`); la intención no cambia.
- **`a=p` con `w=`/`h=` como celdas** (RF-11 dice que en `a=p` son píxeles): `KittyGraphicsTest`
  líneas 112, 147, 288 y 323. Estas cuatro llamadas pasan a `c=`/`r=` (mismo efecto, clave correcta según
  la spec). **Es la única edición de tests con cambio de bytes de entrada**; ver §8.3.
- `KittyGraphicsTest` con `a=T` y `w=`/`h=` (líneas 49, 60, 68, 100, 128, 235, 247, 258, 273, 295, 304, …)
  **no se toca**: `a=T`/`a=t` mantienen `w=`/`h=` como celdas (RF-34).

---

## 8. Riesgos y orden de implementación

### 8.1 Orden (con `./gradlew test` en verde en cada paso)

1. `KittyPlacementGeometry` + `KittyPlacementGeometryTest` (código nuevo sin consumidores: verde trivial).
2. `TerminalImagePlacement` y la separación payload/geometría de `TerminalImageData`, con el camino de una
   sola colocación (OSC 1337, `a=T`, yazi) ya sobre la tabla nueva y las claves en las celdas; adaptar los
   tests que leen geometría (§7.5). Cubre RF-22, RF-29, RF-30, RF-31, RF-32, RF-34, RF-35, RF-36, RF-38.
3. `a=p` con identidad `(i=, p=)`: alta/sustitución/anónimas, `d=p`/`d=i`, tope de 256. Cubre RF-1..5,
   RF-24..29, RF-37.
4. Parseo nuevo: `c=`/`r=`, recorte en píxeles de `a=p`, `z`, `C=` ignorado, cursor inmóvil. Cubre RF-6,
   RF-7, RF-9..17.
5. `TerminalImagePainter` compartido + dos pasadas en `TerminalRenderer` y `ComposeTerminalCanvas` /
   `ComposeTerminalFrame` (omisión de fondo por celda). Cubre RF-8, RF-18..21, RF-23, RNF-3, RNF-4.
6. `KittyPlacementRunsTest` completo (tramos, scroll, resize, alt-screen, presupuesto) y repaso de límites.
   Cubre toda la trazabilidad de §9 y RNF-5, RNF-6.
7. Checklist manual en dispositivo (criterio 3 de la spec) y actualización de `MEMORY.md`.

### 8.2 Dependencias entre partes

`1 → 2 → 3 → 4` (la geometría pura habilita el alta; el alta habilita los tramos; los tramos habilitan el
parseo nuevo, porque sin identidad `p=` el recorte no tiene donde guardarse). `5` depende de `2` y `3`
(necesita colocaciones ordenadas, no del parseo) y puede ir en paralelo a `4` si se quiere acortar, pero el
visual de `z` solo se puede comprobar después de `4`. `6` depende de todo lo anterior. El paso `5` es el
único que toca el camino caliente de pintado: no mezclarlo con `4` en la misma tarea.

### 8.3 Riesgos

- **Cambio de significado del side-band de celda** (id de imagen → clave de colocación): cualquier código
  que hoy compare una celda con un id de payload se rompe en silencio. Mitigación: `getPlacementKeyAt` como
  alias explícito, javadoc actualizado en `TerminalRow`/`TerminalBuffer`, y `getImageDataAt` con la misma
  semántica pública de antes.
- **`a=p` con `w=`/`h=` como celdas** en cuatro tests existentes: si el cliente real (yazi) los usara así
  en `a=p` en vez de `c=`/`r=`, la spec ya decidió lo contrario (`Dudas abiertas`: "`w=`/`h=` son píxeles en
  `a=p`, sin heurística de celdas"). Ver §10: no se cambia la spec, se cambian los tests y se avisa.
- **Coste por fotograma** (RNF-3): dos pasadas por fila y lista reutilizada; el trabajo sigue siendo
  proporcional a las celdas cubiertas visibles. Riesgo de regresión si el pintor pide el bitmap por celda
  en vez de por tramo: el contrato lo dice (un bitmap por colocación y pasada).
- **Referencias huérfanas** (RF-29): claves nunca reutilizadas y entradas anuladas; las filas recicladas
  del transcript pueden conservar claves viejas → todo lector debe tolerar `null` (documentado en el
  javadoc de `getPlacement`/`getPlacementAt`).
- **Tope de 256 + presupuesto**: `enforcePlacementCap` debe ejecutarse **antes** de reservar celdas, o
  quedarían reservas de la colocación desalojada.
- **Pintado del glifo con `z<0`**: es el único cambio en el camino de texto (omitir fondo de celdas
  concretas). Si el renderer no propaga `skipBackgroundCells` a nivel de celda dentro del run, el fondo del
  run tapa la imagen y HU-2 falla en dispositivo aunque los JUnit estén verdes → caso explícito del
  checklist manual.

---

## 9. Trazabilidad RF → partes del plan

| RF | Dónde se resuelve |
|---|---|
| RF-1, RF-2, RF-3, RF-4 | §2.2 (mapa `(registryId,p=)`), §4a, tests §7.2 (2, 3, 4) |
| RF-5 | §4a (retransmisión llama a `clearPlacementsOfImage`), test §7.2 (7) |
| RF-6 | §4f (`a=p` no mueve el cursor), test §7.2 (1) |
| RF-7, RF-9, RF-10 | `resolveCells` (§3), §4a, tests §7.1 |
| RF-8 | `clipToVisibleArea` (§3), §4b, tests §7.1 y §7.2 (12) |
| RF-11..15 | `resolveSourceCrop` (§3), §4a, tests §7.1 y §7.2 (12) |
| RF-16, RF-35 | `sourcePixelBand` con recorte = imagen completa (§3), §7.5 (yazi intacto) |
| RF-17 | `sourcePixelBand` (mapeo por celda, mín. 1 px), test §7.1 |
| RF-18, RF-19 | §4b (dos pasadas), §6.3, tests §7.2 (5) |
| RF-20, RF-21 | `comparePlacementOrder`/`topmostKeyAt` (§3), §2.3, §4b, tests §7.1 y §7.2 (5) |
| RF-22 | §2.3 (solo side-band, nunca texto/estilos), tests §7.2 (1) |
| RF-23 | §4b (`backgrounds.colorAt` + bitmap con alfa), checklist manual §7.4 |
| RF-24..28 | §4c, tests §7.2 (6) |
| RF-29 | §2.2/§2.3, `clearCellsOf` (§4c), tests §7.2 (6, 11) |
| RF-30 | `shiftPlacements` (§4d), tests §7.2 (8) |
| RF-31 | `clipToVisibleArea` al pintar, sin tocar `resize` (§4d), test §7.2 (9) |
| RF-32 | §4d (alt-screen por colocación), test §7.2 (10) |
| RF-33 | `KittyVirtualPlacement`/`KittyPlaceholderDecoder` sin tocar (§1), `KittyPlaceholderTest` |
| RF-34 | §4f (`a=t`/`a=T` con `w=`/`h=` como celdas), §7.5 |
| RF-36 | §2.1 (`registryId`), §2.2 (bytes solo en el payload), `decodeRawArgb` en `TerminalImageData` |
| RF-37 | `oldestPlacementIndexForImage` (§3), `enforcePlacementCap` (§4e), tests §7.1 y §7.2 (11) |
| RF-38 | `evictImagesForBudget` + `clearPlacementsOfImage` (§4e), test §7.2 (11) |
| RF-39 | `parseKittyId` (`Integer.parseUnsignedInt`) para `i=`/`I=`/`p=` y `Integer.toUnsignedString` en `replyKitty` (§4a), `KittyOpenTuiPreviewTest` (§7.3, captura real) |
| RNF-1 | Sin cambios en `gradle/libs.versions.toml` ni en dependencias de módulo |
| RNF-2 | `KittyPlacementGeometry` + `KittyPlacementGeometryTest` (§3, §7.1) |
| RNF-3 | `collectPlacementsForRow` con lista del llamante, un bitmap por colocación y pasada, banda por tramo (§4b, §5) |
| RNF-4 | Pintor y geometría compartidos (§3, §5.2, §6.5) |
| RNF-5 | Orden fijado por `(z, seq)`; sin temporizadores ni orden de hash (§3, §4b) |
| RNF-6 | §7.5 (adaptaciones mínimas) y suites existentes citadas en §7.2 |
| RNF-7 | §2.3 (mismo `int[]` por fila), §6.4 (payload único), §4e (presupuesto de bytes) |

---

## 10. Ambigüedades detectadas (no se cambia la spec)

1. **`w=`/`h=` en `a=p` choca con cuatro tests existentes.** La spec decide que en `a=p` son píxeles
   (RF-11 + `Dudas abiertas`) y RNF-6 pide que "la suite existente siga en verde". Se resuelve adaptando
   esas cuatro llamadas a `c=`/`r=` (mismo efecto, bytes distintos) y anotándolo en la tarea. Si se prefiere
   no tocar esos bytes de entrada, hay que decidir en la spec una regla de compatibilidad (p. ej. tratar
   `w=`/`h=` como celdas solo cuando no hay `c=`/`r=`), lo que contradice la decisión ya tomada.
2. **RF-9 con métricas de celda desconocidas.** `mCellWidthPixels`/`mCellHeightPixels` valen 0 hasta que el
   host los fija; con 0, derivar la celda que falta no tiene sentido físico. El plan usa 1 px por celda
   (mismo criterio que `deriveMissingCells` hoy): conviene confirmarlo en la spec o dejarlo dicho.
3. **RF-15 y la identidad de la colocación vacía.** La spec pide registrar la colocación con recorte vacío;
   el plan la registra (`isVisible()==false`, no reserva celdas) y permite `a=p` con el mismo `p=` para
   sustituirla. Si "registrar" debiera significar otra cosa (p. ej. responder INVALID), se corrige en la
   spec antes de las tareas.
4. **RF-8 vs origen fuera de rejilla.** La spec no dice nada del caso "el origen del cursor ya está fuera
   de la rejilla"; el plan mantiene el INVALID actual (§6.6). Es la única lectura coherente con RNF-6.
