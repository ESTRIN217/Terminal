# Checklist de dispositivo — Spec 003

Validación manual en dispositivo (no automatizable: pintado y gestos). Correr los casos con
el **canvas Compose** (sesión normal, renderer nativo activo) y repetir con la **view legada**
(con el split activo, que fuerza el renderer legado — o desactivando el ajuste del renderer).

| # | Caso | Resultado esperado |
|---|---|---|
| 1 | `echo https://example.com/deep/path` | la URL sale subrayada (RF-3/RF-6) |
| 2 | Tap sobre la URL subrayada | se abre en el navegador, el tap no escribe en la shell (RF-1) |
| 3 | `echo https://very-long-domain.example.com/aVeryLongPath?q=1` (se parte en dos filas) | ambos tramos salen subrayados; tap en el tramo de la **segunda** fila abre la URL completa (RF-8) |
| 4 | Subir el scroll hasta un URL viejo del transcript y tocarlo | se abre igual que en pantalla viva (RF-7) |
| 5 | Hyperlink OSC 8 de siempre (p. ej. la salida de zypper/apt con links) | sigue abriéndose; tiene prioridad si coincidiera con un texto (RF-2) |
| 6 | Ajuste *Hipervínculos de terminal* → OFF: repetir 1 y 2 | ni subraya ni abre (RF-4); los OSC 8 también quedan inertes como antes |
| 7 | Menú More → URLs del transcript | tap copia, long-press abre; sin cambios (RF-10) |
| 8 | En una TUI con tracking de ratón (p. ej. `yazi`), tocar una URL | el click va a la app, no abre nada (RF-12) |
| 9 | URL sin esquema (`example.com/a`) ni `mailto:` | no subraya (límite del reconocedor, paridad con el diálogo) |

Defensas de carrera a vigilar si toca reflow: tap inmediatamente después de girar/abrir el
split — no debe abrir nada ni tirar (fila fuera de la rejilla).
