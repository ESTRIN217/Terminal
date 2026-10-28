# Tareas 021 — Almacenamiento USB: detección informativa + acceso a ficheros vía SAF

Importante: T1–T3 son de **factibilidad** y NO dejan código de producción permanente;
su salida es un registro de resultados en `spec.md` (apartado específico). Con F3 en
negativo, la spec se reescopa (RF-4/RF-5 fuera) y las tareas T5+ no se marcan.

- [ ] **T1. Factibilidad F3 — ¿Android monta el pendrive en el dispositivo de pruebas?** RF-4
  - Hecho cuando: resultado documentado en `specs/021-usb-almacenamiento/spec.md`
    (apartado "Resultados de factibilidad"): volumen visible en `StorageManager`/`dumpsys mount`
    al enchufar el pendrive, o decisión de reescopar (RF-1 + limitaciones, se actualiza spec).

- [ ] **T2. Factibilidad F2 — acceso a `/storage/XXXX-YYYY` desde el app (targetSdk 28).** RF-4
  - Hecho cuando: resultado documentado: `java.io.File` funciona o necesita `DocumentsContract`
    (o ambos fracasan → reescopo). Sin cambios en el manifest ni en permisos existentes.

- [ ] **T3. Factibilidad F1 — ¿proot admite `-b /storage/XXXX-YYYY:/root/usb`?** RF-5
  - Hecho cuando: resultado documentado en spec.md; el patch temporal del bind está revertido
    y la vía preferida (bind vs puente) queda decidida en la spec antes de escribir código
    definitivo.

- [x] **T4. Funciones puras `UsbBlockDeviceFormatLibrary` + tests JUnit.** RF-1, RF-2, RF-4, RNF-2
  - Hecho cuando: la librería implemente `isMassStorage` (clase 0x08), `formatLsblk` (tabla
    con aviso de limitación, sin flotantes), `parseVolumeId`, JSON round-trip de
    block devices y `encode/decodePathForBridge`; `UsbBlockDeviceFormatLibraryTest` verde
    con `./gradlew :termux-shared:test` (tests en rojo primero).

- [x] **T5. Constantes en `TermuxConstants`.** RF-3, RF-4
  - Hecho cuando: `TermuxConstants` incluya rutas guest (`usr/local/bin/lsblk`,
    `usr/local/bin/terminal-usb-cp`) y endpoint del puente de ficheros sin tocar las
    constantes de la 020.

- [x] **T6. Snapshot extendido en `UsbBridgeServer` (sección block devices).** RF-1, RF-6
  - Hecho cuando: ATTACHED/DETACHED actualicen `/tmp/usb_devices.json` con la sección
    block devices (volúmenes + flag mass-storage) usando la librería pura; JSON validado
    con round-trip en `app` test.

- [x] **T7. Wrapper guest `lsblk` instalado vía `usbToolsNeedRefresh`.** RF-1, RF-2, RF-3
  - Hecho cuando: `DebianInstaller` instale `usr/local/bin/lsblk` (0755, byte-compare) que
    lea el snapshot, imprima tabla + aviso, exit 0, y conserve intactos los tools de la 020
    (`lsusb`/`termux-usb`/`terminal-usb`).

- [x] **T8. Acceso a ficheros: bind proot o puente de ficheros según F1/F2.** RF-4, RF-5
  - Hecho cuando (vía (a), decidida según factibilidad F1/F3): `handleClient` de
    `UsbBridgeServer` refresca `updateDeviceSnapshot` just-in-time en cada conexión (RF-6
    enmienda; idempotencia verificada en test JVM, disparo real en dispositivo), y
    `DebianInstaller` instaló (0755, byte-compare, `usbToolsNeedRefresh`) el wrapper guest
    `terminal-usb-cp` (python3): ping al bridge antes de leer el snapshot (refresco JIT),
    `list/read/write` sobre `/root/storage/<ID>/` vía el bind global `-b /storage`,
    `cp -n` sin sobrescribir en silencio (exit 3 = destino existía, convención documentada
    en el commit), y `..` que escapa del volumen rechazado (exit 2). Puedes pensar que la
    parte JIT del snapshot fue refactorizada en `writeSnapshotFiles` (testable, ambos ficheros).
    Verificación guest: `terminal-usb-cp read/write` con fichero ≥10 MB sin corrupción.

- [x] **T8.2. Fix en dispositivo: herramientas guest leen volúmenes en vivo del
      `/proc/mounts` del guest.** RF-1, RF-4
  - Hecho cuando: `lsblk` añade filas en vivo (`type=volume`, tamaño en vivo por
    `statvfs` sobre el bind, `-` si no medible,
    `/root/storage/<ID>`) parseando `/proc/mounts` del guest con deduplicado
    contra el snapshot (microSD visible, sin colgarla del pendrive); y
    `terminal-usb-cp` descubre volúmenes en vivo de `/proc/mounts` (se elimina
    el ping y el fallback al snapshot; exit codes y anti-`..` intactos);
    smoke funcional JVM del python3 generado contra `/proc/mounts` fake.

- [x] **T8.1. Fix en dispositivo: fuente de mountpoints = `/proc/mounts`.** RF-1, RF-6
  - Hecho cuando (defecto detectado al instalar el build de T8): SELinux deniega el
    `readdir` de `/storage` (untrusted_app) de modo que `File("/storage").list()`
    devolvía una lista vacía con el volumen montado. Fix red→verde: función pura
    `UsbBlockDeviceFormatLibrary.extractVolumeMountpoints(mountsContent)` (patrón
    `/storage/XXXX-YYYY`, sin duplicados, ordenado, `emulated` excluido, tolerante a
    espacios/options de montinfo) con tests JUnit, y `UsbBridgeServer.listMountedVolumeIds`
    pasa a leer `/proc/mounts` (fichero global) con catch `IOException` → lista vacía +
    log, delegando todo el parseo en la librería (cero regex en el server). Shape
    JSON T6/T4 intacto (`volumeId`/`mountpoint` rellenos).

- [x] **T9. Documentación en wiki (limitaciones + guía) y validación global.** RF-2, RF-5
  - Hecho cuando: `wiki/limitaciones.html` documente SELinux/sysfs (sin fix) y el resultado
    del experimento del bind; documentación de uso de `lsblk` y `terminal-usb-cp` en español;
    verificación en dispositivo checklist (`lsblk` sin crash, `terminal-usb-cp` con todas las vías
    confirmadas) y `./gradlew test` verde al 100% en todos los módulos.

## Verificación en dispositivo (2026-10-27, cierre de T9)

- `lsblk`: filas live de los dos volúmenes (microSD `47A8-1024` 3G y pendrive
  `459E-170F` 965M), `type=volume`, sizes reales vía statvfs, exit 0.
- `terminal-usb-cp 47A8-1024 write /tmp/a` → OK (`ls -l` confirma);
  repetición → exit 3 "destination exists (not overwritten)";
  `read 47A8-1024 a` → contenido correcto; limpieza OK.
- `./gradlew test` completo verde (última pasada antes del cierre).
