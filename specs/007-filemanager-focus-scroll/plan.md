# Plan — Spec 007 (cursor de teclado y scroll del gestor)

## Enfoque

Tres capas, de abajo arriba:

1. **Política pura (`FocusCursorPolicy`)** — sin Android. Decide el índice de cursor
   después de publicar un listado (`normalizedIndex`: vacío → `-1`; cambio de directorio
   → `-1`; sin cursor previo → `-1`; mismo directorio → acotado) y tras un paso de
   navegación (`movedIndex`: `-1` cuenta como un paso antes de la fila 1). Delegación en
   la VM: `FileManagerViewModel` deja de decidir y solo pinta estado.
2. **ViewModel** — `applyListing` usa `normalizedIndex`; `moveFocus` usa `movedIndex`.
   Sin cambios de flujo (hilos, jobs, caché intactos).
3. **Screen** — dos comportamientos de scroll:
   - La `LaunchedEffect` única mira el directorio publicado: si cambió,
     `listState.scrollToItem(0)` (instantáneo; mata la retención de la `LazyColumn`,
     RF-2); si no, `ensureItemVisible(focusedIndex)` para el resaltado (RF-4).
   - `ensureItemVisible`: extensión privada sobre `LazyListState` con la geometría de
     `layoutInfo`; nulo/parcial/cortado por bordes. Sustituye al
     `animateScrollToItem` incondicional (causa del "yank" de página).

## Tradeoffs mirados

- *Restaurar scroll/cursor del padre al volver (pila)*: mejor UX teórica, pero la pila
  guarda solo rutas (no posiciones) en `backStack`/`forwardStack`; guardar posiciones es
  otro diseño (fuera de scope, se puede pedirla como spec aparte).
- *Reset del cursor a 0 con resaltado*: disponible en la pregunta al usuario; eligió
  lista limpia. Si en dispositivo molesta, es un cambio de una constante del policy.
- *Nudge con `animateScrollBy` vs `scrollToItem(index, offset)`*: para la fila cortada
  por abajo la geometría está en `visibleItemsInfo` (`animateScrollBy` exacto); para la
  fila bajo el viewport no la hay (filas uniformes en este listado → estimación con la
  última fila visible).

## Riesgos

- La política cambia el cursor visible tras toggles de mismo directorio cuando el
  resaltado cae en otro fichero (el cursor es por índice, no por ruta): paridad con el
  comportamiento previo a la spec, sin regresión.
- `scrollToItem(0)` en listados vacíos: guard con `files.isNotEmpty()`.

## Verificación

- `./gradlew :filemanager:test` (nuevo `FocusCursorPolicyTest`, rojo primero).
- `./gradlew test` completo en verde.
- Aparcamiento final: checklist de dispositivo en spec.md (el usuario valida).
