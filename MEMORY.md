# MEMORY.md — Terminal
## Estado actual
- **v3.1.0 PUBLICADA (2026-10-30/31)**: tag `v3.1.0` (`a8932425`), release en GitHub
  con APK firmado (`terminal_3.1.0_universal.apk`) + `sha256sums` renombrados al
  patrón correcto + los `+github-debug` de CI. En master (`aeacac26`).
  **Pendiente:** commit de reajustes del sitio (`public/`, usuario) +
  `update-site.py` + `firebase deploy --only hosting` (sitio y wiki, dos targets).
- **Sitio (`public/`) reajustado por el mantenedor (2026-10-31)**: footer de 4
  columnas, cards podadas a 4, FAQ ampliada, Android 8.0/API 26, minSdk en
  JSON-LD (ambos HTML). El `404.html` del sitio se dejó SIN datos que caducan
  (enlaces a `/releases/latest`, sin versión/fecha horneada).
- **Wiki revisada y corregida (2026-10-31, `aeacac26`)**: requisito API 26 y
  APK ~104 MB (`instalacion.html`), sección «Navegador interno» + Ejecutar/
  Descomprimir del gestor (`uso-terminal.html`), ajuste «Abrir enlaces en la
  app» en `hipervinculos.html`. USB (020/021) y nvim ya estaban.

## Decisiones permanentes
- **Imágenes del sitio → JPEG**: PNG con degradado → `ffmpeg -vf format=yuvj420p
  -q:v 3`. Sin canal alpha real en las capturas; ~7× menos peso. Las imágenes
  viven en `public/img/*.jpg`; `icon-192.png` queda como PNG (transparencia real).
- **Wiki y 404 versionless**: la wiki y los 404 no llevan número de
  versión horneado; los datos que caducan del sitio principal van solo en
  `public/index.html` bajo `data-site=` (los actualiza `update-site.py`, que
  hoy SOLO procesa ese fichero).
- **`nvim` en DEBIAN_EDITOR_CANDIDATES** (enmienda spec 009 RF-2, 2026-10-29):
  `apt install neovim` da `usr/bin/nvim`. Test de regreso:
  `EditorPolicyTest.debianCandidatesIncludeNvim`.
- **Spec 022 R8 verificado** (2026-10-30, release 3.1.0). Header → «cerrada».
- **Spec 020** header → «cerrada» (verificada en dispositivo 2026-10-26).

## Residuales y limitaciones conocidas
- btop ≥80×24, Adreno sin validar en hardware, glxinfo BadMatch rootless,
  Zink/OpenCL sin vía, Sixel/animaciones no soportadas. Zutty congelado bajo VirGL
  (documentado en `limitaciones.html #virgl-zutty`); inofensivo.
- 017 fuera de alcance: ekbar completo, `extra_keys_config`, `userActions`.
- Android Studio ARM64: guía en `wiki/android-studio.html`. Parche skiko
  (`libskiko-linux-arm64.so` → Maven `skiko-awt-runtime-linux-arm64`).

## Spec 021 — Almacenamiento USB (CERRADA 2026-10-28)
- **Reglas permanentes:** vía bind `-b /storage:$HOME/storage` (SELinux deniega
  readdir de `/storage`, no el hijo); puente de ficheros descartado;
  `lsblk` NO leerá sysfs real (SELinux untrusted_app); no usar
  `ACTION_MEDIA_MOUNTED` (restringido).
