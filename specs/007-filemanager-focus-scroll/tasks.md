# Tareas — Spec 007

## T-1 — Tests en rojo de la política de cursor (RF-1, RF-3, RF-5) — HECHA ✔

Nuevo `FocusCursorPolicyTest` (JUnit plano, sin Android): cambio de directorio resetea;
mismo directorio acota/conserva; `-1` permanece `-1`; listados vacíos → `-1`; primer
`↓` desde `-1` → índice 0; extremos acotados; lista vacía → no-op.

- Verificable: la clase corre sin Robolectric. Estado: **rojo** (la clase no existe).

## T-2 — `FocusCursorPolicy` (RF-1, RF-3, RF-5) — HECHA ✔

Nuevo `FocusCursorPolicy.kt` en `com.estrin217.filemanager.compose`, `object` puro con
`normalizedIndex` y `movedIndex`, KDoc con `@param`/`@return`.

## T-3 — ViewModel delega en el policy (RF-1, RF-3, RF-5) — HECHA ✔

`FileManagerViewModel`: `applyListing` llama `normalizedIndex` (fuera el
`coerceIn(0, sorted.lastIndex)` inline); `moveFocus` llama `movedIndex`.

## T-4 — Screen: reset de scroll por directorio + scroll mínimo (RF-2, RF-4) — HECHA ✔

`FileManagerScreen`: sustituir `LaunchedEffect(state.focusedIndex)` incondicional por
efecto con `(currentPath, files.size, focusedIndex)` + guardia de directorio publicado
(`scrollToItem(0)` instantáneo) y `ensureItemVisible` (extensión privada de
`LazyListState`). Doc de `FileManagerUiState.focusedIndex` precisada (negativo = sin
cursor, no solo lista vacía).

## T-5 — Tests en verde — HECHA ✔

- Verificable: `./gradlew :filemanager:test` verde; luego `./gradlew test` (gate único).

## T-6 — Validación en dispositivo (checklist de spec.md) — HECHA

- Verificable: la checklist de dispositivo está en verde en `spec.md`.

## T-cierre — Validación en dispositivo y cierre definitivo ✔

Hecho cuando: la lista del gestor abre en la fila 1 sin resaltado, ni scroll heredado ni
yank de página al navegar con teclas (todo el RF-1…RF-5 verificado); validada en
dispositivo 2026-10-20 por el usuario; `spec.md` → `cerrada` con la checklist marcada;
`MEMORY.md` aggiornado.
