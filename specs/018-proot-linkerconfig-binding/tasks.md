# Tareas 018

- [x] **T1.** Caso especial `EACCES` + `/linkerconfig` en `new_binding()`
  (`app/src/main/cpp/proot/path/binding.c`), mirroring `canon.c`. RF-01, RF-03
  - Hecho cuando: el diff nativo compila (`./gradlew assembleDebug`) y el
    bloque original de error queda intacto para el resto de casos.
- [x] **T2.** Build APK + `./gradlew test` en verde (sin cambios de tests).
  RF-02, RF-04
  - Hecho cuando: `./gradlew test` exitosa y el asset `proot` re-generado
    (1539896 B, 2026-10-25 08:05, antes 1539848 B).
- [ ] **T3.** Verificación en dispositivo: sesión sin warning, `ls
  /linkerconfig` en guest, resultado de `termux-x11 :0` documentado. RF-02
  - Hecho cuando: captura/log del resultado anotado en spec (estado) y
    `MEMORY.md` actualizado.
- [x] **T4.** Docs: `wiki/x11.html` actualizado a nuevo comportamiento.
  RF-01, RF-02
  - Hecho cuando: la sección del warning ya no lo describe como limitación
    vigente (pendiente re-etiquetar fino según lo observado en dispositivo).
