# AGENTS.md — Terminal (ESTRIN217 fork)

## Build & Test

- **Java 17 required.** Build fails with older versions.
- **NDK `30.0.14904198`** — native C code in `terminal-emulator`, `termux-shared`, and `app` modules. CMake `3.31.6`.
- Build: `./gradlew assembleDebug`
- Tests: `./gradlew test` (unit tests only; no instrumented tests in CI)
- **No lint, detekt, ktlint, or formatter is configured.** The only CI quality gate is `./gradlew test`.
- Gradle 9.7.0, AGP 9.3.1. Daemon enabled, parallel builds capped at 2 workers.

## Module Architecture

```
app  →  termux-shared  →  terminal-view  →  terminal-emulator
```

| Module | Package | Purpose |
|---|---|---|
| `terminal-emulator` | `com.termux.terminal` | VT100/xterm emulation engine, JNI pty, zero internal deps |
| `terminal-view` | `com.termux.view` | Android `View` rendering for the terminal |
| `termux-shared` | `com.termux.shared` | Shared logic: settings, file utils, error system, crash handling, constants |
| `app` | `com.termux` | UI activities, services, app entry point — **Java + Kotlin (Compose)** |

- `terminal-view` exposes `terminal-emulator` via `api()` (transitive). Don't change this to `implementation()` without understanding the impact on consumers.
- Modify `terminal-emulator` for emulation bugs. Modify `app` for UI/Activity bugs. Modify `termux-shared` for cross-cutting concerns.

## Language: Java + Kotlin

- **Library modules (`terminal-emulator`, `terminal-view`, `termux-shared`) are pure Java.** Do not add Kotlin files there.
- **`app` module uses Kotlin** for the Jetpack Compose UI layer (`com.termux.terminal.compose` package). Existing activities/services remain Java.
- **Hungarian notation** (`m` prefix: `mTermuxService`, `mIsVisible`) is used in **both Java and Kotlin** — keep this when adding new code.
- **`LOG_TAG`:** every class defines `private static final String LOG_TAG = "ClassName";` at the top (Java) or `companion object` (Kotlin).
- **`final` classes** for concrete implementations (e.g., `TerminalView`, `TerminalSession`).
- **Javadoc/KDoc** on all public methods with `@param`/`@return`. Use `{@link ClassName}` for cross-references.
- **Constants in `TermuxConstants.java`** (~1357 lines) — all string constants, paths, intent actions, and extras live here. Add new constants here, not scattered across classes.
- Indent: 4 spaces (2 for YAML). LF line endings. UTF-8. See `.editorconfig`.

## Compose UI (app module only)

- Compose stack: BOM (`2025.08.00`), Material3, ViewModel, coroutines, Ktor (HTTP), commons-compress (rootfs extraction).
- Entry point: `TermuxComposeActivity.kt` — the main Compose activity.
- `TerminalViewRegistry` holds the active `TerminalView` reference for Compose callbacks.
- New Compose UI goes in `com.termux.terminal.compose`. New settings screens go in `com.termux.terminal.compose.settings`.

## Error Handling

- **Return `Error` objects, don't throw.** `null` means success:
  ```java
  Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(ctx, true, true);
  if (error != null) {
      Logger.logErrorExtended(LOG_TAG, "Failed\n" + error);
      return;
  }
  ```
- **`Errno` class** defines error codes (`ERRNO_SUCCESS`, `ERRNO_CANCELLED`, `ERRNO_FAILED`). Use `Errno.getError()` factory methods.
- **Try/catch is targeted,** not blanket. Catch specific exceptions (`IOException`, `BadTokenException`). Log via `Logger`, show Toast to user, set state flag (e.g., `mIsInvalidState = true`).
- **Crash handling:** `CrashHandler` (uncaught exceptions) → writes to crash log file → notifies app via broadcast. Set in `TermuxApplication.onCreate()`.
- **Logging:** Use `com.termux.shared.logger.Logger`, not `android.util.Log` directly. Logger handles Android's 4068-byte logcat limit by splitting long messages.

## Architecture Patterns

- **Client Interface Pattern:** Interfaces define contracts (`TerminalSessionClient`, `TerminalViewClient`). Base classes provide no-op defaults (`TermuxTerminalSessionClientBase`). Concrete implementations in `app` extend bases. Follow this pattern for new callbacks.
- **Service lifecycle:** `TermuxService` outlives `TermuxActivity`. Activity re-binds on rotation/restart. Don't store activity references in the service — use the client interface.
- **Static utility classes** for stateless helpers (`TermuxUtils`, `TermuxThemeUtils`, `DataUtils`).

## Gotchas

- **targetSdk 28 is intentional** — raising it triggers scoped storage and other restrictions that break file access model.
- **minSdk 24** — proot uses `getifaddrs` (Bionic API 24+). Native code is compiled against android-28.
- **ABI splits** are enabled by default for debug builds (`arm64-v8a` + universal). Controlled by `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS` env var.
- **arm64-v8a only** — proot binary (loader + AArch64 assembly) is hardcoded for arm64. No other ABIs are supported at runtime.
- **proot build pipeline** — CMake builds a multi-stage proot binary (loader → strip → objcopy → loader-info → final proot), staged to `app/src/main/assets/arm64-v8a/proot`. `merge.*Assets` tasks depend on `buildCMake` to ensure the binary is in the APK on first build.
- **Debian rootfs** downloads at **first launch** (not build time) via `DebianInstaller.java` with streaming SHA-256 verification. The old `downloadBootstraps` Gradle task was removed.
- **Shared UID** (`com.termux`) — all Termux apps share a Linux UID. APKs must be signed with the same key.
- **JitPack NDK** (`29.0.14206865` via `JITPACK_NDK_VERSION` env) differs from local/CI NDK (`30.0.14904198`). This is expected — JitPack uses an older NDK.
- **Commit convention:** Conventional Commits with capitalized leading types: `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`.
- **Version name** must follow semver (`major.minor.patch`). Validated in `app/build.gradle.kts`. Use `./gradlew :app:versionName` to print the current version.

## Fork-specific context

- Package: `com.estrin217.terminal`. App name: "Terminal". Not affiliated with the Termux team.
- README and docs are in Spanish (repository language). Code comments are in Spanish or English.
- This fork bundles proot + Debian rootfs instead of the upstream Termux bootstrap packages.
