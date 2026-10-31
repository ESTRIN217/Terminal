# MEMORY.md — Terminal
## Estado actual
- **v3.1.0 lista para publicar (2026-10-30)**: rama `experimental`, 50 commits
  sobre `master`/`3.0`. Specs 020–023 CERRADAS. R8/`assembleRelease` verificado
  (`190ceaf2`). CHANGELOG partido en [3.0.0] 26-oct / [3.1.0] 30-oct.
  versionCode 123, versionName 3.1.0. Imágenes del sitio migradas a JPEG.
  **Pendiente:** merge a master, tag v3.1.0, APK firmado, `update-site.py` +
  `firebase deploy --only hosting` (dos targets).

## Decisiones permanentes
- **Imágenes del sitio → JPEG**: PNG con degradado → `ffmpeg -vf format=yuvj420p
  -q:v 3`. Sin canal alpha real en las capturas; ~7× menos peso. Las imágenes
  viven en `public/img/*.jpg`; `icon-192.png` queda como PNG (transparencia real).
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
