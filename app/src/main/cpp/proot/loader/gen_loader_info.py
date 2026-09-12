#!/usr/bin/env python3
"""Generate loader/loader-info.c from `llvm-readelf -s <loader>` output.

Replaces loader-info.awk (which needs gawk for strtonum; only mawk is
available on build hosts). Reads readelf symbol table from stdin, finds
`_start` and `pokedata_workaround`, and emits the offset C file consumed
by the bundled proot build (HAS_POKEDATA_WORKAROUND, arm64-only).
"""
import sys


def parse_value(token):
    token = token.strip()
    if token.lower().startswith("0x"):
        token = token[2:]
    return int(token, 16)


def main():
    start = None
    pokedata = None
    for line in sys.stdin:
        parts = line.split()
        # llvm-readelf -s rows: Num: Value Size Type Bind Vis Ndx Name
        if len(parts) < 8:
            continue
        name = parts[-1]
        if name in ("_start", "pokedata_workaround"):
            try:
                value = parse_value(parts[1])
            except ValueError:
                continue
            if name == "_start":
                start = value
            else:
                pokedata = value
    if start is None or pokedata is None:
        sys.stderr.write(
            "gen_loader_info: missing symbols (start=%s pokedata=%s)\n"
            % (start, pokedata)
        )
        return 1
    sys.stdout.write("#include <unistd.h>\n")
    sys.stdout.write(
        "const ssize_t offset_to_pokedata_workaround=%d;\n"
        % (pokedata - start)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
