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

## Enmienda A

- [x] **T5.** `TermuxInstaller`: actualización de `proot`/`virgl_test_server`
  cuando el asset difiere en tamaño del binario instalado; helper puro con
  test JUnit (rojo→verde). RF-05
  - Hecho cuando: `./gradlew test` en verde y la lógica de upgrade cubierta.
- [x] **T6.** Overlay X11 sin comando ni botón de copiar; strings ×3
  ajustadas. RF-06
  - Hecho cuando: `./gradlew test` en verde; sin referencias en main/test a
    `x11_copy_command`, `x11_command_copied`, `buildGuestCommand`,
    `buildLaunchCommand`, `chooseLaunchCommand` ni `companionCommand`.
- [x] **T7.** `TermuxService` deja de escribir el wrapper y lo elimina
  (idempotente); `TermuxX11GuestLoaderLibrary` reducido; se retira la
  inyección `TERMUX_X11_APK`; constantes huérfanas fuera. RF-07, RF-08
  - Hecho cuando: `TermuxX11GuestLoaderLibraryTest` ajustado y verde;
    `./gradlew test` global verde.
- [x] **T8.** Wiki (RF-10), spec (estado), `MEMORY.md`, commits
  Convencionales. RF-10
  - Hecho cuando: página sin referencias al comando copiable/guest;
    memoria actualizada.
