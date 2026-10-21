# Spec 009 — Gestor de archivos: menú More por ítem y diálogo de propiedades

Estado: **cerrada** (2026-10-21; T1–T10 implementadas, `./gradlew test`
completo verde — 638 tests, 0 fallos; literal grep limpio; locales del
módulo completos tras el cierre de 28 claves ausentes en pt-BR)

## Contexto y objetivo

En `FileManagerScreen` cada ítem del listado (fila de lista o mosaico de cuadrícula)
tiene un icono «Info» que abre el diálogo de detalles; ese diálogo mezcla datos
(propiedades) con acciones (abrir, copiar ruta, renombrar, editar). El objetivo es:

- Sustituir el icono de la fila (ítem de lista) por un icono «More» que abre un menú
  propio del ítem con todas sus acciones en un mismo sitio.
- Dejar el diálogo de detalles como diálogo de **propiedades** puro (solo datos,
  dos pestañas y un botón de aceptar): las acciones que tenía pasan al menú.

## Usuarios

El usuario del gestor de archivos como pestaña de sesión (o en
`FileManagerComposeActivity`): toca el «More» de un ítem para actuar sobre ese
archivo o carpeta concreta sin entrar en ella ni seleccionarla.

## Historias de usuario

- HU-1. Como usuario, quiero un menú por ítem («More») con abrir con, cortar,
  copiar, pegar, eliminar, renombrar, comprimir, compartir, copiar ruta, favoritos,
  acceso directo y propiedades, para actuar sobre un archivo concreto de un toque.
- HU-2. Como usuario, quiero elegir al abrir un archivo entre una app Android
  («Sistema») o un editor del rootfs («Rootfs»), para editar texto con nano/vim sin
  salir del gestor.
- HU-3. Como usuario, quiero comprimir un archivo o carpeta eligiendo nombre
  (`.zip`/`.tar.xz`/`.7z`) y contraseña opcional, para empaquetar sin terminar en
  una terminal.
- HU-4. Como usuario, quiero un diálogo de propiedades con dos pestañas (básico y
  permisos), para consultar datos técnicos del ítem sin acciones accidentales.

## Definiciones

- **More por ítem.** Menú contextual de un único ítem del listado; actúa solo sobre
  ese archivo o carpeta, independiente de la selección múltiple y de la carpeta
  abierta.
- **Abrir con Sistema.** Abrir el archivo con la app Android correspondiente (el
  flujo «open» actual del gestor).
- **Abrir con Rootfs.** Abrir el archivo en una sesión de terminal del rootfs
  Debian con un editor disponible: nano si existe, y si no vim. Si el rootfs aún no
  está instalado o no hay ninguno de los dos, aviso y no se abre nada.
- **Contenido (pestaña básico).** Tamaño formateado para archivos; para carpetas,
  el recuento «N carpetas · M archivos» de sus entradas visibles.
- **Disponibilidad del editor de rootfs.** Comprobación referencial: nano si existe
  `usr/bin/nano` en el directorio rootfs instalado (estado vigente de
  `DebianInstaller`), si no vim con `usr/bin/vim`; si el rootfs no está completo,
  ambos se consideran indisponibles.
- **Datos de permisos.** Se leen con los helpers vigentes de `termux-shared`:
  `FileAttributes` vía `lstat` (propietario/grupo con nombre si `UserUtils` lo
  resuelve, uid/gid numérico si no, y modo), `FilePermissions` para el modo
  simbólico y `SELinuxUtils.getFileContext()` para el contexto de SELinux. No se
  añade infraestructura de lectura nueva.
- **Compresión con contraseña.** Solo el formato `.7z` soporta cifrado en esta
  implementación (AES-256 de commons-compress); `.zip` y `.tar.xz` se crean sin
  contraseña.
- **Favorito.** Carpeta del cajón de marcadores (spec 008); los archivos no pueden
  ser favoritos.

## Requisitos funcionales

- RF-1: EL SISTEMA sustituye el icono «Info» de cada fila de lista por un icono
  «More» que abre el menú del ítem con los elementos, en este orden: Abrir con…,
  Cortar, Copiar, Pegar (condicional, RF-4), Eliminar, Renombrar, Comprimir…,
  Compartir, Copiar ruta, Añadir a favoritos (o Quitar de favoritos), Crear acceso
  directo y Propiedades. Mientras el gestor esté ocupado, EL SISTEMA deja
  habilitados Cortar, Copiar, Compartir, Copiar ruta y Propiedades, y deshabilita
  Pegar, Eliminar, Renombrar y Comprimir (precedente `enabled = !busy` del menú
  More de la topbar).
- RF-2: EL SISTEMA muestra «Abrir con…» solo para archivos, como submenú con dos
  opciones: «Sistema» (abre el archivo con la app Android, flujo actual) y
  «Rootfs» (abre una sesión de terminal del rootfs con el primer editor disponible
  nano→vim sobre ese archivo); SI no hay editor disponible o el rootfs no está
  listo, ENTONCES el sistema avisa con la snack bar y no abre nada. SI el host no
  cablea «Rootfs», ENTONCES el elemento se oculta (precedente de la 008).
- RF-3: CUANDO el usuario pulsa «Cortar» o «Copiar» del menú del ítem, EL SISTEMA
  pone ese archivo (solo ese) en el portapapeles del gestor con el modo
  correspondiente (cortar/copiar), como ya hace la selección múltiple, sin alterar
  la selección vigente ni el modo selección.
- RF-4: CUANDO hay portapapeles, EL SISTEMA muestra «Pegar» en el menú del ítem y
  al pulsarlo pega en la carpeta abierta con la semántica actual (cortar mueve,
  copiar duplica) y el aviso con el recuento pegado; SI la operación falla (ninguna
  fuente existe o ninguna se completa), ENTONCES el sistema muestra el aviso de
  error de pegado (arreglo aprobado del defecto vigente que daba «N pegados» con
  N=0) y el portapapeles solo se limpia en el modo cortar si algo se movió.
- RF-5: CUANDO el usuario pulsa «Eliminar», EL SISTEMA abre el diálogo de
  confirmación de papelera actual para ese ítem y, al confirmar, lo mueve a la
  papelera con la snack bar de deshacer vigente.
- RF-6: CUANDO el usuario pulsa «Renombrar», EL SISTEMA abre el diálogo de nombre
  con el nombre actual del ítem y al confirmar aplica el renombrado vigente.
- RF-7: CUANDO el usuario pulsa «Comprimir…», EL SISTEMA abre un diálogo con:
  campo de nombre (por defecto el nombre del ítem; vacío deja el botón aceptar
  deshabilitado, como en los demás diálogos de nombre), tres opciones de formato
  `.zip`, `.tar.xz` y `.7z` (solo una activa) y un campo de contraseña opcional
  solo visible con `.7z`; al confirmar EL SISTEMA crea
  `<nombre>.<extensión-elegida>` en la carpeta abierta con el contenido del ítem
  (archivo o carpeta completa), con el indicador de ocupación del gestor, y avisa
  con la snack bar el resultado; SI el archivo de destino ya existe, ENTONCES el
  sistema avisa con error y no lo toca; SI el nombre no es válido (p. ej. contiene
  `/`), ENTONCES el sistema avisa con error y no crea nada.
- RF-8: CUANDO el usuario pulsa «Compartir», EL SISTEMA comparte ese ítem con el
  selector de Android (el flujo de compartir vigente).
- RF-9: CUANDO el usuario pulsa «Copiar ruta», EL SISTEMA copia la ruta absoluta
  del ítem al portapapeles con el aviso actual.
- RF-10: PARA carpetas, EL SISTEMA muestra «Añadir a favoritos» (o «Quitar de
  favoritos» si ya lo es) que añade/retira esa carpeta concreta del cajón de
  marcadores; PARA archivos, EL SISTEMA no muestra el elemento.
- RF-11: CUANDO el usuario pulsa «Crear acceso directo», SI el launcher lo admite,
  ENTONCES EL SISTEMA fija un atajo que abre la app así: carpeta → gestor
  navegando a ella (flujo actual); archivo → el archivo con la app Android
  («Sistema»); SI el archivo del atajo luego no existe, ENTONCES la app abre el
  gestor en su carpeta por defecto sin error (paridad con 008 RF-16); SI no lo
  admite, el aviso actual y nada. SI el host no cablea el atajo, ENTONCES el
  elemento se oculta (precedente de la 008).
- RF-12: CUANDO el usuario pulsa «Propiedades», EL SISTEMA abre el diálogo
  «Propiedades de <nombre>» con dos pestañas y un botón de aceptar (cierra):
  1. **Básico:** nombre, carpeta superior (el mismo mapeo amable que la topbar:
     Home/Root/segmento), tipo (MIME), contenido (tamaño formateado; en carpetas
     «N carpetas · M archivos» de sus entradas directas según el ajuste de ocultos
     vigente, sin filtro de búsqueda) y última modificación (fecha legible).
  2. **Permisos:** propietario y grupo (nombre si `UserUtils` lo resuelve; si no,
     uid/gid numérico), modo (simbólico `rwxr-xr-x` con octal) y contexto de
     SELinux via `SELinuxUtils`; en enlaces se lee del enlace (lstat, no del
     destino). Un dato no legible se muestra como «—» sin impedir abrir el
     diálogo.
- RF-13: EL SISTEMA aplica a los enlaces simbólicos: todas las acciones del menú
  y de permisos operan sobre el enlace (no resuelto), SALVO «Abrir con…» que
  opera sobre el destino resuelto (Sistema mantiene el flujo open actual, Rootfs
  abre el destino en el editor); el diálogo de propiedades muestra en básico su
  destino y estado (roto) además del resto de datos.

## Requisitos no funcionales

- RNF-1: Cero dependencias nuevas en el catálogo: la compresión usa
  `commons-compress` y su backend `xz` (`org.tukaani:xz`, ya en
  `libs.versions.toml`, antes solo en `app`), ambos de alta en el módulo
  `filemanager`. Alternativa descartada: zip4j (nueva dependencia externa solo
  por cifra `.zip`; el `.7z` con contraseña ya lo cubre commons-compress).
- RNF-2: Todo texto nuevo visible va a `strings.xml` (EN, `values-es-rVE`,
  `values-pt-rBR`); nada literal en Compose (constitución 6).
- RNF-3: La lógica nueva es funciones puras con test JUnit sin Android
  (constitución 3): elección del editor disponible (nano→vim sobre una lista de
  rutas), nombre de salida de la compresión y su política de colisión, y la
  conversión de modo POSIX a texto simbólico con octal. Los helpers de lectura
  (lstat/SELinux) se reutilizan de `termux-shared` sin duplicarlos.
- RNF-4: Nada que bloquee el hilo UI: la compresión corre como el resto de
  operaciones del ViewModel (ocupación, resultados por snack bar).
- RNF-5: Datos del usuario sagrados (constitución 5): comprimir jamás sobrescribe
  un destino existente; eliminar sigue pidiendo confirmación y mantiene el deshacer.
- RNF-6: El módulo `filemanager` sigue sin ser dueño de la app (RNF-5 de la 008):
  «Abrir con Rootfs» y el atajo que abre un archivo son callbacks opcionales que
  la app cablea en el host (`FileManagerSessionHost` → `TermuxComposeActivity`);
  sin cableado, el elemento del menú se oculta (precedente de la 008).

## Casos límite

- Ítem con menú abierto mientras el gestor está ocupado (recargando, comprimiendo):
  Pegar, Eliminar, Renombrar y Comprimir quedan deshabilitados (RF-1); Cortar,
  Copiar, Compartir, Copiar ruta y Propiedades siguen habilitados.
- Menú por ítem con selección múltiple activa: el menú sigue disponible y sus
  acciones actúan sobre ese ítem sin tocar la selección ni salir del modo
  selección (RF-3); la barra inferior de selección coexiste.
- Carpeta durante la compresión con contenido profundo/numeroso: la ocupación
  permanece activa hasta acabar; cancelar no está soportado (igual que pegar).
- Nombre de compresión que existe: aviso de colisión, sin sobreescritura.
- Rootfs no instalado: «Rootfs» avisa y no abre sesión.
- Archivo durante compresión modificado en disco: se comprime tal cual se lee, sin
  copia de seguridad.
- Enlace roto: el menú sigue funcionando (eliminar/renombrar/propiedades sobre el
  enlace); «Abrir con» actúa como hoy (aviso de enlace roto).
- Contexto SELinux o datos de permisos ilegibles: «—» en la fila correspondiente,
  el diálogo se abre igual.
- Pegar cuyo origen ya no existe (fuentes borradas tras copiar/cortar): aviso de
  error de pegado (RF-4, arreglo aprobado); el portapapeles de cortar solo se
  limpia si algo se movió.

## Fuera de alcance

- Menú More por ítem en la **cuadrícula** (el mosaico conserva su icono «Info»,
  que ahora abre el nuevo diálogo de propiedades solo con pestañas; esto enmienda
  el significado de la tap larga «detalles» de la 008 RF-17 para el mosaico, sin
  tocar la lista).
- Compresión multi-selección (solo un ítem por operación).
- Cifrar `.zip` con contraseña (requeriría zip4j; fuera).
- Editar permisos/propietario desde el diálogo (solo lectura).
- Otros formatos de compresión o niveles de compresión configurables.

## Criterios de finalización

- `./gradlew test` en verde, incluidos los tests nuevos (elección nano→vim según
  disponibilidad, nombre de compresión con política de colisión y modo POSIX a
  texto simbólico).
- En dispositivo: menú del ítem completo y en orden; abrir con Sistema/Rootfs;
  comprimir `.zip`, `.tar.xz` y `.7z` (con y sin contraseña) genera el archivo en
  la carpeta abierta; favoritos solo de carpetas; atajo de archivo abre el archivo;
  propiedades con dos pestañas y «—» donde no haya dato.
- Sin texto literal en Compose: `grep -rnP '(Text|contentDescription)\(\s*"'
  --include=*.kt app/src/main filemanager/src/main` sigue sin devolver literales.

## Dudas abiertas

- (Resueltas en clarificación) `.Zz` = `.7z`; contraseña solo donde el formato la
  soporta (solo `.7z`); editor de rootfs = «el disponible» (nano si está, si no
  vim); «Pegar» pega en la carpeta abierta; favoritos solo en carpetas; el atajo de
  un archivo abre el archivo; aprobado el arreglo del aviso de pegado fallido
  (RF-4) con su condición de limpieza del portapapeles al cortar.
- (Resueltas en revisión QA) Mecanismos fijados: lectura de permisos reutilizando
  `termux-shared` (`FileAttributes`/lstat, `UserUtils`, `FilePermissions` y
  `SELinuxUtils`); disponibilidad de editor referencial a los binarios del rootfs;
  backend `xz` del catálogo citado en RNF-1; «Pegar» marcado condicional en RF-1;
  estados busy por elemento; selección indestructible por el menú.
