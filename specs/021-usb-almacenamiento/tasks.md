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

- [ ] **T8. Acceso a ficheros: bind proot o puente de ficheros según F1/F2.** RF-4, RF-5
  - Hecho cuando: SE cumpla una de estas:
    (a) F1 positivo: `ProotShellEnvironment` añade `-b /storage/XXXX-YYYY:/root/usb` dinámicamente
        y `terminal-usb-cp` es un wrapper sobre `cp`;
    (b) F1/F2 van por puente: `UsbFileBridgeServer` (LIST/GET/PUT/COPY con `ERR: EXISTS`,
        sin sobrescribir en silencio) + `terminal-usb-cp` cliente del socket abstracto.
    Verificación guest: `terminal-usb-cp read/write` con fichero ≥10 MB sin corrupción.

- [ ] **T9. Documentación en wiki (limitaciones + guía) y validación global.** RF-2, RF-5
  - Hecho cuando: `wiki/limitaciones.html` documente SELinux/sysfs (sin fix) y el resultado
    del experimento del bind; documentación de uso de `lsblk` y `terminal-usb-cp` en español;
    verificación en dispositivo checklist (`lsblk` sin crash, `terminal-usb-cp` con todas las vías
    confirmadas) y `./gradlew test` verde al 100% en todos los módulos.
