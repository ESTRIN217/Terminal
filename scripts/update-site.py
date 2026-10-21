#!/usr/bin/env python3
"""update-site.py — Sincroniza la página estática de `public/` con la última release.

Qué actualiza en `public/index.html`:
  - La versión, en todas sus apariciones (título, og:title, badge, enlaces de descarga,
    nombre del APK, tag de la release y JSON-LD). El valor viejo se lee del marcador
    `data-site="version"` y se reemplaza en todo el fichero.
  - Los marcadores puntuales: `data-site="apk-size"`, `data-site="published"` y
    `data-site="sha256"`.

Uso:
  scripts/update-site.py [--repo ESTRIN217/Terminal] [--html public/index.html] [--dry-run]

  --repo   Dueño/repo de GitHub de donde se saca la release. Por defecto ESTRIN217/Terminal.
  --html   Ruta del HTML a actualizar. Por defecto public/index.html.
  --dry-run  No escribe nada: imprime el diff y la lista de cambios.

Notas:
  - Solo biblioteca estándar: no añade dependencias al repo.
  - Idempotente: si la página ya está al día no toca el fichero.
  - El SHA-256 no se inventa, se lee del asset `<versión>_sha256sums` de la misma release,
    que es la única fuente de verdad. La página solo lo copia.
  - Si el asset de la APK no existe, falla con la lista de lo que sí hay; no adivina.
"""

import argparse
import difflib
import json
import os
import re
import sys
import urllib.error
import urllib.request

DEFAULT_REPO = "ESTRIN217/Terminal"
DEFAULT_HTML = os.path.join("public", "index.html")
API_ROOT = "https://api.github.com"
USER_AGENT = "terminal-update-site"
TIMEOUT = 30

MONTHS = [
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
]

VERSION_MARKER = re.compile(
    r'(<[^>]*\bdata-site="version"[^>]*>)([^<]*)(<)', re.IGNORECASE
)
SITE_MARKER = re.compile(
    r'(<[^>]*\bdata-site="([a-z0-9-]+)"[^>]*>)([^<]*)(<)', re.IGNORECASE
)


def fetch(url, accept=None):
    """Descarga una URL y devuelve los bytes; con `accept`, el texto."""
    headers = {"User-Agent": USER_AGENT}
    if accept:
        headers["Accept"] = accept
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        raise SystemExit(f"ERROR: {url} -> HTTP {error.code} {error.reason}")
    except urllib.error.URLError as error:
        raise SystemExit(f"ERROR: no se pudo conectar con {url}: {error.reason}")


def fetch_json(url):
    return json.loads(fetch(url, accept="application/vnd.github+json"))


def fetch_text(url):
    return fetch(url, accept="text/plain").decode("utf-8", errors="replace")


def pick_apk(assets, version):
    """Devuelve el asset de la APK firmada, o aborta diciendo qué hay.

    El prefijo del nombre no importa (`terminal_…`, `app_…`): lo que decide es la
    versión y que no sea una build de debug.
    """
    wanted = "_%s_universal.apk" % version
    for asset in assets:
        name = asset.get("name", "")
        if name.endswith(wanted) and "debug" not in name:
            return asset
    candidates = [
        asset.get("name") for asset in assets
        if asset.get("name", "").endswith("_universal.apk") and "debug" not in asset.get("name", "")
    ]
    if candidates:
        raise SystemExit(
            "ERROR: la release no tiene una APK universal de la %s. Candidatas: %s"
            % (version, ", ".join(candidates))
        )
    available = ", ".join(asset.get("name", "?") for asset in assets) or "(ninguno)"
    raise SystemExit("ERROR: la release no adjunta ninguna APK. Assets: %s" % available)


def pick_sha256(assets, version, apk_name):
    """Lee el `<versión>_sha256sums` de la release y devuelve el hash del APK."""
    wanted = "_%s_sha256sums" % version
    asset = next((a for a in assets if a.get("name", "").endswith(wanted)), None)
    if asset is None:
        raise SystemExit(
            "ERROR: la release no adjunta ningún asset que acabe en %s. Sin ese fichero "
            "no hay hash que copiar a la página." % wanted
        )
    for line in fetch_text(asset["browser_download_url"]).splitlines():
        fields = line.split()
        if len(fields) == 2 and fields[1].lstrip("*") == apk_name:
            if not re.fullmatch(r"[0-9a-f]{64}", fields[0]):
                raise SystemExit("ERROR: hash con formato raro en %s: %r" % (asset["name"], fields[0]))
            return fields[0]
    raise SystemExit(
        "ERROR: %s no contiene una línea para %s" % (asset["name"], apk_name)
    )


def format_size(num_bytes):
    """'14 MB': megas redondeados, que es como se habla del tamaño de una APK."""
    return "%d MB" % round(num_bytes / (1024 * 1024))


def format_date(iso_timestamp):
    """'2026-10-03T00:43:05Z' -> '3 de octubre de 2026'."""
    match = re.match(r"(\d{4})-(\d{2})-(\d{2})", iso_timestamp or "")
    if not match:
        return ""
    year, month, day = (int(part) for part in match.groups())
    if not 1 <= month <= 12:
        return ""
    return "%d de %s de %d" % (day, MONTHS[month - 1], year)


def read_current_version(html):
    match = VERSION_MARKER.search(html)
    if not match:
        raise SystemExit(
            'ERROR: no encuentro el marcador data-site="version" en el HTML. '
            "Ese marcador es el que dice qué versión está puesta ahora."
        )
    return match.group(2).strip()


def replace_version(html, old, new):
    """Sustituye la versión vieja en todo el fichero (nombre del APK, tag, JSON-LD...)."""
    return html.replace(old, new)


def set_markers(html, values):
    """Rellena los marcadores data-site con `values`; ignora los que no conoce."""
    def substitute(match):
        key = match.group(2)
        return match.group(1) + values.get(key, match.group(3)) + match.group(4)

    return SITE_MARKER.sub(substitute, html)


DATE_SHAPE = re.compile(r"^\d{1,2} de [a-zé]+ de \d{4}$")
SIZE_SHAPE = re.compile(r"^\d+ MB$")
SEMVER_SHAPE = re.compile(r"^\d+\.\d+\.\d+(-\S+)?(\+\S+)?$")


def validate_data(data):
    """No deja escribir basura en la página: la función pura es la última barrera.

    `pick_apk` y `pick_sha256` ya filtran al descargar, pero `render` es la que
    escribe: si alguien la llama con datos de otro sitio, aquí se corta.
    """
    if not SEMVER_SHAPE.match(data.get("version", "")):
        raise SystemExit("ERROR: versión con formato raro: %r" % data.get("version"))
    if not re.fullmatch(r"[0-9a-f]{64}", data.get("sha256", "")):
        raise SystemExit("ERROR: hash con formato raro: %r" % data.get("sha256"))
    if not SIZE_SHAPE.match(data.get("apk_size", "")):
        raise SystemExit("ERROR: tamaño con formato raro: %r" % data.get("apk_size"))
    if not DATE_SHAPE.match(data.get("published", "")):
        raise SystemExit("ERROR: fecha con formato raro: %r" % data.get("published"))


def render(html, data):
    """Aplica todos los cambios y devuelve (html nuevo, resumen de cambios)."""
    validate_data(data)
    old_version = read_current_version(html)
    new_version = data["version"]
    changes = []

    updated = replace_version(html, old_version, new_version)
    if old_version != new_version:
        changes.append("versión: %s -> %s" % (old_version, new_version))

    markers = {"apk-size": data["apk_size"], "published": data["published"], "sha256": data["sha256"]}
    for key, value in markers.items():
        before = SITE_MARKER.findall(html)
        if not any(key == match[1] for match in before):
            raise SystemExit('ERROR: el HTML no tiene ningún marcador data-site="%s".' % key)
        if value and value in updated:
            changes.append("%s: %s" % (key, value))

    return set_markers(updated, markers), changes


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=DEFAULT_REPO, help="dueño/repo de GitHub")
    parser.add_argument("--html", default=DEFAULT_HTML, help="ruta del HTML")
    parser.add_argument("--dry-run", action="store_true", help="no escribir, solo mostrar el diff")
    args = parser.parse_args()

    if not os.path.isfile(args.html):
        raise SystemExit("ERROR: no encuentro %s. Ejecútalo desde la raíz del repo." % args.html)

    release = fetch_json("%s/repos/%s/releases/latest" % (API_ROOT, args.repo))
    tag = release.get("tag_name", "")
    version = tag[1:] if tag.startswith("v") else tag
    if not SEMVER_SHAPE.match(version):
        raise SystemExit("ERROR: el tag %r no es una versión semver." % tag)

    assets = release.get("assets", [])
    apk = pick_apk(assets, version)
    if not apk.get("size"):
        raise SystemExit("ERROR: el asset %s no informa de tamaño; no publicaría un '0 MB'." % apk["name"])
    data = {
        "version": version,
        "apk_size": format_size(apk.get("size", 0)),
        "published": format_date(release.get("published_at", "")),
        "sha256": pick_sha256(assets, version, apk["name"]),
    }

    with open(args.html, encoding="utf-8") as handle:
        original = handle.read()

    updated, changes = render(original, data)

    if updated == original:
        print("Sin cambios: %s ya está en la %s (%s)." % (args.html, tag, apk["name"]))
        return 0

    diff = difflib.unified_diff(
        original.splitlines(keepends=True),
        updated.splitlines(keepends=True),
        fromfile=args.html,
        tofile=args.html,
    )
    if args.dry_run:
        print("".join(diff), end="")
        print("\n--dry-run: no se ha escrito nada.")
    else:
        with open(args.html, "w", encoding="utf-8") as handle:
            handle.write(updated)
        print("Actualizado %s -> %s" % (args.html, tag))

    for change in changes:
        print("  - %s" % change)
    return 0


if __name__ == "__main__":
    sys.exit(main())