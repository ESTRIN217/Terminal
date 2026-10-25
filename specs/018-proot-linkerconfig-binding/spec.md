# Spec 018 — Binding `/linkerconfig` superviviente en proot

Estado: implementada (pendiente de verificación en dispositivo, 2026-10-25).

## Contexto y objetivo

Al arrancar la sesión Debian, proot emite:

```
proot warning: can't sanitize binding "/linkerconfig": Permission denied
```

y el binding `-b /linkerconfig` (spec 014, Enmienda A) se descarta antes de
ejecutar el tracee. Consecuencia observada en el guest:

```
WARNING: linker: Warning: failed to find generated linker configuration from
"/linkerconfig/ld.config.txt"
CANNOT LINK EXECUTABLE "/system/bin/app_process": library "libnativeloader.so"
not found: needed by main executable
```

Quitar el bind no es una opción ("no sirve quitarlo"): sin él, `app_process`
no consigue namespaces del linker y muere con CANNOT LINK.

**Causa raíz.** En `new_binding()` (`app/src/main/cpp/proot/path/binding.c`)
la sanitización del lado host usa `realpath2(tracee->reconf.tracee, ...)`.
Dicha referencia nunca se asigna en este árbol de código (solo se lee), por lo
que en fase de parseo CLI `tracee == NULL` y `realpath2` cae en `realpath(3)`
crudo ejecutado por el propio proot (uid de app, dominio SELinux
`untrusted_app`), que recibe `EACCES` al canonicalizar `/linkerconfig` en el
dispositivo de referencia (Unisoc, Android 12). El `goto error` staple el
binding incondicionalmente, sin importar `must_exist`. El parche existente en
`path/canon.c` (termino/proot#254: `EACCES` sobre `/linkerconfig` en
`substitute_binding_stat`) solo cubre la traducción en runtime por ptrace
(`tracee != NULL`), no la fase de arranque, y por tanto no evita ni el warning
ni el descarte.

**Objetivo:** que el binding `/linkerconfig` sobreviva a la sanitización en la
fase de arranque y que el warning desaparezca, con un cambio nativo mínimo y
localizado.

## Usuarios

- Usuario del app que abre sesiones Debian (el guest de este fork); le afecta
  el startup de toda sesión proot en dispositivos donde la sanitización de
  `/linkerconfig` produce `EACCES`.

## Requisitos funcionales

- **RF-01.** SI la sanitización (`realpath2`) del lado host de un binding
  falla con `EACCES` y la ruta host es exactamente `/linkerconfig`, ENTONCES
  proot la acepta como ruta literal (sin canonicalizar) y registra el binding
  normalmente. `RF-02.`
- **RF-02.** CUANDO proot arranca una sesión Debian, EL SISTEMA no emite el
  warning `can't sanitize binding "/linkerconfig": Permission denied`.
- **RF-03.** EL SISTEMA mantiene sin cambios el resto de bindings y
  mensajes: cualquier otra ruta cuyo sanitizado falle sigue avisando y
  descartándose como hoy (`/apex`, `/system`, `/dev`…).
- **RF-04.** EL SISTEMA conserva el comando proot de `ProotShellEnvironment`
  byte a byte (sigue con `-b /linkerconfig`; la causa no construye Java).

## Requisitos no funcionales

- **NFR-01.** Cambio confinado a `app/src/main/cpp/proot,path/binding.c`;
  sin cambios Java, Kotlin ni Compose. Sin dependencias nuevas (regla 1).
- **NFR-02.** `./gradlew test` sigue siendo el único gate; el test
  `ProotShellEnvironmentTest` (assertta `-b /linkerconfig`) queda verde sin
  modificaciones.
- **NFR-03.** Sin strings ni recursos nuevos (no hay texto visible de la UI).

## Casos límite

- SI el binding falla con un errno distinto de `EACCES` (p. ej. `ENOENT`),
  ENTONCES el comportamiento es el de hoy (warning + descarte).
- SI la ruta host del binding es `/linkerconfig` con sufijo
  (`/linkerconfig/foo`), ENTONCES NO recibe el caso especial (comparación
  exacta, simétrica a `canon.c`); esas rutas solo aparecen como hosts de
  bindings compuestos y la práctica sin `-b /linkerconfig` sigue cubierta.
- SI el dispositivo permite `lstat /linkerconfig`, ENTONCES el binding se
  sanitiza por la vía de siempre (el caso especial ni se ejecuta).
- SI el binding se registra en un dispositivo donde el kernel también niega al
  tracee leer `/linkerconfig`, la sesión degradará igual que antes si la ruta
  no es legible para child: nuestra garantía es que el binding ya existe y
  funciona cuando el kernel lo permite. La spectras de CANNOT LINK en el guest
  de este dispositivo se atiende por la vía de spec 014 Enmienda B (spawn de
  companion desde la app), que ya es el camino soportado. Verificar en
  dispositivo realmente para confirmar el nuevo estado.

## Fuera de alcance

- El arranque del companion `termux-x11` dentro del guest si el kernel sigue
  negando el sabor `/linkerconfig`/`/apex` al tracee (ya se respeta por
  spec 014 Enmienda B el spawn pesar de la pestaña).
- Cualquier re-hardcoding de `proot-distro`, de `get_root`, de la fase de
  sub-reconfiguración o del hack de `canon.c` (queda tal cual).
- Cambios en `buildProotCommand`, en `TermuxConstants` o en el instalador
  (solo se reconstruye el binario proot embebido).

## Criterios de finalización

- `./gradlew test` en verde sin cambios de test.
- `./gradlew assembleDebug` produce el APK con el proot rebuilt.
- En dispositivo de referencia (Unisoc/A12): nueva sesión Debian sin el
  warning `can't sanitize binding "/linkerconfig"`, `ls /linkerconfig` en el
  guest muestra contenido y `ld.config.txt` está presente.

## Dudas abiertas

- Ninguna.
