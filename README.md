# Terminal

[![Build status](https://github.com/ESTRIN217/Terminal/workflows/Build/badge.svg)](https://github.com/ESTRIN217/Terminal/actions)
[![Testing status](https://github.com/ESTRIN217/Terminal/workflows/Unit%20tests/badge.svg)](https://github.com/ESTRIN217/Terminal/actions)

> **ES — Aviso de fork independiente:** este repositorio (`ESTRIN217/Terminal`) es un fork
> independiente y **no está afiliado, respaldado ni soportado por el equipo de Termux**.
> No reportes sus issues al proyecto Termux ni uses sus canales de soporte/donación.
> Reporta todo en [Issues de este fork](https://github.com/ESTRIN217/Terminal/issues).
>
> **EN — Independent fork notice:** this repository (`ESTRIN217/Terminal`) is an independent
> fork and is **not affiliated with, endorsed by, or supported by the Termux team**.
> Do not report its issues upstream or use Termux support/donation channels.
> Report everything at [this fork's Issues](https://github.com/ESTRIN217/Terminal/issues).

**Terminal** es una aplicación Android de terminal + entorno Linux (Debian vía proot),
con UI moderna en Jetpack Compose y emulación de terminal nativa.

## Contenido

- [Instalación](#instalación)
- [Desinstalación](#desinstalación)
- [Enlaces](#enlaces)
- [Depuración](#depuración)
- [Mantenimiento y contribuciones](#mantenimiento-y-contribuciones)
- [Atribución](#atribución)

## Instalación

- Paquete: `com.estrin217.terminal`.
- Requiere Android con arquitectura `arm64-v8a` (el binario proot embebido es solo AArch64).
- Obtén los APK desde [`GitHub Releases`](https://github.com/ESTRIN217/Terminal/releases)
  (sección `Assets`) o desde los artefactos del workflow `Build`
  ([`actions`](https://github.com/ESTRIN217/Terminal/actions) — requiere login de GitHub).
- Los APK de GitHub están firmados con una clave de prueba **no oficial**
  (`app/testkey_untrusted.jks`). No instales builds del fork distribuidos por terceros
  (Telegram, redes sociales, etc.).
- Si cambias de origen de instalación, desinstala primero todas las APK del fork
  (ver [Desinstalación](#desinstalación)). No mezcles firmas distintas.

En el primer arranque se descarga y configura un rootfs Debian (unos ~50 MB comprimidos)
y se instala el binario proot embebido.

## Desinstalación

Para desinstalar por completo, elimina la app `Terminal` (`com.estrin217.terminal`)
desde `Ajustes de Android` → `Aplicaciones`. Haz copia de seguridad de tus datos antes
si los necesitas.

## Enlaces

- Repo: <https://github.com/ESTRIN217/Terminal>
- Issues: <https://github.com/ESTRIN217/Terminal/issues>
- Discussions: <https://github.com/ESTRIN217/Terminal/discussions>
- Wiki: <https://github.com/ESTRIN217/Terminal/wiki>

## Depuración

Puedes ajustar el nivel de `logcat` en `Terminal` → `Ajustes` → `Terminal` →
`Debugging` → `Log Level` (por defecto `Normal`; vuelve a `Normal` tras depurar).
Usa `logcat -d` en el terminal o `adb logcat` desde un PC.

Desde el menú largo del terminal (`More` → `Report Issue`) puedes generar un reporte
automático. Publica el texto completo al abrir un issue; los issues con
(partiales) capturas de pantalla en lugar de texto probablemente se cierren.

### Niveles de log

- `Off` — no registra nada.
- `Normal` — errores, avisos, info y stacktraces.
- `Debug` — mensajes de depuración.
- `Verbose` — mensajes detallados.

### Limitaciones conocidas (SELinux OEM)

En algunos dispositivos, la política SELinux del fabricante impide a la app enumerar
`/dev`, `/dev/pts` y `/sys`, por lo que `ls /dev` puede fallar con `Permission denied`
dentro de la sesión Debian. No es un bug: el acceso a dispositivos concretos
(`/dev/null`, `/dev/tty`, ...) sigue funcionando. Detalles en
[`docs/proot-debian-arm64-plan.md`](docs/proot-debian-arm64-plan.md) (sección 7).

Del mismo modo, desde Android 8 (API 26) SELinux deniega a las apps `untrusted_app`
leer `/proc/stat`, así que los monitores de CPU (`btop`, `htop`) no mostrarán
estadísticas de CPU en un dispositivo stock con SELinux enforcing (`btop` falla con
`Failed to parse /proc/stat`). El bind `-b /proc` está presente y correcto; sin root
o SELinux permissive no hay workaround.

## Mantenimiento y contribuciones

- Java 17 requerido. NDK `30.0.14904198`, CMake `3.31.6`.
- Build: `./gradlew assembleDebug`. Tests: `./gradlew test` (solo unitarios).
- `targetSdk 28` es intencional (modelo de acceso a archivos).
- Los mensajes de commit deben seguir [Conventional Commits](https://www.conventionalcommits.org)
  con tipos `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`
  (primera letra en mayúscula).
- El `versionName` en `app/build.gradle.kts` debe seguir semver `major.minor.patch`.
- La librería `termux-shared` centraliza constantes y utils compartidos. No uses
  valores hardcodeados si existe una constante.

## Atribución

Este proyecto es un fork de [`termux/termux-app`](https://github.com/termux/termux-app)
(licencia [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html)) y se distribuye bajo la
misma licencia. No existe ninguna relación con el equipo de Termux.

Excepciones heredadas del upstream:

- Código de [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator)
  (Apache 2.0) en las librerías [`terminal-view`](terminal-view) y
  [`terminal-emulator`](terminal-emulator).
- Ver [`termux-shared/LICENSE.md`](termux-shared/LICENSE.md) para excepciones de `termux-shared`.
