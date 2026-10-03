#!/usr/bin/env bash
# checkpoint.sh — Crea un punto de restauración probado (tag git + APK de debug).
#
# Un "checkpoint" es un estado del código que sabemos que compila y pasa tests:
#  1. Verifica que el árbol de trabajo esté limpio.
#  2. Ejecuta ./gradlew test (se puede saltar con --skip-tests).
#  3. Compila assembleDebug con TERMUX_APK_VERSION_TAG=checkpoint-<tag>.
#  4. Copia los APK generados a ./checkpoints/ (no versionado).
#  5. Crea un tag anotado `<name>` en el commit actual.
#
# Uso:
#   scripts/checkpoint.sh [name] [--skip-tests] [--no-tag]
#
#   name       Nombre del tag. Por defecto: checkpoint-<YYYYMMDD-HHMM>.
#   --skip-tests  Omite ./gradlew test (solo compila).
#   --no-tag      No crea el tag git.
#
# Ejemplo:
#   scripts/checkpoint.sh v1.119.0-dogfood

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CHECKPOINT_DIR="${REPO_ROOT}/checkpoints"

NAME="checkpoint-$(date +%Y%m%d-%H%M)"
SKIP_TESTS=0
CREATE_TAG=1

for arg in "$@"; do
    case "${arg}" in
        --skip-tests) SKIP_TESTS=1 ;;
        --no-tag) CREATE_TAG=0 ;;
        *) NAME="${arg}" ;;
    esac
done

cd "${REPO_ROOT}"

if [ -n "$(git status --porcelain)" ]; then
    echo "ERROR: el árbol de trabajo tiene cambios sin commitear." >&2
    echo "  Haz commit o stash antes de crear un checkpoint." >&2
    exit 1
fi

CURRENT_VERSION="$(${REPO_ROOT}/gradlew -q :app:versionName 2>/dev/null || echo desconocida)"
COMMIT="$(git rev-parse --short HEAD)"
echo "Checkpoint '${NAME}' para ${CURRENT_VERSION} (${COMMIT})"

if [ "${SKIP_TESTS}" -eq 0 ]; then
    echo ">>> ./gradlew test"
    ./gradlew test
fi

echo ">>> ./gradlew assembleDebug (TERMUX_APK_VERSION_TAG=checkpoint-${NAME})"
TERMUX_APK_VERSION_TAG="checkpoint-${NAME}" ./gradlew assembleDebug

mkdir -p "${CHECKPOINT_DIR}"
for apk in app/build/outputs/apk/debug/*.apk; do
    [ -e "${apk}" ] || continue
    cp -v "${apk}" "${CHECKPOINT_DIR}/"
done

if [ "${CREATE_TAG}" -eq 1 ]; then
    if git rev-parse "${NAME}" >/dev/null 2>&1; then
        echo "ERROR: el tag '${NAME}' ya existe." >&2
        exit 1
    fi
    git tag -a "${NAME}" -m "Checkpoint ${CURRENT_VERSION} (${COMMIT})"
    echo ">>> Tag '${NAME}' creado"
fi

echo
echo "Listo. Respaldo del checkpoint:"
git tag -l "${NAME}"
ls -lh "${CHECKPOINT_DIR}"