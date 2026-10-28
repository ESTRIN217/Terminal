# Spec 022 — Navegador interno: pestañas GeckoView para abrir webs desde la terminal

Estado: **aprobada (2026-10-28) — pendiente de implementación**

## Contexto y objetivo

Hoy los enlaces que aparecen al usar la terminal se abren FUERA de la app:

- Hipervínculos OSC 8 subrayados y URLs tapadas en la pantalla (spec 003, ambos
  renderers) terminan llamando al opener externo.
- El diálogo de URLs del More-menu (`ShareUtils.openUrl`) hace lo mismo.

El usuario pierde el contexto de la terminal: Android cambia de app, la sesión X11/proot
sigue de fondo y hay que volver con el conmutador. Para un terminal cuyo caso de uso
"salto a la web de la herramienta que estoy instalando", el salto completo fuera de la app
es fricción innecesaria.

El objetivo: **una pestaña de navegador dentro de la app**, integrada en el sistema de
pestañas polimórfico (Terminal / Administrador de ficheros / X11) para que se alcance una
web sin abandonar la sesión, con el motor synaptic de Firefox self-contained.

### Decisión de motor ya tomada (con el usuario, 2026-10-28)

Motor **GeckoView del canal release, artefacto arm64-v8a** (el fork es arm64-only, la
universal lleva esa misma lib):

- Verificado HOY en maven.mozilla.org que el canal release SÍ sigue publicando:
  `geckoview-arm64-v8a` tiene builds de 158/159 (oct-2026); los `maven-metadata.xml`
  antiguos dicen que el listado acabó en 99 (2022) pero los directorios existen — el
  catálogo maven está desincronizado, no muerto.
- Último build de release publicado a día de hoy: **159.0.20261022150525** (22-oct-2026,
  POM y AAR verificados). **Pin elegido (aprobado por el mantenedor): 159.0.20261022150525**;
  se aplica cuidando: edilicio de pin con nueva versión → sigue el mantenedor, manual
  (RF-12, sin automatización).
- Coste pagado conscientemente: el AAR arm64 es 87 MiB comprimido (verificado), el APK
  universal/release pasa de ~30-60 MB a ~130-150 MB; GeckoRuntime añade un proceso de
  contenido (200-300 MB de RAM en uso). La web pública (`update-site.py`) reescribe
  tamaño/fecha, así que el dato ponerse a correr solo.
- Alternativa descartada: WebView del sistema (0 MB pero "no es un navegador real": APIs
  de web desactivadas, versión flotante por fabricante); Custom Tabs (no es in-app);
  nightly (ruido diaria sin garantía de retro-compat).

## Usuarios

- Usuarios de la terminal (rootfs Debian con proot) que navegan documentación GD/gitrepos/
  páginas de las herramientas que instalan y quieren no perder la sesión.
- El mantenedor, que valida releases en el dispositivo ESTRIN Unisoc/Android 12 aarch64.

## Historias de usuario

- **HU-1.** Como usuario, quiero abrir una pestaña de navegador desde la app (acción del
  drawer) para consultar una web sin salir de la terminal. *(Enmienda 2026-10-28: antes
  "botón de pestaña nueva" del tab bar.)*
- **HU-2.** Como usuario, quiero que al pulsar un enlace de la terminal (hipervínculo OSC 8,
  URL tapada o del diálogo del More-menu) se abra dentro de una pestaña de la app, con el
  título de la página en la pestaña, para no perder el contexto.
- **HU-3.** Como usuario, quiero decidir en Ajustes si los enlaces se abran dentro de la app
  o en el navegador externo, porque a veces prefiero delegar al navegador ya logueado/nejado.

## Definiciones

- **Pestaña de navegador:** pestaña del sistema polimórfico de sesiones, puramente UI
  (como Administrador de ficheros/X11): existe mientras el proceso vive; NO toca
  `TermuxService`, NO entra en el split.
- **Enlace:** URI detectada hoy por la spec 003 (`TerminalUrlScanner`) — hipervínculo OSC 8
  o URL de texto llano en pantalla; incluye las del diálogo de URLs del More-menu.
- **Motor:** GeckoView embeda self-contained registrado arriba; vivirá como un caso de
  pestaña al nivel del app (paquete Compose), nunca en las librerías Java puras.

## Requisitos funcionales (EARS)

- **RF-1 (pestaña de navegador nueva):** CUANDO el usuario toque la acción "nueva pestaña
  de navegador" del menú lateral de navegación (drawer), EL SISTEMA creará una pestaña con
  el título "Navegador" (numerada: "Navegador 2", "Navegador 3", … cuando haya más) y la
  activará. *(Enmienda 2026-10-28: la entrada vive en el drawer en vez del tab bar, igual
  que la del Administrador de ficheros y X11; el tab bar queda como fila pura de pestañas
  abiertas.)*
- **RF-2 (navegación básica):** MIENTRAS una pestaña de navegador esté abierta, EL SISTEMA
  proveerá: barra con la URL de la página actual editable + recargar + retroceder/adelante
  (inhabilitadas cuando no hay historial en esa dir). El botón ir de la barra carga la URL
  tecleada (con `https://` implícito si no tiene scheme).
- **RF-3 (título de página):** CUANDO la página termine de cargar, EL SISTEMA actualizará el
  título de la pestaña con el título del documento (el nombre corto de dominio si viene
  vacío), truncado razonable en la barra de pestañas.
- **RF-4 (enlace del terminal → pestaña interna):** CUANDO el ajuste de enlaces esté en
  "dentro de la app" (valor por defecto) y el usuario abra un enlace (hipervínculo/URL
  tapada del terminal o del diálogo del More-menu), EL SISTEMA abrirá el URI en pestaña de
  navegador nueva dentro de la app.
- **RF-5 (ajuste intern/extern):** CUANDO el ajuste de enlaces esté en "navegador externo",
  EL SISTEMA abrirá el URI como hoy (delegado externo al sistema con `ACTION_VIEW`),
  indistinto del renderizador que lo generó.
- **RF-6 (fallback externo):** SI la carga de una página falla "definitivamente" (error de
  red/PRC dígitos no recuperable informado por el motor), ENTONCES EL SISTEMA pintará el
  mensaje de error dentro de la pestaña con la acción "abrir en navegador externo" y no
  enlazará al usuario.
- **RF-7 (esquemas no web):** SI el URI a abrir no es http/https (p. ej. `mailto:`,
  `content://`, `file://`), ENTONCES EL SISTEMA lo abrirá SIEMPRE por el cauce externo a la
  app ya existente, sin crear pestaña.
- **RF-8 (target=_blank / popups):** CUANDO una web pida abrir un popup o `_blank`, EL
  SISTEMA lo respetará según el ajuste de RF-5 (interna → nueva pestaña del mismo motor;
  externa → opening externo), sin silenciar el intent.
- **RF-9 (visible identidad de sesión):** CUANDO el usuario cierre la pestaña de navegador,
  EL SISTEMA liberará los recursos de motor de esa pestaña sin afectar a las demás pestañas
  (ni a `TermuxService`), igual que hace la pestaña del Administrador de ficheros.
- **RF-10 (traducción):** EL SISTEMA tendrá todo texto visible de la pestaña de navegador en
  los strings existentes (en/es-VE/pt-BR) — sin literales.
- **RF-11 (licencias):** CUANDO se compense la pantalla de licencias de la app, EL SISTEMA
  añadirá la entrada del motor (licencia MPL-2.0) con la URL de su proveedor, igual que las
  entradas actuales.
- **RF-12 (carga dependiente del pin):** EL SISTEMA fijará exactamente el build del motor
  (versión+build id) en el catálogo de dependencias del proyecto; nunca rangos ni cabi
  diarios (Control de versiones, trình).

## Requisitos no funcionales

- **RNF-0 (enmienda aprobada 2026-10-28):** el SDK mínimo del app sube de 24 a **26**:
  el motor elegido (release 159) declara minSdk 26 en su AndroidManifest. Decisión
  del mantenedor: subir el minSdk del app COMPLETO en vez de buscar pines viejos
  (peor compat web) o `overrideLibrary` (usuario truncado en fallo del motor).
  `targetSdk` sigue 28 (intencional). proot/getifaddrs (API 24+) no se afecta.
  Pérdida aceptada: Android 7.x/8.0 (API 24-25) fuera de soporte del app entero.

- **RNF-1 (peso acotado):** el botón de paquete universal NO se upgradea con las 4 ABIs ni
  la variante universal del motor (232 MiB): se logicamente el artefacto arm64-v8a del
  motor (87 MiB comprimido, verificado) — el APK universal lo hereda como ya hace con proot.
- **RNF-2 (tocado punta-corriente):** los enlaces/split/modelo de pestañal ya existentes no
  cambian de API: se añfullName (constitución § 5 sobre datos, § 2 sobre spec manda).
- **RNF-3 (memoria entendida):** el comportamiento aceptable se demostrará EN DISPOSITIVO
  (Unisoc/A12) con X11 + sesión terminal abiertos; se documentará en la spec el uso de
  memoria constatado; si el dispositivo no permite (a decisión del mantenedor), se
  reescopará.
- **RNF-4:** la determinación de nodificar el título (truncado), la decisión intern/extern y
  el shema-check serán funciones puras con tests JUnit (Constitución § 3-4).

## Casos límite

- URL sin scheme en barra (`ejemplo.com/path`) → se completa con `https://`.
- URLs de archivo de esquema ajeno tras tap (p. e. `mailto:` o `tel:`) → cauce externo (RF-7).
- Página sin título → dominio corto en la pestaña.
- Página fallando la carga → RF-6 con acción de salida externa; no se cruza el motor.
- Cierre de la app mientras hay pestañas de navegador → las pestañas de navegador NO se
  restauran cuando el proceso muere (coherente con X11/Administrador de ficheros;
  los reAbre el usuario).
- `maven-parser-truncado`: el catalogo maven del motor le puede liear — el pin es textual
  (persona+fecha) y el update del pin, manual y motivada a gestión del mantenedor.
- Descargas de ficheros en página: fuera de alcance; las pide el usuario a terminal/curl o
  navegador externo (RF-7 no ataca con esquemas no manejados; el http fetching "download"
  del motor se queda en depackage builit-in del motor, sin UI de descarga propia).

## Fuera de alcance

- Historial persistente, marcadores, pestañas privadas, sincronizaciónmozilla, no.
- Soporte vírgene de biscuto de cookies, skills completo de Permisos (ubicación, cámara,
  notificaciones web) → todo denieto por defecto del motor.
- Multi-proceso X11 *( tracked issue del término de las otras spec: no ninguna )*
-Downloads/progress bar own; files decidir newsletter externo.
- Restaura pestañas de navegador tras un cierre de proceso.
- Modo oscuro de la página ligado al tema del app.

## Criterios de finalización

1. Botón de pestaña de navegador en el tab bar → pestaña "Navegador" activa y numerada
   (RF-1); cerrarla no toca el service ni las demás pestañas (RF-9).
2. En la pestaña: barra de URL editable, recargar, atrás/adelante habilitados por historia
   del propio tab (RF-2); el título del documento llega a la pestaña (RF-3).
3. Con el ajuste por defecto, pulsar un enlace del terminal (ambos renderers y More-menu)
   abre pestaña interna; con "navegador externo", se comporta como hoy (RF-4/RF-5).
4. Fallo de carga mantiene el mensaje internamente con salida externa disponible (RF-6).
5. `https:` Obligatorio: cualquier esquema no http/s va SIEMPRE externo (RF-7).
6. `./gradlew test` en verde con los JUnit nuevos (RNF-4), strings 3 locales (RF-10),
   licencia del motor añadida (RF-11), pin textual del build en el catálogo (RF-12).
7. Verificación EN DISPOSITIVO: pestañas del navegador usables, caso de carga normal (web
   compleja) sin bloquear el resto de pestañas; medida y memoria constatada documentada
   (RNF-3), tamaño del APK resultante reportado.

## Resultados de factibilidad (en curso)

- **F3 — Build local (2026-10-28, T2):** POSITIVO.
  - El AAR `geckoview-arm64-v8a-159.0.20261022150525` se resuelve desde
    maven.mozilla.org (local y el `maven-metadata.xml` de ese repo está desincronizado:
    el canal release SÍ sigue publicando builds de oct-2026).
  - Requisitos del motor descubiertos al medir: exige **compileSdk 37.2** (bump de
    37.1, solo compileSdk; targetSdk intacto) y **minSdk 26** → enmienda aprobada
    (RNF-0): minSdk del app 24→26 con decisión del mantenedor.
  - `./gradlew test` VERDE con el AAR en el classpath; `assembleDebug` produce el
    APK con `libxul.so` (154 MB descomprimido) y `libmozglue.so` dentro
    (`unzip -l` verificado).
  - Tamaño constatado del APK universal DEBUG: **140.8 MB** (antes ~30-60).
    La cifra de release se mide en T11.
- **F1/F2 (procesos con shared UID + memoria en el Unisoc/A12):** PENDIENTES — T1,
  mantenedor en dispositivo. Si F1 o F2 fallan: reescopo en frío antes del código de UI.

## Dudas abiertas

- Ninguna (cerradas 2026-10-28, aprobación del mantenedor):
  - Pin: **159.0.20261022150525** (la más reciente del release a día de hoy; se descarta la
    158.0.20261021001945).
  - Política de actualización del pin: **manual por release, sin automatización**;
    `update-site.py` no cambia (ya recalcula tamaño/fecha del APK publicado).
