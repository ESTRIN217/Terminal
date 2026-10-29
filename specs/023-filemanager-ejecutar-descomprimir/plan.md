# Plan 023 — Gestor de archivos: Ejecutar en la terminal y Descomprimir

Spec: `specs/023-filemanager-ejecutar-descomprimir/spec.md`. Cubre RF-1 a
RF-9 y RNF-1 a RNF-6.

## Archivos y responsabilidades

| Fichero | Cambio | RF |
|---|---|---|
| `filemanager/…/compose/ExtractPolicy.kt` (nuevo) | `ExtractFormat` (contenedor/suelto), `ExtractionTarget` (`Folder(name)`/`Single(name)`), `supported(name)` y `targetOf(name)` | RF-5, RF-6 |
| `filemanager/…/compose/RunPolicy.kt` (nuevo) | `RunPlan` sealed (`Direct`/`Interpreter`/`NotExecutable`) y `decide(name, canExecute, interpretersAvailable)` con escapado de shell | RF-2, RF-3, RF-4 |
| `filemanager/…/ArchiveExtractor.kt` (nuevo, Kotlin, raíz junto a `ArchiveCreator`) | Extracción en disco: zip (`ZipFile`), tar/tar.gz/tgz/tar.bz2/tar.xz (`TarArchiveInputStream` sobre `CompressorStreamFactory` o crudo), 7z (`SevenZFile`, contraseña opcional), suelto gz/bz2/xz (`CompressorInputStream`); validación zip-slip; destino parcial eliminado al fallar | RF-6, RF-7, RF-8, RNF-5 |
| `filemanager/…/FileManagerViewModel.kt` | `extractItem(file, password?, onResult)` en IO con busy + refresh; notificaciones `notifyExtractDone/Collision/Failed/UnsafeEntry`; resultado `ExtractionOutcome` con `PasswordRequired` para el bucle del diálogo | RF-6, RF-7, RF-8 |
| `filemanager/…/FileManagerScreen.kt` | Menú por ítem: «Ejecutar en la terminal» (tras «Abrir con…», archivos, callback opcional) y «Descomprimir» (tras «Comprimir…», formatos extraíbles); `DialogKind.EXTRACT_PASSWORD` + diálogo de contraseña; cableado de los resultados | RF-1, RF-5, RF-8 |
| `app/…/FileManagerSessionHost.kt` | `onRunInTerminal: ((File) -> Unit)?` opcional (`null` ⇒ ocultar, precedente 008) | RF-1, RNF-6 |
| `app/…/TermuxMainScreen.kt` | Propaga `onRunInTerminal` hacia la activity | RF-1 |
| `app/…/TermuxComposeActivity.kt` | `runFileInTerminal(hostPath)`: rootfs/MAX sesiones/ruta mapeable; intérpretes disponibles del rootfs (`usr/bin/bash`, `usr/bin/python3`); `launchRunSession(plan)` espejo de `launchEditorSession`; aviso `not-executable` si la RunPolicy lo decide | RF-2, RF-3, RF-4, RNF-6 |
| `filemanager/res/values{,-es-rVE,-pt-rBR}/strings.xml` | Textos nuevos listados abajo | RNF-2 |

Lecturas/UT ya existentes reutilizadas sin duplicar: `DebianInstaller.isInstalled()`,
`ProotShellEnvironment.hostPathToGuestPath/isHostPathMappable`,
`TermuxConstants.DEBIAN_*`, el patrón `createTermuxSession(null, arrayOf("-c",
script), null, parentDir, false, null)` de `launchEditorSession`/`openTerminalIn`,
y el flujo busy/refresh/`statusMessage` del ViewModel.

## Funciones puras (con «hoy» como parámetro)

```kotlin
// ExtractPolicy.kt
sealed interface ExtractionTarget {
    data class Folder(val name: String)  // contenedor → dir/<name>/ nueva
    data class Single(val name: String)  // compresor suelto → dir/<name> fichero
}

enum class ExtractFormat {
    ZIP, TAR, TAR_GZ, TGZ, TAR_BZ2, TAR_XZ, SEVEN_Z,   // contenedores
    GZ, BZ2, XZ                                        // soltos; Folder cuando
}                                                      // la extensión previa es tar

/** null cuando el nombre no es extraíble (sin extensión conocida, sin base). */
fun parse(name: String): Pair<ExtractFormat, ExtractionTarget>?
// hoy: no existe extracción; la comparación es insensible a mayúsculas;
// «app.tar.gz» → TAR_GZ+Folder("app"); «a.tgz» → TGZ+Folder("a");
// «d.notes.txt.gz» → GZ+Single("notes.txt"); «.gz» → null (sin base)
```

```kotlin
// RunPolicy.kt
sealed interface RunPlan {
    data class Direct(val command: String)            // ./<name> escapado
    data class Interpreter(val interpreter: String, val command: String)
    data object NotExecutable
}

/** Bash siempre que esté; python3 solo si el rootfs lo aporta. */
fun decide(name: String, canExecute: Boolean, interpretersAvailable: Set<String>): RunPlan
// hoy: File.canExecute() del host y los {bash, python3} sondeados en usr/bin del
// rootfs los entrega la app; la política es solo de decisión y escapado:
// ext .sh/.bash → "bash", .py → "python3"; el nombre se escapa como el guest path
// de launchEditorSession (apóstrofe → '\'' ).
```

```kotlin
// ArchiveExtractor.kt
sealed interface ExtractOutcome { Ok; Collision; InvalidName; PasswordRequired; UnsafeEntry(entry: String); Failed }

/** Extrae [archive] (formato dado) en [destination]; el caller ya validó
 *  colisión y creó el directorio destino�� vacío. `null` = éxito. */
fun extract(archive: File, destination: File, format: ExtractFormat, password: CharArray?): ExtractOutcome?
// hoy: el ViewModel valida destino (Collision) y formato (InvalidName) con la
// policy ANTES de tocar disco; a mitad de escritura cualquier fallo elimina
// [destination] completo (recién creado, nada preexistente dentro, RNF-5).
```

## Algoritmo en pseudocódigo

### RF-5/RF-6: menú + extracción
```
menú por ítem (archivo):
  "Comprimir…"     enabled=!busy (vigente)
  "Descomprimir"   SOLO si parse(item.name) != null && !isFolder; enabled=!busy
al pulsar:
  viewModel.extractItem(item, null, onResult)
extractItem (IO):
  parse(item.name) ?: return (snap: nunca pasa — el menú filtra)
  destino = dir/<target.name> (Folder → mkdir; Single → fichero)
  SI destino.exists() -> onResult(Collision)        (aviso, nada tocado, RNF-5)
  SI Folder && !destination.mkdir() -> onResult(Failed)
  outcome = ArchiveExtractor.extract(item, destino, format, password)
  onResult(outcome ?? Ok)            (PasswordRequiredException del 7z sin →
                                       PasswordRequired para pedir el diálogo, RF-8)
Pantalla (onResult):
  Ok      -> ocupación/snack ya lo pintan VM (notifyExtractDone)
  Collision/Failed/UnsafeEntry -> notificación del VM con nombre/entrada
  PasswordRequired -> dialogFile = item; dialog = EXTRACT_PASSWORD
diálogo EXTRACT_PASSWORD: campo oculto + aceptar/cancelar
  aceptar -> viewModel.extractItem(item, password, onResult)   (un solo reintento)
```

### RF-7: zip-slip y cierre de extracción
```
entrada segura: !blank && !startsWith('/') && !contains("..") && !contains('\\')
CUALQUIER entrada insegura -> aborta todo, elimina destino, onResult(UnsafeEntry)
Ningún formato deja carpeta a medias: try/catch ancho con deleteDir(destination).
```

### RF-2..RF-4: ejecutar (app)
```
runFileInTerminal(hostPath):
  service/MAX sesiones idénticos a openFileInEditor (límite de sesión, aviso)
  rootfs instalado && hostPath mapeable && File(hostPath).isFile  si no: avisos vigentes
  interpreters = {"bash" si usr/bin/bash canExecute, "python3" si usr/bin/python3}
  plan = RunPolicy.decide(file.name, file.canExecute(), interpreters)
  NotExecutable -> AlertDialog msg_file_not_executable (nada de sesión, RF-3)
  else ->
    guestPath   = map(hostPath); guestDir = map(parent)
    script      = "cd '$guestDir'; ${plan.command}; exec bash"
    session     = service.createTermuxSession(null, ["-c", script], null, parent, ...)
    addSession(session, "run: ${file.name}")
```

## Interfaz (firmas relevantes)

- `FileManagerScreen(…, onRunInTerminal: ((File) -> Unit)? = null)`; `ItemMoreMenu`
  añade `onRunInTerminal: (() -> Unit)?` y `canExtract: Boolean` + `onExtract: () -> Unit`.
- `FileManagerSessionHost(…, onRunInTerminal: ((File) -> Unit)? )` (opcional, `null` defecto).
- `ViewModel`: `fun extractItem(file: File, password: CharArray?, onResult: (ExtractionOutcome) -> Unit)`;
  notificaciones `notifyExtractDone/…`.
- `ExtractPolicy.parse(name): Pair<ExtractFormat, ExtractionTarget>?`,
  `RunPolicy.decide(name, canExecute, interpretersAvailable): RunPlan`,
  `ArchiveExtractor.extract(archive, destination, format, password?): ExtractOutcome?`.

## Decisiones (con alternativa descartada)

1. **La extracción vive en el módulo (VM + `ArchiveExtractor`), sin callback.**
   Mismo argumento que la compresión (decisión 2 de la 009): la app queda a
   cero de cableado para este RF. Descartado subirlo tras `onExtract` de la app.
2. **Destino folder-nueva con rechazo de colisión** (opción preferida del
   mantenedor). Descartado extracción suelta en la carpeta abierta: colisiones
   y limpieza a mitad de tirada; y el diálogo de destino: aparato para lo que
   la política determinista resuelve.
3. **`rar` fuera** (common compresse no lo desempaqueta). Descartado junrar:
   dependencia nueva con licencia y mantenibilidad dudosas (RNF-1).
4. **RunPolicy decide solo con (nombre, canExecute, intérpretes)**: los bits
   del host y los binarios del rootfs los sondea la app (única que puede);
   la política queda pura y reproducible en test. Descartado resolver el
   intérprete dentro del extractor/servicio de la app: lógica escondida sin test.
5. **`cd` al directorio del fichero dentro del script de ejecución** (además del
   workingDirectory del servicio): hace `./<nombre>` correcto aunque el mapeo de
   cwd de la sesión cambie. Descartado confiar solo en el workingDirectory.
6. **Un solo reintento con contraseña (RF-8)**: sobra para el caso humano;
   descartado el bucle pregunta-falla-pregunta (persistence molesto, camino a
   estado raro).
7. **Ejecutar/Extraer sobre el enlace sin resolver** (009 RF-13 por defecto):
   mismo enfoque de las demás acciones del menú; descartado las excepciones
   nuevas de resolución (más superficies, un solo caso ganable).
8. **`DialogKind.EXTRACT_PASSWORD` propio** (no se reutiliza COMPRESS): su
   aceptar dispara una extracción, no una compresión; estado separado, diálogo
   pequeño.

## Estrategia de tests (`./gradlew test`)

- `ExtractPolicyTest.kt`: zip/tar/tar.gz/tgz/tar.bz2/tar.xz/7z → Folder(con
  base correcta, incl. doble extensión); gz/bz2/xz único → Single (incl
  «notes.txt.gz»); mayúsculas; sin base (`.gz`) y sin extensión → null;
  carpetas: la UI decide con `!isFolder` (fuera del policy).
- `RunPolicyTest.kt`: ejecutable → Direct `./name`; `.sh`/`.bash` sin bit →
  Interpreter bash; `.py` sin bit → Interpreter python3 (si está); `.py` con
  python3 ausente y `.txt` → NotExecutable; ejecutable con bit y con
  interpretes → Direct (gana el bit); nombre con comillas escapado.
- `ArchiveExtractorTest.kt` (JUnit, tmp-dir): crea archivitos con
  `ArchiveCreator`/streams de commons y verifica la extracción de zip (niveles
  anidados), tar.xz, 7z sin contraseña; 7z con contraseña (correcta ok /
  incorrecta Failed / sin contraseña → PasswordRequired); gz suelto → fichero
  destino exacto; zip-slip («../evil.txt») → UnsafeEntry y destino eliminado;
  destino con entrada duplicada no bloquea (sobreescritura dentro del trazo).
- Compose/ViewModel sin test (coherente con el repo, no Robolectric en CI);
  los flujos IO/UI se validan por los criterios de finalización en dispositivo.
- Delta del catálogo: ninguno (RNF-1).

## Strings nuevos (los tres locales, EN base)

`action_run_in_terminal` ("Run in terminal"), `action_extract` ("Extract"),
`extract_password_title` ("Extract %1$s"), `msg_extract_done` ("Extracted
%1$s"), `msg_extract_exists` ("%1$s already exists"), `msg_extract_failed`
("Failed to extract %1$s"), `msg_extract_unsafe_entry` ("Aborted: unsafe
archive entry %1$s"), `msg_file_not_executable` ("File is not executable and
no supported interpreter is available in the rootfs"). Reutilizar
`debian_installer_title`, `filemanager_unmappable_ path`, el título de
máx. sesiones y `compress_password_label` (etiqueta de campo) donde ya existan.

## Riesgo notado

FUSE/storage: `File.canExecute()` del host puede ser fiel o no. Con FUSE la
política deriva al intérprete (`bash <nombre>`), que es exactamente el caso
que interesa para los scripts descargados. Probado en dispositivo con los
criterios de finalización.
