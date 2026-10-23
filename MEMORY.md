# MEMORY.md — Terminal
## Estado actual
- Rama `3.0`; árbol limpio salvo trabajo de esta sesión (doc T9 + spec 013 borrador).
- **Spec 011:** T1–T8 hechas y committeadas (`Added GPU acceleration and llvmpipe
  controls`). **T9 a medias (2026-10-22):** la doc de validación del pipeline Adreno
  (hardware requerido + pasos 1–7) quedó en `wiki/limitaciones.html`; la parte en
  dispositivo Mali pendiente del mantenedor.
- **Spec 012 cerrada** (llvmpipe multihilo, validada en Infinix Mali T606: env
  on/off correcto, htop 3.4.1 ok). Espec 010 cerrada (overlay /proc); specs 001–009
  cerradas; 2.1.0 publicada.
- **Spec 013 (VirGL) borrador en `specs/013-virgl-aceleracion-gpu/`:** solo VirGL
  (única vía sin root para Mali), binario del servidor compilado por NDK/CMake como
  proot (embebido, sin descargas), display mínimo = VNC dentro del invitado +
  visor externo (documentado), `gpu_solution` se extiende a
  NONE|ADRENO_KGSL|VIRGL mutuamente excluyentes. Pendiente: aprobación del
  borrador → plan → tareas. Dudas clave pinned: tag/commit de virglrenderer,
  path del socket (`VIRGL_TEST_SERVER_URI` vs /tmp vtest) con mesa 25.0.7.
- **Spec 014 (termux-x11 complemento) borrador en
  `specs/014-termux-x11-complemento/`:** integrar el módulo `lorie` del fork
  ESTRIN217 de termux-x11 (clonado en `/root/Terminal-external/termux-x11`, con
  submódulos; ya está reorganizado como librería `lorie` + host `lorie-app`, y su
  build.gradle resuelve APPLICATION_ID desde la app host). Objetivo: lib en el
  proyecto + pestañas X11 polimórficas con acceso desde el drawer (patrón
  filemanager). **APROBADA (2026-10-22):** submódulo git (`termux-x11/` en la raíz,
  `:termux-x11:lorie` + alias `:shell-loader:stub`), pestaña que embebe `LorieView`
  con client surrogate (`LorieView.Client` nuevo, `MainActivity` lo implementa),
  companion arrancado con comando copy-paste (`app_process` + CLASSPATH del APK),
  sin icono LAUNCHER standalone, `LoriePreferences` fuera de alcance. Plan y 10
  tareas escritos y aprobados. **T1 hecha (commit T1):** submódulo pin
  `1c721a9` (gradle adapts), includes planos `:termux-x11-lorie`/`:termux-x11-stub`,
  catalog alias, `preBuild` de lorie verde con configuration-cache (providers.exec).
  Pendiente: T3→T10. Hecho T2 (submódulo pin `65a9a06`): interface `LorieView.Client`,
  `MainActivity` la implementa, manifest de lorie en base (sin LAUNCHER/Application/
  Receiver) y `lorie-app` los re-declara para standalone.

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
- Host de build aarch64: los binarios x86_64 del SDK (aidl, aapt, bcc) no corren;
  aapt2 sí (binario aarch64). Solución aplicada: `apt-get install aidl` y wrapper
  en `build-tools/36.0.0/` y `36.1.0/` (`#!/bin/sh exec /usr/bin/aidl "$@"`,
  binario original en `aidl.x86_64.bak`).
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
