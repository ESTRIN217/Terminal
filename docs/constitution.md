# Constitución — Terminal

Principios innegociables. Si una decisión los contradice, se cambia la decisión o esta constitución
de forma explícita; nunca se incumple en silencio.

1. **Stack mínimo.** Dependencia nueva solo si no hay alternativa en `gradle/libs.versions.toml`, con el porqué en el `plan.md`. *Verificable:* todo delta del catálogo se cita en un plan.
2. **La spec manda.** Sin `specs/NNN-*/` aprobada no hay cambio de comportamiento; la spec se corrige antes que el código. *Verificable:* cada tarea implementada referencia sus RF.
3. **Lógica fuera de la UI.** Parseo, split y cálculos van en funciones puras; Compose y las Views solo pintan y despachan. *Verificable:* la lógica nueva tiene test JUnit que corre sin Android.
4. **Tests primero.** Test en rojo, luego código, `./gradlew test` en verde antes de marcar tarea. *Verificable:* `./gradlew test` es el único gate; no hay lint ni formatter que lo sustituya.
5. **Datos del usuario sagrados.** Nada se borra o sobrescribe fuera de la sesión sin confirmación; toda descarga con SHA-256 fijado en `TermuxConstants`; secretos solo en `key.properties`. *Verificable:* cero secretos en el repo, cero borrados silenciosos.
6. **Un idioma por capa.** Identificadores y comentarios técnicos en inglés; docs, specs y `MEMORY.md` en español; todo texto visible en `strings.xml` (EN, `values-es-rVE`, `values-pt-rBR`), nunca literal en Compose. *Verificable:* `grep -rnP '(Text|contentDescription)\(\s*"' --include=*.kt app/src/main filemanager/src/main` no devuelve literales.
