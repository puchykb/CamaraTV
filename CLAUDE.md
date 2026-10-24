# CamaraTV

App para Android TV (probada en el TV box Flow de Personal) que muestra cámaras de seguridad en vivo: varias cámaras, PTZ, audio, zoom digital, búsqueda en la red y avisos de movimiento encima de otras apps. Se reparte como APK a otras personas, así que **no puede llevar datos ni contraseñas de nadie**.

## Compilar

Desde esta carpeta (PowerShell):

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
```

APK: `app\build\outputs\apk\release\app-release.apk`. Subir `versionCode`/`versionName` en `app/build.gradle.kts` en cada versión.

## Versiones (no borrar nunca)

El usuario quiere poder volver a cualquier versión anterior. En cada versión nueva:

1. Subir `versionCode`/`versionName`, compilar.
2. Copiar el APK a `..\CamaraTV-versiones\CamaraTV-vX.Y-<destino>.apk` (nunca sobrescribir ni borrar los anteriores).
3. `git commit` + `git tag -a vX.Y` en este repo.

Estado actual:
- **v2.0** (tag `v2.0`) → **TV box del usuario**. Firma debug, siembra su cámara desde `camera.properties`. Se compila desde `..\CamaraTV-v2build\` (copia de trabajo de v2.0 que sí tiene `camera.properties`; no se versiona en git).
- **v3.0** (tag `v3.0`) → **para compartir** con amigos. Firma propia, sin credenciales.

Las dos firmas son distintas: pasar un equipo de v2 a v3 requiere desinstalar primero.

## Firma

La firma de release está **fuera del proyecto**, en `..\CamaraTV-firma\` (`camaratv-release.jks` + `keystore.properties` con la contraseña). `app/build.gradle.kts` la lee de ahí; si no existe, firma con la clave debug. Sin ese archivo no se pueden publicar actualizaciones que se instalen encima: hay que tener copia de seguridad.

## Instalar en un TV box sin ADB

Con la app **Downloader** desde un servidor HTTP en la PC:

```powershell
python -m http.server 8000 --bind <IP de la PC> --directory <carpeta que contenga solo el APK>
```

y en Downloader: `http://<IP de la PC>:8000/<archivo>.apk`. La primera vez hay que activar "Mostrar sobre otras apps" para los avisos.

Para probar sin TV: emulador `Medium_Phone_API_35` (`emulator -avd Medium_Phone_API_35 -no-window`), `adb install -r`, capturas con `adb shell screencap`. El emulador llega a la LAN por NAT pero no recibe multicast (WS-Discovery no se puede probar ahí).

## Tipos de cámara (`CameraType`)

- **XMEYE** (XMEye/iCSee, Xiongmai, TCP 34567): `DvripClient` + `VideoDecoder` (MediaCodec) + `AudioPlayer` (G.711). Único tipo con avisos de movimiento.
- **ONVIF** (Hikvision, Dahua, Tapo, Reolink, Uniview…): `OnvifClient` (SOAP + WS-Security digest, compensa el reloj de la cámara con GetSystemDateAndTime) pide perfiles y URL RTSP; se reproduce con Media3 ExoPlayer (RTSP sobre TCP). PTZ con ContinuousMove/Stop.
- **RTSP** (URL manual): ExoPlayer directo; usuario/contraseña se insertan en la URL.

## Arquitectura (`app/src/main/java/com/pux/camaratv/`)

- `LiveSession.kt` — interfaz común de reproducción (`XmeyeSession`, `RtspSession`) + `PtzDirection`.
- `DvripClient.kt` — protocolo DVRIP/Sofia: login (hash "Sofia" = MD5 plegado a 8 chars), keep-alive 1006, SystemInfo 1020, OPMonitor (1413 claim + 1410 start, datos 1412), PTZ 1400 (Preset 65535 = mover, -1 = parar), alarmas Guard 1500 / eventos 1504.
  Tramas: `00 00 01 FC/FE` I-frame (cabecera 16, codec 2=H264 3=H265), `FD` P-frame (8), `FA` audio (8, codec 14=G.711A 10=G.711U, 8 kHz), `F9` info.
- `OnvifClient.kt` — cliente ONVIF mínimo (DOM con namespaces).
- `MainActivity.kt` — vista en vivo en un TextureView (zoom digital por escala/traslación), control remoto, menú.
- `CameraStore.kt` — `Camera`/`CameraType`, lista en SharedPreferences (JSON).
- `CamerasActivity` / `CameraEditActivity` / `ScanActivity` — configuración; la búsqueda combina WS-Discovery (ONVIF), puerto 34567 y puerto 554.
- `MotionService.kt` — servicio en primer plano, una conexión de alarmas por cámara XMEye, banner overlay.
- `AlertSettings.kt` — avisos siempre / de noche / pausados / desactivados. `BootReceiver.kt` — arranque.

Dependencias: solo Media3 (`media3-exoplayer`, `media3-exoplayer-rtsp` 1.4.1). compileSdk 34, minSdk 21, AGP 8.7.3, Kotlin 2.0.0, Gradle 8.9.

## Pendiente / ideas

- Avisos de movimiento para cámaras ONVIF (eventos PullPoint).
- Probar ONVIF/RTSP con cámaras reales de varias marcas (solo se probó contra una cámara ONVIF simulada).
- App para Samsung TV (Tizen).
