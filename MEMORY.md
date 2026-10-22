# MEMORY.md — Terminal
## Estado actual
- Rama `3.0`; había cambios sin confirmar en 011/012 antes de esta tarea.
  Preservarlos; no limpiar ni revertir cambios ajenos.
- **Spec 011 en curso:** T1–T3 hechas; catálogo Mesa, seis paquetes con SHA-256
  fijados y preferencia `gpu_solution`. T4–T9 pendientes según el plan.
- **Spec 012 en curso:** T1 (lógica pura) y T2 (constantes/prefs con defaults
  on/8, round-trip y claves preservadas) hechas el 2026-10-22;
  T3 cablea env con gate GPU; T4 añade Switch/slider 1..16, resumen efectivo,
  nota de sesiones, estado Adreno y strings EN/ES/PT. T5 validada en Infinix
  Mali T606 (8 núcleos): env on/off correcto, htop 3.4.1 arranca; glmark2 no
  disponible en caché apt, sin score. README/wiki detallan el default y apagado;
  `./gradlew test` verde. Spec 012 cerrada.
- Spec 010 cerrada: overlay `/proc` vivo para htop/btop vía binds proot,
  muestreo ligado a sesiones y validación previa en dispositivo. Contadores
  deben ser acumulativos/monótonos para evitar porcentajes negativos.
- Specs 001–009 cerradas; 2.1.0 publicada; `release/2.0` tiene commits sin push.
- Sitio estático en `public/`; tras una release los datos caducables los
  actualiza `scripts/update-site.py`. Wiki estática en `wiki/`.

## Arquitectura y reglas prácticas
- `terminal-emulator` → `terminal-view` → `termux-shared` → `app`;
  `filemanager` → `termux-shared`. No invertir dependencias.
- Librerías `terminal-emulator`, `terminal-view`, `termux-shared`: Java puro.
  Kotlin Compose solo en `app` y `filemanager`.
- Lógica determinista en funciones puras con tests JUnit sin Android; UI
  Compose despacha y presenta. Variables/datos cambiantes entran por parámetros.
- Preferencias persistidas: añadir claves, no reescribir ni perder las existentes
  (en filemanager: `sort_option`, `sort_ascending`, `show_hidden`,
  `folders_first`, `view_mode`, `favorites`, `folder:<path>:*`).
- Error convencional del proyecto: devolver `Error`/`null` éxito; capturas
  específicas, logging con `Logger`; no ocultar fallos.
- Strings visibles en `values/`, `values-es-rVE/`, `values-pt-rBR/`; sin literales
  Compose. Docs y memoria en español, identificadores/comentarios técnicos en inglés.
- Constantes compartidas en `TermuxConstants`; Java/Kotlin usan prefijo `m` para
  miembros según el estilo existente. No agregar dependencias sin justificar en plan.
- `targetSdk 28` y `minSdk 24` son intencionales; proot soportado en arm64-v8a.
- Build Java 17/NDK 30.0.14904198/CMake 3.31.6; tests `./gradlew test`; evitar builds Gradle paralelos por riesgo al incremental Kotlin.

## Learnings vigentes
- Glifos multicolor Compose requieren `tint = Color.Unspecified`; viewport no
  cuadrado: centrar el arte en viewport cuadrado para que no se distorsione.
- Semánticas similares pero distintas (p. ej. extensión para orden vs icono) no
  se unifican sin spec; comparar centinelas de estado en algoritmos duplicados.
- Lecturas externas de `TerminalBuffer` pueden devolver vacío; validar filas
  antes de conversiones que lanzan. No escribir estado desde measure de Compose.
- `BUILD SUCCESSFUL` no garantiza APK utilizable: inspeccionar el APK con unzip.
- `docs/` y `AGENTS.md` se corrigen contrastando las fuentes del repositorio.
- Conventional Commits con tipos capitalizados: `Added`, `Changed`, `Fixed`,
  `Security`, etc.; versiones semver.
