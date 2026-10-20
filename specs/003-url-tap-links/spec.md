# Spec 003 — Links de texto plano clicables y subrayados

Estado: implementada (validada en dispositivo el 2026-10-20)

## Contexto y objetivo
Hoy las URLs impresas en la terminal solo se pueden abrir indirectamente: el menú More
las lista en un diálogo (tap = copiar, long-press = abrir). Los hipervínculos OSC 8 ya
se subrayan y abren con un tap, pero casi ninguna aplicación los emite: la inmensa
mayoría de los links que aparecen son texto normal (la salida de un build, un chat, un
log de git).

Una mejor manera: que un link impreso como texto se comporte como un hipervínculo. El
usuario lo subraya al leer y lo abre con un tap, sin recurrir al menú More (que queda
comodin intacto para copiar del transcript completo).

## Usuarios
- Usuario de la terminal: toca una URL impresa para abrirla en cualquier app/mirador,
  usa el gesto de pinza para cambiar el tamaño, y tiene un ajuste para desactivar todo
  esto cuando le molesta.

## Historias de usuario
- HU-1. Como usuario, quiero tocar una URL impresa en la terminal para abrirla, sin
  necesidad del menú More.
- HU-2. Como usuario, quiero reconocer visualmente (subrayado) qué textos son links
  antes de tocarlos.
- HU-3. Como usuario, quiero que los links largos que se parten al saltar de renglón
  se abran completos aunque toque el tramo de cualquiera de sus filas.
- HU-4. Como usuario, quiero un solo ajuste de "hipervínculos" que desactive el
  subrayado y la apertura de todos los links, OSC 8 y de texto.

## Definiciones
- **Link de texto plano**: URL impresa como texto normal de la terminal (no marcada
  con OSC 8), reconocida con el mismo reconocedor de URLs que usa hoy el diálogo del
  menú More.
- **Hipervínculo OSC 8**: link declarado por la aplicación con la secuencia OSC 8;
  ya funciona, esta spec no cambia su comportamiento.
- **Renglón lógico**: secuencia de filas visibles unidas por wrapping suave (una línea
  lógica que no cupo en el ancho de la terminal continúa en la fila siguiente). Un
  renglón lógico cabe en una fila o se reparte en varias.
- **Abrir**: lanzar la URL al visor del sistema con la misma lista de esquemas
  permitidos que se usa hoy para los hipervínculos OSC 8 (http, https, ftp, file,
  gemini, mailto).

## Requisitos funcionales
- RF-1: CUANDO el usuario hace tap (sin selección de texto activa, sin app con
  seguimiento de ratón activo, sin gesto de pellizco en curso) sobre una celda que
  pertenece a un link de texto plano, EL SISTEMA abre la URL completa del link
  (incluidos los tramos en otras filas del mismo renglón lógico) y no entrega el tap
  a la sesión.
- RF-2: CUANDO el tap cae en una celda que es a la vez hipervínculo OSC 8 y parte de
  un link de texto plano, EL SISTEMA abre el hipervínculo OSC 8 (prioridad del OSC 8).
- RF-3: MIENTRAS la detección está activa, EL SISTEMA subraya cada celda que forma
  parte de un link de texto plano con el mismo estilo de subrayado que un hipervínculo
  OSC 8, en ambos renderers.
- RF-4: CUANDO el usuario desactiva el ajuste de hipervínculos de terminal, EL SISTEMA
  deja de subrayar y de abrir los links de texto plano (y también los OSC 8).
- RF-5: SI el esquema de la URL detectada no está en la lista de esquemas permitidos,
  ENTONCES EL SISTEMA no la subraya ni la abre, y el tap sigue el camino normal
  (activar el panel, atajo de teclado, etc.).
- RF-6: EL SISTEMA detecta y abre los links por igual en el renderer Compose y en la
  TerminalView legada, con el mismo reconocedor y la misma lista de esquemas.
- RF-7: CUANDO la celda tocada pertenece al tramo de un link en cualquier fila del
  transcript (incluido el histórico con scroll hacia arriba), EL SISTEMA aplica RF-1
  y RF-3 igualmente.
- RF-8: CUANDO un link de texto plano ocupa varias filas visibles por wrapping suave,
  EL SISTEMA lo tratará como un único link (el tap en cualquier tramo abre la URL
  completa; el subrayado cubre todos los tramos).
- RF-9: MIENTRAS hay un gesto de selección o pellizco en curso, EL SISTEMA no abre
  ningún link (la apertura solo ocurre en un tap limpio).
- RF-10: CUANDO el usuario elige la entrada del menú More para ver URLs del
  transcript, EL SISTEMA mantiene el comportamiento actual del diálogo (tap = copiar,
  long-press = abrir).
- RF-11: CUANDO se muestra la descripción del ajuste de hipervínculos en Settings,
  EL SISTEMA menciona tanto los hipervínculos OSC 8 como las URLs impresas.
- RF-12: CUANDO una app activa el seguimiento de ratón y el usuario toca dentro de un
  link de texto plano, EL SISTEMA entrega el toque a la app (el link no interfiere;
  igual que hoy con los OSC 8).

## Requisitos no funcionales
- RNF-1: MIENTRAS la terminal pinta filas cuyo contenido no cambió, EL SISTEMA reutiliza
  el resultado de la detección anterior (sin volver a ejecutar el reconocedor cada frame).
- RNF-2: EL SISTEMA implementa la detección y el mapeo celdas↔caracteres con funciones
  puras probadas con JUnit, sin APIs de Android (verificable: `./gradlew test` verde).
- RNF-3: EL SISTEMA no añade dependencias nuevas al catálogo.
- RNF-4: EL SISTEMA mantiene al paridad de comportimientos con el diálogo actual: el
  reconocedor es el mismo, con sus mismos casos reconocidos y sus mismos límites.

## Casos límite
- **Conocido/límite del reconocedor (paridad, no bug nuevo)**: el reconocedor del
  diálogo no exige frontera de palabra y su lista de caracteres de ruta incluye
  puntuación; una URL tocada abre exactamente lo mismo que copiaría esa entrada del
  diálogo. No se corrige aquí ni se amplía.
- **URL sin esquema** (p. ej. `example.com/a`): el reconocedor no la detecta; no se
  subraya; pasa como texto normal.
- **tap en fila fuera de la rejilla por carrera con un cambio de tamaño**: el tap abre
  nada sin lanzar (misma defensa que ya existe para los OSC 8).
- **glifos de doble ancho dentro de un URL**: el mapeo celdas↔caracteres del subrayado
  y del tap es correcto (un glifo ancho son 2 celdas y el tap en cualquiera las 2
  abre el link).
- **link ocupa la celda final de la fila**: el subrayado llega hasta la última celda y
  el tap en ella abre el link.
- **petición de detección mientras el buffer se redimensiona** (al hacer scroll o
  cambiar el ancho): se recalcula y nunca lanza.

## Fuera de alcance
- Detectar bare domains sin `scheme://`, marcado de Markdown `[t](u)`, o direcciones
  de correo sin `mailto:`; el reconocedor define qué existe.
- Menu contextual por link (abrir/copiar/compartir) anclado al toque; el tap
  abre y nada más.
- Cambios al diálogo del menú More o su orden en el menú.
- Renombrar el ajuste en Settings (solo se cambia la descripción, RF-11).

## Criterios de finalización
- `./gradlew test` verde con tests nuevos de la detección (renglones lógicos,
  múltiples links por renglón, mapeo con glifos anchos, filas de transcript).
- En dispositivo: con renderer Compose y con el legado (1) un link impreso se subraya,
  (2) el tap lo abre en el visor, (3) un link partido se abre completo tocando
  cualquier tramo, (4) el ajuste apagado lo deja todo como hoy, (5) el diálogo del
  menú More copia/abre como antes.

## Dudas abiertas
- Ninguna: las decisiones de interacción (tap abre), visual (subrayar), alcance
  (ambos renderers, un único ajuste ya existente) las fijó el usuario el 2026-10-19.
