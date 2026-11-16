# Arquitectura de CamNode

Fecha: 14 de noviembre de 2026. Stack alineado con Birmind: AGP 9.3.3,
Kotlin 2.2.10 integrado, Compose BOM 2026.09.00, Hilt 2.60.1 con KSP,
Navigation 3, DataStore, OkHttp 5 y CameraX 1.6.2.

## Modelo

Una sola app. Cada teléfono elige su rol en cada momento: **cámara** o
**visor**. Hay dos caminos de red y no se necesita ningún servidor:

- **Misma Wi-Fi.** Las cámaras se anuncian por mDNS y los visores las
  encuentran solos. No hace falta Tailscale dentro de casa.
- **Tailscale.** Desde cualquier otro lugar. La app no embebe ningún SDK de
  Tailscale: usa la IP `100.x` que la app de Tailscale pone en el teléfono.

| Concepto | Tipo | Qué es |
| --- | --- | --- |
| Hogar | `Household` | Dominio de confianza: id, nombre y secreto compartido |
| Nodo local | `LocalNode` | Identidad estable de este teléfono |
| Miembro | `Member` | Un teléfono conocido por el hogar: rol, `endpoints` (Wi-Fi y Tailscale), `updatedAtMillis`, `removed` |
| Directorio | `Directory` | Lista replicada de miembros |
| Invitación | `Invite` | Hogar más endpoints semilla, codificada en el QR |
| Estado de nodo | `NodeStatus` | Rol y batería de cualquier nodo con servidor; `camera: CameraStatus?` solo si transmite |
| Evento | `CameraEvent` | Alerta con id único (hoy `MOVED`); los receptores descartan duplicados |

Roles: `CAMERA`, `VIEWER` y `RECORDER` (la PC). `Member.receivesAlerts` marca a los
maestros: el teléfono que llevas y el grabador.

### Descubrimiento sin servidor

Tailscale no propaga mDNS, así que el directorio se replica por *gossip*.
`Directory.merge` es last-writer-wins por miembro, con desempate determinista.
Es conmutativa, asociativa e idempotente (ver `DirectoryTest`), por lo que
cualquier par de teléfonos puede sincronizarse en cualquier orden y converger.

- Un teléfono que se une guarda las semillas de la invitación y las reintenta
  en cada sincronización hasta el primer intercambio exitoso.
- Cada cámara anuncia su endpoint y sincroniza con las demás cámaras cada
  minuto.
- Cada visor sincroniza con todas las cámaras cada 15 s mientras la pantalla
  de cámaras está visible.
- Quitar un dispositivo crea un *tombstone* que se propaga y no resucita.
- Las cámaras sin dirección conocida siguen en `Directory.cameras` para que el
  visor muestre "Sin conexión" en vez de ocultarlas.

### Descubrimiento en la misma Wi-Fi y elección de ruta

- La cámara registra `_camnode._tcp` por NSD con TXT `n=<nodeId>` y
  `h=<Household.discoveryTag>`. La etiqueta son 6 bytes de SHA-256 del id del
  hogar: filtra anuncios ajenos y no revela el nombre ni la clave.
- El visor busca ese servicio solo mientras la pestaña Cámaras está visible.
  Cuando aparece una cámara, sincroniza y revisa el estado al instante.
- Para cada cámara prueba en paralelo la dirección mDNS y todas las anunciadas,
  y usa la mejor que responde: Wi-Fi antes que Tailscale (`byPreference`). El
  video usa esa misma ruta.
- La IP de Wi-Fi sale de `ConnectivityManager` (transporte Wi-Fi/Ethernet). Los
  datos móviles también tienen IPs privadas `10.x` que no sirven como LAN.
- Descubrir no autoriza nada: cada llamada sigue exigiendo la clave del hogar.
- El reloj propio nunca retrocede para los registros propios.

## Módulos

```text
app/                  Application, MainActivity (deep link), Navigation 3, pestañas
core/model/           JVM puro: tipos de dominio, Directory, InviteCodec, puertos
core/control/         JVM puro: contrato entre nodos. ControlServer (Ktor CIO), ControlClient (OkHttp)
core/network/         Android: direcciones Wi-Fi/Tailscale, mDNS (LanDiscovery), permiso de red local
core/data/            DataStore tipado, repositorios, DirectorySync, PeerProbe, LocalNodeServer, EventDispatcher
feature/alerts/       Pestaña Alertas: AlertReceiverService (maestro), BootReceiver, historial
feature/applock/      Bloqueo con PIN y disfraz de la app: AppLockGate, disfraces, LauncherDisguiser, ajustes
recorder/             App JVM de escritorio: graba las cámaras con ffmpeg y recibe alertas
core/designsystem/    Tema y componentes compartidos
feature/household/    Bienvenida, crear, unirse (QR con CameraX + ZXing), invitar, pestaña Hogar
feature/node/         Pestaña Transmitir: CameraStreamController (RootEncoder RTSP), CameraNodeService, ContinuousOperation
feature/viewer/       Pestaña Cámaras: cuadrícula y detalle con Media3 RTSP, descubrimiento y elección de ruta
tools/import_icons.py Importa Material Symbols como vector drawables a core:designsystem
build-logic/          Convention plugins: camnode.android.{application,library,compose,feature}, camnode.hilt, camnode.jvm.library
```

Reglas de dependencia:

- `app` → `feature:*` → `core:data` → `core:network` → `core:control` → `core:model`.
- `recorder` → `core:control` → `core:model`. El contrato tiene una sola
  implementación compartida por Android y la PC.
- Una feature nunca depende de otra. `app` compone la navegación y las
  features reciben callbacks (`onBack`, `onOpenCamera`), no conocen las rutas.
- `core:model` no depende de Android, así que la lógica de dominio se prueba
  en la JVM.
- Las versiones viven en `gradle/libs.versions.toml`. La configuración común
  de Android vive en `build-logic`, no se copia entre módulos.

Para agregar una capacidad nueva (por ejemplo detección de movimiento o
grabación), crear `feature/<nombre>` con `plugins { alias(libs.plugins.camnode.android.feature) }`
y registrarla en `settings.gradle.kts`. El plugin aporta Compose, Hilt, el
tema y el modelo.

## Contrato entre teléfonos

Versionado por ruta para que convivan versiones distintas de la app.

| Puerto | Protocolo | Uso |
| --- | --- | --- |
| 47180 | HTTP JSON | `GET /v1/status` → `NodeStatus`; `POST /v1/directory` (body `Directory`) → directorio fusionado; `POST /v1/camera` (body `CameraCommand`, campos opcionales `facing` y `orientation`) → `NodeStatus` o 404 si no transmite; `POST /v1/events` (body `CameraEvent`) → 202 o 404 si no recibe alertas |
| 8554 | RTSP, H.264 + AAC opcional | Video en vivo; RTP sobre TCP en los visores |
| 5353 | mDNS `_camnode._tcp` | Anuncio de cámaras en la misma Wi-Fi |

El cambio de lente no reinicia el stream. El cambio de orientación sí, durante
unos 2 s, porque ancho y alto se intercambian; los visores reconectan solos y la
cámara pide un cuadro clave cada vez que se conecta un espectador.

Un teléfono tiene un solo servidor de control (`LocalNodeServer`). La cámara y el
receptor de alertas registran sus capacidades por separado y el servidor vive
mientras haya alguna, así que un teléfono puede ser cámara y maestro a la vez.

Autenticación:

- La API de control exige `Authorization: Bearer <secreto>`, comparado en tiempo
  constante. Responde `401` si no coincide y el visor lo muestra como "Clave del
  hogar distinta".
- RTSP usa Basic auth con usuario `camnode` y el secreto como contraseña
  (`rtsp://camnode:<secreto>@<ip>:8554/`).

Los cambios incompatibles van en `/v2/...`. Los campos nuevos de JSON deben ser
opcionales, porque el decodificador ignora claves desconocidas.

## Roles y permisos

Todos los dispositivos entran como **Cámara**; un administrador decide quién ve o
controla. Las reglas viven en `core:model` (`AccessRole`, `RoleBook`) y son JVM puro.

| Rol | Ver video y alertas | Controlar y pausar cámaras | Invitar, roles, quitar |
| --- | --- | --- | --- |
| Administrador | Sí | Sí | Sí |
| Visor | Sí | No | No |
| Grabador (PC) | Sí | No | No |
| Cámara | No | No | No |
| Sin acceso (revocado) | No | No | No |

- **Identidad**: cada dispositivo genera su par de llaves ECDSA P-256 (`NodeKeys`)
  y anuncia la pública en su registro del directorio. La privada nunca sale de él.
- **Dueño**: quien crea un hogar seguro queda fijado en `Household.ownerKey` (viaja
  en la invitación). Es administrador siempre y el único que nombra administradores.
- **Permisos firmados** (`RoleGrant`): un administrador firma "este id con esta llave
  tiene este rol". `RoleBook.merge` descarta toda firma inválida o fuera de autoridad,
  así un permiso falso (aunque sea más reciente) no sube ni tapa un rol real. Los
  administradores nombrados no pueden tocar al dueño ni a otros administradores. Una
  revocación bloquea al id aunque cambie de llave. `Directory.merge` exige la llave
  del dueño para no aceptar permisos sin verificar.
- **Pedidos firmados**: cada llamada a la API de control firma método, ruta,
  destinatario, hora y hash del cuerpo (`ControlProtocol.signedPayload`). El servidor
  rechaza firmas inválidas, de más de 5 minutos, dirigidas a otro nodo o repetidas
  (`ReplayGuard`), y aplica la tabla de permisos por ruta (`401` clave/firma, `403` rol).
- **Video por visor**: `POST /v1/stream-ticket` (requiere ver) entrega a cada visor su
  propia contraseña RTSP (`TicketBook`, 12 h, estable para no reconectar). Al conectar
  se re-verifica el rol, así una revocación corta conexiones nuevas.
- **Hogares anteriores a los roles** (sin dueño) conservan el acceso por clave
  compartida y muestran "Crear hogar seguro" en Hogar: crea un hogar nuevo con clave
  nueva y este teléfono como dueño; los demás se vuelven a unir como cámaras.
- Pendiente: cerrar las conexiones RTSP ya abiertas de un visor revocado (hoy solo se
  bloquean las nuevas) y firmar los registros de miembro (hoy un miembro podría
  anunciar direcciones falsas de otro id: causa fallas de conexión, no acceso).

## Servidor RTSP adaptado (`third_party/rtspserver`)

Copia de pedroSG94/RTSP-Server 1.4.3 (Apache 2.0, ver su `LICENSE`) con cambios
marcados en cada archivo:

- **Vulnerabilidad corregida**: la versión original solo pedía la contraseña en
  `DESCRIBE`; un cliente que enviaba `SETUP` y `PLAY` directamente recibía el video
  sin credenciales (verificado en un Samsung A02: 2.4 MB en 4 s). Ahora cada conexión
  debe autenticarse en `DESCRIBE`, `SETUP`/`PLAY` sin autenticar responden `401` y
  `ServerClient` solo envía media si `mayStream` (el original enviaba tras cualquier
  `PLAY`, incluso respondiendo `401`). Tras el arreglo el mismo ataque recibe 0 bytes.
  Prueba de regresión en `ServerCommandManagerAuthTest`.
- `setAuthenticator`: verificador por usuario y contraseña, para los permisos por visor.

## Pausa y navegación

- **Pausa** (`CameraCommand.paused`, solo administradores): libera cámara, codificador
  y servidor RTSP; el servicio y la API siguen vivos para reanudar a distancia, porque
  Android permite reabrir la cámara dentro de un servicio de cámara en curso. Medido en
  el A02: 18 % de CPU en reposo, 3 % en pausa, cámara del sistema cerrada; reanudar a
  distancia con pantalla apagada mostró el primer cuadro en 1.8 s.
- **Deslizar entre cámaras** en la vista completa (`HorizontalPager`). Con un dedo y
  sin zoom de pantalla el gesto cambia de cámara; con dos dedos o con zoom mueve la
  imagen.

## Bloqueo con PIN y disfraz

- `AppLockGate` envuelve toda la UI en `MainActivity`. Mientras está bloqueada no se
  compone nada de CamNode: se muestra el disfraz elegido (`AppDisguise`), que es una app
  que funciona de verdad. Para abrir la app se ingresa el PIN como esa app lo pide:
  calculadora (escribir el PIN y `=`), reloj (alarma HH:MM; el diálogo permite escribir
  la hora), calendario (nuevo evento el DD/MM) o notas (guardar una nota que diga solo
  el PIN; esa nota no se guarda). Las alarmas y los eventos que no son el PIN se pasan a
  las apps del sistema. Las notas del disfraz viven en `decoy_notes.json`, separadas
  del estado de CamNode.
- El PIN se guarda como PBKDF2-HMAC-SHA256 (120 000 iteraciones, con sal) en
  `StoredState.appLock`. `PinThrottle` permite 5 intentos y después obliga a esperar
  30 s, tiempo que se duplica en cada ronda (máximo 1 h). La espera sobrevive a reinicios. Los
  intentos de un disfraz también cuentan: solo cuentan las entradas de 4 dígitos que el
  disfraz trata como candidatas. Comprobar el PIN tarda unos 2-3 s en el A02.
- `AppLockSession` vuelve a bloquear la app cuando pasa más de 15 s en segundo plano.
- Ícono y nombre: un `activity-alias` por disfraz y estilo de ícono (`.launcher.Clock`,
  `.launcher.Clock2`, `.launcher.Clock3`…; `LauncherEntry`) y solo uno activo. Los íconos
  los genera `tools/make_launcher_icons.py`; la pantalla de ajustes muestra los íconos
  reales de los alias. El nombre visible sale de `disguise_*` en `feature:applock` y
  se traduce con el resto.
  Al desactivar el alias que abrió la tarea, Android la cierra; por eso
  `LauncherDisguiser` aplica el cambio cuando la app pasa a segundo plano, y al arrancar
  corrige el alias si quedó desalineado. Cada alias apunta a una actividad de entrada
  que hereda `MainActivity` y define su propio tema de inicio. En Android 12 o superior
  el tema muestra el icono correspondiente a la apariencia antes de abrir la app.
- Recientes: en Android 13+ se usa `setRecentsScreenshotEnabled(false)`. En versiones
  anteriores se usa `FLAG_SECURE`, que también bloquea las capturas de pantalla mientras
  el bloqueo está activo.
- Límite deliberado: la notificación del servicio de cámara y el indicador de cámara de
  Android siguen mostrando el nombre real. El disfraz oculta la app, no la grabación.
- Para cambiar el PIN hay que desactivar el bloqueo y volver a activarlo. Si el PIN
  actual no encaja con el disfraz nuevo (por ejemplo 2580 con Reloj), la pantalla lo
  explica.

## Idiomas

- Inglés en `values/` (para idiomas sin traducción) y traducciones a español, portugués,
  francés, alemán, hindi, indonesio (`values-in`), árabe, chino simplificado y ruso.
  Cada módulo guarda sus textos en su propio `res/`.
- `AppLanguage` (`core:data`): en Android 13+ usa el idioma por app del sistema
  (`LocaleManager`, `locales_config.xml`). En versiones anteriores lo guarda en
  SharedPreferences, y `MainActivity`, `CamNodeApplication` y los servicios envuelven su
  contexto (`attachBaseContext`). En esas versiones `MainActivity` fija además la
  dirección de escritura (árabe) para Compose. El selector está en Hogar → Idioma.
- La calculadora y el teclado de PIN se mantienen de izquierda a derecha también en
  árabe, como los teclados numéricos reales.
- `bundle.language.enableSplit = false`: si Play dividiera el paquete por idioma, el
  selector no tendría los idiomas que el teléfono no usa.
- Los errores de `core:model`/`core:control` son códigos (`InviteProblem`) o textos técnicos
  en inglés; cada pantalla los traduce. El grabador de escritorio sigue solo en español.
- Las traducciones son una primera versión; conviene que las revise un hablante nativo,
  sobre todo hindi, árabe y chino.

## Seguridad

- El secreto se genera con `SecureRandom` (24 bytes, base64url), nunca se
  imprime (`toString` enmascarado) y está excluido del backup en la nube y de la
  transferencia entre dispositivos.
- HTTP y RTSP viajan en claro. Por Tailscale van dentro de WireGuard. En la
  Wi-Fi de casa van sin cifrar: la clave autoriza, pero alguien conectado a tu
  Wi-Fi podría capturar el video. Usa una Wi-Fi con contraseña. Cifrar en LAN
  (TLS/SRTP) queda pendiente.
- Con targetSdk 37, Android 17 bloquea la red local sin `ACCESS_LOCAL_NETWORK`.
  La app lo declara y lo pide en Transmitir, Cámaras y Unirse
  (`LocalNetworkPermission`). En Android 16 o anterior no se pide.
- Quitar un dispositivo lo oculta, pero no revoca el secreto que ya conoce.
  Para revocar de verdad hay que crear un hogar nuevo. La rotación del secreto
  queda pendiente.
- La invitación (QR o enlace) contiene el secreto y equivale a acceso total.

## Rol cámara en segundo plano

- `CameraNodeService` es un foreground service de tipo `camera` (y `microphone`
  si hay permiso), con un wake lock parcial de 10 minutos que el ciclo de
  sincronización renueva, y un Wi-Fi lock de baja latencia.
- Android exige iniciar un servicio de cámara con la app visible. Por eso no hay
  inicio automático al encender el teléfono, y un reinicio `START_STICKY` desde
  segundo plano se rechaza limpiamente.
- `CameraStreamController` es un singleton dueño del stream. La vista previa se
  conecta y desconecta sin cortar la transmisión.
- Si el servidor RTSP falla tras arrancar, el servicio se detiene y la pantalla
  muestra el motivo.
- `ContinuousOperation` muestra si Android restringe la batería de CamNode y
  pide la exención. En HONOR y Huawei abre además "Inicio de aplicaciones"
  mediante una acción pública (`hihonor.intent.action.HSM_STARTUPAPP_MANAGER`),
  declarada en `<queries>` y solo si el sistema la resuelve.
- Dos cámaras del mismo teléfono a la vez dependen del hardware. En el HONOR
  LGN-LX3 cada cámara tiene costo de recurso 100 sobre 100, así que no es
  posible. Para dos vistas se usan dos teléfonos.

## Zoom y eficiencia

- **Zoom de pantalla** (pellizcar en la vista completa, doble toque restablece):
  instantáneo y sin costo para la cámara.
- **Zoom del sensor** (`CameraCommand.zoom`, botones +/− en la vista completa):
  recorta antes de codificar, así que visores y grabaciones ganan detalle real.
  El rango depende de la lente (`CameraStatus.zoomMin/zoomMax`; HONOR trasera
  1–8x) y se reinicia a 1x al cambiar de lente.
- Medido en el HONOR a 720p/20 fps con audio: la cámara usa ~42 % de CPU en
  segundo plano y ~78 % con la vista previa visible. Por eso la vista previa se
  puede ocultar y arranca oculta en gama baja.
- Gama baja (`DeviceClass`: ≤2 GB de RAM o `isLowRamDevice`): calidad inicial
  360p/15 fps, vista previa oculta y cuadrícula del visor sin video en vivo (se
  abre al tocar; botón en la barra superior para activarlo).
- Visor: el silencio se aplica por volumen para conservar las pistas de la
  conexión compartida al pasar de cuadrícula a detalle. Media3 cae a otro
  decodificador si el de hardware falla. El codificador de la cámara es por
  hardware (`c2.qti.avc.encoder` en el HONOR).
- El detector de movimiento lee el acelerómetro a ~5 Hz (`SENSOR_DELAY_NORMAL`).

## Rotación de la vista, modos de imagen y reposo

- **Girar la vista** es del visor: `CameraSettingsRepository.rotateDisplay` guarda
  0/90/180/270 por cámara en este teléfono y `RtspPlayer` gira la superficie
  (`TextureView` solo si hay rotación o zoom de pantalla) midiéndola con ancho y
  alto intercambiados en giros de 90°, así ocupa el espacio sin deformarse. La
  cámara no se entera: no hay reconexión. La orientación de la cámara
  (`VideoOrientation`) queda como ajuste de instalación para que las grabaciones
  salgan derechas.
- **Modos de imagen** (`ImageMode`: color, blanco y negro, nocturno), elegidos desde
  el maestro (`CameraCommand.imageMode`) o en la cámara. `ImagePlan` (puro, probado)
  decide filtros GL (gris, gamma, contraste) y, en nocturno, compensación de
  exposición máxima y el rango de fps del sensor con piso más bajo sin superar los
  fps del stream. Se reaplica en cada `onCameraOpened` porque la petición de captura
  se reconstruye al reabrir la cámara. Linterna (`torch`) solo en lentes con flash.
  Medido en un Samsung A02: en blanco y negro la saturación media baja de 4.4 a 1.0;
  los filtros suman unos 5-12 puntos de CPU (no ahorran consumo en la cámara). En
  una habitación con poca luz el nocturno mostró la escena donde color era negro,
  pero sobreexpuso las zonas iluminadas; en oscuridad total no se ve nada. Afinar
  gamma/contraste con una escena nocturna estable queda pendiente.
- **Reposo sin espectadores**: con 0 clientes RTSP, el GL limita a 2 fps
  (`forceFpsLimit`) y el sensor usa su rango más lento (`idleFpsRange`, 15 fps en el
  A02). El grabador cuenta como espectador. El Wi-Fi de baja latencia se sostiene solo
  mientras alguien mira. Medido en el A02 (pantalla apagada): 40-42 % de CPU con un
  espectador, 17-20 % en reposo.
- **Arranque rápido**: el socket conecta antes de que el visor termine el saludo
  RTSP, así que el cuadro clave pedido al conectar se perdía. Ahora se pide cuando el
  cliente puede recibir (`ServerClient.canSend`, tras `PLAY`) y otra vez 300 ms
  después. Medido por Tailscale con `ffmpeg` sin análisis: cámara activa ~1.0-1.3 s
  (antes ~1.6 s), saliendo de reposo ~1.3-1.5 s (antes ~3 s). El saludo RTSP solo
  toma ~0.3 s; acortar el intervalo de cuadros clave a 1 s no mejoró nada.

## Orientación

RootEncoder usa 0 para horizontal y 90 para vertical. `VideoOrientation.AUTO`
traduce la rotación de la pantalla (`ROTATION_0` → 90) al iniciar; los valores
fijos sirven para teléfonos montados de lado o de cabeza. La rotación se aplica
en la cámara, así que todos los visores y grabaciones ven la imagen derecha.

## Alertas de movimiento

- `MovementAnalyzer` (lógica pura, probada) aprende la posición en reposo durante
  15 s y alerta si el teléfono se inclina más de 15° o recibe un tirón de más de
  2 m/s², como máximo una vez por minuto.
- `EventDispatcher` entrega cada `CameraEvent` a todos los maestros, probando sus
  direcciones (Wi-Fi primero) y reintentando cada 15 s durante 10 minutos.
- `AlertReceiverService` (servicio `specialUse`) mantiene el servidor para recibir
  alertas, guarda las últimas 50 y muestra una notificación de prioridad alta.
  Vuelve a iniciarse al reiniciar el teléfono (`BootReceiver`).
- Un maestro con ahorro de batería activo puede recibir tarde en reposo profundo
  (Doze); la pestaña Alertas pide la exención.

## Batería

La pestaña Hogar consulta cada 30 s el `NodeStatus` de cada dispositivo con
servidor activo (cámaras transmitiendo, maestros, grabador) y ordena de menor a
mayor batería, con aviso "Cargar pronto" a partir de 20 % sin cargar. Un visor
sin servidor no expone su batería.

El porcentaje aparece junto a un ícono verde con rayo cuando carga y un ícono
ámbar cuando queda poca batería sin cargar. `DeviceBattery` consulta el estado
de `ACTION_BATTERY_CHANGED`, porque `BatteryManager.isCharging` devuelve falso
en los dos teléfonos probados aunque Android indique que están cargando.

## Nombres de dispositivos

Hogar permite renombrar cualquier miembro con el botón de lápiz. El nombre se
guarda en `Member.name` y se replica por el directorio, incluso si el dispositivo
está desconectado al editarlo. Al recibir el cambio, el teléfono actualiza también
`LocalNode.name` para conservarlo en sus siguientes anuncios. Los teléfonos deben
estar actualizados para que sus anuncios no repongan el nombre anterior.

Las nuevas alertas usan el nombre actual sin reiniciar la cámara. El historial
conserva el nombre que tenía el dispositivo al producirse cada evento.

## Conexión permanente

Para que un celular sea alcanzable siempre, en cada teléfono:

1. **Tailscale como VPN siempre activa.** Ajustes → Conexiones/Red → VPN →
   Tailscale → "VPN siempre activa". Android la levanta al encender y la reconecta
   si se cae. Dejar desactivado "Bloquear conexiones sin VPN": Tailscale solo
   enruta la tailnet y el bloqueo cortaría el resto de internet si Tailscale cae.
   Por adb: `settings put secure always_on_vpn_app com.tailscale.ipn` (se aplica al
   reiniciar). Verificado en un Samsung A10: Tailscale volvió solo tras reiniciar.
2. **Tailscale y CamNode sin ahorro de batería** (`dumpsys deviceidle whitelist
   +<paquete>` o desde Ajustes). En HONOR/Huawei además "Inicio de aplicaciones" en
   manual para ambas apps.
3. Si el teléfono tiene PIN, Tailscale conecta tras el primer desbloqueo después
   de reiniciar (Android no expone las apps antes de desbloquear).

CamNode muestra el estado de Tailscale y atajos a esos ajustes en Transmitir y
Alertas (`TailscaleSetup`).

Android prohíbe iniciar un servicio de cámara al encender. CamNode recuerda si la
cámara quedó encendida (`cameraShouldRun`, solo se borra al detenerla el usuario)
y, tras un reinicio o una actualización, muestra "La cámara se detuvo · Toca para
reanudar" (`CameraResumeReceiver`). El maestro de alertas sí vuelve solo.

## Grabador de escritorio

```sh
./gradlew :recorder:installDist
recorder/build/install/camnode-recorder/bin/camnode-recorder join "camnode://join?..." --name "PC sala" --dir ~/CamNode --days 7
recorder/build/install/camnode-recorder/bin/camnode-recorder run
```

- Requiere Java 17+ y `ffmpeg`. El enlace sale de Hogar → Invitar dispositivo →
  Compartir enlace. El estado vive en `~/.config/camnode-recorder/state.json`.
- Se anuncia como `RECORDER` y maestro, sincroniza el directorio cada 30 s y
  mantiene un `ffmpeg -c copy` por cámara: segmentos MP4 fragmentados de 5 minutos
  en `<dir>/<cámara>_<id>/AAAA-MM-DD_HH-MM-SS.mp4`, reproducibles aunque se corte
  la luz. Borra MP4 con más días que `--days`.
- Las alertas se guardan en `<dir>/events.jsonl` y lanzan `notify-send`.
- Para que arranque solo, crear un servicio de usuario de systemd con
  `ExecStart=.../camnode-recorder run`.

## Interfaz

- Tema oscuro fijo de consola de seguridad (`CamNodeTheme`), sin color dinámico,
  con colores de estado propios (`CamNodeTheme.status`: en vivo, en línea,
  advertencia).
- Componentes en `core:designsystem`: `CamNodeCard`, `StatusPill` (punto
  pulsante en vivo), `MetricChip`, `SettingRow`, `IconTile`, `NoticeCard`,
  `EmptyState`, `CamNodeButton`, `SectionHeader`. Las features no definen
  colores ni estilos propios.
- Íconos Material Symbols Rounded en `CamNodeIcons`. Para agregar uno, sumarlo a
  `tools/import_icons.py` y ejecutar `python3 tools/import_icons.py`.
- Navegación con pestañas Cámaras, Alertas, Transmitir y Hogar: barra inferior en
  vertical y riel lateral en pantallas anchas. Rotar no corta el video
  (`configChanges`).
- Cuadrícula y detalle comparten `ViewerViewModel` en la actividad. Las conexiones
  RTSP pertenecen a `PlaybackSessions`; las pantallas solo conectan superficies.
  Una conexión sin pantallas se conserva silenciada durante 5 s para cambios
  rápidos de pestaña. Después se libera. Al pasar la app al fondo se detienen
  todos los transportes inmediatamente. Cambiar de hogar libera las conexiones.
- Cámaras tiene tres formatos seleccionables en la barra superior. Tarjetas
  separa el nombre y las métricas del video. Mosaico adapta el número de columnas
  al ancho disponible y usa indicadores compactos. Monitor destaca una cámara y
  coloca las demás en una tira inferior o lateral según el espacio. La cámara
  principal no se repite entre las miniaturas.
- `CameraLayout` se guarda en el DataStore local mediante `CameraSettingsRepository`.
  La preferencia se conserva al volver a abrir la app y no modifica el contrato
  entre teléfonos ni la configuración de transmisión. Cambiar el formato reutiliza
  las conexiones existentes en `PlaybackSessions`.

## Bibliotecas nuevas respecto a Birmind

| Biblioteca | Versión | Motivo |
| --- | --- | --- |
| RootEncoder + RTSP-Server (JitPack) | 2.8.1 / 1.4.3 | Codificación H.264 por hardware y servidor RTSP multi-cliente sin preview. JitPack está restringido a `com.github.pedroSG94` |
| Media3 ExoPlayer RTSP + ui-compose | 1.11.1 | Reproducción RTSP con buffer corto y `ContentFrame` en Compose |
| Ktor server CIO | 3.5.2 | API de control embebida. Misma versión de `ktor-network` que exige RTSP-Server |
| ZXing core | 3.5.4 | Generar y leer QR sin Google Play services, útil en teléfonos viejos |

RTSP-Server se compila con Kotlin 2.3.21. Kotlin 2.2.10 lee metadata de una
versión adelante. Antes de actualizar dependencias, aplicar la política de
`Birmind/docs/bibliotecas.md`.

## Pendiente y límites conocidos

- Validación con un segundo teléfono en la misma red: la cuadrícula y el detalle
  del visor reproduciendo una cámara remota, los botones de lente y rotación, y
  la notificación en un teléfono maestro. Cada pieza se probó por separado desde
  la PC (API, stream, rotación, grabador, alerta de movimiento hasta la PC).
- Grabación solo de eventos o con detección de movimiento por imagen.
- El bitrate real medido en el A02 (2.5-2.8 Mbps) supera el configurado para 720p
  (1.5 Mbps); revisar el modo de control de bitrate del codificador.
- El umbral de gama baja (≤2.5 GB) no incluye celulares como el A02 (3.5 GB,
  Snapdragon 450), que siguen arrancando en 720p con audio. Medir 540p/15 fps y el
  costo del audio antes de cambiar los valores por defecto.
- Ver grabaciones desde la app (hoy se abren en la PC).
- Cada mosaico de la cuadrícula abre un decodificador. Los teléfonos viejos
  suelen soportar de 4 a 8 simultáneos.
- El APK debug pesa 63 MB porque incluye librerías nativas de las cuatro
  ABI. Considerar `splits.abi` como en LeonidaAtlas.
- Próximas features previstas: detección de movimiento con notificaciones,
  grabación de clips, rotación del secreto del hogar.

### Prueba manual de humo

1. Dos teléfonos en la misma Wi-Fi. En A: crear hogar → Transmitir → Iniciar transmisión.
2. En B: unirse escaneando el QR de A → pestaña Cámaras. A debe aparecer en vivo con chip "Wi-Fi" y batería.
3. En B, abrir A y tocar el botón de cambiar lente. A debe pasar a la cámara frontal sin cortar el video.
4. Activar Tailscale en ambos y apagar la Wi-Fi de B. El chip debe cambiar a "Tailscale".
5. Apagar la pantalla de A durante 10 minutos. B debe seguir viendo video.
6. Copiar el enlace RTSP desde Transmitir y abrirlo en VLC desde una PC.
7. Desde un tercer teléfono C unido con el QR de B: C debe ver A sin haber escaneado A.
