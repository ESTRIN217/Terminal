# MEMORY.md — Terminal
Memoria del proyecto entre sesiones. Máximo ~50 líneas: resume o elimina lo que ya no 
aporte. 
## Estado actual 
- Rama `release/2.0`, versión 2.0.0 (versionCode 120). Signing release usa `key.properties`
  (gitignored) para no exponer credenciales; `enableOnBackInvokedCallback` activo.
- Extra keys con paridad de Termux: macros, popup swipe-up, `extra-keys-style`,
  `extra-keys-text-all-caps`, claves especiales KEYBOARD/DRAWER/PASTE/SCROLL y DECCKM.
- `./gradlew test` y `:app:assembleDebug` en verde.

## Decisiones (y por qué) 
- El parser Kotlin de extra keys **reutiliza** `ExtraKeysConstants.CONTROL_CHARS_ALIASES` y
  `ExtraKeysInfo.getCharDisplayMapForStyle()` de `termux-shared` en vez de duplicar los mapas:
  una sola fuente de verdad. Si algún día se borra el paquete Java `extrakeys/`, hay que
  trasplantar esos mapas a Kotlin antes.
- El popup se resuelve con un `pointerInput` propio que **consume el `move`**: eso cancela a la
  vez el tap y el long-press de `combinedClickable`, así que no hay doble disparo y se conserva
  TalkBack. Si el offset del `Popup` saliera desfasado en dispositivo, la alternativa es dibujarlo
  como overlay dentro de la `Box` de `ExtraKeysBar` con `localBoundingBoxOf`.
- `ExtraKeyConfig.isMacro` lo decide el config (`key` ausente + `macro` presente); antes se
  infería de `prefix.isNotEmpty()` y por eso `{macro: ...}` se escribía literal en el terminal.
- El split de macros vive en `TerminalKeyHandler.parseMacro()` (función pura) para poder testearlo
  sin Compose; `TermuxMainScreen` solo la consume.
- **Tests de extra keys = JUnit plano, no Robolectric**: el native runtime de Robolectric no
  existe para Linux aarch64 (este host) y dejaba los 17 tests en rojo. Se añadió
  `testImplementation(libs.org.json)` + `unitTests.isReturnDefaultValues = true`.
- `extra-keys-style=default` cambia el aspecto de la barra (ENTER ↲, TAB ↹, BKSP ⌫, DEL ⌦,
  "-" → ―). Es la paridad buscada, pero es un cambio visual visible al actualizar.

## Aprendizajes y errores a evitar 
- Compose 1.13: `ViewConfiguration.current` no existe → `LocalViewConfiguration.current`.
- Compose 1.13: `PointerInputChange.positionChange()` es una extensión de primer nivel; sin su
  import Kotlin resuelve el campo interno `positionChange$ui: Boolean` y falla la compilación.
- `optString("macro", "")` nunca devuelve `null`: no usarlo como bandera de presencia.
- El paquete Java `termux-shared/.../extrakeys/` sigue sin vistas que lo inflen; solo se usan sus
  constantes desde Kotlin.
- Los modificadores sticky nunca se desactivan solos (hay que pulsarlos otra vez): Termux los
  apagaba tras cada tecla salvo que estuvieran bloqueados.

## Próximos pasos 
- Verificar en dispositivo: posición del `Popup` del popup, repeat de flechas con el popup abierto,
  y las 4 claves especiales.
- Opcionales: auto-desactivar sticky modifiers, lock por long-press, altura de barra configurable
  (`terminal-toolbar-height-scale-factor`), y borrar el Java muerto de `extrakeys/`.
