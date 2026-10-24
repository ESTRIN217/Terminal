# Plan — Spec 015 (`DISPLAY` pre-exportado en el guest)

## Archivos y responsabilidades

| Archivo | Responsabilidad | RF |
|---|---|---|
| `termux-shared/.../shell/command/environment/ProotShellEnvironment.java` | Constante `ENV_DISPLAY` y `environment.put(ENV_DISPLAY, X11_DEFAULT_DISPLAY)` en `getEnvironment()`, con Javadoc que referencia la spec 014/015 | RF-01 |
| `app/src/test/java/.../environment/ProotShellEnvironmentTest.java` | Test nuevo: `testGetEnvironment_displayDefaultsToZero`; retoque de `testGetEnvironment_guestValues` (mantiene la clave) | RF-01, NFR-01, NFR-03 |
| `wiki/x11.html`, `wiki/limitaciones.html` | Instrucciones: el `export DISPLAY=:0` pasa de obligatorio a override opcional | — |
| `MEMORY.md`, CHANGELOG | Cierre de spec y decision | — |

## Algoritmo

1. Test en rojo: la aserción `env.get("DISPLAY")` falla porque la clave no existe.
2. Implementación trivial: `environment.put(ENV_DISPLAY, TermuxConstants.TERMUX_X11.X11_DEFAULT_DISPLAY)`.
3. `./gradlew test` verde; `assembleDebug` innecesario (cambio Java puro, pero build por prudencia no: es unit-test only).

## Riesgos

- Ninguno funcional: la clave nueva solo añade una variable de entorno al guest
  (idéntico efecto al `export` manual que el wiki ya instruye).
