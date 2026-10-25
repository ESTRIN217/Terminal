# Plan 018 — Binding `/linkerconfig` superviviente en proot

## Archivos y responsabilidades

| Archivo | Cambio | RF |
|---|---|---|
| `app/src/main/cpp/proot/path/binding.c` | Caso especial en `new_binding()`: `realpath2` falla con `EACCES` y host exactamente `/linkerconfig` → aceptar ruta literal | RF-01..RF-03 |
| `app/src/main/cpp/proot/path/binding.c` (comentarios) | Comentario mirroring `canon.c` con referencia a termux/proot#254 | — |
| `wiki/x11.html` | Quitar el warning de la lista de comportamientos/ruidos documentados si procede, tras verificación en dispositivo | RF-02 |
| `MEMORY.md` | Registro del resultado | — |
| `specs/018-proot-linkerconfig-binding/` | Spec, plan y tareas | — |

Sin cambios en: `ProotShellEnvironment.java`, `ProotShellEnvironmentTest.java`,
`TermuxConstants.java`, instalador, Compose (RF-04).

## Algoritmo (pseudocódigo, en `new_binding`)

```
status = realpath2(tracee->reconf.tracee, binding->host.path, host, true);
if (status < 0) {
    if (status == -EACCES && strcmp(host, "/linkerconfig") == 0) {
        /* Mirror can.c substitute_binding_stat: the directory exists on
         * Android but the kernel denies the stat to proot itself; accept
         * the literal host path so the binding survives (termux/proot#254). */
        strcpy(binding->host.path, host);
        status = 0;
    } else if (must_exist && PROOT_IGNORE_MISSING_BINDINGS==NULL) {
        note(...warning actual...);
        goto error;
    } else {
        goto error;
    }
}
```

Estructura real: añadir el caso especial **antes** de la rama de error, de forma
que el flujo original (`binding->host.length`, guest side, `insort_binding`)
continúe sin volverse a tocar.

## Decisiones y alternativas descartadas

- **Caso especial en `new_binding` (elegido).** Es espejo exacto del parche
  existente en `canon.c`; riesgo mínimo y comportamiento idéntico en el resto
  de rutas. Alternativa descartada: generalizar a todo `EACCES` — cambiaría el
  contrato de sanitización de todos los bindings (máscara de errores reales,
  p. ej. permisos rotos del rootfs) sin necesidad observada.
- **`PROOT_IGNORE_MISSING_BINDINGS=1` (descartada).** Solo silencia el
  warning; el `goto error` no es condicional a `must_exist`, así que el
  binding sigue descartándose y el guest seguiría con CANNOT LINK.
- **Quitar el mensaje via `note()` condicional (descartada).** Silenciaria sin
  restaurar el binding: el problema real es el descarte, no el ruido.
- **Java-side (descartado).** No existe gancho Java para la fase de
  sanitización de proot; la única vía es el binario nativo del propio fork.
- **Sin tests JUnit nuevos (justificado).** La lógica nueva es C compilado
  dentro del binario proot: no hay superficie Java ni función pura nueva; el
  gate es `./gradlew test` (regresión) + verificación en dispositivo.

## Estrategia de verificación

1. `./gradlew assembleDebug` (rebuild nativo; `merge.*Assets` depende de
   `buildCMake` → proot staged a `app/src/main/assets/arm64-v8a/proot`).
2. `./gradlew test` en verde.
3. Dispositivo (RF-02/RF-01): prompt limpio sin warning; `ls /linkerconfig`
   lista `ld.config.txt`; `CANNOT LINK` ya no correlaciona con el binding
   ausente (si persiste por SELinux del tracee, queda documentado y vale la
   vía Enmienda B).
