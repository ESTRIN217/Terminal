# Tareas — Spec 004

Estado: cerrada el 2026-10-20 (validada en dispositivo).

- [x] **T4** Validar en dispositivo: sesión Debian → `date` muestra la hora
  local del teléfono; `echo $TZ` = el ID del dispositivo. Checklist en
  `device-checklist.md`.
  - ✔️ 2026-10-20: `date` → `mar 20 oct 2026 08:11:00 -04` (hora local correcta,
    offset -04 de la zona del dispositivo). Verificado por el usuario en el
    móvil — "Perfecto verificado". Spec cerrada.

- [x] **T1** (RF-02, RF-03, NFR-01) Test en rojo: `testIsGuestTimeZoneAvailable_*`
  con rootfs de juguete en un directorio temporal (comprobar entrada presente →
  `true`, ausente → `false`, ID con `..`/vacío → `false`) y
  `testGetEnvironment_tzNotSetWithoutRootfs` (host sin rootfs → `TZ` ausente).
- [x] **T2** (RF-01, RF-02, RF-03) Implementar `ENV_TZ`, `GUEST_ZONEINFO_DIR`,
  `isGuestTimeZoneAvailable(String)` / `isGuestTimeZoneAvailable(String, String)`
  y exportar `TZ` en `ProotShellEnvironment.getEnvironment` solo si hay probe
  positiva. Comentarios técnicos en inglés.
- [x] **T3** (NFR-01) `./gradlew test` en verde.
