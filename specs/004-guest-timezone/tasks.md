# Tareas — Spec 004

Estado: implementada (pendiente de validar en dispositivo).

- [x] **T1** (RF-02, RF-03, NFR-01) Test en rojo: `testIsGuestTimeZoneAvailable_*`
  con rootfs de juguete en un directorio temporal (comprobar entrada presente →
  `true`, ausente → `false`, ID con `..`/vacío → `false`) y
  `testGetEnvironment_tzNotSetWithoutRootfs` (host sin rootfs → `TZ` ausente).
- [x] **T2** (RF-01, RF-02, RF-03) Implementar `ENV_TZ`, `GUEST_ZONEINFO_DIR`,
  `isGuestTimeZoneAvailable(String)` / `isGuestTimeZoneAvailable(String, String)`
  y exportar `TZ` en `ProotShellEnvironment.getEnvironment` solo si hay probe
  positiva. Comentarios técnicos en inglés.
- [x] **T3** (NFR-01) `./gradlew test` en verde.
- [ ] **T4** Validar en dispositivo: sesión Debian → `date` muestra la hora
  local del teléfono; `echo $TZ` = el ID del dispositivo. Checklist en
  `device-checklist.md`.
