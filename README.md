# MiAudio

Reproductor Android con estética oscura tipo Spotify para escuchar **el audio de vídeos de YouTube**, podcasts en listas y música local, con biblioteca para **Android Auto**.

## Qué hace

- **Búsqueda en YouTube** con paginación igual que la web (40 resultados de entrada y sigue bajando de 20 en 20, sin repetidos).
- **Listas de YouTube completas** como álbumes: se pueden escuchar enteras o guardar dentro de una lista propia; las listas mixtas se agrupan por álbum de origen.
- **Audio local** del móvil con sus listas: memoria interna, tarjeta SD y USB; se puede elegir canción a canción o **carpeta entera** (con «ver por carpetas»).
- **Recomendados y reproducción continua** al terminar una cola (desactivable en Ajustes).
- **«Seguir escuchando»** en la portada con lo último que sonó.

## Pensado para podcasts en el coche

- **Arranca solo al conectar Android Auto** por el último elemento reproducido, en el minuto exacto donde se quedó (interruptor en Ajustes).
- **Caché sin cobertura**: mientras suena un episodio se descargan los dos siguientes; sin datos sigue la serie. Si ya está descargado, no gasta datos. Límite configurable (1,5 GB, borra lo más antiguo) y vaciado manual.
- **Marca de escuchados**: check verde y título atenuado en lo ya oído (85 % de la duración o final), también visible en el coche.
- **Posición por lista**: cada lista guarda el capítulo por el que va y cada episodio recuerda su minuto. Al abrir una lista se puede **seguir donde lo dejé** o empezar desde el principio.

## Mandos del coche

- **Anterior / siguiente** y **retroceder 15 s / avanzar 30 s** en la pantalla de Android Auto y en el reproductor de la app.

## Compilar

Requisitos: JDK 17+, Android SDK con `platforms/android-35` y `build-tools/35.0.0`, Gradle 8.13.

```
# local.properties (no versionado)
sdk.dir=/ruta/al/android-sdk
# firma opcional (sin esto el release sale sin firmar)
miaudio.storeFile=keystore/miaudio.jks
miaudio.storePassword=...
miaudio.keyAlias=...
miaudio.keyPassword=...
```

```
gradle assembleRelease
```

APK en `app/build/outputs/apk/release/app-release.apk`.

## Dependencias

- [Media3](https://developer.android.com/media/media3) 1.5.1 (ExoPlayer + `MediaLibraryService` para Android Auto).
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) v0.27.1 para leer YouTube.
- Coil 2.7.0 para las carátulas y Material 3 para la interfaz.

## Aviso

Proyecto personal para uso propio. No está publicado en Play Store (se instala como APK). Para que aparezca en Android Auto puede hacer falta activar «Fuentes desconocidas» en el modo desarrollador de Android Auto. La extracción de YouTube depende de NewPipeExtractor: si YouTube cambia algo, hay que actualizar la librería.
