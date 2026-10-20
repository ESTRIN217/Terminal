# Tasks — Spec 003 Links de texto plano clicables y subrayados

- [x] **T1. `TerminalUrlScanner`: regex única + funciones puras de mapeo.** RF-1, RF-6, RF-8; RNF-2
  - Hecho cuando: la clase existe en `terminal-emulator` con `getUrlPattern()`
    (builder movido verbatim), `collectLogicalLine`, `findUrlSpans`, `cellContains`/
    `columnRangeFor` y `getUriAt`; su test en rojo (esquemas permitidos, rangos de
    celdas, glifo ancho, dos links, filas fuera de la rejilla) pasa en verde.
- [x] **T2. `TermuxUrlUtils.getUrlMatchRegex()` delega en el scanner.** RNF-4
  - Hecho cuando: los tests existentes que tocan `TermuxUrlUtils` siguen en verde sin
    cambiar sus llamadas; el patrón devuelto es el mismo objeto que el del scanner.
- [x] **T3. Caché por huella de contenido (RNF-1).**
  - Hecho cuando: segunda consulta con contenido sin cambios no re-ejecuta el
    `find()` (contador de rescans en el test); un cambio en el texto (o el wrap) de
    cualquier fila del renglón provoca rescan.
- [x] **T4. Subrayado en el canvas Compose (`ComposeTerminalFrame`).** RF-3, RF-6
  - Hecho cuando: `ComposeTerminalFrameTest` en rojo→verde: el run se corta donde
    empieza/termina un span, el tramo sale subrayado y celdas fuera del span no.
- [x] **T5. Tap en el canvas Compose (`ComposeTerminalCanvas`).** RF-1, RF-2, RF-5, RF-9
  - Hecho cuando: tras un miss de OSC 8 el tap sobre un span abre la URL (orden RF-2),
    esquema no permitido no abre y cae al camino normal, y el resto de gates de hoy
    (selección, pinch, mouse-tracking) siguen intactos.
- [x] **T6. Subrayado en el renderer legacy (`TerminalRenderer`).** RF-3, RF-6
  - Hecho cuando: mismo corte de runs/subrayado en la view legada, con los centinelas
    de estado comparados entre renderers (lección de Spec 002); test de renderer no
    posible (Canvas) — validación en dispositivo.
- [x] **T7. Tap en la view legada (`TerminalView.openHyperlinkAt` + un solo punto de
  apertura).** RF-1, RF-2, RF-5, RF-6
  - Hecho cuando: tras el miss de OSC 8 añade el probe del scanner y abre con la
    misma allowlist; las defensas de fila fuera de la rejilla se heredan del scanner.
- [x] **T8. Strings del ajuste (RF-11) + verificación de puertas RNF-1 en el legado.**
  - Hecho cuando: `terminal_hyperlinks_desc` actualizada en `values/`, `values-es-rVE`,
    `values-pt-rBR` y el grep de literales (constitución #6) sigue vacío; el renderer
    legacy pide spans solo con el toggle activado.
- [ ] **T9. `./gradlew test` completo en verde + checklist del dispositivo.**
  - Hecho cuando: los 5 ítems del criterio de finalización de la spec se cumplen en
    ambos renderers (subrayado, tap abre, link partido completo, ajuste apagado,
    diálogo More intacto), `./gradlew test` en verde y commits Convencionales en
    `release/2.0`.

Máx 30 min por tarea; T1→T8 en orden. T4–T7 dependen de T1–T3; T6/T7 son pares
(view), T4/T5 son pares (canvas).
