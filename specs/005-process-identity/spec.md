# Spec 005 — Identidad de proceso visible para apps CLI

Estado: implementada (validada en dispositivo 2026-10-20)

## Contexto y objetivo
Algunas apps CLI (fastfetch, neofetch y similares) identifican la terminal subiendo por la
cadena de procesos en `/proc/<pid>/comm` hasta el proceso de la app (el shell y `proot` son
descartados por esas apps en la búsqueda). Linux limita `comm` a 15 caracteres y Android
trunca el nombre del paquete conservando el final: `com.estrin217.terminal` queda como
`rin217.terminal`. Además, `fastfetch` no tiene caso especial para este paquete (solo para
`com.termux`), así que muestra el nombre truncado tal cual.

Objetivo: que el sistema exponga la identidad de proceso `Terminal` (el label de la app),
de modo que las herramientas CLI muestren `Terminal: Terminal` en lugar del nombre truncado.

Decisiones ya tomadas con el usuario:
- La identidad es `Terminal` (coherente con el label de la app y con la respuesta actual de
  XTVERSION en el emulador, `DCS > | Terminal(2.0.0) ST`).

## Usuarios
- Usuarios de la terminal que ejecutan herramientas de diagnóstico o personalización CLI
  (fastfetch, neofetch, prompts, scripts propios).

## Historias de usuario
- HU-1. Como usuario de herramientas CLI, quiero que la terminal se identifique como
  `Terminal` para que los informes de sistema muestren el nombre de la app sin truncar.

## Definiciones
- **comm**: nombre de tarea visible en `/proc/<pid>/comm`; máx. 15 caracteres (`TASK_COMM_LEN - 1`).
- **Identidad de proceso**: el valor en `comm` del proceso principal del app.
- **XTVERSION**: consulta `CSI > 0 q` que el emulador ya responde con `Terminal(2.0.0)`.

## Requisitos funcionales
- RF-1: MIENTRAS el proceso principal del app esté vivo, EL SISTEMA expone la identidad de
  proceso `Terminal` (valor verificable en `/proc/<pid>/comm` del proceso de la app).
- RF-2: SI el cambio de identidad de proceso falla (syscall no disponible/SECCOMP/otro error),
  ENTONCES EL SISTEMA continúa el arranque normal, conserva la identidad previa que fijó
  Android y registra el fallo sin lanzar excepción ni bloquear la app.
- RF-3: EL SISTEMA no modifica el `applicationId` ni el nombre de paquete Android (el cambio
  solo afecta al `comm`; `cmdline` sigue siendo `com.estrin217.terminal`).
- RF-4: CUANDO se fija la identidad de proceso en `Application.onCreate`, EL SISTEMA la
  propaga a las sesiones de terminal (host y guest vía proot) por herencia del proceso padre,
  de modo que las apps CLI detectan `Terminal` tanto en sesiones host como guest.
- RF-5: EL SISTEMA aplica la identidad en el hilo principal de `Application.onCreate`
  (antes de la creación de cualquier sesión) y es idempotente durante la vida del proceso.

## Requisitos no funcionales
- El coste es una única llamada syscall en el arranque.
- La identidad fija (`Terminal`) debe caber en el límite de 15 caracteres de `comm` sin
  truncarse.
- Sin dependencias nuevas.

## Casos límite
- Fallo del syscall en ROMs con políticas restrictivas: arranque continúa (RF-2); la app es
  igualmente operativa (la identidad ya se comportaba truncada).
- Las sesiones heredan el `comm` del proceso padre: el `execve` de shells/proot renombra su
  propio `comm` (bash/proot), lo cual no afecta a la búsqueda descendente de las apps CLI
  porque elles vuelven al proceso de la app.
- Recreación/reciclaje del proceso: `Application.onCreate` se ejecuta de nuevo y re-fija la
  identidad (RF-5, idempotente).

## Fuera de alcance
- `TERM` y `TERM_PROGRAM` (`kitty` en el guest por el branding KGP de yazi).
- La respuesta XTVERSION del emulador (ya define `Terminal`).
- Casos especiales upstream (fastfetch/neofetch detectando forks de Termux).
- Cambios de `applicationId`, package name o `TermuxConstants`.
- Cambio del nombre visible en `ps`/Activity Manager por el framework Android (usa `cmdline`).

## Criterios de finalización
- `./gradlew test` en verde.
- El nuevo test JUnit de identidad es rojo antes del código y verde después (RF-5, límite de
  15 chars y valor).
- En dispositivo: `fastfetch` muestra `Terminal: Terminal` en sesión host y guest; en la
  máquina host del app, `cat /proc/<pid_app>/comm` devuelve `Terminal`.

## Dudas abiertas
- Ninguna: nombre aprobado (`Terminal`), y el usuario aprobó abrir spec e implementar.
