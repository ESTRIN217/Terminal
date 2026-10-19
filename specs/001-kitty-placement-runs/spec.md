# Spec 001 — Colocaciones múltiples y recorte de origen en kitty graphics

Estado: implementada

## Contexto y objetivo

Las previsualizaciones de imagen de la TUI de **opencode** (stack OpenTUI) se ven
descolocadas, estiradas y solapadas dentro de la app, mientras que **yazi**
(ratatui-image) se ve correctamente. La diferencia está en cómo cada cliente usa el
protocolo *kitty graphics*:

- opencode **coloca** imágenes ya transmitidas (`a=p`) con un **rectángulo de origen**
  en píxeles (`x=`, `y=`, `w=`, `h=`) y un rectángulo de destino en celdas (`c=`, `r=`),
  y mueve el cursor después con una secuencia de posicionamiento.
- Cuando solo una parte de una imagen sigue teniendo celdas reservadas, OpenTUI parte
  la imagen en **varios tramos** y emite **una colocación por tramo**, cada una con su
  propio `p=`, su rectángulo de celdas y su recorte de origen.
- opencode usa además `z=` para decidir si la imagen va por debajo o por encima del
  texto (`z<0` = debajo de los glifos).
- yazi, en cambio, envía una imagen ya redimensionada y **una sola colocación** con
  `c=`/`r=`, que ya está soportada.

Hoy el emulador acepta `a=p`, pero (a) ignora el recorte de origen y pinta la imagen
completa estirada al rectángulo, (b) guarda **una única colocación por imagen**, de modo
que cada tramo sustituye al anterior, y (c) ignora `z`. El resultado es el collage
descolocado que se ve en opencode.

**Objetivo:** que una imagen pueda tener varias colocaciones simultáneas, cada una con
su rectángulo de celdas, su recorte de origen y su orden respecto al texto, de forma que
opencode se pinte como en kitty, sin cambiar lo que ya funciona (yazi, `a=T`/`a=t`,
OSC 1337 y los placeholders Unicode `U=1`).

## Usuarios

- Quien usa **opencode** dentro de Terminal y necesita ver las previsualizaciones de
  imagen alineadas con la TUI.
- Quien usa **yazi** u otros clientes de imagen y no debe notar ningún cambio.
- Mantenedores: el comportamiento debe ser determinista, acotado en memoria y
  verificable sin dispositivo.

## Historias de usuario

- HU-1. Como usuario de opencode, quiero que cada tramo de una previsualización se
  dibuje en su rectángulo exacto para poder leer la imagen sin estiramientos ni solapes.
- HU-2. Como usuario de opencode, quiero que las imágenes con `z<0` queden detrás del
  texto para que la TUI siga siendo legible.
- HU-3. Como usuario de yazi, quiero que las previsualizaciones que ya se ven bien
  sigan viéndose igual.
- HU-4. Como mantenedor, quiero pruebas automáticas que reproduzcan las secuencias
  exactas de OpenTUI para detectar regresiones sin abrir la app.

## Definiciones

- **Imagen transmitida:** payload registrado por el cliente con un identificador `i=`.
  Puede transmitirse sin mostrarse (`a=t`).
- **Colocación:** cada rectángulo de celdas que muestra (una parte de) una imagen.
  Tiene identificador de colocación `p=`, origen (fila y columna de la celda superior
  izquierda), tamaño en celdas (`c=`/`r=`), recorte de origen y `z`.
- **Tramo (run):** cada una de las colocaciones parciales en que un cliente divide una
  imagen. En esta spec, "tramo" y "colocación" son lo mismo.
- **Recorte de origen:** sub-rectángulo de la imagen, en píxeles (`x=`, `y=`, `w=`,
  `h=`), que se muestra en el rectángulo de celdas de la colocación.
- **Colocación anónima:** la que llega sin `p=` o con `p=0`.
- **Orden `z`:** apilado de la colocación respecto a los glifos del texto y al resto de
  colocaciones. Negativo = debajo del texto; cero o positivo = encima.
- **Reserva de celda:** marca interna de que una celda está cubierta por una colocación.
  No es contenido de texto y no altera caracteres ni estilos.
- **Área visible:** las celdas de la pantalla activa (sin contar el histórico desplazado).
- **Colocación virtual (`U=1`):** la que no ocupa celdas hasta que el cliente emite
  texto *placeholder* Unicode.

## Requisitos funcionales

### Modelo de colocaciones

- **RF-1.** CUANDO se recibe una colocación (`a=p`) de una imagen ya transmitida con un
  identificador `p=`, EL SISTEMA crea una colocación independiente asociada al par
  (imagen, `p`) y conserva intactas las demás colocaciones de esa misma imagen.
- **RF-2.** SI llega una colocación con la misma imagen y el mismo `p=` que una
  existente, ENTONCES EL SISTEMA sustituye por completo la existente (origen,
  rectángulo de celdas, recorte y `z`) y no altera las demás.
- **RF-3.** CUANDO una colocación llega sin `p=` o con `p=0`, EL SISTEMA la considera
  anónima, le asigna una identidad interna nueva y no sustituye ninguna colocación
  anterior.
- **RF-4.** SI dos imágenes distintas usan el mismo valor de `p=`, ENTONCES EL SISTEMA
  las mantiene como colocaciones distintas: la identidad es (imagen, `p`).
- **RF-5.** CUANDO se retransmite una imagen con un `i=` ya registrado, EL SISTEMA
  descarta el payload anterior y todas las colocaciones de esa imagen antes de registrar
  la nueva.

### Geometría y recorte

- **RF-6.** CUANDO se recibe `a=p`, EL SISTEMA toma como origen la celda del cursor en
  ese instante y **no mueve el cursor**; `C=` no cambia la posición del cursor.
- **RF-7.** CUANDO la colocación incluye `c=` y `r=`, EL SISTEMA usa exactamente ese
  número de celdas como rectángulo de destino, sin recalcularlo por la relación de
  aspecto de la imagen.
- **RF-8.** SI la colocación cruza el borde derecho o inferior del área visible,
  ENTONCES EL SISTEMA muestra solo la parte visible del rectángulo **recortándola, sin
  reescalar** la imagen para que quepa.
- **RF-9.** SI falta `c=` o `r=`, ENTONCES EL SISTEMA calcula la dimensión ausente a
  partir de la relación de aspecto en píxeles de la región de origen (o de la imagen
  completa si no hay recorte), redondeando hacia arriba y con un mínimo de 1 celda.
- **RF-10.** SI faltan `c=` y `r=` y la imagen no tiene tamaño en píxeles conocido,
  ENTONCES EL SISTEMA no crea la colocación y responde con el error de comando inválido
  que ya usa en ese caso.
- **RF-11.** CUANDO la colocación incluye `x=`, `y=`, `w=` o `h=`, EL SISTEMA los
  interpreta como el sub-rectángulo de origen **en píxeles** y escala únicamente ese
  sub-rectángulo al rectángulo de celdas, de forma independiente en cada eje.
- **RF-12.** SI el recorte omite `w=` o `h=`, ENTONCES EL SISTEMA usa como ancho o alto
  del recorte lo que reste de la imagen desde `x=`/`y=`.
- **RF-13.** SI `x=` o `y=` son negativos, ENTONCES EL SISTEMA los trata como 0.
- **RF-14.** SI el recorte excede los límites de la imagen, ENTONCES EL SISTEMA usa la
  intersección con la imagen, sin ampliar el lienzo ni leer píxeles fuera de ella.
- **RF-15.** SI la intersección del recorte con la imagen es vacía o tiene ancho o alto
  cero, ENTONCES EL SISTEMA registra la colocación pero no pinta nada ni reserva celdas.
- **RF-16.** CUANDO una colocación no incluye recorte de origen, EL SISTEMA escala la
  imagen completa al rectángulo de celdas (comportamiento actual, el que usa yazi).
- **RF-17.** EL SISTEMA hace corresponder cada colocación con celdas completas, sin
  desplazamientos subcelda: los píxeles del recorte se mapean al rectángulo entero de
  celdas, con los bordes alineados a la rejilla.

### Orden y pintado

- **RF-18.** CUANDO una colocación incluye `z=` negativo, EL SISTEMA la pinta **por
  debajo de los glifos**; SI `z=` es cero, positivo o está ausente, ENTONCES la pinta
  por encima de los glifos.
- **RF-19.** EL SISTEMA pinta siempre en este orden: fondo de la celda, colocaciones con
  `z<0`, glifos del texto y colocaciones con `z>=0`.
- **RF-20.** MIENTRAS varias colocaciones se solapan, EL SISTEMA las pinta de menor a
  mayor `z` y, a igual `z`, en el orden en que se crearon (la última queda encima).
- **RF-21.** SI dos colocaciones cubren la misma celda, ENTONCES EL SISTEMA asocia la
  celda a la colocación superior según RF-20, a efectos de pintado y de borrados por
  celda.
- **RF-22.** EL SISTEMA no modifica caracteres, estilos ni posiciones del contenido de
  texto al colocar, sustituir o borrar imágenes: la reserva de celdas solo afecta al
  pintado.
- **RF-23.** EL SISTEMA respeta la transparencia de la imagen: los píxeles transparentes
  no ocultan el fondo de la celda ni, cuando la colocación va por debajo del texto, los
  glifos que se dibujan encima.

### Borrados

- **RF-24.** CUANDO se recibe un borrado por imagen (`d=i` o `d=I`) con `i=`, EL SISTEMA
  elimina todas las colocaciones de esa imagen y libera sus celdas; ambos modos tienen
  el mismo efecto.
- **RF-25.** CUANDO se recibe un borrado por colocación (`d=p` o `d=P`) con `i=` y `p=`,
  EL SISTEMA elimina solo esa colocación, libera sus celdas y conserva el payload y las
  demás colocaciones de la imagen.
- **RF-26.** SI el borrado por colocación referencia un `p=` inexistente, ENTONCES EL
  SISTEMA no cambia nada y responde OK (operación idempotente).
- **RF-27.** SI un borrado `d=p` o `d=P` llega sin `p=` pero con `x=` e `y=`, ENTONCES
  EL SISTEMA conserva el comportamiento actual: elimina las colocaciones que cubren esa
  celda (coordenadas 1-based).
- **RF-28.** CUANDO se recibe `d=a` o `d=A`, EL SISTEMA quita todas las colocaciones y
  conserva los payloads (comportamiento actual); `d=c` y `d=C` siguen eliminando lo que
  cubre la celda del cursor.
- **RF-29.** EL SISTEMA no deja reservas huérfanas: tras cualquier borrado o
  retransmisión, ninguna celda visible queda asociada a una colocación eliminada.

### Pantalla, desplazamiento y buffer

- **RF-30.** MIENTRAS el contenido se desplaza verticalmente, EL SISTEMA desplaza todas
  las colocaciones junto con sus filas y recorta las que salen del área visible, sin
  destruirlas ni recolocarlas.
- **RF-31.** CUANDO cambia el tamaño de la pantalla, EL SISTEMA no recoloca ni reescala
  las colocaciones existentes: las recorta al nuevo tamaño.
- **RF-32.** MIENTRAS una colocación pertenece al buffer alternativo, EL SISTEMA no la
  muestra en el buffer principal (ni al revés); al limpiar el buffer alternativo se
  descartan sus colocaciones como hoy.

### Compatibilidad

- **RF-33.** CUANDO la colocación es virtual (`U=1`), EL SISTEMA mantiene el
  comportamiento actual: no le aplica el recorte de origen ni el orden `z` de esta spec.
- **RF-34.** CUANDO la imagen llega por OSC 1337 o por `a=T`/`a=t`, EL SISTEMA mantiene
  el comportamiento actual: una colocación implícita, sin recorte de origen nuevo.
- **RF-35.** CUANDO una imagen se coloca una sola vez con `c=`/`r=` y sin recorte, EL
  SISTEMA produce exactamente el mismo resultado visible que antes de esta spec (caso
  yazi).

### Memoria y límites

- **RF-36.** EL SISTEMA comparte un único payload decodificable por imagen entre todas
  sus colocaciones y lo cuenta una sola vez en el presupuesto de memoria.
- **RF-37.** SI una imagen supera las 256 colocaciones activas, ENTONCES EL SISTEMA
  descarta la colocación más antigua de esa imagen y libera sus celdas antes de crear la
  nueva.
- **RF-38.** CUANDO una imagen se desaloja por presupuesto de memoria, EL SISTEMA elimina
  todas sus colocaciones.
- **RF-39.** EL SISTEMA interpreta los identificadores de cliente `i=` (imagen) y `p=`
  (colocación) como enteros de **32 bits sin signo** (1..4294967295): el mismo valor en
  `a=t`/`a=T` y en `a=p` identifica la misma imagen aunque el bit alto esté puesto, y las
  respuestas repiten el identificador en su forma sin signo. Un cliente real
  (opencode/OpenTUI) usa ids secuenciales con base alta, p. ej. `i=2401244685`.

## Requisitos no funcionales

- **RNF-1.** Sin dependencias nuevas: la funcionalidad se resuelve con lo que ya hay en
  el catálogo.
- **RNF-2.** La geometría (resolución de rectángulos, recorte, orden y borrados) es
  lógica pura y tiene pruebas JUnit que corren sin Android.
- **RNF-3.** El coste de pintar un fotograma es proporcional a las celdas visibles
  cubiertas y a las colocaciones de esas filas; no se decodifica la imagen una vez por
  colocación ni se reescala la imagen completa por colocación en cada fotograma.
- **RNF-4.** La geometría se calcula una sola vez y ambos renderers (vista legado y
  canvas Compose) producen el mismo resultado visible para la misma secuencia.
- **RNF-5.** Determinismo: la misma secuencia de comandos produce el mismo estado y el
  mismo pintado, sin depender de temporizadores ni del orden de iteración de tablas.
- **RNF-6.** Sin regresiones: la suite existente de kitty graphics, OSC 1337,
  placeholders y presupuesto de memoria sigue en verde.
- **RNF-7.** La memoria de payload no crece con el número de colocaciones: las
  colocaciones solo guardan geometría y referencias.

## Casos límite

- Recorte mayor que la imagen, o con `x`/`y` fuera de la imagen.
- Recorte con ancho o alto cero, o intersección vacía.
- `x=`/`y=` negativos.
- Rectángulo de destino completamente fuera del área visible.
- Colocación que cruza el borde derecho, el inferior o ambos.
- Colocación que empieza en la última columna o en la última fila.
- `c=` o `r=` enormes (mayores que la pantalla).
- `z` ausente, `z=0`, `z` muy negativo y `z` muy positivo.
- Colocaciones solapadas de la misma imagen y de imágenes distintas, con y sin `z`.
- Imagen de 1×1 píxel o sin tamaño en píxeles conocido.
- Colocación sin `c=`/`r=`, sin `c=`/`r=` ni píxeles, o con solo una de las dos.
- `p=0` repetido varias veces; `p=` repetido con distinto rectángulo.
- Retransmisión del mismo `i=` mientras tiene varias colocaciones activas.
- Dos imágenes distintas con el mismo `p=`.
- `d=p`/`d=P` de una colocación inexistente; borrar una colocación y recolocarla con el
  mismo `p=`.
- `d=i`/`d=I` de una imagen con varias colocaciones; `d=i` de una imagen inexistente.
- Imagen con colocaciones en el buffer alternativo y salida de ese buffer.
- Desplazamiento vertical hasta sacar la colocación por arriba; subir después.
- Cambio de tamaño con colocaciones activas, incluido reducir columnas por debajo del
  origen de una colocación.
- Imágenes deshabilitadas por preferencia: no se coloca nada y se mantiene la respuesta
  actual.
- `q=1`/`q=2`: las respuestas de error y de OK se suprimen como hoy.
- Presupuesto de memoria excedido mientras la imagen tiene varias colocaciones.
- Secuencia con más de 256 colocaciones de la misma imagen.

## Fuera de alcance

- Animaciones kitty, `a=f` y frames.
- Composición avanzada, mezcla alfa distinta de la transparencia simple, filtros.
- Sixel.
- Borrado por índice `z` (`d=z`) y modos de borrado no mencionados (`d=x`, `d=y`).
- Fidelidad de `C=` (movimiento automático del cursor): se ignora y el cliente mueve el
  cursor explícitamente.
- Recorte de origen para `a=T`/`a=t` y para OSC 1337.
- `z` para colocaciones virtuales `U=1` y para OSC 1337.
- Desplazamientos subcelda (la colocación siempre ocupa celdas completas).
- Calidad de escalado (suavizado, muestreo): se mantiene la del renderer.
- Persistencia de imágenes entre sesiones o en el histórico más allá de lo que ya hace
  el buffer.

## Criterios de finalización

1. Pruebas JUnit en `terminal-emulator` (sin Android) que reproducen, como mínimo:
   - una secuencia de OpenTUI con `a=p` y recorte `x/y/w/h` seguida de posicionamiento
     del cursor: solo se pinta el sub-rectángulo, sin estirar la imagen completa;
   - dos o más tramos de la misma imagen con `p=` distintos: coexisten y cada uno ocupa
     su rectángulo;
   - `z<0` produce orden por debajo del texto y `z>=0` por encima, con el criterio de
     desempate de RF-20;
   - `d=p` elimina una sola colocación y `d=i` todas las de la imagen;
   - retransmitir el mismo `i=` borra las colocaciones anteriores;
   - recorte mayor que la imagen, recorte vacío, cruce de bordes y rectángulo fuera de
     pantalla se recortan sin reescalar y sin excepciones;
   - el caso de una sola colocación con `c=`/`r=` (yazi) da el mismo resultado que antes.
2. `./gradlew test` en verde, incluida la suite existente de kitty, OSC 1337,
   placeholders y presupuesto de memoria.
3. Checklist manual en dispositivo:
   - abrir opencode y mostrar una previsualización de imagen: se ve alineada con el
     bloque que reserva la TUI, sin solapes y sin estiramiento; repetir tras redimensionar
     la ventana y tras hacer scroll;
   - una previsualización con texto encima (`z<0`) sigue siendo legible;
   - abrir yazi y comprobar que la previsualización se ve igual que antes;
   - mostrar una imagen por OSC 1337 y comprobar que no cambia;
   - mover la misma imagen entre buffer principal y alternativo y comprobar que no queda
     ninguna copia fantasma.
4. Ningún cambio en el catálogo de dependencias ni en los textos visibles.

## Dudas abiertas

Ninguna. Las decisiones de interpretación quedan tomadas en esta spec: `z=0` se pinta
sobre el texto, `w=`/`h=` son píxeles en `a=p` (sin heurística de celdas), `d=i` y
`d=I` son equivalentes y el límite es de 256 colocaciones por imagen. Si algún cliente
real no encaja con alguna, se corrige aquí antes del plan.

## Aclaraciones de la fase de plan

Resueltas al aprobar `plan.md` (2026-10-19); no cambian los RF, los concretan:

1. **RF-11 / RNF-6.** Las cuatro llamadas `a=p` de la suite propia que hoy usan `w=`/`h=`
   como celdas (`KittyGraphicsTest`) se adaptan a `c=`/`r=`: son tests nuestros, no un
   cliente real. Para `a=T`/`a=t` no cambia nada (RF-34), incluida la lectura de
   `w=`/`h=` como celdas.
2. **RF-9 con métricas de celda desconocidas** (`mCellWidthPixels`/`mCellHeightPixels` = 0):
   la dimensión que falte se deriva con 1 píxel = 1 celda, como hoy.
3. **RF-15.** Una colocación con recorte vacío **se registra** (responde OK, sustituible
   por el mismo `p=`) pero no reserva celdas ni pinta nada; no es INVALID.
4. **RF-8 y origen fuera de la rejilla.** Si la celda del cursor está fuera de la rejilla,
   la colocación sigue siendo INVALID (comportamiento actual). El recorte sin reescalado
   solo actúa sobre rectángulos cuyo origen está dentro.

## Correcciones tras validar en dispositivo

5. **RF-39 (nuevo, T9).** La validación en dispositivo de T1–T8 mostró las previsualizaciones
   de opencode **vacías**: la TUI reservaba su caja pero no se pintaba nada. Capturada la
   secuencia real en un PTY (`a=t,f=100,i=2401244685,m=1` → `a=p,i=2401244685,p=1,c=19,r=7,
   x=0,y=0,w=80,h=60,C=1,z=-1073741823,q=2` → `a=d,d=I,i=…`), la causa era el parseo de
   `i=`/`p=`: OpenTUI numera imágenes de forma secuencial con base alta y `Integer.parseInt`
   desbordaba por encima de 2147483647, guardaba `0`, registraba el payload sin asociarlo al
   `i=` y respondía `ENOENT:Image not found` (silenciado por `q=2`) en cada `a=p`. Se añade
   RF-39 y `KittyOpenTuiPreviewTest` reproduce la secuencia capturada.
