# CamNode

Convierte teléfonos Android viejos en cámaras de seguridad privadas. Cada
teléfono del hogar puede transmitir video o ver las demás cámaras. En la misma
Wi-Fi se encuentran solos; fuera de casa se conectan por Tailscale. No hay
servidor ni nube intermedia.

## Requisitos

- Android 9 (API 28) o superior en cada teléfono.
- Misma Wi-Fi para usar la app dentro de casa.
- Para ver las cámaras desde fuera: la app de Tailscale conectada en cada
  teléfono, con la misma cuenta o tailnet.
- Para uso continuo, el teléfono cámara conectado al cargador.

## Uso

1. En el primer teléfono: **Crear mi hogar**.
2. En cada teléfono adicional: **Unirme con una invitación** y escanear el
   código de **Hogar → Invitar dispositivo** de un teléfono que ya esté en el
   hogar. Abrir el enlace `camnode://join?...` desde otra app también funciona.
3. En los teléfonos cámara: pestaña **Transmitir** → **Iniciar transmisión**.
   Sigue transmitiendo con la pantalla apagada o mientras usas otras apps.
   Revisa la tarjeta **Funcionamiento continuo** para que el sistema no la cierre.
4. En los teléfonos visores: pestaña **Cámaras**. Toca una cámara para verla en
   pantalla completa, cambiar de lente o activar el sonido.

5. En el teléfono que llevas contigo: pestaña **Alertas** → **Este es un
   teléfono maestro**. Recibirás una notificación si alguien mueve una cámara.
6. En **Hogar** ves la batería de cada dispositivo, con los que hay que cargar
   arriba.

7. **Hogar → Bloqueo con PIN**: pide un PIN de 4 dígitos para abrir la app y,
   si quieres, la disfraza de calculadora, reloj, calendario o notas, con tres
   íconos a elegir para cada uno. La
   notificación de la cámara sigue mostrando "CamNode".

8. **Hogar → Idioma**: español, inglés, portugués, francés, alemán, hindi,
   indonesio, árabe, chino o ruso; por defecto, el del teléfono.

Cada cámara también se puede ver en VLC con el botón de copiar enlace de la
tarjeta **Conexión**.

## Grabar en una PC

```sh
./gradlew :recorder:installDist
recorder/build/install/camnode-recorder/bin/camnode-recorder join "<enlace de Hogar → Invitar → Compartir>" --dir ~/CamNode
recorder/build/install/camnode-recorder/bin/camnode-recorder run
```

Necesita Java 17+ y `ffmpeg`. Graba todas las cámaras en segmentos de 5 minutos,
borra lo que tenga más de 7 días y recibe las alertas de movimiento.

## Desarrollo

```sh
./gradlew test            # Pruebas unitarias de todos los módulos
./gradlew lint            # Lint de Android
./gradlew assembleDebug   # APK en app/build/outputs/apk/debug/
```

La arquitectura, los contratos entre teléfonos y las decisiones de seguridad
están en [`docs/arquitectura.md`](docs/arquitectura.md).
