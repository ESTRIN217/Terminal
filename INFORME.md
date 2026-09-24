# Informe: Kitty Graphics Protocol en `com.estrin217.terminal`

> Fecha de pruebas: 2026-09-24  
> Entorno: proot Debian (Android arm64), yazi 26.9.1, suite en `/root/kgp-test/`  
> Evidencia: grabación `Screen_Recording_20260924_133417.mp4` (70 s, 720×1612) + sesión TTY real

---

## 1. Resumen ejecutivo

| Área | Estado |
|------|--------|
| Implementación KGP (query + render directo) | ✅ **Funciona** |
| Render `a=T` (RGBA, PNG, alfa, placement en celdas) | ✅ **Visto en pantalla** (PNG "KGP", cuadrado verde/círculo) |
| Unicode placeholders en la **suite** (`kgp-test` 5/5b, `kgp-show`) | ❌ **Solo caracteres** — glyphs morados (`t=d`) o tofu blanco (`t=f`); **no** compone la imagen |
| Transmisión `t=f` (file medium) | ✅ **ack OK** — el fallo no es de transmisión |
| Detección de yazi (`ya env`) | ✅ `Brand=Kitty`, `kgp:true`, **`Drivers.matches: Kgp`** |
| **Preview de imágenes en yazi** | ❌ **Grid de círculos azules** (PUA sin resolver) — no pinta la imagen |
| Delete con data free (`a=d,d=I`) | ⚠️ **No libera** — `a=p` sigue `OK` (BUG-1) |

**Conclusión (confirmada con vídeo):** el protocolo y el render **directo** (`a=T`) funcionan. El fallo raíz es **H1: los unicode placeholders (`U=1` + `U+10EEEE`) no se resuelven** en la app — ni en la suite ni en yazi. `t=f` transmite bien (H2 descartado). La app imprime los glyphs de placeholder (con color de fg) en vez de componer la imagen en la celda.

---

## 2. Entorno de la prueba

```
TERM (sin yazi-env)     = xterm-256color
TERM (con yazi-env)     = xterm-kitty
TERM_PROGRAM            = kitty          (solo tras source yazi-env.sh)
Brand.from_env          = Some(Kitty)
Emulator.probe.kgp      = true
Emulator.probe.kgp_shm  = true
Drivers.matches         = Kgp            ← adapter de placeholders
csi_16t (celda)         = 8 × 16 px
Dimension               = 90 cols × 83 rows, 720 × 1328 px
CSI 14t (ventana)       = 720 × 752 px   (lectura puntual en kgp-query)
yazi                    = 26.9.1 (8dd895c)
ImageMagick             = 7.1.1
```

Suite: `/root/kgp-test/`  
Config yazi: `~/.config/yazi/yazi.toml`

---

## 3. Resultados de la suite (`./kgp-test.sh`)

### 3.1 Matriz (corrida del vídeo, ~1:34–1:36)

| # | Test | Resultado | Evidencia / observación visual |
| - | ---- |-----------|--------------------------------|
| 1 | Query `a=q` + `CSI c` | ✅ PASS | `\x1b_Gi=31;OK\x1b\` + DA `?64;…` |
| 2 | RGBA 1×1 `f=32` | ✅ PASS | ack `i=2,I=1;OK` (píxel 1×1: no distinguible a simple vista) |
| 3 | PNG chunked `f=100` (2 chunks) | ✅ PASS | ack `i=3,I=2;OK`; **PNG "KGP" grande pintado** |
| 4 | PNG con alfa | ✅ PASS | Cuadrado verde + círculo semitransparente **visibles** |
| 5 | Unicode placeholders `t=d` `U=1` | ⚠️ PASS* | Rejilla 6×3: **glyphs morados/mojibake** — **no** es el gradiente (*solo "enviado") |
| 5b | Placeholders + `t=f` | ⚠️ PASS* | `t=f ack: OK`; rejilla 4×2: **tofu blanco** — **no** hay imagen |
| 6 | Placement `c=20` | ✅ PASS | PNG escalado a 20 columnas **visible** |
| 7 | Delete `a=d,d=I,i=2` | ⚠️ WARN | `i=2,I=2;OK` ×2 tras `d=I` y `a=d` — BUG-1 |

**Resumen del vídeo:** `PASS=7 FAIL=0 WARN=1 SKIP=0` → `Suite OK — KGP core funciona`

\* PASS = *transmisión/placement aceptados*; el **render visual de placeholders falló** (solo chars).

### 3.2 Salida representativa (query)

```
hex 1b5f47693d33313b4f4b1b5c1b5b3f36343b313b323b363b393b31353b31383b32313b323263
     ESC _ G i = 3 1 ; O K ESC \ ESC [ ? 6 4 ; 1 ; 2 ; … c
```

→ Soporta KGP **y** responde al query (no solo DA).

### 3.3 Observaciones visuales (grabación)

1. **PNG directo** (`a=T`) → se ve (PNG "KGP", cuadrado verde/círculo).
2. **Test 5 placeholders `t=d`** → **solo glyphs morados** en rejilla 6×3 (id=5), no el gradiente.
3. **Test 5b `t=f`** → ack OK + **tofu blanco** 4×2, no la imagen.
4. **yazi** (66 s) → preview = **grid de círculos azules** (glyphs PUA con color); **no** se pinta la imagen.

---

## 4. Problema principal: yazi solo muestra caracteres

### 4.1 Qué hace yazi con `Drivers.matches: Kgp`

Flujo (unicode placeholders, camino moderno):

1. Transmite el PNG (`a=t`, a menudo `t=f` archivo o `t=s` shared memory).
2. Crea **virtual placement**: `a=p,U=1,i=<id>,c=<cols>,r=<rows>`.
3. Escribe texto: celdas `U+10EEEE` con diacríticos de fila/columna y **fg color = image id** (y underline = placement id).
4. El terminal debe **resolver** cada celda → buscar imagen por id → pintar en la celda.

Si el paso 4 falla, el usuario ve **caracteres** (PUA / tofu / basura) en lugar de la foto.

### 4.2 Hipótesis (resultado tras el vídeo)

| # | Hipótesis | Señal | Veredicto |
|---|-----------|-------|-----------|
| **H1** | Virtual placement `U=1` **no se aplica** al resolver placeholders | Suite 5 = glyphs morados; yazi = círculos azules | ✅ **CONFIRMADA** — raíz del bug |
| **H2** | Transmisión yazi falla (`t=f`/`t=s` no legible) | Test 5b: `t=f ack: OK` | ❌ **Descartada** — `t=f` funciona |
| **H3** | Image id en fg no coincide (truecolor vs `38;5;N`) | Suite usa `38;5;N` y **también** falla | ⚠️ Secundaria — aun con encoding estándar no compone |
| **H4** | Race: placeholders antes de que la imagen esté lista | Fallo **consistente** en 5, 5b y yazi | ⚠️ No es la causa principal |
| **H5** | Diacríticos no interpretados | yazi dibuja grid regular de glyphs | ⚠️ Mismo fallo base (H1) |

**Dato clave:** la suite **y** yazi fallan igual en placeholders; el render directo `a=T` sí funciona. El problema **no** es KGP ausente ni la transmisión: es la **resolución de unicode placeholders**.

### 4.3 Evidencia de la grabación (línea de tiempo)

| Tiempo | Qué se ve |
|--------|-----------|
| ~0–15 s | Query OK, RGBA ack, PNG chunked ack + **PNG "KGP" pintado** |
| ~35 s | PNG alfa visible (verde/círculo); test 5 header |
| ~50–56 s | Test 5 = **glyphs morados**; 5b = `t=f ack OK` + **tofu blanco**; placement KGP grande; delete `i=2,I=2;OK`×2 |
| ~62 s | Resumen `PASS=7 FAIL=0 WARN=1`; arranca `kgp-show --placeholders id=7` |
| ~66 s | **yazi abierto**: `alpha-128.png` seleccionado; preview = **grid de círculos azules** |

### 4.4 Qué NO es el problema

- Parser APC de la app: los tests 1–6 demuestran que sí parsea `_G`.
- Transmisión `t=f`: test 5b recibe `OK`.
- Detección de yazi: `Drivers.matches: Kgp` es correcto.
- Falta de `chafa` u otros adapters: no aplica (adapter = Kgp).

---

## 5. Hallazgos / bugs de la app

### BUG-1: `a=d,d=I` no libera la imagen (confirmado)

**Spec:** `d=I` (mayúscula) debe borrar la imagen **y** sus datos. Después, `a=p,i=<id>` debe responder `ENOENT`.

**Observado:** tras `a=d,d=I,i=2` y `a=d` global:

```
\x1b_Gi=2,I=2;OK\x1b\
```

**Impacto:**
- Preview yazi: normalmente reutiliza ids nuevos → **no bloquea** el preview actual.
- Largo plazo: **posible leak** de imágenes en la app al scrollear previews (si no hay LRU/quotas).
- La spec también exige storage quotas; conviene auditar eso.

**Acción sugerida (app):** implementar free real en `d=I`/`d=A`/`d=N` y devolver `ENOENT` en `a=p` posterior.

### BUG-2: Formato de ack no estándar (cosmético)

| Esperado (spec) | Observado |
|-----------------|-----------|
| `i=2;OK` | `i=2,I=1;OK` |
| `i=2,p=1;OK` | `i=3,I=2;OK` |

El extra `I=` no está en la spec de responses. Puede confundir clientes estrictos.

**Acción:** responder solo `i=<id>[,p=<pid>];OK` o `…;ENOENT:…`.

### BUG-3 (confirmado): placeholders no resuelven

**Síntoma (vídeo):**

| Flujo | Entrada | Render |
|-------|---------|--------|
| Suite `t=d` | `U=1` + `U+10EEEE` + `38;5;5` | glyphs **morados** (no imagen) |
| Suite `t=f` | idem + `t=f` (ack OK) | **tofu blanco** |
| yazi (Kgp) | placeholders yazi | **círculos azules** en grid |

La app **imprime** los codepoints placeholder (con su color) pero **no compone** la imagen de `i=<id>` en la celda. Es el flujo que usan yazi/kitty modernos.

**Acción (app):** al pintar `U+10EEEE` con diacríticos + fg/underline → buscar imagen por id + placement virtual `U=1` → render en la celda.

### Observación (no bug): `kgp_shm: true`

yazi ve shared memory disponible. `t=f` ya funcionó (test 5b); `t=s` sigue sin probarse de forma explícita, pero el fallo de render es previo a la transmisión (H1).

### Observación (cosmético): acks en notación caret

Los acks aparecen en pantalla como `^[_Gi=2,I=1;OK^[\` (ECHOCTL del tty al llegar la respuesta). No afecta al parseo; el hex confirma bytes reales.

---

## 6. Suite de aislamiento (disponible)

| Comando | Qué aísla |
|---------|-----------|
| `./kgp-show.sh --placeholders` | Placeholders con `t=d` (direct) |
| `./kgp-show.sh --placeholders --medium f` | Placeholders + `t=f` (archivo) — **estilo yazi local** |
| `./kgp-test.sh` test 5 | Placeholders `t=d` en la batería |
| `./kgp-test.sh` test **5b** | Placeholders + `t=f` (file medium, PNG temporal) |
| `yazi-env.sh` + `ya env` | Adapter y probes |
| Bajar/subir `image_delay` | Race H4 |

---

## 7. Acciones recomendadas

### Inmediatas (este entorno)

1. Diagnóstico **cerrado con vídeo** (§4.3): H1 confirmada; H2 descartada.
2. Dejar `image_delay = 100` mientras no haya fix de placeholders (evita ruido de H4).
3. Mantener delete como **WARN** documentado (BUG-1).

### Verificación de regresión (cuando la app arregle placeholders)

```bash
cd /root/kgp-test
./kgp-test.sh
./kgp-show.sh assets/gradient-64.png --placeholders --id 7 --cells 4x2          # → gradiente, no chars
./kgp-show.sh assets/gradient-64.png --placeholders --medium f --id 8 --cells 4x2
source ./yazi-env.sh && yazi /root/kgp-test/assets                               # → imagen en preview
```

- `./kgp-test.sh` → PASS 1–6 y 5b; delete WARN o PASS.
- Placeholders → **imagen**, no glyphs/tofu/círculos.
- `ya env` → `Drivers.matches: Kgp`, `kgp: true` (ya se cumple).

### En la app terminal (para el equipo de `com.estrin217.terminal`)

1. **Prioridad alta (BUG-3 / H1):** resolver **unicode placeholders** (`U=1` + `U+10EEEE` + fg id → pintar imagen en la celda). Es lo que usan yazi/kitty modernos. Sin esto, yazi no puede previsualizar.
2. **Prioridad media (BUG-1):** free correcto con `d=I` / quotas.
3. **Prioridad baja (BUG-2):** normalizar ack (`i=…;OK` sin `I=` extra).
4. `t=f` ya responde OK; probar `t=s` (shm) si yazi llega a usarlo.

---

## 8. Cómo reproducir

```bash
cd /root/kgp-test
./kgp-query.sh
./kgp-test.sh
./kgp-show.sh assets/gradient-64.png --placeholders --id 7 --cells 4x2
source ./yazi-env.sh
ya env
yazi /root/kgp-test/assets
```

Evidencia: `/root/Screen_Recording_20260924_133417.mp4`  
Docs del protocolo: <https://sw.kovidgoyal.net/kitty/graphics-protocol/>  
Detección yazi: `ya env` → `Brand.from_env`, `Drivers.matches`.

---

## 9. Anexo: comandos hex de referencia

**Query:**
```
1b 5f 47  i=31,s=1,v=1,a=q,t=d,f=24,q=0  3b  41414141  1b 5c  1b 5b 63
```

**RGBA 1×1 rojo (`C=1,a=T`):**
```
1b5f47 C=1,a=T,f=32,s=1,v=1,t=d,i=2,q=2,m=0 ; /wAA/w== 1b5c
```

**Delete:**
```
1b5f47 a=d,d=I,i=2,q=2 ; 1b5c
```
