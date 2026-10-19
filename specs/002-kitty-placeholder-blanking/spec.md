# Spec 002 — Celdas placeholder nunca se pintan como glifo

Estado: aprobada

> Implementada el 2026-10-19 (spec 002). Pendiente la validación en dispositivo con
> copilot, opencode y yazi.

## Contexto y objetivo

La TUI de **GitHub Copilot CLI** (v1.0.98) muestra las imágenes con el protocolo *kitty*
en modo **placeholders Unicode**: transmite y crea la colocación virtual con
`a=T,U=1,i=<id>,c=48,r=14,f=100,q=2` y después imprime una rejilla de celdas
`U+10EEEE` + diacríticos de fila/columna, con el id de imagen codificado en el color de
primer plano (`38;2;R;G;B`).

En el dispositivo, la previsualización se ve así: la imagen se pinta bien en las 48
columnas de la rejilla, pero **el relleno de la caja que la rodea aparece lleno de
"símbolos" repetidos**: son glifos `.notdef` de `U+10EEEE` con sus diacríticos, pintados
por la app. Esas celdas contienen el carácter placeholder pero **no decodifican** (el
color de primer plano es el color de texto normal, no un id de imagen, o el destino cae
fuera de la rejilla virtual), y nuestros dos renderers solo ocultan el glifo cuando la
celda decodifica correctamente. Kitty **nunca** dibuja `U+10EEEE`: la celda muestra la
imagen o el fondo, jamás el carácter.

**Objetivo:** que una celda placeholder nunca se pinte como texto, sino como el corte de
imagen que le corresponda o, si no le corresponde ninguno, como el fondo de la celda; sin
cambiar nada de lo que ya funciona (opencode, yazi, OSC 1337, `a=T` sin `U=1`).

## Usuarios

- Quien usa **copilot** dentro de Terminal y quiere ver la previsualización limpia, sin
  glifos de relleno.
- Quien usa **opencode** o **yazi** y no debe notar ningún cambio.
- Mantenedores: la regla debe ser determinista y verificable sin dispositivo.

## Historias de usuario

- HU-1. Como usuario de copilot, quiero que la zona de relleno de la imagen se vea como
  el fondo de la caja, sin símbolos, para poder leer la previsualización.
- HU-2. Como usuario de opencode/yazi, quiero que sus previsualizaciones sigan igual.
- HU-3. Como mantenedor, quiero una prueba automática que reproduzca la mezcla de celdas
  resolubles y no resolubles de la secuencia real de copilot.

## Definiciones

- **Celda placeholder:** celda cuyo contenido empieza por `U+10EEEE`.
- **Celda resoluble:** celda placeholder que el decodificador convierte en un destino
  (id de imagen + fila + columna de rejilla) usando el color de primer plano y los
  diacríticos (con las reglas de herencia de kitty).
- **Rejilla virtual:** el rectángulo `c=`×`r=` declarado por la colocación virtual
  (`U=1`); una celda resoluble puede caer dentro o fuera de él.
- **Fondo efectivo:** el color de fondo que el resto del texto usaría en esa celda
  (paleta, vídeo inverso, selección y fondo por defecto), incluido el fondo que fije el
  cliente con SGR.

## Requisitos funcionales

- **RF-1.** CUANDO una celda contiene `U+10EEEE` y las imágenes están activas, EL SISTEMA
  no dibuja el glifo del placeholder ni el de sus diacríticos, en ningún caso.
- **RF-2.** CUANDO una celda resoluble cae dentro de la rejilla virtual y su imagen sigue
  registrada, EL SISTEMA pinta el corte de imagen correspondiente sobre el fondo efectivo
  de la celda (comportamiento actual).
- **RF-3.** CUANDO una celda resoluble cae fuera de la rejilla virtual, o su imagen ya no
  está registrada, o la colocación virtual ya no existe, EL SISTEMA pinta solo el fondo
  efectivo de la celda.
- **RF-4.** CUANDO una celda placeholder no es resoluble, EL SISTEMA pinta solo el fondo
  efectivo de la celda.
- **RF-5.** EL SISTEMA calcula ese fondo con la misma lógica que el resto del texto y
  cubre toda la celda, de modo que no queden píxeles de un fotograma anterior ni glifos
  previos.
- **RF-6.** SI las imágenes están desactivadas, ENTONCES EL SISTEMA no cambia el pintado
  de texto: los placeholders se ven como caracteres normales.
- **RF-7.** Una celda placeholder no resoluble no altera la herencia de fila/columna de
  las celdas siguientes dentro de la misma línea lógica.
- **RF-8.** La regla se aplica igual en los dos renderers (el legado y el canvas Compose).

## Requisitos no funcionales

- **RNF-1.** Sin dependencias nuevas.
- **RNF-2.** Sin trabajo por celda que no exista ya: el recorrido de la fila se reutiliza
  y no se añaden asignaciones por fotograma más allá de las actuales.
- **RNF-3.** El único gate automático sigue siendo `./gradlew test`; la parte visual se
  valida en dispositivo.

## Casos límite

- Celda placeholder sin diacríticos, con herencia disponible de la celda de su izquierda:
  se resuelve y se pinta la imagen (no debe confundirse con "no resoluble").
- Celda placeholder no resoluble seguida de celdas resolubles en la misma fila: las
  siguientes siguen pintándose.
- Fila que envuelve (wrap) en el borde derecho: la herencia y el fondo siguen aplicándose
  en la fila de continuación.
- Placeholder con la imagen ya desalojada o borrada: fondo limpio, sin glifo.
- Placeholder en la última columna de la pantalla y en el borde inferior.
- Histórico: la regla se aplica igual al hacer scroll a filas antiguas.

## Fuera de alcance

- Hacer que la imagen llene celdas más allá de `c=`/`r=` cuando el cliente imprime una
  rejilla mayor: kitty muestra "solo parte de la imagen" en ese caso y esta spec replica
  ese comportamiento.
- Cambiar lo que emite copilot, o añadir *sixel*.
- El texto copiado/seleccionado de las celdas placeholder.

## Criterios de finalización

- `./gradlew test` en verde con pruebas nuevas que cubran RF-1, RF-3, RF-4 y RF-7.
- En dispositivo, con copilot: la previsualización muestra la imagen y el relleno de la
  caja sin ningún `U+10EEEE` visible.
- opencode y yazi se ven igual que antes (RF-2) y OSC 1337 no cambia.

## Dudas abiertas

- Ninguna pendiente.
