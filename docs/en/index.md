# Terminal — Documentation

[Terminal] is a terminal emulator for Android that runs a full Debian environment through
proot. No root, no bootstrap archives, and no setup beyond installing the APK: on first
launch the app downloads a Debian rootfs, verifies it, and configures it.

**Terminal is an independent fork and is not affiliated with, endorsed by, or supported by
the Termux team.** Report issues at [this fork's Issues].

## Requirements

- Android with an `arm64-v8a` CPU. The bundled proot binary is AArch64-only.
- Storage permission for `/sdcard` access inside the session.

## Where things are

- **Repository** — <https://github.com/ESTRIN217/Terminal>
- **Issues** — <https://github.com/ESTRIN217/Terminal/issues>
- **Wiki** — <https://github.com/ESTRIN217/Terminal/wiki>
- **Releases** — <https://github.com/ESTRIN217/Terminal/releases>

## Guides

| Topic | Document |
|---|---|
| Roadmap and status of every feature | [`roadmap-2.0.md`](roadmap-2.0.md) |
| Release history | [`CHANGELOG.md`](../CHANGELOG.md) |
| How the proot + Debian arm64 integration works | [`proot-debian-arm64-plan.md`](proot-debian-arm64-plan.md) |
| Hyperlinks (OSC 8) and selecting URLs | [`hipervinculos-y-seleccionar-url.md`](hipervinculos-y-seleccionar-url.md) |
| Historical plans (completed) | [`archive/`](archive/) |

## Known limitations

On some devices the OEM SELinux policy blocks the app from enumerating `/dev`, `/dev/pts`
and `/sys`, so listing them inside the Debian session can fail with `Permission denied`.
Individual device access (`/dev/null`, `/dev/tty`, …) keeps working.

Similarly, since Android 8 (API 26) SELinux denies `untrusted_app` reads of `/proc/stat`, so
CPU monitors such as `btop` and `htop` show no CPU statistics on stock devices with SELinux in
enforcing mode. The `-b /proc` bind is present and correct; there is no workaround without
root or a permissive policy.

## Building from source

Java 17 and the NDK `30.0.14904198` are required.

```sh
./gradlew test          # unit tests
./gradlew assembleDebug # debug APK
```

Release builds read signing credentials from `key.properties` in the repository root; see
[`key.properties.example`](../key.properties.example). That file is never committed.

[Terminal]: https://github.com/ESTRIN217/Terminal