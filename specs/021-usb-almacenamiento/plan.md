# Plan 021 — Almacenamiento USB: detección informativa (lsblk-like) + acceso a ficheros vía SAF

Spec de referencia: `specs/021-usb-almacenamiento/spec.md` (Estado: **implementada y
verificada en dispositivo (2026-10-27)**). Nota de cierre: la vía definitiva es el bind
`-b /storage:$HOME/storage` ya existente; el puente de ficheros de sockets quedó DESCARTADO
por factibilidad — las filas y páginas de este plan marcadas con **(DESCARTADO)** no se
implementaron y se conservan por trazabilidad.

Reutiliza al máximo la infra de la spec 020: bridge abstracto `@terminal_usb`, snapshots
`/tmp/usb_devices.json` / `/tmp/usb_devices.txt` (`TermuxConstants.USB_DEVICES_*`),
recepción de `ACTION_USB_DEVICE_ATTACHED/DETACHED` en `UsbBridgeServer`,
instalación de tools en `/usr/local/bin/` + `usbToolsNeedRefresh` en `DebianInstaller`,
y el patrón de librería pura `UsbDeviceFormatLibrary` → nueva `UsbBlockDeviceFormatLibrary`.

> **Orden crítico:** las tareas de factibilidad (F1, F2, F3) van ANTES de ninguna decisión
> de código definitivo. Si F2 falla, RF-4/RF-5 se descartan y la spec se reescopa a RF-1 +
> limitaciones (se actualiza spec.md antes que el código; Constitución § 2).

## Archivos y responsabilidades

| Archivo | Cambio | RF |
|---|---|---|
| `termux-shared/.../termux/usb/UsbBlockDeviceFormatLibrary.java` (NUEVO) | Funciones puras: clasificación de interfaz USB (clase 0x08 → mass-storage), formateo de tabla lsblk-ish, parsing de rutas de volumen `/storage/XXXX-YYYY`, serialización JSON de la sección block devices | RF-1, RF-2, RF-4, RNF-2 |
| `termux-shared/src/test/.../UsbBlockDeviceFormatLibraryTest.java` (NUEVO) | Tests JUnit sin SDK Android: formateo lsblk, clasificación 0x08 vs otras clases, paths con/ sin espacios, JSON round-trip | RNF-2 |
| `termux-shared/.../termux/TermuxConstants.java` | Constantes: rutas guest `usr/local/bin/lsblk`, `usr/local/bin/terminal-usb-cp`; endpoint del puente de ficheros (ej. socket abstracto `@terminal_usbfs`) **(sin uso, cauce descartado)**; clave extra de intent para rutas de volumen | RF-1, RF-3, RF-4 |
| `app/src/main/java/com/termux/app/usb/UsbBridgeServer.java` | Extensión: clasificación de interfaz 0x08 en el snapshot; sección "block devices" (volúmenes desde `/proc/mounts` — enmienda RF-6 T8.1) añadida aditivamente a `/tmp/usb_devices.json` en ATTACHED/DETACHED + refresh JIT por conexión al bridge | RF-1, RF-6 |
| `app/.../usb/UsbFileBridgeServer.java` (NUEVO, condicional F3) **(DESCARTADO)** | Servidor del puente de ficheros sobre el socket abstracto: LIST / GET / PUT / COPY; no llegó a implementarse (F2/F3 positivos → vía bind) | RF-4 |
| `app/src/main/java/com/termux/app/DebianInstaller.java` | Instalación de `usr/local/bin/lsblk` (wrapper informativo) y `usr/local/bin/terminal-usb-cp` con modo 0755 dentro del mecanismo `usbToolsNeedRefresh` existente (byte-compare) | RF-1, RF-3, RF-4 |
| `app/.../ProotShellEnvironment` | (Condicional F1) **(NO requerido:** el bind global `-b /storage:$HOME/storage` ya existía y basta; sin cambios) | RF-5 |
| `docs/limitaciones.html` (wiki) **(ruta real: `wiki/limitaciones.html`)** | Documentación: (a) por qué sysfs no es accesible (SELinux untrusted_app, sin fix); (b) resultado del experimento del bind; (c) uso de `lsblk` y `terminal-usb-cp` | RF-2, RF-5 |
| `specs/021-usb-almacenamiento/spec.md` / `plan.md` | Registro del resultado de los experimentos de factibilidad F1/F2/F3 (apartado "Resultados de factibilidad") | RF-5 |

## Funciones puras (`UsbBlockDeviceFormatLibrary`)

1. `isMassStorage(int deviceClass, int[] ifaceClasses) -> boolean`
   Clase de interfaz USB `0x08` en el device o entre sus interfaces → `true`; resto → `false`
   (los pendrives suelen anunciar 0x08 solo a nivel de interfaz).

2. `formatLsblk(...) -> String`
    Tabla "lsblk-ish" con columnas `NAME SIZE TYPE MOUNTPOINT`;
    `MOUNTPOINT` = `-` si el volumen no está montado por el framework.
    Imprime una cabecera de aviso fija (limitación sysfs). `SIZE` en unidades legibles
    (`B/K/M/G`, floor, sin flotantes); capacidad desconocida/0 → `-`.

3. `parseVolumeId(String mountPath) -> String/null`
   Dada `/storage/XXXX-YYYY/path` devuelve el volumen `XXXX-YYYY`; `null` si no encaja.
   Tolerante a rutas sin `/storage` (para tests).

4. `toJson(List<UsbBlockDevice> blockDevices) -> String` / `fromJson(String) -> List`
    Round-trip de la sección "block devices" del snapshot; shape definitivo
    (`name`, `capacityBytes`, `massStorage`, `volumeId`, `mountpoint`, `type`) y clave
    JSON `blockDevices` (no `block_devices`).

5. `extractVolumeMountpoints(String mountsContent) -> List<String>`
   T8.1: parseo de `/proc/mounts` (host o guest) → mountpoints `/storage/XXXX-YYYY`
   estrictos, sin duplicados, ordenados; `emulated` nunca machea.

6. `encodePathForBridge(String path) -> String` / `decodePathForBridge(String) -> String`
   Codificación segura para rutas con espacios/UTF-8 (quedó implementada por completitud
   del protocolo; con la vía bind el puente no se usa).

## Protocolo del puente de ficheros (`@terminal_usbfs`) — **(DESCARTADO por factibilidad)**

> **No implementar:** F2/F3 cerraron positivos (acceso directo al volumen por el bind
> existente), por lo que la vía del puente de ficheros no se construyó. Se conserva el diseño
> a continuación por trazabilidad histórica del plan; el cliente operativo es el wrapper fino.

Mismo patrón que `@terminal_usb` (socket abstracto, `LocalServerSocket(String)`):
- Comando `LIST <volume> <path>`
  → `OK <json de entradas (name, size, dir)>\n` o `ERR <mensaje>\n`.
- Comando `GET <volume> <path>`
  → stream binario del fichero (`OK <size>\n` + payload crudo), o `ERR`.
- Comando `PUT <volume> <path> <size>\n` + payload binario
  → `OK\n` / `ERR <mensaje>\n`. Exige confirmación previa de sobreescritura según
  Constitución § 5 (nunca sobrescribe en silencio: si existe, responde `ERR: EXISTS`).
- Comando `COPY-IN <volume> <remote_src> <local_size>\n` + payload binario
  → equivale a una escritura atómica (tmp + rename) para no dejar ficheros a medias.

El host resuelve sobre `/storage/XXXX-YYYY` con `DocumentsContract` (descendencia) o
`java.io.File` (fallback si el build permite acceso directo a path, validado por F2).
Los permisos de storage ya están gestionados por el filemanager del app (no pedir de nuevo).

## Experimentos de factibilidad (registro en spec.md; CERRADOS 2026-10-27)

- **F1 — ¿proot acepta `-b /storage/XXXX-YYYY:/root/usb`?**
  CERRADO por vías existentes: el bind global `-b /storage:$HOME/storage` ya presente en
  `ProotShellEnvironment` cumple el objetivo (readdir del catálogo denegado por SELinux, pero
  el acceso directo al hijo `/root/storage/<ID>` funciona). No se requirió parche.
- **F2 — ¿El proceso de la app (targetSdk 28) puede listar/leer/escribir `/storage/XXXX-YYYY`?**
  POSITIVO con matiz: lectura, escritura (dd 10 MiB round-trip + md5) y listing directo al
  path del volumen funcionan; el `readdir` del catálogo `/storage` lo deniega SELinux → el
  descubrimiento de montado va por `/proc/mounts` (host para el snapshot, guest en vivo para
  las tools).
- **F3 — ¿El framework monta el pendrive en el dispositivo de pruebas?**
  POSITIVO: volúmen visible como `/storage/459E-170F` (montado DESPUÉS del attach, origen de la
  enmienda RF-6 de refresco JIT).

## Scripts Guest en `/usr/local/bin/`

1. `/usr/local/bin/lsblk` (wrapper informativo, Python3):
   - Hardware (pendrive/sda-like) desde `/tmp/usb_devices.json`: sección JSON `blockDevices`.
   - Volúmenes montados EN VIVO del `/proc/mounts` del guest (T8.2: pendrive, microSD u otros
     volúmenes FUSE), capacidad por `statvfs` sobre `/root/storage/<ID>`; deduplicado.
   - Nunca lee `/sys/dev/block` directamente (no se puede: SELinux).
   - Imprime tabla + avisos de limitación; exit 0 siempre (tolerante a snapshot/mounts corruptos).
   - Se instala por `usbToolsNeedRefresh` (byte-compare) — NO soplar sobre
     `lsusb`/`termux-usb`/`terminal-usb` ya presentes de la 020.

2. `/usr/local/bin/terminal-usb-cp` (Python3):
   - Subcomandos `list [path]`, `read <src> [dst]`, `write <src> [dst]`, selector `[ID]`
     en cualquier posición (antes o después del subcomando), case-insensitive.
   - Vía definitiva (bind): wrapper fino sobre `cp` contra `/root/storage/<ID>`.
   - Descubrimiento de volúmenes EN VIVO del `/proc/mounts` del guest (T8.2); sin ping al
     bridge ni fallback al snapshot (eliminados).
   - Sin sobrescribir en silencio: destino existente → stderr + exit 3; `..` que escapa del
     volumen → exit 2.

## Estrategia de tests

- JUnit en `termux-shared` (`UsbBlockDeviceFormatLibraryTest`):
  - `isMassStorage`: clase 0x08 → true; 0x03 (HID), 0xE0 (hub), 0xFF (vendor) → false.
  - `formatLsblk`: tabla con/ sin montaje, tamaños 0 / 1B / <1KiB / MiB / GiB, sin flotantes.
  - `parseVolumeId`: validez, mayúsculas, rutas con espacios, rutas ajenas a `/storage`.
  - JSON round-trip idempotente (lista vacía, 1 y N devices, campos nulos).
  - `encodePathForBridge` round-trip con espacios, tildes, UTF-8.
- Test de `DebianInstaller` (añadiendo a `DebianInstallerTest`): verificar instalación
  0755 de `usr/local/bin/lsblk` y `usr/local/bin/terminal-usb-cp` y byte-compare del refresh,
  sin regresión de los tools de la 020.
- Test en `app` del ciclo de vida del snapshot extendido (sección block devices):
  `UsbBridgeServer` atiende ATTACHED/DETACHED y escribe JSON válido (parse con la librería pura).
- Puerta única de calidad CI: `./gradlew test` (en verde). Sin lint ni formatter.
