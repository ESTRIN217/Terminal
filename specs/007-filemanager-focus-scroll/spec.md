# Spec 007 — Cursor de teclado y scroll del gestor de archivos

## Contexto

Bug reportado: al abrir una carpeta con muchas entradas, la lista "cae" con la fila 10
arriba y resaltada — se abre una carpeta y hace scroll automático.

## Causa raíz (dos defectos combinados)

1. **El cursor sobrevive entre directorios.** `FileManagerViewModel.applyListing`
   normalizaba `focusedIndex` con `coerceIn(0, lastIndex)` sin distinguir si el listado
   era de un directorio nuevo: el índice de la carpeta anterior (flechas ↑/↓, tap en una
   fila, PGDN que salta +10) se heredaba a la nueva. El `LaunchedEffect(focusedIndex)` de
   `FileManagerScreen` llamaba `animateScrollToItem(index)`, que **alinea la fila
   resaltada con el borde superior del viewport** → la fila 10 heredada quedaba arriba,
   resaltada.
2. **La `LazyColumn` retiene su offset de scroll entre directorios**
   (`rememberLazyListState` persiste por composición, no por carpeta): si la carpeta
   anterior quedó scrolleada, la nueva se mostraba ya metida página adentro, con la fila
   10 arriba aun sin cursor heredado.

Además `animateScrollToItem` se aplicaba sin condiciones: mover el resaltado a una fila
ya totalmente visible arrancaba la página de todos modos.

## Código de referencia

- Cursor introducido en `945930b2` (navegación por teclado y extra keys), normalización
  inline en `applyListing`.
- `FocusCursorPolicy` no existía: la decisión de cursor vivía dentro del
  `uiState.update` de la VM y del `LaunchedEffect` de la pantalla.

## RF

### RF-1 — El cursor muere al cambiar de directorio

Tras cualquier cambio de directorio (`navigateTo`, `goBack`, `goForward`, `goUp`,
apertura inicial) el cursor queda sin asignar (`focusedIndex = -1`): la lista abre en
arriba y **sin resaltado** hasta interacción (flechas de teclado o tap en una fila).
Mismo criterio para un cambio de directorio con listado vacío.

### RF-2 — El scroll se reinicia al cambiar de directorio

La `LazyColumn` se posiciona en la primera fila al publicar el listado de un directorio
distinto; la nueva carpeta nunca hereda el offset de scroll de la anterior.

### RF-3 — El relistado del mismo directorio conserva el cursor

Búsqueda, orden, ocultos, crear/borrar/renombrar/pegar (mismo directorio) conservan el
cursor si hay uno asignado (acotado al nuevo tamaño) y lo dejan sin asignar si no lo
había (`-1` nunca se convierte en 0).

### RF-4 — Scroll mínimo para hacer visible el resaltado

- Fila totalmente visible: la lista no se mueve.
- Fila cortada por el borde superior: se alinea con el borde superior.
- Fila cortada por el borde inferior: empuje mínimo (por el desbordamiento).
- Fila fuera de vista: alineación contra el borde más próximo (arriba si está por
  encima, abajo si está por debajo, estimando la altura con la última fila visible).

### RF-5 — Primer "abajo" desde cursor sin asignar va a la fila 1

`moveFocus(+1)` con cursor `-1` posiciona el índice 0 (fila 1), no salta a la fila 2.
El resto de acciones (HOME/END/PGUP/PGDN/TAB/ENTER) no cambian de semántica.

## Decisiones

- **Lista limpia al abrir sin resaltado** (decisión del usuario 2026-10-20) frente a la
  alternativa de heredar el cursor implícito a fila 1. Con flechas el cursor aparece en
  fila 1; Tab antes de interacción no selecciona nada (no-op seguro).
- **Lógica del cursor en clase pura (`FocusCursorPolicy`)** con test JUnit sin Android.
  La decisión deja de vivir en el `uiState.update` de la VM.
- **Sin dependencias nuevas** (catálogo sin delta).
- La política de scroll visual (`ensureItemVisible`, reset por cambio de directorio) es
  UI de `FileManagerScreen` (Compose, pinta y despacha); no es testable con JUnit plano.

## Ficheros afectados

- `filemanager/src/main/java/com/estrin217/filemanager/compose/FocusCursorPolicy.kt` (nuevo, lógica pura)
- `filemanager/src/main/java/com/estrin217/filemanager/compose/FileManagerViewModel.kt`
- `filemanager/src/main/java/com/estrin217/filemanager/compose/FileManagerUiState.kt` (doc)
- `filemanager/src/main/java/com/estrin217/filemanager/compose/FileManagerScreen.kt`
- `filemanager/src/test/java/com/estrin217/filemanager/compose/FocusCursorPolicyTest.kt` (nuevo)

## Checklist de dispositivo

- [ ] Abrir una carpeta grande: abre en la fila 1, sin resaltado, sin scroll.
- [ ] Abrir carpeta con las teclas (↓×10 + Enter): la nueva carpeta abre arriba, sin resaltado.
- [ ] ↓ una vez: resaltado en fila 1; otra ↓: fila 2 (no salta de 2 en 2).
- [ ] ↓ hasta la fila parcialmente visible: empuje mínimo, la fila queda cortada arriba — la lista sube solo lo justo.
- [ ] Volver (↑ barra) desde una subcarpeta scrolleada: la carpeta padre abre arriba.
- [ ] Búsqueda/orden/ocultos con cursor puesto: el resaltado persiste en su índice.
