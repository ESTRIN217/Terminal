# Tareas — Spec 015

Estado: cerrada 2026-10-24 (T1–T3 verificadas con `./gradlew test` en verde;
T4 hecha; pendiente validación fina en dispositivo, no bloquea).

- [x] **T1** (RF-01, NFR-01) Test en rojo: `testGetEnvironment_displayDefaultsToZero`
  y `testGetEnvironment_displayIsTheOnlyNewKey`; ampliación de
  `testGetEnvironment_guestValues`. Rojo confirmado (3 fallos).
- [x] **T2** (RF-01, RF-02) Implementar constante `ENV_DISPLAY` y export en
  `ProotShellEnvironment.getEnvironment()`.
- [x] **T3** (NFR-01, NFR-03) `./gradlew test` en verde (40 tests del paquete,
  suite completa OK).
- [x] **T4** Documentación: wiki (x11, limitaciones) — export pasa a override
  opcional.
- [x] **T5** Cierre: CHANGELOG, MEMORY.md, estado de spec, commit.
