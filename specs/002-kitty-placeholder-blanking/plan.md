# Plan 002 — Celdas placeholder nunca se pintan como glifo

Estado: aprobado

Plan de la spec `specs/002-kitty-placeholder-blanking/spec.md`. Java puro en
`terminal-emulator`/`terminal-view`, Kotlin en `app`, Hungarian notation `m`, javadoc con
`@param`/`@return`. Sin dependencias nuevas (RNF-1): `gradle/libs.versions.toml` no se toca.

---

## 1. Archivos y responsabilidades

| Archivo | Acción | Responsabilidad | RF |
|---|---|---|---|
| `terminal-emulator/src/main/java/com/termux/terminal/KittyPlaceholderDecoder.java` | modificado | Nuevo `UnresolvedCellVisitor` y sobrecarga de `collectRow(..., unresolvedVisitor)`. La sobrecarga de 4 argumentos se conserva delegando con `null` (compatibilidad con tests y consumidores). | RF-1, RF-4, RF-7 |
| `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` | modificado | Recoge las celdas placeholder no resolubles y las blanquea tras el texto; `drawPlaceholderCell` blanquea antes de resolver (también si el destino cae fuera de la rejilla o no hay imagen). | RF-1, RF-3, RF-4, RF-5, RF-8 |
| `app/src/main/java/com/termux/terminal/compose/ComposeTerminalCanvas.kt` | modificado | Misma recogida y blanqueo en el canvas Compose; `drawComposePlaceholderCell` blanquea antes de resolver. | RF-1, RF-3, RF-4, RF-5, RF-8 |
| `terminal-emulator/src/test/java/com/termux/terminal/KittyPlaceholderBlankTest.java` | nuevo | Contrato del decodificador: celda no resoluble reportada, resoluble no reportada, herencia intacta. | RF-1, RF-4, RF-7 |

`KittyPlaceholderTest`, `KittySuiteReplicationTest` y el camino `a=T`/`a=t` de
`KittyGraphicsTest` no se tocan (RF-2/RF-8 sin regresión).

## 2. Decodificador: reportar la celda, no solo el destino

```java
/** Visitor for placeholder cells that cannot be resolved to an image target. */
public interface UnresolvedCellVisitor {
    /** @param column screen column of the unresolved placeholder cell */
    void visit(int column);
}

public static void collectRow(TerminalRow lineObject, int columns, RowState state,
                              CellVisitor visitor) {
    collectRow(lineObject, columns, state, visitor, null);
}

public static void collectRow(TerminalRow lineObject, int columns, RowState state,
                              CellVisitor visitor, UnresolvedCellVisitor unresolvedVisitor) {
    // ... mismo recorrido ...
    if (codePoint == PLACEHOLDER_CODE_POINT) {
        final Target target = decode(lineObject.getStyle(column), text, charIndex, charsUsed, state);
        if (target != null) visitor.visit(column, target);
        else if (unresolvedVisitor != null) unresolvedVisitor.visit(column);
    }
    // ...
}
```

Decisiones:

- **No se inventa un destino.** `decode()` sigue devolviendo `null` para fg no-id, sin
  tocar `RowState`; por eso una celda no resoluble no avanza la herencia (RF-7). El nuevo
  visitante solo comunica el hecho de que la celda *es* un placeholder.
- **Una celda resoluble pero fuera de rejilla no es "no resoluble"**: se reporta como
  destino y el renderer decide (RF-3). Así el decodificador no necesita conocer las
  colocaciones virtuales.
- El visitante es una interfaz propia (no `IntConsumer`) para mantener el estilo del
  módulo y evitar boxing.

## 3. Renderers: nunca dibujar `U+10EEEE`

Ambos renderers comparten la misma estructura y ya recogen los placeholders resolubles en
una lista que se pinta **después** del texto con el fondo efectivo de la celda. El cambio
añade una lista de columnas no resolubles al mismo recorrido y un blanqueo después del
texto:

1. Durante `collectRow` (solo con imágenes activas, RF-6):
   - destino válido → `skipCells.add(col)` + lista de placeholders (como hoy);
   - sin destino → `skipCells.add(col)` + `unresolved[count++] = col`.
2. Tras el texto (y tras las pasadas de imagen, igual que los placeholders resolubles):
   - cada columna de `unresolved` se blanquea con el fondo efectivo (paleta, vídeo
     inverso, selección y SGR del cliente), RF-5.
3. `drawPlaceholderCell` / `drawComposePlaceholderCell`: **blanquear primero** y luego
   resolver el destino. Si la colocación virtual no existe, la imagen fue desalojada o el
   `(fila, columna)` cae fuera de `c×r`, la celda queda con su fondo y sin glifo (RF-3).

Sin asignaciones por celda en el renderer legado: el buffer de columnas no resolubles es
un `int[]` de tamaño `columns` alojado una vez por frame y reutilizado en todas las filas
(RNF-2). En Compose es un `IntArray(columns)` por frame con un contador local.

## 4. Tests

`KittyPlaceholderBlankTest` (JUnit 3, `TerminalTestCase`, sin Android):

- RF-4: placeholder con fg por defecto → cero destinos y columna reportada como no
  resoluble.
- RF-1/RF-2: placeholder con fg-id y diacríticos → un destino y cero no resolubles.
- RF-7: `[resoluble][no resoluble][resoluble]` → la tercera celda decodifica con
  `col = 1` (hereda de la primera), no con `col = 2`.
- Texto normal (`"hello"`) → ninguna celda reportada.

La parte de pintado (RF-3/RF-5/RF-8) no es verificable con JUnit puro (no hay Robolectric,
`Canvas` es Android): se valida en dispositivo con copilot, opencode y yazi, y el build de
`app` garantiza que ambos caminos compilan.

## 5. Trazabilidad

| RF | Dónde |
|---|---|
| RF-1 | decodificador (reporte) + renderers (`skipCells` siempre) |
| RF-2 | sin cambios en `decode`/pintado de destino válido; tests existentes |
| RF-3 | blanqueo previo en `drawPlaceholderCell`/`drawComposePlaceholderCell` |
| RF-4 | nuevo visitante + blanqueo de columnas no resolubles |
| RF-5 | blanqueo con el mismo resolvedor de fondo que el resto del texto |
| RF-6 | el bloque entero vive dentro de `if (imagesEnabled)` |
| RF-7 | `decode` sale antes de tocar `RowState`; test de herencia |
| RF-8 | mismo cambio en `TerminalRenderer` y `ComposeTerminalCanvas` |

## 6. Riesgos

- **Orden de blanqueo**: se hace después de la segunda pasada de imágenes para no tapar
  una imagen `z>=0` que cubra la celda; es el mismo orden que ya usan los placeholders
  resolubles.
- **Celdas bajo `z<0`**: `skipBackgroundCells` deja el glifo visible; si además son
  placeholder no resoluble, `skipCells` gana y la celda se blanquea. Es lo que dicta la
  spec (la celda placeholder manda).
- **Coste**: un `int[]` por frame; cero asignaciones por celda.
