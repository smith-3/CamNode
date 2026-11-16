# Guía de agentes — CamNode Android

- Este proyecto es la app CamNode (`com.fragmind.camnode`). La regla del
  directorio padre que limita el trabajo a TecnoTime no define el alcance de
  esta app. El resto de reglas generales sí aplican: no hacer commits, publicar
  ni descartar cambios sin autorización, e informar en español.
- Seguir `/home/smith/.codex/RTK.md`: prefijar los comandos con `rtk`.
- Antes de cambiar comportamiento, leer `docs/arquitectura.md`. Ahí están los
  módulos, las reglas de dependencia y el contrato HTTP/RTSP entre teléfonos.
- Cambios al contrato entre teléfonos: campos nuevos opcionales, o una ruta
  `/v2`. Teléfonos con versiones distintas conviven en el mismo hogar.
- Nuevas capacidades en `feature/<nombre>` con el plugin `camnode.android.feature`.
  Las features no dependen entre sí.
- UI: usar los componentes, colores (`CamNodeTheme.status`) e íconos
  (`CamNodeIcons`) de `core:designsystem`. Los íconos nuevos se agregan con
  `tools/import_icons.py`, no a mano.
- Validar la UI en dispositivo con capturas (`adb exec-out screencap`) en
  vertical y horizontal, seleccionando el dispositivo con `-s`.
- `core:model`, `core:control` y `recorder` son JVM puro: no usar APIs de Android
  ahí. El contrato entre nodos vive solo en `core:control`.
- Verificar con `./gradlew test lint assembleDebug :recorder:installDist`. Las propiedades del dominio
  (`Directory.merge`, `InviteCodec`) se prueban en `core:model` sin Android.
