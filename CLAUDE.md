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

## GitHub

Repo público: https://github.com/puchykb/CamaraTV — cada versión para compartir se publica como Release con el APK adjunto (`gh release create vX.Y <apk>`).

**Cuenta: solo `puchykb`.** En esta PC hay otras cuentas de GitHub de otros proyectos (discoveryparaguay, webmaster-motorhub) en la config global de git y en el administrador de credenciales de Windows: no tocarlas ni usarlas. Este repo tiene config local:
- `user.name=puchykb`, `user.email=267768372+puchykb@users.noreply.github.com`
- `credential.helper` vaciado y reemplazado por `gh auth git-credential` (gh logueado como puchykb).

Antes de un push, verificar con `printf 'protocol=https\nhost=github.com\n\n' | git credential fill` (en Bash) que el usuario sea `puchykb`. El tag `v2.0` (versión del TV del usuario) no se publica.

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

- **XMEYE** (XMEye/iCSee, Xiongmai, TCP 34567): `DvripClient` + `VideoDecoder` (MediaCodec) + `AudioPlayer` (G.711). Avisos por Guard/1504.
- **ONVIF** (Hikvision, Dahua, Tapo, Reolink, Uniview…): `OnvifClient` (SOAP + WS-Security digest, compensa el reloj de la cámara con GetSystemDateAndTime) pide perfiles y URL RTSP; se reproduce con Media3 ExoPlayer (RTSP sobre TCP). PTZ con ContinuousMove/Stop.
  Avisos (desde v3.1): `watchMotion` = CreatePullPointSubscription + PullMessages (long-poll 10 s) + Renew cada 30 s, con cabeceras WS-Addressing y los ReferenceParameters de la suscripción (Dahua los exige). Topics con "Motion" → VideoMotion, People/Human/Person → HumanDetect; se ignora `PropertyOperation="Initialized"`. Hasta 3 pulls fallidos seguidos se reintentan sin perder la suscripción.
- **RTSP** (URL manual): ExoPlayer directo; usuario/contraseña se insertan en la URL. Sin avisos (RTSP no tiene eventos).

## Arquitectura (`app/src/main/java/com/pux/camaratv/`)

- `LiveSession.kt` — interfaz común de reproducción (`XmeyeSession`, `RtspSession`) + `PtzDirection`.
- `DvripClient.kt` — protocolo DVRIP/Sofia: login (hash "Sofia" = MD5 plegado a 8 chars), keep-alive 1006, SystemInfo 1020, OPMonitor (1413 claim + 1410 start, datos 1412), PTZ 1400 (Preset 65535 = mover, -1 = parar), alarmas Guard 1500 / eventos 1504.
  Tramas: `00 00 01 FC/FE` I-frame (cabecera 16, codec 2=H264 3=H265), `FD` P-frame (8), `FA` audio (8, codec 14=G.711A 10=G.711U, 8 kHz), `F9` info.
- `OnvifClient.kt` — cliente ONVIF mínimo (DOM con namespaces).
- `MainActivity.kt` — vista en vivo en un TextureView (zoom digital por escala/traslación), control remoto, menú.
- `CameraStore.kt` — `Camera`/`CameraType`, lista en SharedPreferences (JSON).
- `CamerasActivity` / `CameraEditActivity` / `ScanActivity` — configuración; la búsqueda combina WS-Discovery (ONVIF), puerto 34567 y puerto 554.
- `MotionService.kt` — servicio en primer plano, un `Watcher` por cámara XMEye u ONVIF (reintento 10 s XMEye / 60 s ONVIF / 30 min si la cámara no tiene eventos), banner overlay. Los errores se registran en logcat con el tag `MotionService`.
- `MainActivity` corta el stream en `onStop` (si no, seguía el video/audio de fondo al apretar HOME) y lo retoma en `onStart`.
- `AlertSettings.kt` — avisos siempre / de noche / pausados / desactivados. `BootReceiver.kt` — arranque.

Dependencias: solo Media3 (`media3-exoplayer`, `media3-exoplayer-rtsp` 1.4.1). compileSdk 34, minSdk 21, AGP 8.7.3, Kotlin 2.0.0, Gradle 8.9.

## Pendiente / ideas

- Probar ONVIF/RTSP (video, PTZ y avisos) con cámaras reales de varias marcas (solo se probó contra una cámara ONVIF simulada: `onvif_mock.py` en el scratchpad de la sesión).
- La v2.0 (TV del usuario) tiene el mismo problema de seguir transmitiendo al apretar HOME; se corrigió recién en v3.1.
- App para Samsung TV (Tizen).
