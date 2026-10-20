# Spec 004 — Zona horaria del guest Debian

## Problema

Al abrir una sesión de Debian, `date` muestra la fecha correcta pero la hora en
UTC (p. ej. `11:50 UTC` siendo localmente `07:50`). Causa: el guest (glibc)
deriva su zona de `/etc/localtime`, que en el rootfs recién instalado apunta a
UTC, y el entorno de sesión del guest no exporta `TZ`. El dispositivo sí conoce
su zona (p. ej. `America/Caracas`, UTC-4), que `ProotShellEnvironment` ignora.

Nota aparte: no hay que confundir esto con el aviso de logcat de la libc Bionic
(`__bionic_open_tzdata: couldn't find any tzdata ...`), que es ruido del
ROM/emulador que busca los datos de zona del dispositivo y no lo imprime este
código; este repo no puede suprimirlo.

## Requisitos

- **RF-01.** El entorno del guest Debian (todas las sesiones: terminal,
  editores del file manager) exporta `TZ` con el ID de la zona horaria del
  dispositivo (`TimeZone.getDefault().getID()`).
- **RF-02.** `TZ` solo se exporta si la base `zoneinfo` del rootfs contiene esa
  zona (existe `<rootfs>/usr/share/zoneinfo/<ID>` como archivo). Si no existe,
  `TZ` se omite y el guest mantiene su comportamiento actual (UTC).
- **RF-03.** Se rechazan IDs que no correspondan a una entrada IANA válida:
  vacíos, con `..`, o tipo POSIX heredado (`GMT+04:00`, que en glibc invierte el
  signo del desplazamiento). Nunca se exporta `TZ` con un ID no encontrado en el
  rootfs.

## Decisiones

- La sonda es una función estática pura sobre la ruta del rootfs
  (`File.isFile()`), ejecutada en el arranque de cada comando de sesión en el
  host: barata (< 1 acceso) y siempre refleja la zona vigente del dispositivo.
- Sin `LD_PRELOAD` ni enlaces nuevos: la zona la aplica glibc leyendo `TZ`.
- No se toca el `TermuxShellEnvironment` (entorno $PREFIX heredado de upstream),
  fuera del alcance de este fork Debian.

## No funcionales

- **NFR-01.** Lógica fuera de la UI: funciones estáticas puras de
  `ProotShellEnvironment`, cubiertas por JUnit sin Android.
- **NFR-02.** Sin dependencias nuevas (regla 1 de la constitución).

## Referencias

- `ProotShellEnvironment.java` (getEnvironment, hostPathToGuestPath)
- `ProotShellEnvironmentTest.java`
