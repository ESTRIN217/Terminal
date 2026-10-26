# Tareas 020 — Soporte USB sin root: lsusb + bridge UsbManager

- [x] **T1. Funciones puras `UsbDeviceFormatLibrary` y tests JUnit.** RF-1, RF-2, RF-3, RNF-2
  - Hecho cuando: `termux-shared/.../UsbDeviceFormatLibrary.java` implemente el formateo de `lsusb`, el parseo de rutas de dispositivo `/dev/bus/usb/BBB/DDD` y la serialización JSON de dispositivos; `UsbDeviceFormatLibraryTest` pase con `./gradlew :termux-shared:test`.

- [x] **T2. Constantes en `TermuxConstants` y feature en `AndroidManifest.xml`.** RF-3, RF-4
  - Hecho cuando: `TermuxConstants` defina las rutas del socket IPC (`/tmp/terminal_usb.sock`), fichero snapshot (`/tmp/usb_devices.json`) y rutas ejecutables guest; `AndroidManifest.xml` declare el feature `android.hardware.usb.host` como opcional (`android:required="false"`).

- [x] **T3. Scripts guest `lsusb` y `termux-usb` en `DebianInstaller`.** RF-1, RF-3
  - Hecho cuando: `DebianInstaller` instale en `usr/local/bin/lsusb`, `usr/local/bin/termux-usb` y symlink `usr/local/bin/terminal-usb` los ejecutables con modo `0755` durante la instalación y reparación de permisos; `DebianInstallerTest` verifique su instalación.

- [x] **T4. Servidor `UsbBridgeServer` en el host Android.** RF-3, RF-4, RF-5, RF-6
  - Hecho cuando: `UsbBridgeServer` implemente la escucha en el socket UNIX local de `/tmp`, la consulta a `UsbManager`, la emisión del diálogo de autorización para `-r`/`-e`, y el envío del `FileDescriptor` vía `SCM_RIGHTS`.

- [x] **T5. Ciclo de vida en `TermuxService` y sincronización de snapshot.** RF-7
  - Hecho cuando: `TermuxService` inicie `UsbBridgeServer` al abrir sesiones activas y lo detenga al no quedar ninguna; registre el `BroadcastReceiver` para `ACTION_USB_DEVICE_ATTACHED` y `ACTION_USB_DEVICE_DETACHED` manteniendo `/tmp/usb_devices.json` sincronizado.

- [x] **T6. Documentación en wiki y validación global.** Criterios de finalización
  - Hecho cuando: `wiki/limitaciones.html` documente cómo opera el subsistema USB sin root; `./gradlew test` pase al 100% en todos los módulos.
