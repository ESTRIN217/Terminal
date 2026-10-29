# Spec 023 — Gestor de archivos: Ejecutar en la terminal y Descomprimir

Estado: **implementada** (2026-10-29; T1–T8, `./gradlew test` verde completo;
verificación en dispositivo pendiente del mantenedor. Clarificación previa
aprobada por el mantenedor: ejecutar = lanzar el archivo en sesión del
rootfs; destino de extracción = carpeta nueva `<base>/`; formatos = los de
commons-compress, sin rar)

## Contexto y objetivo

El menú More por ítem (spec 009) actúa sobre un archivo o carpeta concreta, pero

- no hay forma de **ejecutar** un archivo del gestor: «Abrir con › Rootfs» siempre
  lo abre con un editor (nano/vim), obligando a teclear el comando a mano para
  lanzar un script o un binario;
- el gestor ya **comprime** (RF-7 de la 009: `.zip`, `.tar.xz`, `.7z`) pero no
  **extrae**: los comprimidos que llegan al dispositivo se quedan sin mirar.

El objetivo es añadir ambas acciones al menú por ítem, con la misma disciplina
que la 009: lógica pura testeada, destino jamás sobrescrito, callbacks
opcionales para lo que vive en la app.

## Usuarios

El usuario del gestor de archivos como pestaña de sesión: toca el «More» de un
archivo para ejecutarlo o extraerlo sin salir del gestor.

## Historias de usuario

- HU-1. Como usuario, quiero «Ejecutar en la terminal» sobre un archivo para
  lanzarlo en una sesión del rootfs (Debian) sin teclear su ruta.
- HU-2. Como usuario, quiero «Descomprimir» sobre los comprimidos soportados
  para extraer su contenido en una carpeta nueva de la carpeta abierta, sin
  pasar por la terminal.
- HU-3. Como usuario, quiero que se me pida la contraseña cuando un `.7z`
  cifrado la necesite, y un aviso claro si es incorrecta.

## Definiciones

- **Formato extraíble.** Contenedor multientrada (`zip`, `tar`, `tar.gz`,
  `tgz`, `tar.bz2`, `tar.xz`, `7z`) o compresor de un solo fichero (`gz`,
  `bz2`, `xz`), decidable por la extensión del nombre (insensible a
  mayúsculas). `rar` NO es extraíble (commons-compress no desempaqueta RAR).
- **Destino de la extracción.** Para contenedores: carpeta nueva
  `<base>/` en la carpeta abierta, donde `<base>` es el nombre sin las
  extensiones del formato (`app.tar.gz` → `app/`; `docs.zip` → `docs/`;
  `backup.tgz` → `backup/`). Para un compresor suelto: fichero `<base>`
  (`contacto.txt.gz` → `contacto.txt`; `data.gz` → `data`).
- **Ejecutar sesión.** Nueva pestaña de terminal del rootfs (precedente
  `openFileInEditor` de la 009): `bash --login -c "<comando>; exec bash"` con
  `cd` a la carpeta del archivo; al terminar el archivo, la pestaña queda como
  shell interactiva.
- **Comando de ejecución.** Archivo ejecutable → `./<nombre>`; no ejecutable
  pero script de texto → intérprete por extensión: `.sh`/`.bash` → `bash`,
  `.py` → `python3` (si el binario existe en el rootfs); en otro caso no se
  ejecuta nada (aviso). Nada hace `chmod` por su cuenta.
- **Entrada insegura (zip-slip).** Nombre de entrada dentro del comprimido que
  escaparía del destino: vacío, ruta absoluta (`/…`), que sube de carpeta
  (`..`) o separador de Windows (`\`). La detección es sobre el nombre crudo.
- **Enlace simbólico.** Precedente de la 009 RF-13: «Ejecutar» y
  «Descomprimir» actúan sobre el enlace sin resolver (solo «Abrir con…»
  resuelve el destino).

## Requisitos funcionales

- RF-1: EL SISTEMA añade al menú More de un **archivo** la entrada «Ejecutar en
  la terminal» inmediatamente después del submenú «Abrir con…», deshabilitada
  MIENTRAS el gestor esté ocupado; SI el host no cablea el callback de
  ejecución, ENTONCES el elemento se oculta (precedente de la 008/009).
- RF-2: CUANDO el usuario pulsa «Ejecutar en la terminal», EL SISTEMA valida
  precondiciones en este orden (aviso y nada de sesión si falla alguna):
  número máximo de sesiones, rootfs Debian instalado, ruta mapeable
  host→guest y que el ítem sea un archivo; después crea la sesión de la
  definición «Ejecutar sesión» con el comando de la definición «Comando de
  ejecución» y nombra la pestaña `run: <nombre-del-archivo>`.
- RF-3: CUANDO el archivo no es ejecutable y no hay intérprete para su
  extensión (RF-4), EL SISTEMA muestra el aviso «archivo no ejecutable
  (sin intérprete)» y no abre ninguna sesión ni lo hace ejecutable.
- RF-4: CUANDO el archivo no es ejecutable, EL SISTEMA ejecuta `bash <nombre>`
  para `.sh`/`.bash` y `python3 <nombre>` para `.py` cuando el binario
  correspondiente existe en el rootfs (`usr/bin`); SI no existe, ENTONCES el
  aviso de RF-3.
- RF-5: EL SISTEMA añade al menú More la entrada «Descomprimir» justo después
  de «Comprimir…», visible SOLO para archivos cuyo formato es extraíble
  (carpetas y enlaces a carpetas nunca la muestran) y deshabilitada MIENTRAS
  el gestor esté ocupado.
- RF-6: CUANDO el usuario pulsa «Descomprimir», EL SISTEMA valida en la
  carpeta abierta el plan puro (destino = definición «Destino de la
  extracción»): SI el destino ya existe (carpeta o fichero según el formato),
  ENTONCES avisa de colisión y no toca nada; SI no existe, ENTONCES extrae con
  el indicador de ocupación del gestor, recarga el listado al acabar y avisa
  con la snack bar el resultado.
- RF-7: EL SISTEMA extrae las entradas de contenedores recreando carpetas y
  ficheros con su ruta relativa dentro del destino;
  SI una entrada es insegura (definición «zip-slip»), ENTONCES EL SISTEMA
  aborta la extracción completa, avisa citando la entrada y elimina el destino
  parcialmente escrito (RNF-5).
- RF-8: CUANDO un `.7z` cifrado se extrae sin contraseña, EL SISTEMA pregunta
  la contraseña con un diálogo (campo de texto oculto) y reintenta una única
  vez con ella; SI la contraseña es incorrecta, ENTONCES avisa el fallo sin
  reabrir el diálogo en bucle.
- RF-9: PARA enlaces simbólicos, EL SISTEMA aplica la regla vigente de la 009:
  «Ejecutar» y «Descomprimir» actúan sobre el enlace sin resolver; el fallo de
  un enlace roto o ilegible es el aviso genérico de esa acción.

## Requisitos no funcionales

- RNF-1: Cero dependencias nuevas: la extracción usa la API ya alta en
  `filemanager` (`commons-compress` + `org.tukaani:xz`). Alternativa
  descartada: junrar u otra librería para `rar` (nueva dependencia externa
  solo por un formato).
- RNF-2: Todo texto nuevo visible va a `strings.xml` (EN, `values-es-rVE`,
  `values-pt-rBR`); nada literal en Compose (constitución 6).
- RNF-3: La lógica nueva es funciones puras con test JUnit sin Android
  (constitución 3): plan de extracción (formato y destino a partir del
  nombre), comando de ejecución (nombre + bits + intérpretes disponibles) y
  el extractor en disco (bindings probados contra archivos reales pequeños
  creados en ficheros temporales del test).
- RNF-4: Nada bloquea el hilo de UI: la extracción corre en IO como el resto
  de operaciones del ViewModel (ocupación + snack bar).
- RNF-5: Datos del usuario sagrados (constitución 5): descomprimir jamás
  sobrescribe un destino existente; si la extracción falla a mitad, el destino
  recién creado se elimina y queda el listado como estaba; nunca se borra
  nada preexistente.
- RNF-6: El módulo sigue sin ser dueño de la app (RNF-6 de la 009): ejecutar
  es callback opcional que la app cablea; sin cableado el elemento se oculta.
  La extracción vive íntegra en el módulo como la compresión (cero cableado).

## Casos límite

- Menú abierto con el gestor ocupado: «Ejecutar» y «Descomprimir» deshabilitadas.
- Enlace simbólico roto: «Ejecutar»/«Descomprimir» avisan con el fallo
  genérico de cada acción; sin resoluciones nuevas.
- Carpeta con nombre de comprimido (`photos.zip` como directorio): «Descomprimir»
  no aparece; «Comprimir…» sigue disponible (comprime la carpeta).
- `app.tar.gz` con `app/` ya existente: aviso de colisión, ninguna lectura del
  archivo comprimido.
- Nombre sin base (`.gz`): formato no reconocido, «Descomprimir» oculto.
- Nombre en mayúsculas (`FOTO.ZIP`, `WebSite.TAR.Gz`): extraíbles por
  comparación insensible al caso.
- `.7z` con contraseña errónea: aviso de fallo (RF-8), el destino parcial
  se elimina.
- Archivo con comillas en el nombre (`mi'guión.sh`): el comando queda escapado
  (política de shell probada en el test de la RunPolicy).
- `MAX_SESSIONS` alcanzado: aviso (precedente del editor).
- Rootfs no instalado: aviso sin abrir sesión (precedente del editor).
- Intérprete `python3` ausente del rootfs: `.py` cae al aviso de RF-3.

## Fuera de alcance

- Extraer `rar`, `zstd`, `iso`, `cab`, `ar`, `cpio`, `deb` o cualquier formato
  no listado.
- `.zip` cifrado (commons-compress lo descifra pero no ofrece una forma fiable
  de detectar «hace falta contraseña» antes de fallar; se queda fuera).
- Conservar permisos/fechas/uid de los ficheros extraídos.
- Ejecutar carpetas («Abrir terminal aquí» ya lo cubre), multi-selección para
  ambas acciones, argumentos al ejecutar, ejecutar como root.
- «Ejecutar» en la cuadrícula (el mosaico sigue su flujo de la 009):
  ambas entradas solo en la fila de lista, como el resto del menú.

## Criterios de finalización

- `./gradlew test` en verde con los tests nuevos: ExtractPolicy (formato,
  destino, mayúsculas, sin base), RunPolicy (directa, `bash`, `python3`,
  no ejecutable, escapado de comillas) y ArchiveExtractor (zip, tar.xz, 7z con
  contraseña, gz suelto, zip-slip aborta y limpia).
- En dispositivo: un `.sh` sin bit ejecutable corre en pestaña nueva y queda
  shell interactiva; un binario ejecutable corre; extraer un `.zip`/`.tar.xz`
  crea la carpeta `<base>/` y refresca; colisión avisa y no toca nada; un
  `.7z` cifrado pide contraseña (correcta extrae, errónea avisa); un `.gz`
  suelto extrae el fichero.
- Sin texto literal en Compose: `grep -rnP '(Text|contentDescription)\(\s*"'`
  sigue sin devolver literales.

## Dudas abiertas

- (Resueltas en clarificación previa, 2026-10-29) «Ejecutar» = lanzar el
  archivo (no solo abrir terminal en su carpeta); destino = carpeta nueva
  `<base>/` con rechazo de colisión; formatos = todos los de commons-compress
  (sin rar). Sin chmod silencioso.
