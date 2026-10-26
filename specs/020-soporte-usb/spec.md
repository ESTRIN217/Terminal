# Spec 020 — Soporte USB sin root: enumeración (lsusb) e interacción (bridge UsbManager)

Estado: **implementada** (2026-10-26; T1–T6 completadas, suite global `./gradlew test` en verde)

## Contexto y objetivo

En el rootfs Debian de Terminal, ejecutar `lsusb` falla con el error:

```
root@localhost:~# lsusb
unable to initialize usb specunable to initialize libusb: -99
```

La causa raíz es que `lsusb` y `libusb` intentan enumerar y abrir directamente los nodos `/dev/bus/usb/` o escanear `/sys/bus/usb/devices/`. En dispositivos Android sin root, el kernel Linux y la política SELinux de `untrusted_app` deniegan el acceso directo a estos nodos del sistema de archivos a las aplicaciones de usuario. Además, aunque PRoot simula UID 0 (`fake_id0`), las llamadas al sistema reales se ejecutan bajo el UID no privilegiado de la app Android (`u0_a...`).

Sin embargo, Android proporciona la API oficial de host USB (`android.hardware.usb.UsbManager`), que permite a las aplicaciones:
1. Enumerar los dispositivos USB físicamente conectados (Vendor ID, Product ID, clase de dispositivo, fabricante, nombre del producto) sin diálogos ni permisos especiales.
2. Solicitar autorización explícita al usuario mediante el diálogo nativo de Android (`UsbManager.requestPermission()`).
3. Abrir una conexión y obtener un descriptor de archivo nativo (`FileDescriptor`) con el que interactuar con el dispositivo mediante llamadas de control/transferencias de datos.

El objetivo de esta spec es doble:
1. **Enumeración:** Hacer que el comando `lsusb` funcione en Debian sin root, mostrando la lista de dispositivos USB reales conectados vía USB-OTG en el formato estándar de `lsusb`.
2. **Interacción:** Proveer un mecanismo puente (equivalente a `termux-usb` o CLI puente) accesible desde la sesión Debian para solicitar permiso de acceso al dispositivo USB al usuario y transferir el descriptor de archivo (`fd`) o canal de comunicación al programa invitado (`libusb`, herramientas serie, etc.).

## Usuarios

- Usuarios y desarrolladores que conectan periféricos USB (adaptadores USB-Serie, Arduino, ESP32, dongles SDR, etc.) al móvil vía OTG y necesitan listar e interactuar con el hardware desde la línea de comandos de Debian sin necesidad de rootear el teléfono.

## Historias de usuario

- **HU-1.** Como usuario, quiero ejecutar `lsusb` en la terminal Debian y ver el listado de dispositivos USB conectados al puerto del teléfono (con su bus, ID y nombres), sin errores de `libusb: -99`.
- **HU-2.** Como usuario, quiero solicitar acceso a un dispositivo USB desde la terminal Debian mediante un comando puente y que el sistema Android me muestre el diálogo de autorización.
- **HU-3.** Como usuario, quiero que tras autorizar el dispositivo en el diálogo de Android, la herramienta indicada (compatible con FDs o proxy) reciba el descriptor de archivo para leer/escribir datos en el hardware.

## Definiciones

- **UsbManager**: Servicio de Android (`Context.USB_SERVICE`) que gestiona el ciclo de vida de los dispositivos USB host.
- **OTG (On-The-Go)**: Interfaz que permite al dispositivo Android actuar como host USB para conectar periféricos externos.
- **Bridge CLI**: Comando o script disponible en Debian (e.g. `termux-usb` o `terminal-usb`) que se comunica con el servicio host de Android para listar dispositivos, solicitar permisos y recibir el file descriptor.
- **FD (File Descriptor)**: Descriptor de archivo numérico abierto por Android tras la concesión de permisos, utilizado por `libusb` u operaciones de E/S.

## Requisitos funcionales (EARS)

- **RF-1**: CUANDO el usuario ejecute `lsusb` en la sesión Debian, EL SISTEMA mostrará los dispositivos USB conectados al host Android en el formato clásico:
  `Bus <BBB> Device <DDD>: ID <VID>:<PID> <Descripción/Fabricante>`.
- **RF-2**: SI no hay dispositivos USB conectados al ejecutar `lsusb`, ENTONCES EL SISTEMA terminará con código 0 y sin listar dispositivos de usuario (o mostrando únicamente los hubs/buses raíz virtuales).
- **RF-3**: EL SISTEMA proveerá una herramienta de línea de comandos puente en el rootfs (compatible con la convención `termux-usb`) con opciones para:
  - `-l`: listar los dispositivos USB detectados en formato de ruta (`/dev/bus/usb/BBB/DDD`) o JSON.
  - `-r <device>`: solicitar al usuario el permiso de acceso para el dispositivo indicado.
  - `-e <command> <device>`: ejecutar `<command>` pasándole el descriptor de archivo abierto (o mediante variable de entorno `$TERMUX_USB_FD` / stdin).
- **RF-4**: CUANDO se solicite permiso mediante `-r` o `-e`, EL SISTEMA invocará el diálogo modal nativo de Android solicitando la aprobación del usuario.
- **RF-5**: SI el usuario concede el permiso en el diálogo de Android, ENTONCES EL SISTEMA abrirá el dispositivo mediante `UsbManager.openDevice()` y pasará el descriptor de archivo al comando solicitado.
- **RF-6**: SI el usuario deniega el permiso o cancela el diálogo, ENTONCES EL SISTEMA terminará la llamada con un mensaje descriptivo de permiso denegado y código de salida distinto de 0.
- **RF-7**: MIENTRAS una sesión esté activa y se conecte o desconecte un dispositivo USB, EL SISTEMA actualizará el estado de los dispositivos disponibles en las siguientes invocaciones de `lsusb` o del bridge.

## Requisitos no funcionales

- **RNF-1**: La solución no requerirá privilegios de root, modificaciones del kernel ni parches en SELinux del teléfono.
- **RNF-2**: La lógica de formateo de `lsusb`, parseo de identificadores y serialización de peticiones debe estar implementada en funciones puras con tests JUnit que corran sin el SDK de Android.
- **RNF-3**: No se añadirán dependencias externas pesadas fuera de las autorizadas en la Constitución (Constitución § 1).

## Casos límite

- Desconexión del cable OTG o del dispositivo USB mientras un proceso tiene el FD abierto: el kernel cerrará los endpoints y las operaciones de lectura/escritura fallarán con error de E/S (`EIO` o `ENODEV`).
- Dispositivos conectados a través de un HUB USB OTG con múltiples puertos: deben ser enumerados individualmente con sus respectivos pares VID:PID.
- Dispositivos que no expongan cadenas de texto legibles para fabricante o producto: `lsusb` mostrará el identificador hexadecimal y consultará la base de datos `usb.ids` si está presente en el rootfs Debian.

## Fuera de alcance

- Dispositivos USB internos del móvil no expuestos como periféricos host por Android (módem interno, SoC buses).
- Montaje a nivel de bloque en Linux (`mount /dev/sdX`) para pendrives; el acceso a archivos en pendrives sigue gestionado por Android mediante el Storage Access Framework y los binds en `/root/storage`.
- Inyección forzada de nodos físicos en `/dev/bus/usb/` dentro del kernel del host (imposible sin root/SELinux permissive).

## Criterios de finalización

1. Ejecutar `lsusb` en una sesión Debian sin root no produce `unable to initialize libusb: -99`, sino la lista formateada de los dispositivos USB conectados vía OTG.
2. La herramienta puente (`termux-usb` / `terminal-usb`) permite listar dispositivos y abrir el diálogo de confirmación de permisos nativo de Android.
3. Al aceptar el diálogo, la herramienta subordinada recibe el descriptor de archivo para operar con el dispositivo.
4. Todos los tests de unidad JUnit pasan con `./gradlew test`.

## Dudas abiertas

- Ninguna por ahora.
