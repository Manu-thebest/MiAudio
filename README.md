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
- **Caché sin cobertura**: mientras suena una canción se descargan **las tres siguientes** de la lista, en su propio hilo para no frenar las búsquedas; sin cobertura sigue la serie sin pararse. Si ya está descargado, no gasta datos. Límite configurable (1,5 GB, borra lo más antiguo) y vaciado manual.
- **Buffer largo**: la app guarda hasta 1 minuto por delante como mínimo y hasta 10 minutos si la red va bien, así un túnel o un tramo sin cobertura no corta la canción en curso.
- **Marca de escuchados**: check verde y título atenuado en lo ya oído (85 % de la duración o final), también visible en el coche.
- **Posición por lista**: cada lista guarda el capítulo por el que va y cada episodio recuerda su minuto. Al abrir una lista se puede **seguir donde lo dejé** o empezar desde el principio.

## Copia en Google Drive

En **Ajustes → Copia en Google Drive**:

- **Exportar listas, posiciones y enlaces**: crea un fichero `miaudio-AAAA-MM-DD.json` con las listas, su orden, el índice de cada lista, la posición de cada audio, las marcas de escuchado y la última cola. No incluye audio ni descargas. En el selector se elige **Drive** como carpeta y queda en la nube.
- **Importar desde Google Drive**: elige ese fichero desde Drive y restaura lo que falte. No pisa lo que ya hay: las listas con el mismo nombre se dejan como están y las posiciones solo se rellenan si aquí estaban a cero.

No hace falta cuenta ni permiso especial de Google: se usa el selector de ficheros del móvil, donde Drive ya aparece.

- **Copia automática en la nube**: se elige una carpeta de Drive una sola vez. Desde entonces la copia se reescribe sola al salir de la app (`miaudio-copia.json`), sin volver a tocar nada.
- **Desactivarla**: en Ajustes, sección «Copia de seguridad», hay dos entradas: **«Copia en la nube»** (estado, guardar ahora, cambiar de carpeta) y **«Quitar la copia en la nube»** (solo desactivar, o desactivar y borrar el fichero de la nube). Al desactivar se suelta también el permiso de esa carpeta. Abajo del todo de Ajustes se ve la versión instalada.

## Tema claro u oscuro

En Ajustes, sección «Apariencia» → **Tema**: **Oscuro** (el de siempre), **Claro** o **Según el móvil** (sigue el modo dia/noche del sistema). El cambio se ve al momento sin cerrar la app, y se recuerda al volver a abrirla.

## Listas

- **Reproducir en orden aleatorio**: mezcla la lista (también la carpeta de audios) y esa cola mezclada es la que ve el coche.
- **Reordenar o quitar**: mantener pulsada una canción y elegir subir, bajar, mover al final o quitar. También desde «Reordenar o quitar canciones» de la propia lista, donde se puede mover una canción varias veces seguidas.
- **Marcar como escuchado / no escuchado** a mano, sin esperar al 85 %.
- **No enseñar lo ya escuchado**: oculta de las listas lo que ya está oído, con un acceso al final para volver a verlo.
- **Importar `.m3u` / `.m3u8`**: se lee la playlist, se empareja cada línea con el audio del móvil (por nombre de fichero o título, sin extensión) y se crea la lista. Las líneas que son enlaces `http` se añaden como streaming; lo que no se encuentra se cuenta y se avisa.
- **Quitar canciones que ya no están**: repasa las listas y borra las pistas locales que han desaparecido (fichero borrado o memoria desconectada). Los enlaces de YouTube no se tocan.

## Importar de Spotify

**Ajustes → Importar una lista de Spotify** (o compartiendo la lista desde Spotify con Mi Audio, como se comparte con WhatsApp):

1. Se pega el enlace de la lista (`open.spotify.com/playlist/...`).
2. La app lee el nombre y las canciones de esa lista **sin cuenta ni permisos de Spotify**: pide la página pública de la lista.
3. Busca cada canción en YouTube (título + artista), mira **12 resultados** y puntúa: **prefiere el audio oficial** (canal «… - Topic» de la discográfica, VEVO, «official audio», «audio oficial», lyric video), el título y la duración exactos y el canal del propio artista; y **hunde los conciertos y directos** («en directo», «live at», «en vivo», «festival», «unplugged», «session», «tour»), además de karaokes, covers, remixes, versiones aceleradas y audios de 1 hora.
4. Crea la lista con las canciones encontradas y avisa de cuántas no ha podido encontrar.

Detalles a tener en cuenta:

- Se leen las **primeras 50 canciones** de la lista: es lo que Spotify publica en esa página sin iniciar sesión.
- Tarda unos segundos por canción (una búsqueda de YouTube por tema) y se puede cancelar.
- Funciona con enlaces de lista y de álbum públicos. Las listas privadas (o «Tus me gusta») necesitan cuenta, así que no entran.

### Botón Aleatorio en el reproductor

Abajo del todo de la pantalla completa hay un **botón Aleatorio**, al alcance del pulgar (además de la fila «Reproducir en orden aleatorio» de cada lista y carpeta):

- Al activarlo se pone **verde** y lo que va sonando después sale al azar, sin cortar la canción actual. La línea de la cola lo indica («· aleatorio»).
- Al volver a tocarlo, la cola recupera su orden.

## Mandos del coche

- **Cambiar de lista desde el coche**: al elegir una canción dentro de una lista, el coche solo manda esa canción; la app la reconoce, carga **la lista entera** y empieza por ahí, así que el cambio de lista sí se aplica (antes seguía con lo que estaba sonando).
- **Anterior / siguiente** y **retroceder 5 s / avanzar 5 s** en la pantalla de Android Auto y en el reproductor de la app.

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
