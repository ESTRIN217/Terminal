# Spec 015 — `DISPLAY` pre-exportado en el guest Debian

Estado: implementada (pendiente de validación en dispositivo)

## Problema

Para ejecutar apps gráficas en el guest Debian sobre el companion X11 (spec 014,
display por defecto `:0`, comando primario `termux-x11 :0`), el usuario debe
escribir `export DISPLAY=:0` a mano en cada sesión (lo documenta el wiki:
`wiki/x11.html` y `wiki/limitaciones.html`). El valor ya es fijo en el fork
(`TermuxConstants.TERMUX_X11.X11_DEFAULT_DISPLAY`), pero el entorno guest que
construye `ProotShellEnvironment` no exporta `DISPLAY`, así que cada terminal
nueva arranca sin él.

## Requisitos

- **RF-01.** El entorno del guest Debian (todas las sesiones proot: terminal,
  editores del file manager) exporta `DISPLAY` con el valor de
  `TermuxConstants.TERMUX_X11.X11_DEFAULT_DISPLAY` (`:0`), siempre, sin depender
  del estado del companion ni de ninguna preferencia (decisión del usuario:
  incondicional; si el servidor no corre, las apps gráficas fallan igual que
  fallan hoy tras el export manual, y la sesión CLI no se ve afectada).
- **RF-02.** El export manual sigue funcionando: el valor inyectado es solo el
  valor base del entorno del proceso; el usuario puede hacer `export DISPLAY=:1`
  en sesión y gana.

## Decisiones

- Inyección incondicional en `ProotShellEnvironment.getEnvironment()`, junto al
  bloque de branding kitty (precedente de variables guest siempre presentes).
  Se reutiliza `X11_DEFAULT_DISPLAY` (spec 014) en lugar de duplicar `":0"`.
- No se toca `TermuxShellEnvironment` (entorno `$PREFIX` heredado de upstream,
  fuera del alcance del fork Debian — misma decisión que la spec 004).
- Sin modificaciones dentro del rootfs (no hay `profile.d` ni `bashrc`): la
  sesión recibe `DISPLAY` en el entorno del proceso tras proot, que es
  determinista y cubre también las invocaciones no interactivas (`bash -c`).
- Se marca con la spec en el Javadoc para el contexto de los comentarios.

## No funcionales

- **NFR-01.** Lógica fuera de la UI: inyección cubierta por test JUnit sin
  Android (`ProotShellEnvironmentTest`).
- **NFR-02.** Sin dependencias nuevas (regla 1 de la constitución).
- **NFR-03.** El resto del entorno guest queda byte a byte igual: la difusión
  frente al estado previo es exactamente una clave nueva (`DISPLAY`).

## Referencias

- `ProotShellEnvironment.java` (getEnvironment)
- `TermuxConstants.java` (`TERMUX_X11.X11_DEFAULT_DISPLAY`, spec 014)
- `ProotShellEnvironmentTest.java`
- `wiki/x11.html`, `wiki/limitaciones.html` (instrucciones manuales vigentes)
