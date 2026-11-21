# Brook — Reproductor de Música Android (Estilo Noctalia)

Aplicación Android nativa (**Kotlin + Jetpack Compose + AndroidX Media3**) inspirada en la estética de cristal ahumado y neón cian de **Noctalia**, diseñada para reproducir música en streaming **Opus 160 kbps** desde YouTube Music **sin anuncios** y **sin descargar archivos**.

## Características principales
- **Audio Opus 160 kbps directo (`itag 251`)**: máxima calidad gratis, cero anuncios y sin ocupar almacenamiento.
- **Reproducción en segundo plano**: `MediaSessionService` + `ExoPlayer` con controles en pantalla bloqueada, notificación y Bluetooth.
- **Estilo Noctalia**: cristal ahumado (`#101A22`), bordes cian (`#00F0FF`), barra de progreso ondulada animada y mini-ecualizador.
- **Conexión opcional con tu cuenta de YouTube Music**: carga tus *Me gusta*, playlists privadas e historial (cookies cifradas localmente con Android Keystore `AES-256-GCM`).
- **Auto-Radio infinita + Cola + Favoritas + Playlists**.
- **Sincronización con Noctalia PC (CachyOS)**: importa y exporta `music_library.json`.
- **Actualizador integrado**: comprueba, descarga e instala nuevas versiones de `Brook.apk` directamente desde **GitHub Releases**.

## Seguridad
- 3 clientes OkHttp aislados (API autenticada sin redirecciones, streaming de audio sin cookies y carátulas con validación de dominio por salto).
- `network_security_config.xml` con `cleartextTrafficPermitted="false"` y solo CAs del sistema.
- `MediaSession.Callback` restringido para impedir que apps externas inyecten URIs en `ExoPlayer`.
- `WebView` de inicio de sesión con `FLAG_SECURE`, sin acceso a `file://`/`content://` y borrado inmediato de caché/cookies en texto plano tras guardar en Keystore.

## Compilación local
```bash
./build-apk.sh
# O compilar e instalar por USB:
./build-apk.sh --install
```
