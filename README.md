# CamaraTV

Mirá tus cámaras de seguridad en la tele. App para **Android TV** (TV box, Google TV, Chromecast con Google TV, Smart TV con Android) pensada para manejarse con el control remoto.

## Qué hace

- 📺 **Video en vivo** a pantalla completa, con **audio**.
- 🎮 **Cámaras PTZ**: las flechas del control mueven la cámara.
- 🔍 **Zoom digital** (hasta 4x) y recorrido de la imagen ampliada.
- 📷 **Varias cámaras**: cambiás con CH+/CH-, con los números 1-9 o desde el menú.
- 🔎 **Búsqueda automática** de cámaras en tu red.
- 🔔 **Avisos de movimiento** que aparecen encima de lo que estés viendo (YouTube, Netflix, la tele…), con opción de abrir la cámara al instante. Se pueden pausar, dejar solo de noche o desactivar.

## Cámaras compatibles

| Tipo | Marcas / apps | Video | PTZ | Avisos |
|---|---|:-:|:-:|:-:|
| **XMEye / iCSee** | Cámaras y grabadores Xiongmai genéricos (apps XMEye, iCSee, XMEye Pro) | ✅ | ✅ | ✅ |
| **ONVIF** | Hikvision, Dahua, Imou, Tapo, Reolink, Uniview y la mayoría de cámaras IP | ✅ | ✅ | ✅* |
| **RTSP** | Cualquier cámara que dé una URL `rtsp://` | ✅ | — | — |

> \* En ONVIF los avisos necesitan que la cámara tenga la **detección de movimiento activada** (en su app o página web) y que ofrezca eventos ONVIF; al tocar "Probar conexión" la app indica si la cámara tiene avisos o no. Si detecta personas, el aviso dice "Persona detectada".
> Las cámaras que funcionan **solo por la nube** (Ring, Blink, Wyze, la mayoría de Ezviz) no tienen video local y no son compatibles.
> En **Tapo** hay que crear una "cuenta de cámara" en la app Tapo (Configuración avanzada) para habilitar ONVIF/RTSP.

## Instalación

1. Bajá el APK desde **[Releases](../../releases/latest)**.
2. En el TV instalá la app **Downloader** (Google Play) y abrí el link directo del APK, o copialo con un pendrive.
3. Permití "instalar apps desconocidas" para Downloader cuando lo pida.
4. La primera vez, activá **"Mostrar sobre otras apps"** para que funcionen los avisos de movimiento.

## Uso con el control remoto

| Tecla | Acción |
|---|---|
| Flechas (mantener) | Mover la cámara (PTZ) |
| OK | Cambiar calidad alta / baja |
| Mantener OK o Menú | Menú: zoom, cambiar cámara, avisos, configurar |
| CH+ / CH- o 1-9 | Cambiar de cámara |
| En modo zoom: OK / flechas / Atrás | Más zoom / recorrer / salir |

Para agregar cámaras: **Mantener OK → Configurar cámaras → Buscar cámaras en la red** (o agregarla a mano con su IP).

## Privacidad

- La app se conecta **solo a tus cámaras, dentro de tu red**. No usa servidores externos ni la nube.
- Usuarios y contraseñas de las cámaras quedan guardados únicamente en el TV.

## Compilar

Requiere Android Studio (o JDK 17 + Android SDK 34).

```bash
./gradlew assembleRelease
```

Sin la clave de firma del autor, el APK de release se firma con la clave de depuración.
