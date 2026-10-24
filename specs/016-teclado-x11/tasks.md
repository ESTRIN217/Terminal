# Tareas — Spec 016

Estado: cerrada (2026-10-24)

- [x] **T1** (RF-01) lorie: desacoplar `InputEventSender` de `MainActivity`
  (constructor `(InputStub, Consumer<Boolean>)`, original delega), commit en el
  submódulo (`9f8d6d12`) y pin actualizado en el repo principal.
- [x] **T2** (RF-03, NFR-01) Test en rojo → verde: `X11ExtraKeysTest`.
- [x] **T3** (RF-01, RF-02) `X11TabController`: `InputEventSender` +
  `setOnKeyListener` + `handleKey` espejo + sync `preferScancodes` +
  filtro win-key.
- [x] **T4** (RF-03) Extra keys: `sendExtraKey` (aplicador de pasos con
  modificadores por delta, keycode/text, PASTE, KEYBOARD, zoom),
  `X11SessionRegistry.get`, branch `X11` en `TermuxMainScreen`.
- [x] **T5** (NFR-03) `./gradlew test` verde full; `assembleDebug` OK con
  `libXlorie.so` verificado vía unzip en ambos APK.
- [x] **T6** Documentación: CHANGELOG, MEMORY, estado de spec y cierre del
  plan; la implementación ya quedó reflejada en la documentación del repo.
- [x] **T7** Validación en dispositivo: HW keys (con/sin workaround), IME
  (letras/enter/del/composición), barra extra keys (CTRL+c, ESC, PASTE, macro,
  ZOOM), touch sin regresión.
