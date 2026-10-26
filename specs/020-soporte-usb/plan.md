# Plan 020 — Soporte USB sin root: lsusb + bridge UsbManager

Spec de referencia: `specs/020-soporte-usb/spec.md` (aprobada).

## Archivos y responsabilidades

| Archivo | Cambio | RF |
|---|---|---|
| `termux-shared/.../termux/usb/UsbDeviceFormatLibrary.java` (NUEVO) | Funciones puras: formateo de línea `lsusb`, parseo de identificadores VID/PID y rutas `/dev/bus/usb/BBB/DDD`, serialización/deserialización de inventario USB en JSON | RF-1, RF-2, RF-3, RNF-2 |
| `termux-shared/src/test/.../UsbDeviceFormatLibraryTest.java` (NUEVO) | Tests JUnit exhaustivos de las funciones puras sin SDK de Android | RNF-2 |
| `termux-shared/.../termux/TermuxConstants.java` | Constantes para rutas del socket `/tmp/terminal_usb.sock`, fichero de snapshot `/tmp/usb_devices.json`, rutas guest `/usr/local/bin/lsusb` y `/usr/local/bin/termux-usb` | RF-1, RF-3 |
| `app/src/main/AndroidManifest.xml` | Declaración `<uses-feature android:name="android.hardware.usb.host" android:required="false" />` | RF-4 |
| `app/src/main/java/com/termux/app/usb/UsbBridgeServer.java` (NUEVO) | Servidor UNIX domain socket (`LocalServerSocket` en `${TMPDIR}/terminal_usb.sock`), gestión de `UsbManager`, emisión de diálogo de permisos y paso de FD mediante `SCM_RIGHTS` | RF-3, RF-4, RF-5, RF-6, RF-7 |
| `app/src/main/java/com/termux/app/DebianInstaller.java` | Instalación de los scripts ejecutables `/usr/local/bin/lsusb` y `/usr/local/bin/termux-usb` en el rootfs con modo `0755` durante `repairInstalledRootfsPermissions()` | RF-1, RF-3 |
| `app/src/main/java/com/termux/app/TermuxService.java` | Inicio y parada del `UsbBridgeServer` según sesiones activas (espejo del patrón de `VirglServerManager`) | RF-7 |
| `wiki/limitaciones.html` | Documentación de la solución: cómo funciona `lsusb` y `termux-usb` sin root vía API de Android | Documentación |

## Funciones puras (`UsbDeviceFormatLibrary`)

1. `formatLsusb(int bus, int device, int vid, int pid, String? manufacturer, String? product) -> String`
   Produce:
   `Bus %03d Device %03d: ID %04x:%04x %s`
   Si `manufacturer` y `product` están disponibles: `"<manufacturer> <product>"` (colapsando espacios).
   Si solo uno está disponible: el que esté.
   Si ninguno está disponible: cadena vacía tras el ID.

2. `parseDevicePath(String path) -> int[] {bus, dev}`
   Parsea cadenas tipo `"/dev/bus/usb/001/002"` o `"001/002"`. Devuelve `[1, 2]` o `null` si formato inválido.

3. `devicePath(int bus, int device) -> String`
   Produce `"/dev/bus/usb/" + String.format(Locale.ROOT, "%03d/%03d", bus, device)`.

4. `toJson(List<UsbDeviceInfo> devices) -> String`
   Serializa la lista de dispositivos conectados a formato JSON legible por scripts.

5. `fromJson(String json) -> List<UsbDeviceInfo>`
   Deserializa el JSON de dispositivos.

## Protocolo del Socket IPC (`terminal_usb.sock`)

Socket UNIX de tipo stream en `/tmp/terminal_usb.sock`:
- Comando `LIST\n`:
  Responde con JSON que contiene la lista de dispositivos disponibles (path, bus, dev, vid, pid, desc).
- Comando `REQUEST <device_path>\n`:
  Dispara `UsbManager.requestPermission(device, pendingIntent)`.
  Suspende la respuesta hasta que el usuario responda en el diálogo nativo de Android.
  Responde `OK: GRANTED\n` o `ERR: DENIED\n`.
- Comando `OPEN <device_path>\n`:
  Si el permiso está concedido, llama a `UsbManager.openDevice(device)`.
  Obtiene el FD de `connection.getFileDescriptor()`.
  Envía el FD mediante `LocalSocket.setFileDescriptorsForSend(new FileDescriptor[]{fd})` junto con el payload `OK: FD\n`.
  Si no hay permiso o error, responde `ERR: <mensaje>\n`.

## Scripts Guest en `/usr/local/bin/`

1. `/usr/local/bin/lsusb`:
   - Script POSIX / Python3.
   - Si `/tmp/usb_devices.json` existe y es legible, lee los dispositivos. Si no, conecta al socket `/tmp/terminal_usb.sock` y envía `LIST`.
   - Incluye siempre el controlador raíz virtual (`Bus 001 Device 001: ID 1d6b:0002 Linux Foundation 2.0 root hub`).
   - Imprime cada dispositivo en formato estándar `lsusb`.
   - Código de salida 0.

2. `/usr/local/bin/termux-usb`:
   - Script cliente Python3 con manejo de `socket.recvmsg` / `SCM_RIGHTS`.
   - Soporta flags estándar de Termux:
     `-l`: lista dispositivos como array JSON o rutas.
     `-r <device>`: solicita permisos y espera confirmación.
     `-e <command> <device>`: solicita/abre el dispositivo, recibe el FD vía `SCM_RIGHTS`, exporta `TERMUX_USB_FD=<fd>` y ejecuta `<command>`.
   - `/usr/local/bin/terminal-usb`: enlace simbólico a `termux-usb`.

## Estrategia de tests

- Tests unitarios en `termux-shared` para `UsbDeviceFormatLibrary`:
  - Formateo de `lsusb` con varios casos (VID/PID de 1 a 4 dígitos hex, mayúsculas/minúsculas, con/sin nombre).
  - Parseo de paths malformados, válidos y con ceros a la izquierda.
  - Serialización y deserialización JSON bidireccional idempotente.
- Tests en `app`:
  - Test de ciclo de vida e inicialización de scripts en `DebianInstallerTest`.
- Puerta de calidad CI: `./gradlew test` en verde.
