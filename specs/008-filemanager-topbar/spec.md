# Spec 008 — Gestor de archivos: topbar, breadcrumb, organizar, menú More y cajón de marcadores

Estado: **cerrada** (2026-10-20; implementada y validada en dispositivo)

## Contexto y objetivo

El gestor de archivos (`filemanager`, `FileManagerScreen`) muestra hoy el trabajo
histórico en la topbar: título = nombre de carpeta con la ruta absoluta debajo, y una
fila propia con botones de navegación (back, forward, up, terminal). El objetivo es
reorganizar la parte superior para que:

- El título sea la carpeta actual y, debajo, el recuento de carpetas y archivos de la
  carpeta abierta; la ruta absoluta desaparece de la topbar.
- La ruta se muestre como un «tren de carpetas» (breadcrumb) bajo la topbar: el
  usuario salta a cualquier ancestro tocando su segmento.
- La ordenación, el modo de vista («lista»/«cuadrícula») y las preferencias
  «carpeta primero» / «solo para esta carpeta» estén juntos en un menú de acciones
  nuevo («Organizar»).
- El menú More amplíe las acciones de navegación y utilidades.
- El botón atrás de la topbar se sustituya por un botón de menú que abre un cajón
  (drawer) con los marcadores, donde viven los favoritos del usuario.

## Usuarios

El usuario del gestor de archivos como pestaña de sesión en la app consola (o en la
actividad legacy `FileManagerComposeActivity`): navega carpetas con táctil o con
teclas extra y quiere moverse rápido entre carpetas conocidas.

## Historias de usuario

- HU-1. Como usuario, quiero ver el nombre de la carpeta (no su ruta absoluta) y el
  recuento de carpetas y archivos justo debajo del título, para saber dónde estoy y
  cuánto hay de un vistazo.
- HU-2. Como usuario, quiero la ruta como tren de segmentos (Home > sdcard > Sub),
  tocables, para saltar a un ancestro sin volver carpeta por carpeta.
- HU-3. Como usuario, quiero un menú «Organizar» con vista (lista/cuadrícula), orden
  (nombre, tipo, tamaño, última modificación), ascendente, carpeta primero y «solo
  para esta carpeta», para acomodar el listado sin recorrer varios diálogos.
- HU-4. Como usuario, quiero un menú More completo (nuevo tab, subir, ir a,
  actualizar, seleccionar todo, ocultos, compartir, copiar ruta, abrir en terminal,
  favoritos, acceso directo) para tener todas las acciones de navegación y
  utilidades en un mismo sitio.
- HU-5. Como usuario, quiero un cajón de marcadores en lugar del botón atrás, con
  los marcadores fijos y los favoritos que yo añada, para ir a carpetas importantes
  desde cualquier punto del árbol.

## Definiciones

- **Breadcrumb (tren de carpetas).** Ruta en un renglón, clicable por segmento:
  la carpeta raíz con nombre conocido («Home» para `DEBIAN_GUEST_HOME_DIR_PATH`,
  «Root» para `/`) y para el resto el nombre del segmento; el último segmento es la
  carpeta abierta y es solo informativo.
- **Favorito.** Carpeta añadida por el usuario a la lista de marcadores (persistente);
  distinta de los marcadores fijos (Home, SDCard, Root) que no se pueden quitar.
- **Modo de vista.** Lista (filas actuales) o cuadrícula (mosaicos de icono + nombre).
- **Solo para esta carpeta.** Cuando está activo, los cambios de Organizar (vista y
  orden) se guardan por carpeta y no tocan los valores globales; entrar en otra
  carpeta no hereda lo que se cambió solo para la recién abandonada.

## Requisitos funcionales

- RF-1: MIENTRAS el usuario navega sin selección, EL SISTEMA muestra en la topbar
  como título el texto de `FileManagerUiState.title` (nombre amable de la carpeta:
  Home, Root o nombre del segmento) y como subtítulo «N carpetas · M archivos» de
  las entradas visibles de la carpeta (sin ocultos si `showHidden` es falso; la
  búsqueda no lo cambia); la ruta absoluta ya no se dibuja en la topbar y el
  recuento vale para ambas vistas (lista y cuadrícula).
- RF-2: CUANDO cambia `currentPath`, EL SISTEMA refresca el breadcrumb bajo la
  topbar con un segmento por ancestro: nombre conocido («Home», «Root») y nombre de
  segmento para el resto, terminando en la carpeta abierta; los segmentos anteriores
  navegan a esa carpeta (con la comprobación de acceso al almacenamiento cuando
  toca) y el último solo se pinta (no navega).
- RF-3: MIENTRAS el breadcrumb no cabe en pantalla, EL SISTEMA muestra por defecto
  la parte final de la ruta (cola pegada al borde) y permite deslizar para verla
  entera; un segmento que no cabe se corta con puntos suspensivos.
- RF-4: EL SISTEMA sustituye el icono atrás de la topbar por un icono de menú que
  abre el cajón de marcadores; back/forward pasan al renglón del breadcrumb como
  iconos a la izquierda del tren de carpetas, y «subir» + «abrir en terminal» se
  mudaron al menú More (la fila de navegación anterior desaparece).
- RF-5: EL SISTEMA muestra en el cajón los marcadores fijos (Home, SDCard, Root) y
  los favoritos del usuario con su nombre amable y ruta; abrir uno navega a la
  carpeta (con la comprobación de acceso cuando toca). El cajón sustituye al diálogo
  de marcadores; cada favorito tiene una acción para quitarlo (los fijos no).
- RF-6: CUANDO el usuario pulsa «Añadir a favoritos» del menú More, EL SISTEMA
  guarda la carpeta abierta en favoritos de forma persistente y aparece en el cajón;
  SI la carpeta abierta ya está en favoritos, ENTONCES el elemento pasa a «Quitar de
  favoritos» y al pulsarlo la retira.
- RF-7: EL SISTEMA ofrece en la zona de acciones de la topbar un icono «Organizar»
  que abre un menú con: vista (Lista, Cuadrícula — solo una activa), orden (Nombre,
  Tipo, Tamaño, Última modificación — solo uno activo), Ascendente (conmutador),
  Carpeta primero (conmutador) y una sección aparte «Solo para esta carpeta»
  (conmutador); el estado activo se refleja al abrir.
- RF-8: CUANDO el usuario cambia vista u orden en el menú Organizar, EL SISTEMA
  reordena el listado en memoria (sin releer el disco si la caché sigue siendo
  válida) y guarda el ajuste: global si «Solo para esta carpeta» está inactivo y por
  ruta de la carpeta abierta si está activo; cada carpeta abre con su ajuste propio
  si lo tiene, o el global si no.
- RF-9: EL SISTEMA muestra el menú More con los elementos, en este orden: Nuevo tab,
  Subir, Ir a…, Actualizar, Seleccionar todo, Mostrar/No mostrar archivos ocultos,
  Compartir carpeta, Copiar ruta, Abrir en terminal, Añadir/Quitar de favoritos y
  Crear acceso directo; y conserva los elementos condicionales actuales (Pegar
  cuando hay portapapeles).
- RF-10: CUANDO el usuario pulsa «Nuevo tab», EL SISTEMA abre una pestaña nueva de
  gestor (sesión FileManager nueva y activada) no abierta; la pestaña actual no se
  cierra.
- RF-11: CUANDO el usuario pulsa «Subir», EL SISTEMA navega a la carpeta padre de la
  carpeta abierta si existe (apilando la actual en el histórico atrás y limpiando el
  de adelante); SI no hay padre (raíz), ENTONCES queda desactivado.
- RF-12: CUANDO el usuario pulsa «Ir a…», EL SISTEMA abre un diálogo con un campo de
  ruta y al confirmar con una ruta existente navega a ella (relativa se ancla a la
  carpeta abierta, absoluta se usa tal cual); una ruta inexistente o ilegible suelta
  el aviso actual de carpeta no legible y no navega.
- RF-13: CUANDO el usuario pulsa «Actualizar», EL SISTEMA relee el listado del
  directorio completo (relectura de disco, no solo el re-filtrado).
- RF-14: CUANDO el usuario pulsa «Compartir carpeta», EL SISTEMA abre el selector
  de Android con la carpeta resuelta como URI de FileProvider y la ruta como texto
  de respaldo (`EXTRA_TEXT`); el destino recibe lo que soporte.
- RF-15: CUANDO el usuario pulsa «Copiar ruta», EL SISTEMA copia la ruta absoluta de
  la carpeta abierta al portapapeles y avisa con la snack bar actual («Path copied
  to clipboard»).
- RF-16: CUANDO el usuario pulsa «Crear acceso directo», SI el launcher del
  dispositivo admite atajos fijados, EL SISTEMA pide fijar un atajo con el nombre
  amable de la carpeta e icono de carpeta que abre la app directamente en una
  pestaña de gestor navegando a esa carpeta (el intent crea una sesión FileManager
  nueva con la ruta como destino inicial); SI el launcher no lo admite, ENTONCES el
  sistema avisa con la snack bar y no crea nada.
- RF-17: SI el usuario activa la vista Cuadrícula, ENTONCES EL SISTEMA pinta
  mosaicos (misma detección de icono por familia/variante, nombre con puntos
  suspensivos) de la misma lista ordenada, manteniendo tap largo para selección
  multiselección, abrir carpteta/archivo y detalles; el cursor de teclado sigue
  funcionando por índice.
- RF-18: AL terminar la carga de una carpeta, EL SISTEMA recalcula «N carpetas · M
  archivos» desde el listado visible (carpetas primero; archivos después).

## Requisitos no funcionales

- RNF-1: Cero dependencias nuevas: el atajo usa `ShortcutManagerCompat` de
  `androidx.core` (ya en el catálogo) y el cajón es Material 3 (ya en el BOM).
- RNF-2: Todo texto nuevo visible va a `strings.xml` en EN, `values-es-rVE` y
  `values-pt-rBR`; nada literal en Compose (constitución 6).
- RNF-3: La lógica nueva (segmentos del breadcrumb, recuento por tipo, comparador
  con «carpeta primero») vive en funciones puras con test JUnit que corre sin
  Android (constitución 3 y 4).
- RNF-4: La lista, el cursor y la selección no retroceden respecto a la spec 007:
  al cambiar de carpeta el scroll vuelve arriba y el cursor se resetea; en
  cuadrícula el desplazamiento mínimo aplica sobre celdas (misma política, geometría
  de cuadrícula).
- RNF-5: El módulo `filemanager` sigue sin ser dueño de la app: el «nuevo tab» y el
  atajo del launcher son callbacks/intents que la app cablea en el host
  (`FileManagerSessionHost` → `TermuxComposeActivity`), sin crear sesiones dentro de
  la biblioteca.

## Casos límite

- Carpeta `/` (Root): breadcrumb de un solo segmento («Root»), «Subir» desactivado
  (no hay padre).
- Favorito muerto (carpeta borrada): abrirlo desde el cajón suelta el aviso actual
  de carpeta no legible; se puede quitar con su acción del cajón.
- Carpeta padre ilegible inaccesible: `goUp()` actual ya devuelve `false` — «Subir»
  refleja el comportamiento, desactivado si no puede.
- Ruta de almacenamiento compartido (`/sdcard`, `/storage/emulated/0`): no se
  canonicaliza (ya lo salta el ViewModel) y el breadcrumb usa su último segmento
  como nombre de mostrar.
- Modo Cuadrícula con selección activa: cada mosaico marca su caja de selección y
  la barra inferior de selección sigue funcionando.
- Diálogo «Ir a…» con campo vacío: confirmar deshabilitado.
- Atajo fijo de una carpeta luego borrada: la app abre el gestor en su carpeta por
  defecto, sin error.

## Fuera de alcance

- Visibilidad por carpeta de «mostrar archivos ocultos» (queda global).
- Subir ficheros a un servidor (no hay backend; «Subir» es subir de carpeta).
- Reordenar pestañas del gestor, temas, avatar: fuera del scope.
- Dialogos históricos de orden y marcadores: se retiran al quedar sustituidos.

## Criterios de finalización

- `./gradlew test` verde (incluye los tests nuevos del breadcrumb, del recuento y
  del comparador con «carpeta primero»).
- En dispositivo: título/subtítulo, breadcrumb clicable, cajón con fijos +
  favoritos (añadir/quitar persistente), menú Organizar completo (cambios
  persistentes, «solo esta carpeta» independiente), menú More completo (nuevo tab
  abre pestaña, ir a navega, actualizar, compartir, copiar ruta, terminal, acceso
  directo fija un icono en el launcher que abre la app en esa carpeta), cuadrícula
  con selección y cursor.
- Sin texto literal en Compose: `grep -rnP '(Text|contentDescription)\(\s*"'`
  sigue sin devolver literales.

## Dudas abiertas

- (Resueltas en clarificación) «Subir» = ir a la carpeta padre; «Crear acceso
  directo» = atajo fijo en el launcher; «Compartir» = la carpeta completa (URI de
  carpeta + texto de respaldo); la fila bajo la topbar desaparece y queda el tren
  de carpetas con back/forward a la izquierda.
- (Abierta, no bloquea el plan) RF-9 conserva «Pegar» (condicional) al final de
  More: sin él, copiar/cortar no tiene destino. Si se quiere fuera, se retira aquí
  antes de implementar.
