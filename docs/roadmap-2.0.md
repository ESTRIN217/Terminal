# Plan de trabajo — Terminal 2.0

> Objetivo: arrancar la 2.0 sin romper la capacidad de **dogfooding** (compilar la app dentro de la propia app). Mitigación acordada: **backup instalable funcional** (APK v1.119.0 guardado + segundo dispositivo como rescatador).

Fecha: septiembre 2026 · Base: commit `f8ce27e2` (v1.119.0, rama `master`)

---

## 1. Flujo de trabajo seguro en git

- **`master` = única fuente de builds estables.** Primero se crea un *punto de restauración*: tag `v1.119.0-dogfood` (commit `f8ce27e2`) y se guarda su APK firmado de debug como respaldo inmutable.
- **Ramas por feature:** `feature/2.0-<nombre>` (glob `feature/**`). La CI ya corre tests en PRs a `master` (`run_tests.yml`) — se aprovecha tal cual.
- **Checklist de integración (antes de mergear a `master`):**
  1. `./gradlew test` en CI ✓
  2. `./gradlew assembleDebug` local ✓
  3. Instalar el APK en el dispositivo principal y probar **solo esa feature** ✓
  4. Si toca rendering/sesión, verificar que el flujo de compilación dentro de la app sigue OK ✓
- **Convención:** un commit por feature con Conventional Commits (`Added:`, `Fixed:`, ...). Features atómicas (nada de mega-branches meses sin mergear).
- **Recovery si `master` se llegara a romper:** `git checkout v1.119.0-dogfood && git checkout -b hotfix/restore` y recompilar desde ahí. `master` nunca se reescribe con `reset`/`force-push`.

## 2. Backup instalable funcional (mitigación)

- **Inmediato:** descargar el APK v1.119.0 (de la Release de GitHub o de `assembleDebug`) y guardarlo en el segundo dispositivo **y** en un sitio fuera de ambos (PC/Drive). El segundo dispositivo ya tiene la app instalada = respaldo que no compila pero sí abre terminal.
- **Checkpoint por feature:** script `scripts/checkpoint.sh` (o task de Gradle) que hace `git tag` + `assembleDebug` + copia `terminal_<version>+checkpoint.apk` a carpeta compartida. Permite volver a cualquier punto probado.
- **Toolchain en el segundo dispositivo (paso pendiente, bajo riesgo):** script bootstrap que instale en él JDK 17, Android SDK + NDK `30.0.14904198` y el wrapper de Gradle. Así, si el principal se queda muerto a mitad de una feature, el segundo compila el último tag checkpoint.
- **Regla de oro:** nunca mergear una feature de rendering sin un **feature-flag** (ver Fase 3).

## 3. Roadmap 2.0 (ordenado por riesgo)

### Fase 0 — Cimientos (hacer antes que nada)

- Punto de restauración `v1.119.0-dogfood` + APK guardado + script de checkpoint.
- Framework de **feature-flags** para features de alto riesgo (si no existe: validar en `TermuxViews`/`Settings`).
- Decidir la estrategia de ramas: `master` con bugs-solo + rama `release/2.0` acumulando features (recomendado) **o** features mergeadas directo a `master` con el checklist.

### Fase 1 — Quick wins de bajo riesgo (aisladas, `Added:`)

1. **Mostrar licencia de proot y Debian** — añadir entradas en `LicensesScreen.kt` (licencias en `app/src/main/assets/arm64-v8a/proot` y `app/src/main/assets/debian`). Trivial.
2. **Copiar ruta de archivos/carpetas** en el gestor de archivos — nueva acción en `FileOperationsHelper` + icono en `FileManagerScreen`. Bajo riesgo.
3. **Colores verdaderos 24-bit** — el emulador ya soporta `#RRGGBB` (`terminal-emulator/.../TerminalColors.java:41`); la feature real es exponerlo/aplicarlo en la paleta Compose (`TerminalPalette.kt`) y ajustes. Bajo.

### Fase 2 — UI Compose (medio riesgo)

4. **Multipaneleo nativo `ListDetailPaneScaffold`** (Material3 adaptive): reorganizar navegación de tabs/drawer/ajustes → central (settings). Refactor de navegación; no toca el terminal.
5. **Fuentes y ligaduras tipográficas** — selector de fuente monospace + ligaduras; toca el rendering de `TerminalView` pero con fallback a la fuente por defecto.

### Fase 3 — Rendering del terminal (alto riesgo, el corazón del 2.0)

6. **Terminal nativa pintada con Compose** — reescribir el render con `Canvas` Compose. **Feature-flag obligatorio** + mantener la vista legada como fallback hasta madurar.
7. **Hipervínculos (OSC 8) e imágenes** — toca `terminal-emulator` (protocolo) y el view (render). Necesita los tests del emulador (`./gradlew test`) verdes antes de mergear.

### Fase 4 — Release 2.0

- Bump `versionName` → `2.0.0` y `versionCode` → `120` (el validador semver de `app/build.gradle.kts` ya acepta `2.0.0`).
- Tag `v2.0.0` → la CI ya adjunta APK de debug a la Release automáticamente.
- Verificar migración de datos existentes (rootfs Debian, ajustes) intacta.

---

## 4. Estado

| Fase | Estado |
|---|---|
| Fase 0 — Cimientos | Pendiente |
| Fase 1 — Quick wins | Pendiente |
| Fase 2 — UI Compose | Pendiente |
| Fase 3 — Rendering | Pendiente |
| Fase 4 — Release 2.0 | Pendiente |