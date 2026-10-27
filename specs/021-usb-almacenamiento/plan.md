# Plan 021 — Almacenamiento USB: detección informativa (lsblk-like) + acceso a ficheros vía SAF

Spec de referencia: `specs/021-usb-almacenamiento/spec.md` (borrador).

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
| `termux-shared/.../termux/TermuxConstants.java` | Constantes: rutas guest `usr/local/bin/lsblk`, `usr/local/bin/terminal-usb-cp`; endpoint del puente de ficheros (ej. socket abstracto `@terminal_usbfs`); clave extra de intent para rutas de volumen | RF-1, RF-3, RF-4 |
| `app/src/main/java/com/termux/app/usb/UsbBridgeServer.java` | Extensión: clasificación de interfaz 0x08 en el snapshot; sección "block devices" (volúmenes `StorageManager`/`/storage`) añadida a `/tmp/usb_devices.json` en ATTACHED/DETACHED | RF-1, RF-6 |
| `app/src/main/java/com/termux/app/usb/UsbFileBridgeServer.java` (NUEVO, condicional F3) | Servidor del puente de ficheros sobre el socket abstracto: comandos LIST / GET / PUT / COPY; resuelve con `DocumentsContract` o `java.io.File` sobre `/storage/XXXX-YYYY` | RF-4 |
| `app/src/main/java/com/termux/app/DebianInstaller.java` | Instalación de `usr/local/bin/lsblk` (wrapper informativo) y `usr/local/bin/terminal-usb-cp` con modo 0755 dentro del mecanismo `usbToolsNeedRefresh` existente (byte-compare) | RF-1, RF-3, RF-4 |
| `app/src/main/java/com/termux/app/.../ProotShellEnvironment` | (Condicional F1) si el experimento del bind funciona: añadir `-b /storage/XXXX-YYYY:/root/usb` para cada volumen montado, dinámicamente al abrir sesión | RF-5 |
| `docs/limitaciones.html` (wiki) | Documentación: (a) por qué sysfs no es accesible (SELinux untrusted_app, sin fix); (b) resultado del experimento del bind; (c) uso de `lsblk` y `terminal-usb-cp` | RF-2, RF-5 |
| `specs/021-usb-almacenamiento/spec.md` / `plan.md` | Registro del resultado de los experimentos de factibilidad F1/F2/F3 (apartado "Resultados de factibilidad") | RF-5 |

## Funciones puras (`UsbBlockDeviceFormatLibrary`)

1. `isMassStorage(int usbClass) -> boolean`
   Clase de interfaz USB `0x08` (or de sub-clase) → `true`; resto → `false`.

2. `formatLsblk(List<UsbBlockDevice> devices) -> String`
   Tabla "lsblk-ish" con columnas `NAME SIZE TYPE MOUNTPOINT`;
   `MOUNTPOINT` = `-` si el volumen no está montado por el framework.
   Imprime una cabecera de aviso fija (limitación sysfs). `SIZE` en unidades legibles
   (`B/KiB/MiB/GiB`), calculada sin usar flotantes (regla 1024).

3. `parseVolumeId(String mountPath) -> String/null`
   Dada `/storage/XXXX-YYYY/path` devuelve el volumen `XXXX-YYYY`; `null` si no encaja.
   Tolerante a rutas sin `/storage` (para tests).

4. `toJson(List<UsbBlockDevice> blockDevices) -> String` / `fromJson(String) -> List`
   Round-trip de la sección "block devices" del snapshot (id, vid:pid, volumen,
   mountPoint, sizeBytes, massStorage boolean).

5. `encodePathForBridge(String path) -> String` / `decodePathForBridge(String) -> String`
   Codificación segura para rutas con espacios/UTF-8 en el protocolo del puente de ficheros
   (sin dependencias nuevas; ASCII-safe).

## Protocolo del puente de ficheros (`@terminal_usbfs`) — condicional a F3

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

## Experimentos de factibilidad (registro en spec.md)

- **F1 — ¿proot acepta `-b /storage/XXXX-YYYY:/root/usb`?**
  Añadir temporalmente el bind, abrir sesión Debian y `ls /root/usb`. Documentar en
  spec.md: funciona (bind transparente, vía preferida) o falla (puente de ficheros, RF-4).
  Revertir el patch temporal antes de T-next-decisionado.
- **F2 — ¿El proceso de la app (targetSdk 28) puede listar/leer/escribir `/storage/XXXX-YYYY`?**
  Probar `java.io.File.listFiles()` sobre el volumen con permisos de storage del
  filemanager ya concedidos; si el path directo falla, probar `DocumentsContract`. Documentar cuál funciona.
- **F3 — ¿El framework monta el pendrive en el dispositivo de pruebas?**
  `dumpsys mount`/UI de Android + `StorageManager` log al enchufar el pendrive.
  Si no monta, RF-2/RF-4 se reescopan a limitaciones y la spec se reduce a RF-1.

## Scripts Guest en `/usr/local/bin/`

1. `/usr/local/bin/lsblk` (wrapper informativo, Python3):
   - Lee `/tmp/usb_devices.json`: sección `block_devices` (nueva) + sección devices (0x08).
   - Nunca lee `/sys/dev/block` directamente (no se puede: SELinux).
   - Imprime tabla + aviso de limitación; exit 0 siempre.
   - Se instala por `usbToolsNeedRefresh` (byte-compare) — NO soplar sobre
     `lsusb`/`termux-usb`/`terminal-usb` ya presentes de la 020.

2. `/usr/local/bin/terminal-usb-cp` (Python3, condicional a F2-F3):
   - Subcomandos `list [path]`, `read <src> [dst]`, `write <src> [dst]`.
   - Vía bind (si F1 positivo): wrapper fino sobre `cp`/`mv` con `/root/usb`.
   - Vía puente (si F1 negativo): cliente del socket `@terminal_usbfs` (mismo patrón
     `s.connect(b"\x00terminal_usbfs")`), streaming binario con error descriptivo.
   - Maneja `ERR: EXISTS` en PUT con mensaje de que el fichero ya existe (no sobrescribir).

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
