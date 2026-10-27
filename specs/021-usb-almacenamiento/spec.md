# Spec 021 — Almacenamiento USB: detección informativa de pendrives (lsblk-like) y acceso a ficheros vía SAF

Estado: **implementada y verificada en dispositivo (2026-10-27)**

## Contexto y objetivo

Tras la spec 020 (lsusb + bridge UsbManager), en el guest Debian falla el inventario de
almacenamiento del pendrive:

```
root@localhost:~# lsblk
lsblk: failed to access sysfs directory: /sys/dev/block: Permission denied
```

La causa raíz es que Android's SELinux (política `untrusted_app`) deniega el acceso real a
`/sys/dev/block` al proceso de la app, aunque proot ya bindea `-b /sys` (ver
`ProotShellEnvironment`). No hay fix posible sin root/parche SELinux: se documenta como
**limitación** (mismo patrón que la 020 en `wiki/limitaciones.html`).

En paralelo, Android sí monta los volúmenes FAT32/exFAT de los pendrives vía el framework
(`UsbContextHolder` → `StorageManager` / `DocumentsContract`) y los expone bajo
`/storage/XXXX-YYYY` cuando los permisos de almacenamiento del filemanager están concedidos.

El objetivo de esta spec es doble:

1. **Detección informativa (RF-1):** un `lsblk` en el guest que muestre el inventario de los
   pendrives conectados con formato "lsblk-ish", **leyendo snapshots** y **sin tocar sysfs real**,
   en modo informativo (no consulta el estado real del kernel del host).

2. **Acceso a ficheros (RF-2):** un puente host↔guest para listar/leer/escribir/copiar ficheros
   del volumen montado por el framework, con una herramienta de usuario `terminal-usb-cp` en el
   guest. Se incluye un **experimento de factibilidad** documentada para ver si proot admite un
   bind transparente `"-b /storage/XXXX-YYYY:/root/usb"`; si funciona, se prefiere ese cómodo
   acceso directo; si falla, el puente de ficheros es la vía.

**Fallback de factibilidad:** si (1) el framework Android no monta el pendrive en el hardware
objeto de pruebas o (2) `/storage/XXXX-YYYY` no es accesible aun con el permiso de storage del
filemanager dando consentimiento mediante el diálogo/plantilla SAF, entonces RF-2 queda
descartado en el dispositivo y la spec se **reescopa** a RF-1 + documentación de limitaciones
(no código muerto ni caminos que no se puedan verificar).

## Usuarios

- Usuarios de la terminal Debian (rootfs con proot) que conectan pendrives vía OTG y quieren
  saber qué se alcanzó a ver, y copiar/push ficheros al/desde el móvil **sin root** y sin
  dejarle a Android la UI entera resolviendo por su cuenta el file manager.

## Historias de usuario

- **HU-1.** Como usuario, quiero ejecutar `lsblk` en Debian y ver el inventario de pendrives
  conectados (nombre del volumen, tipo de interfaz, capacidad aproximada) SIN el error
  `failed to access sysfs directory ... Permission denied`, acompañado de un mensaje claro de
  que es inventario informativo, no estado kernel del host.

- **HU-2.** Como usuario, quiero ejecutar `terminal-usb-cp` desde Debian para listar/copiar
  ficheros del pendrive (montado por Android en `/storage/XXXX-YYYY`) sin salir de la
  sesión proot.

## Definiciones

- **Snapshot de USB:** ficheros `/tmp/usb_devices.json` / `/tmp/usb_devices.txt` (spec 020)
  actualizados por el `BroadcastReceiver` de `ACTION_USB_DEVICE_ATTACHED/DETACHED`;
  fuente de verdad para la detección informativa en RF-1.

- **SAF (Storage Access Framework):** API oficial de Android para acceso a ficheros privados
  de otros volúmenes; en dispositivos con el filemanager del app sirviendo la UI,
  `DocumentsContract` sobre `/storage/XXXX-YYYY` cubre el listing con permisos de storage normales.

- **Puente de ficheros:** socket abstracto (ponderando `@terminal_usb` de la 020 con otro
  endpoint) usado por comando guest para pedir LIST/GET/POST/COPY al host, si no funciona el
  bind directo.

- **terminal-usb-cp / lsblk:** comandos de `/usr/local/bin/` en el rootfs debian guest; reciclan
  el mecanismo de instalación de la 020 (lsusb, termux-usb, terminal-usb) para no
  romper `usbToolsNeedRefresh`.

## Requisitos funcionales (EARS)

- **RF-1 (lsblk informativo):** CUANDO el usuario ejecute `lsblk` en la sesión Debian,
  EL SISTEMA mostrará un inventario "lsblk-ish" de los pendrives conocidos por el snapshot
  (`/tmp/usb_devices.json`), **sin intentar leer sysfs real del host**: formato de tabla
  `NAME / RIDGE / CAPACITY / TYPE / MOUNTPOINT` con al menos nombre (sda-like), interfaz
  (`mass-storage` si clase USB = 0x08), y nota de limitación (`-` en mountpoint si no montyado).

- **RF-2 (aviso de limitación):** SI el snapshot está vacío o `/sys/dev/block` sigue siendo
  imposible de leer, ENTONCES `lsblk` terminará con código 0, imprimirá el inventario
  disponible (o vacío) y un aviso informativo de que el inventario viene del bridge USB del app
  por restricción SELinux de `untrusted_app` en `/sys/dev/block` (limitación, no bug).

- **RF-3 (USBTools refresh):** CUANDO se instale o repare el rootfs,
  EL SISTEMA instalará/refrescará `lsblk` (wrapper informativo) y `terminal-usb-cp` en
  `/usr/local/bin/` con el mecanismo `usbToolsNeedRefresh` de la 020, sin romper los
  binarios ya instalados de la 020.

- **RF-4 (acceso SAF):** CUANDO un pendrive esté montado por el framework Android
  (`/storage/XXXX-YYYY`) y los permisos de storage del app estén concedidos,
  EL SISTEMA proveerá en el guest `terminal-usb-cp` con subcomandos:
  - `list [path]` : listing de ficheros del volumen (ruta, tamaño, tipo).
  - `read <src> [dst]` : copiar fichero del volumen al guest (vía stdout si sin dst, o F a file).
  - `write <src> [dst]` : copiar fichero del guest al volumen.

- **RF-5 (experimento bind transparente):** ANTES de que se implante definitivamente el
  puente de ficheros, EL SISTEMA documentará el resultado del experimento de factibilidad de
  proot con `"-b /storage/XXXX-YYYY:/root/usb"`:
  - SI el bind funciona, la vía preferida será el bind directo (documentar variables y
    limitaciones de escritura vs. `DocumentsContract`), y `terminal-usb-cp` será un wrapper
    fino alrededor de `cp`/`mv` sobre `/root/usb` (no cifrado).
  - SI falla, la vía será el puente de ficheros (RF-4), y la limitación del bind se
  documentará en `wiki/limitaciones.html`.

- **RF-6 (sincronización de snapshots):** MIENTRAS haya sesión activa y se enganche o
  desenganche un pendrine, EL SISTEMA actualizará `/tmp/usb_devices.json`/`.txt` con las
  pistas de `StorageManager` además de las de `UsbManager` usadas hoy (VID/PID/interfaz
  0x08 y nombre de volumen), en los ciclos `ATTACHED/DETACHED` de la 020.

## Requisitos no funcionales

- **RNF-1:** Sin root, sin parches de kernel ni SELinux permissive; todo va sobre APIs
  públicas de Android (`UsbManager`, `StorageManager`, `DocumentsContract`/`java.io.File`).

- **RNF-2:** Parsing/formato (lineas lsblk-like, listado de ficheros, paths de volumen) en
  funciones puras con tests JUnit que corran sin el SDK de Android
  (patrón `UsbDeviceFormatLibrary`, nueva `UsbBlockDeviceFormatLibrary`).

- **RNF-3:** Cero dependencias nuevas extra (Constitución § 1).

- **RNF-4:** La detección RF-1 es inversa: el guest no toca nunca sysfs; solo lee snapshots.

## Casos límite

- Pendrive con formato no soportado por Android (NTFS, ext4, LUKS): el framework no lo monta
  (no aparece ni en `StorageManager`, ni en `/storage/`), pero sí en `UsbManager` (interfaz
  0x08): lsblk lo muestra como `mass-storage` que no puede montando una nota de limitación.
- Pendrive desconectado entre snapshot y ejecución de `lsblk`: lsblk cierra con código 0
  y muestra el estado del último inventario (es un snapshot, no estado vivo).
- `/storage/XXXX-YYYY` con rutas con espacios/UTF-8: el puente de ficheros usa nombres
  codificados (o JSON base64-like) para no romper por espacios.
- Escritura en el volumen cuando el soporte FAT32 sin `DocumentsContract` falla (si
  `java.io.File` no puede escribir en `/storage/XXXX-YYYY`): se muestra error descriptivo.
- Si (1) o (2) del fallback de factibilidad falla en dispositivo: **la spec se reescopa en frío**
  a RF-1 + limitaciones, quitando RF-4/RF-5 (no hay código no verifisable).

## Fuera de alcance

- Montar volúmenes desde el guest (no `mount -t vfat /dev/sda1 /mnt`).
- Raw block devices desde el guest (`dd if=/dev/sdb`).
- Driver SCSI/BOT propio; filesystem FAT32 propio (Todo por `UsbDeviceConnection`).
- Comunicación de deltas de bloque en tiempo real (no esperado; snapshot y copiado file-buffer).
- Copiar en caliente ficheros en uso con locks exclusivose.

## Criterios de finalización

1. En el guest sin root, `lsblk` no produce `failed to access sysfs directory ... Permission
   denied`: muestra el inventario snapshot + nota de limitación, exit 0.
2. `terminal-usb-cp list` lista los ficheros del volumen del pendrive (via bind proot o via
   puente, según el experimento RF-5); `read`/`write` copian ficheros de tamaño grande
   (≥ 10 MB) sin corrupción.
3. Los binarios `lsblk`/`terminal-usb-cp` se instalan/refrescan con el mecanismo
   `usbToolsNeedRefresh` sin romper los de la 020 (byte-compare).
4. El resultado del experimento de proot `-b /storage/XXXX-YYYY:/root/usb` está documentado
   (positivo o negativo) en la spec y en wiki.
5. Todas las llamadas JUnit pasan con `./gradlew test`.

## Dudas abiertas

- Ninguna por ahora (objeto de prueba: el mismo dispositivo ESTRIN Unisoc/A12 de la 020).

## Resultados de factibilidad (2026-10-27, dispositivo real)

Verificados en guest Debian por el mantenedor con un pendrive Generic Flash Disk
(058f:6387) montado por el framework como volúmen `459E-170F`:

- **F3/T1 — el framework SÍ monta el pendrive:** volúmen visible como
  `/storage/459E-170F`. Positivo.
- **F2/T2 — el acceso desde el guest funciona con matices:** `readdir` sobre el
  catálogo `/root/storage` lo niega SELinux (igual que `/sys/dev/block`), pero el
  **acceso directo al hijo del volumen funciona**: `ls /root/storage/459E-170F`
  lista, lectura OK, escritura OK (dd 10 MiB round-trip limpio, md5 verificado).
  El bind de /storage ya existía en `ProotShellEnvironment`
  (`-b /storage:$HOME/storage`); no se requería parche.
- **F1/T3 — experimento de `-b /storage/XXXX-YYYY:/root/usb`:** RESUELTO por
  infraestructura ya existente — el bind global `-b /storage:/root/storage`
  cumple el objetivo RF-5 (acceso transparente desde el guest) sin bind
  adicional: **la vía elegida para T8 es (a): `terminal-usb-cp` wrapper fino**
  sobre `cp`/`mv` contra `/root/storage/<ID>/...`. El puente de ficheros
  (RF-4 v.b) queda descartado; la constante `USB_FILE_BRIDGE_ABSTRACT_ADDRESS`
  no se usa (se conserva documentada como referencia del camino descartado).
- **Hueco detectado (temporalidad del snapshot):** el snapshot se escribe en
  ATTACHED/DETACHED, pero el framework monta el volumen DESPUÉS del attach, por
  lo que en el JSON capturado en attach aparecía `blockDevices` con `sda` sin
  `volumeId`/`mountpoint`. Enmienda a RF-6: `handleClient` de `UsbBridgeServer`
  refrescará el snapshot en **cada conexión al bridge** (just-in-time), y el
  wrapper `lsblk` hará un ping al bridge (subcomando list del tool 020) antes de
  leer el snapshot, garantizando mountpoints frescos sin listeners nuevos
  (ACTION_MEDIA_MOUNTED está restringido en Android moderno).
- **Defecto detectado en dispositivo (2026-10-27, fix T8.1):** la fuente de
  mountpoints de T6/T8 (`File("/storage").list()`) fallaba en el dispositivo:
  SELinux `untrusted_app` deniega el `readdir` de `/storage` (mismo patrón que
  `/sys/dev/block`), por lo que `terminal-usb-cp list` decía *"no mounted USB
  volume"* con el pendrive bien montado. En cambio, `/proc/mounts` (global, no
  `/proc/<pid>`) es legible y contiene las líneas FUSE del volumen
  (`/dev/fuse /storage/459E-170F fuse rw,...`), excluyendo el emulado
  `/storage/emulated`. **Enmienda a RF-6:** la fuente de mountpoints pasa a ser
  el contenido de `/proc/mounts`, parseado por una función pura
  (`UsbBlockDeviceFormatLibrary.extractVolumeMountpoints`: patrón estricto
  `/storage/XXXX-YYYY`, sin duplicados, ordenado; `emulated` no nunca machea).
  El shape JSON de T6/T4 no cambia: `blockDevices[].volumeId`/`mountpoint` se
  rellenan igual y el wrapper `terminal-usb-cp` sigue componiendo
  `/root/storage/<ID>/`.
- **Defecto detectado en dispositivo (2026-10-27, fix T8.2 — microSD interna):**
  además del pendrive, el dispositivo tiene una microSD interna montada por el
  framework como FUSE `/storage/<ID>` (p. ej. `47A8-1024`). El snapshot JSON solo
  cuelga los volúmenes bajo entradas USB mass-storage
  (`mergeBlockDevicesIntoJson`), así que (i) la tabla de `lsblk` mostraba la
  microSD como si colgara del pendrive y (ii) con microSD-sola (sin pendrive) no
  aparecía nada. El `/proc/mounts` del **guest** es legible (bind `bind -b /proc`
  ya existente; verificado en dispositivo: contiene `/storage/459E-170F` y
  `/storage/47A8-1024`). Decisión T8.2: las herramientas guest leen los
  volúmenes **en vivo** parseando `/proc/mounts` del guest (patrón estricto
  `/storage/XXXX-YYYY`); el snapshot queda solo para las filas de hardware USB
  (nombre/capacidad) de `lsblk`, que ahora deduplican contra los `volumeId` del
  snapshot para no repetir. El mapeo no clava el volumen al pendrive: con varios
  volúmenes se retratan como filas separadas `type=volume` (`size=-`, montado en
  `/root/storage/<ID>`). En `terminal-usb-cp` el descubrimiento de volúmenes
  pasa a ser en vivo de `/proc/mounts` y se **elimina** el ping al bridge y el
  fallback al snapshot: el refresh JIT del snapshot (RF-6 enmienda) sigue
  ocurriendo en cada conexión al bridge, ahora solo originado por las tools 020
  (`terminal-usb`/`termux-usb`); el ping existía solo para asegurar mountpoints
  frescos en el snapshot, algo que T8.2 ya no necesita. Subcomandos, exit codes
  y anti-`..` intactos.
