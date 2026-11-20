package cat.manu.miaudio

import android.Manifest
import android.content.ComponentName
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * Pantalla principal con estetica tipo Spotify: pestanas abajo, mini reproductor,
 * reproductor a pantalla completa y busqueda paginada igual que YouTube.
 */
class MainActivity : AppCompatActivity() {

    // vistas
    private lateinit var cabecera: TextView
    private lateinit var cajaBuscador: LinearLayout
    private lateinit var entrada: EditText
    private lateinit var recycle: RecyclerView
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniArt: ImageView
    private lateinit var miniTitulo: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var nav: BottomNavigationView
    private lateinit var sheet: View
    private lateinit var sheetArt: ImageView
    private lateinit var sheetTitulo: TextView
    private lateinit var sheetSub: TextView
    private lateinit var sheetBarra: SeekBar
    private lateinit var sheetTiempo: TextView
    private lateinit var sheetTotal: TextView
    private lateinit var sheetPlay: ImageButton
    private lateinit var sheetCola: TextView

    private lateinit var adaptador: Adaptador
    private val reloj = Handler(Looper.getMainLooper())

    // reproduccion
    private var controlador: MediaController? = null
    private var futuro: ListenableFuture<MediaController>? = null
    private var pendiente: Triple<List<MediaItem>, Int, Long>? = null
    private var origenActual: String? = null
    private var ajustandoBarra = false

    // seleccion multiple de audios locales para meterlos en una lista
    private var modoSeleccion = false
    private val seleccion = LinkedHashSet<String>()
    private var filaGuardar = -1

    // navegacion
    private sealed class Nivel {
        object Inicio : Nivel()
        object Buscar : Nivel()
        object Biblioteca : Nivel()
        object Ajustes : Nivel()
        object Locales : Nivel()
        object Carpetas : Nivel()
        data class Carpeta(val nombre: String) : Nivel()
        data class Lista(val nombre: String) : Nivel()
        data class Consulta(val q: String) : Nivel()
        data class YtLista(val url: String, val titulo: String) : Nivel()
    }

    private val pila = ArrayList<Nivel>()
    private val filas = ArrayList<Row>()
    private var version = 0
    private var cargando = false
    private var siguientePagina: Int? = null
    private var consultaActual = ""

    private val pedirPermiso = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        Lib.refrescarLocal(this)
        if (!concedido) Toast.makeText(this, R.string.sin_permiso, Toast.LENGTH_SHORT).show()
        pintar()
    }

    /** Guardar la copia: el selector deja elegir Google Drive como destino. */
    private val guardarCopia = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val texto = Copia.exportar(this)
            if (Copia.escribir(this, uri, texto)) {
                Toast.makeText(this, "Copia guardada", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No se pudo guardar la copia", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Leer la copia: el selector deja elegir un fichero de Drive. */
    private val abrirCopia = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val texto = Copia.leer(this, uri)
            if (texto == null) {
                Toast.makeText(this, "No se pudo leer el fichero", Toast.LENGTH_LONG).show()
            } else {
                val r = Copia.importar(this, texto)
                if (r == null) {
                    Toast.makeText(this, "Ese fichero no es una copia de MiAudio", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        "Copia importada: ${r.listasNuevas} listas nuevas, ${r.listasYaEstaban} ya estaban, " +
                            "${r.posiciones} posiciones y ${r.escuchados} marcas",
                        Toast.LENGTH_LONG
                    ).show()
                    pintar()
                }
            }
        }
    }

    // ------------------------------------------------------------------ ciclo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Lib.init(this)
        Lib.refrescarLocal(this)

        cabecera = findViewById(R.id.cabecera)
        cajaBuscador = findViewById(R.id.buscador)
        entrada = findViewById(R.id.searchInput)
        recycle = findViewById(R.id.list)
        miniPlayer = findViewById(R.id.miniPlayer)
        miniArt = findViewById(R.id.miniArt)
        miniTitulo = findViewById(R.id.miniTitulo)
        miniSub = findViewById(R.id.miniSub)
        miniPlay = findViewById(R.id.miniPlay)
        nav = findViewById(R.id.nav)
        sheet = findViewById(R.id.sheet)
        sheetArt = findViewById(R.id.sheetArt)
        sheetTitulo = findViewById(R.id.sheetTitulo)
        sheetSub = findViewById(R.id.sheetSub)
        sheetBarra = findViewById(R.id.sheetBarra)
        sheetTiempo = findViewById(R.id.sheetTiempo)
        sheetTotal = findViewById(R.id.sheetTotal)
        sheetPlay = findViewById(R.id.sheetPlay)
        sheetCola = findViewById(R.id.sheetCola)

        adaptador = Adaptador()
        recycle.layoutManager = LinearLayoutManager(this)
        recycle.adapter = adaptador
        recycle.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recycle.layoutManager as LinearLayoutManager
                val ultima = lm.findLastVisibleItemPosition()
                if (ultima >= filas.size - 3) siguientePagina?.let { cargarPagina(it) }
            }
        })

        nav.setOnItemSelectedListener { item ->
            pila.clear()
            when (item.itemId) {
                R.id.tabInicio -> mostrar(Nivel.Inicio)
                R.id.tabBuscar -> mostrar(Nivel.Buscar, enfocar = true)
                R.id.tabBiblioteca -> mostrar(Nivel.Biblioteca)
                R.id.tabAjustes -> mostrar(Nivel.Ajustes)
            }
            true
        }

        entrada.setOnEditorActionListener { _, _, _ ->
            buscar(entrada.text.toString())
            true
        }

        findViewById<View>(R.id.miniPlay).setOnClickListener {
            val c = controlador ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        miniPlayer.setOnClickListener { abrirSheet() }

        findViewById<View>(R.id.sheetAbajo).setOnClickListener { cerrarSheet() }
        sheetPlay.setOnClickListener {
            val c = controlador ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        findViewById<View>(R.id.sheetSiguiente).setOnClickListener {
            controlador?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
        }
        findViewById<View>(R.id.sheetAnterior).setOnClickListener {
            val c = controlador ?: return@setOnClickListener
            if (c.currentPosition > 4000) c.seekTo(0) else if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem()
        }
        findViewById<View>(R.id.sheetRetrocede).setOnClickListener {
            controlador?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_BACK)) it.seekBack() }
        }
        findViewById<View>(R.id.sheetAvanza).setOnClickListener {
            controlador?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) it.seekForward() }
        }
        findViewById<View>(R.id.miniParar).setOnClickListener { parar() }
        findViewById<View>(R.id.sheetParar).setOnClickListener { parar() }
        sheetBarra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progreso: Int, usuario: Boolean) {
                if (usuario) {
                    val dur = controlador?.duration ?: 0L
                    if (dur > 0) controlador?.seekTo(dur * progreso / 1000L)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                ajustandoBarra = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                ajustandoBarra = false
            }
        })

        permisoAudioSiHaceFalta()
        permisoNotificacionesSiHaceFalta()
        conectar()

        if (nav.selectedItemId != R.id.tabInicio) nav.selectedItemId = R.id.tabInicio
        if (pila.isEmpty()) mostrar(Nivel.Inicio)
        arrancarReloj()
    }

    override fun onDestroy() {
        reloj.removeCallbacksAndMessages(null)
        futuro?.let { runCatching { MediaController.releaseFuture(it) } }
        controlador = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheet.visibility == View.VISIBLE) {
            cerrarSheet()
            return
        }
        if (pila.size > 1) {
            pila.removeAt(pila.size - 1)
            pintarNivel(pila.last())
            return
        }
        super.onBackPressed()
    }

    // ------------------------------------------------------------- navegacion

    private fun mostrar(n: Nivel, enfocar: Boolean = false) {
        modoSeleccion = false
        seleccion.clear()
        pila.add(n)
        pintarNivel(n)
        cajaBuscador.visibility = if (n is Nivel.Buscar) View.VISIBLE else View.GONE
        if (enfocar) entrada.requestFocus()
    }

    private fun pintarNivel(n: Nivel) {
        version++
        cargando = false
        siguientePagina = null
        origenActual = null
        cajaBuscador.visibility = if (n is Nivel.Buscar) View.VISIBLE else View.GONE
        when (n) {
            is Nivel.Inicio -> nivelInicio()
            is Nivel.Buscar -> nivelBuscar()
            is Nivel.Biblioteca -> nivelBiblioteca()
            is Nivel.Ajustes -> nivelAjustes()
            is Nivel.Locales -> nivelLocales()
            is Nivel.Carpetas -> nivelCarpetas()
            is Nivel.Carpeta -> nivelCarpeta(n.nombre)
            is Nivel.Lista -> nivelLista(n.nombre)
            is Nivel.Consulta -> nivelConsulta(n.q)
            is Nivel.YtLista -> nivelYtLista(n.url, n.titulo)
        }
        recycle.scrollToPosition(0)
    }

    /** Vuelve a pintar la pantalla actual (tras cambios de datos). */
    private fun pintar() {
        if (pila.isNotEmpty()) pintarNivel(pila.last())
    }

    private fun fijar(nuevas: List<Row>, titulo: String? = null) {
        filas.clear()
        filas.addAll(nuevas)
        adaptador.notifyDataSetChanged()
        titulo?.let { cabecera.text = it }
    }

    private fun cargando(titulo: String) {
        fijar(listOf(Row(Row.Tipo.CARGA, getString(R.string.cargando))), titulo)
    }

    // ------------------------------------------------------------- pantallas

    private fun nivelInicio() {
        val hora = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val saludo = when (hora) {
            in 6..12 -> "Buenos días"
            in 13..20 -> "Buenas tardes"
            else -> "Buenas noches"
        }
        cabecera.text = saludo

        val out = ArrayList<Row>()
        Estado.cola()?.let { (cola, indice) ->
            val actual = cola.getOrNull(indice) ?: cola.first()
            out.add(
                Row.accion(
                    getString(R.string.seguir_escuchando),
                    "${actual.title} · ${cola.size} en la cola",
                    actual.art
                ) { reproducirCola(cola, indice, Estado.colaOrigen.ifBlank { null }) }
            )
        }
        out.add(Row.cabecera(getString(R.string.recomendados)))
        out.add(Row(Row.Tipo.CARGA, getString(R.string.cargando)))

        if (Estado.recientes().isNotEmpty()) {
            out.add(Row.cabecera(getString(R.string.busquedas_recientes)))
            Estado.recientes().take(4).forEach { q ->
                out.add(Row.accion(q, "Buscar en YouTube") { buscar(q) })
            }
        }
        if (Lib.store.listas.isNotEmpty()) {
            out.add(Row.cabecera(getString(R.string.tus_listas)))
            Lib.store.listas.take(4).forEach { l ->
                out.add(Row.carpeta(l.nombre, "${l.pistas.size} pistas") { mostrar(Nivel.Lista(l.nombre)) })
            }
        }
        fijar(out)

        val ultimo = Estado.ultimoYt
        if (ultimo.isBlank()) {
            quitarCargaYponer(
                Row.carpeta(
                    "Buscar algo en YouTube",
                    "Aquí saldrán tus recomendaciones",
                    "",
                    { nav.selectedItemId = R.id.tabBuscar }
                )
            )
            return
        }
        enSegundoPlano {
            val rec = runCatching { Yt.relacionados(ultimo, 10) }.getOrDefault(emptyList())
            runOnUiThread {
                if (pila.lastOrNull() !is Nivel.Inicio) return@runOnUiThread
                val idx = filas.indexOfFirst { it.tipo == Row.Tipo.CARGA }
                val nuevas = rec.map { t -> Row.pista(t, sub(t), null, Estado.esEscuchado(t.mediaId())) }
                if (idx >= 0) filas.removeAt(idx)
                filas.addAll(idx.coerceAtLeast(0), nuevas)
                if (nuevas.isEmpty()) {
                    filas.add(Row.carpeta("Sin recomendaciones", "Prueba a buscar música"))
                }
                adaptador.notifyDataSetChanged()
            }
        }
    }

    private fun quitarCargaYponer(r: Row) {
        val idx = filas.indexOfFirst { it.tipo == Row.Tipo.CARGA }
        if (idx >= 0) filas.removeAt(idx) else filas.add(r)
        if (idx >= 0) filas.add(idx, r)
        adaptador.notifyDataSetChanged()
    }

    private fun nivelBuscar() {
        cabecera.text = getString(R.string.buscar)
        val out = ArrayList<Row>()
        val rec = Estado.recientes()
        if (rec.isEmpty()) {
            out.add(Row.cabecera("Busca en YouTube"))
            out.add(Row.carpeta("Canciones, álbumes y listas", "Escribe arriba y pulsa buscar"))
        } else {
            out.add(Row.cabecera(getString(R.string.busquedas_recientes)))
            rec.forEach { q -> out.add(Row.accion(q, "Buscar en YouTube") { buscar(q) }) }
        }
        fijar(out)
    }

    private fun nivelBiblioteca() {
        cabecera.text = getString(R.string.tab_biblioteca)
        val out = ArrayList<Row>()
        out.add(
            Row.accion(
                getString(R.string.audio_movil),
                "${Lib.localTracks.size} audios",
                Lib.localTracks.firstOrNull()?.art ?: ""
            ) { mostrar(Nivel.Locales) }
        )
        out.add(Row.accion(getString(R.string.nueva_lista), "Crear una lista nueva") { preguntarNombre(null) })
        out.add(Row.cabecera(getString(R.string.tus_listas)))
        if (Lib.store.listas.isEmpty()) {
            out.add(Row.carpeta("Todavía no hay listas", "Crea una y añade canciones o listas de YouTube"))
        } else {
            Lib.store.listas.sortedBy { it.nombre.lowercase() }.forEach { l ->
                out.add(
                    Row.carpeta(l.nombre, "${l.pistas.size} pistas", l.pistas.firstOrNull()?.art ?: "") {
                        mostrar(Nivel.Lista(l.nombre))
                    }
                )
            }
        }
        fijar(out)
    }

    private fun nivelLocales() {
        cabecera.text = getString(R.string.audio_movil)
        val out = ArrayList<Row>()
        val pistas = Lib.localTracks
        filaGuardar = -1
        if (pistas.isEmpty()) {
            out.add(Row.carpeta(getString(R.string.sin_permiso), "Toca para pedir permiso") {
                permisoAudioSiHaceFalta()
            })
            fijar(out)
            return
        }
        if (modoSeleccion) {
            accionesSeleccion(out)
        } else {
            out.add(Row.accion(getString(R.string.reproducir_todo), "${pistas.size} audios") { reproducirTodo() })
            out.add(
                Row.accion(
                    "Elegir canciones para una lista",
                    "Marca las que quieras y guárdalas juntas"
                ) {
                    modoSeleccion = true
                    seleccion.clear()
                    pintar()
                }
            )
            out.add(
                Row.accion("Ver por carpetas", "${Lib.localCarpetas.size} carpetas con audio") {
                    mostrar(Nivel.Carpetas)
                }
            )
        }
        pistas.forEach { t ->
            val marcada = modoSeleccion && seleccion.contains(t.mediaId())
            val hecho = if (modoSeleccion) marcada else Estado.esEscuchado(t.mediaId())
            out.add(Row.pista(t, sub(t), null, hecho))
        }
        fijar(out)
        if (modoSeleccion) filaGuardar = 1  // fila 0 = cabecera, fila 1 = boton guardar
    }

    /** Filas de arriba del modo seleccion: guardar, todos y cancelar. */
    private fun accionesSeleccion(out: ArrayList<Row>) {
        out.add(Row.cabecera("Elegir canciones · ${seleccion.size} marcadas"))
        out.add(
            Row.accion(
                "Guardar ${seleccion.size} en una lista",
                if (seleccion.isEmpty()) "Primero marca alguna canción" else "Elige la lista de destino"
            ) {
                if (seleccion.isEmpty()) {
                    Toast.makeText(this, "No has marcado ninguna canción", Toast.LENGTH_SHORT).show()
                } else {
                    guardarSeleccion()
                }
            }
        )
        out.add(Row.accion("Marcar todos", "${Lib.localTracks.size} audios de este móvil") {
            seleccion.clear()
            Lib.localTracks.forEach { seleccion.add(it.mediaId()) }
            pintar()
        })
        out.add(Row.accion("Cancelar selección", "Volver a la lista normal") {
            modoSeleccion = false
            seleccion.clear()
            pintar()
        })
    }

    /** Marca o desmarca una cancion sin repintar toda la lista (no salta el scroll). */
    private fun marcarSeleccion(posicion: Int, t: Track) {
        val id = t.mediaId()
        val marcada = if (seleccion.contains(id)) {
            seleccion.remove(id)
            false
        } else {
            seleccion.add(id)
            true
        }
        if (posicion in filas.indices) {
            filas[posicion] = filas[posicion].copy(hecho = marcada)
            adaptador.notifyItemChanged(posicion)
        }
        actualizarContadorSeleccion()
        val i = filaGuardar - 1
        if (i in filas.indices && filas[i].tipo == Row.Tipo.CABECERA) {
            filas[i] = filas[i].copy(titulo = "Elegir canciones · ${seleccion.size} marcadas")
            adaptador.notifyItemChanged(i)
        }
    }

    private fun actualizarContadorSeleccion() {
        val i = filaGuardar
        if (i < 0 || i >= filas.size) return
        filas[i] = filas[i].copy(
            titulo = "Guardar ${seleccion.size} en una lista",
            sub = if (seleccion.isEmpty()) "Primero marca alguna canción" else "Elige la lista de destino"
        )
        adaptador.notifyItemChanged(i)
    }

    /** Mete las canciones marcadas en la lista que se elija. */
    private fun guardarSeleccion() {
        val elegidas = Lib.localTracks.filter { seleccion.contains(it.mediaId()) }
        if (elegidas.isEmpty()) return
        elegirLista { destino ->
            for (t in elegidas) Lib.store.anadir(destino, t)
            Toast.makeText(
                this,
                "${elegidas.size} canciones guardadas en \"$destino\"",
                Toast.LENGTH_LONG
            ).show()
            modoSeleccion = false
            seleccion.clear()
            pintar()
        }
    }

    private fun nivelCarpetas() {
        cabecera.text = "Carpetas con audio"
        val out = ArrayList<Row>()
        val mapa = Lib.localCarpetas
        if (mapa.isEmpty()) {
            out.add(
                Row.carpeta(
                    "No hay carpetas",
                    "No se ha encontrado audio; toca para volver a revisar"
                ) {
                    Lib.refrescarLocal(this)
                    pintar()
                }
            )
            fijar(out)
            return
        }
        mapa.forEach { (nombre, lista) ->
            out.add(
                Row.carpeta(nombre, "${lista.size} audios", lista.firstOrNull()?.art ?: "") {
                    mostrar(Nivel.Carpeta(nombre))
                }
            )
        }
        fijar(out)
    }

    private fun nivelCarpeta(nombre: String) {
        cabecera.text = nombre.substringAfterLast('/')
        val lista = Lib.pistasDeCarpeta(nombre)
        val out = ArrayList<Row>()
        filaGuardar = -1
        if (lista.isEmpty()) {
            out.add(Row.carpeta("Carpeta vacía", nombre))
            fijar(out)
            return
        }
        if (modoSeleccion) {
            accionesSeleccion(out)
        } else {
            out.add(Row.accion("Reproducir la carpeta", "${lista.size} audios") {
                reproducirCola(lista, 0)
            })
            out.add(Row.accion("Añadir la carpeta entera a una lista", "${lista.size} audios") {
                elegirLista { destino ->
                    for (t in lista) Lib.store.anadir(destino, t)
                    Toast.makeText(
                        this,
                        "${lista.size} audios de \"${nombre.substringAfterLast('/')}\" en \"$destino\"",
                        Toast.LENGTH_LONG
                    ).show()
                    pintar()
                }
            })
            out.add(Row.accion("Elegir canciones sueltas", "Marcar solo algunas de esta carpeta") {
                modoSeleccion = true
                seleccion.clear()
                pintar()
            })
        }
        lista.forEach { t ->
            val marcada = modoSeleccion && seleccion.contains(t.mediaId())
            val hecho = if (modoSeleccion) marcada else Estado.esEscuchado(t.mediaId())
            out.add(Row.pista(t, sub(t), null, hecho))
        }
        fijar(out)
        if (modoSeleccion) filaGuardar = 1  // fila 0 = cabecera, fila 1 = boton guardar
    }

    private fun nivelLista(nombre: String) {
        val l = Lib.store.buscar(nombre)
        cabecera.text = nombre
        if (l == null) {
            mostrar(Nivel.Biblioteca)
            return
        }
        origenActual = nombre
        val out = ArrayList<Row>()
        if (l.pistas.isEmpty()) {
            out.add(Row.carpeta("Lista vacía", "Añade canciones desde Buscar o Biblioteca"))
            fijar(out)
            return
        }
        val seguir = l.indice.coerceIn(0, l.pistas.size - 1)
        if (l.indice > 0 && seguir < l.pistas.size) {
            out.add(
                Row.accion(
                    "Seguir donde lo dejé",
                    "Pista ${seguir + 1} de ${l.pistas.size} · ${l.pistas[seguir].title}"
                ) { reproducirTodo(nombre, seguir) }
            )
            out.add(Row.accion("Reproducir desde el principio", "${l.pistas.size} pistas") {
                reproducirTodo(nombre, 0)
            })
        } else {
            out.add(
                Row.accion(getString(R.string.reproducir_todo), "${l.pistas.size} pistas") {
                    reproducirTodo(nombre, 0)
                }
            )
        }
        var grupoActual: String? = null
        l.pistas.forEach { t ->
            if (t.grupo != null && t.grupo != grupoActual) {
                grupoActual = t.grupo
                out.add(Row.cabecera("Álbum · ${t.grupo}"))
            }
            out.add(Row.pista(t, sub(t), t.grupo, Estado.esEscuchado(t.mediaId())))
        }
        fijar(out)
    }

    private fun nivelAjustes() {
        cabecera.text = getString(R.string.tab_ajustes)
        val out = ArrayList<Row>()
        out.add(Row.cabecera("Reproducción"))
        out.add(
            Row.accion(
                "Seguir con recomendados al acabar",
                if (Estado.seguirConRecomendados) "Activado" else "Desactivado"
            ) {
                Estado.seguirConRecomendados = !Estado.seguirConRecomendados
                pintar()
            }
        )
        out.add(Row.cabecera("Coche y sin cobertura"))
        out.add(
            Row.accion(
                "Al conectar al coche, seguir donde lo dejé",
                if (Estado.autoAlConectar) "Activado" else "Desactivado"
            ) {
                Estado.autoAlConectar = !Estado.autoAlConectar
                pintar()
            }
        )
        out.add(
            Row.accion(
                "Guardar audio para ir sin cobertura",
                if (Estado.cacheOffline)
                    "Activado · ${AudioCache.cuantos()} audios · ${mb(AudioCache.tamanoTotal())}"
                else "Desactivado"
            ) {
                Estado.cacheOffline = !Estado.cacheOffline
                pintar()
            }
        )
        out.add(
            Row.accion("Vaciar audio guardado", "${AudioCache.cuantos()} audios · ${mb(AudioCache.tamanoTotal())}") {
                AudioCache.vaciar()
                Toast.makeText(this, "Audio guardado borrado", Toast.LENGTH_SHORT).show()
                pintar()
            }
        )
        out.add(
            Row.accion(
                "Olvidar marcas de escuchado",
                "${Estado.escuchados().size} elementos marcados"
            ) {
                Estado.olvidarEscuchados()
                Toast.makeText(this, "Marcas borradas", Toast.LENGTH_SHORT).show()
                pintar()
            }
        )
        out.add(Row.cabecera("Copia en Google Drive"))
        out.add(
            Row.accion(
                "Exportar listas, posiciones y enlaces",
                "Se elige la carpeta: Drive, teléfono…"
            ) { guardarCopia.launch(Copia.nombreFichero()) }
        )
        out.add(
            Row.accion(
                "Importar desde Google Drive",
                "Solo enlaces, posiciones y estructura"
            ) { abrirCopia.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        )
        out.add(Row.cabecera("Datos"))
        out.add(
            Row.accion("Reindexar audio del móvil", "${Lib.localTracks.size} audios encontrados") {
                Lib.refrescarLocal(this)
                Toast.makeText(this, "Revisado: ${Lib.localTracks.size} audios", Toast.LENGTH_SHORT).show()
                pintar()
            }
        )
        out.add(Row.accion("Vaciar búsquedas recientes") {
            Estado.vaciarRecientes()
            Yt.olvidarCache()
            Toast.makeText(this, "Borrado", Toast.LENGTH_SHORT).show()
            pintar()
        })
        out.add(Row.cabecera("Acerca de"))
        out.add(Row.accion("Mi Audio 2.0", "YouTube solo audio · listas · Android Auto") { })
        fijar(out)
    }

    private fun mb(bytes: Long): String = when {
        bytes <= 0L -> "0 MB"
        bytes < 1024L * 1024L -> "${bytes / 1024} KB"
        else -> "${bytes / (1024L * 1024L)} MB"
    }

    // ------------------------------------------------------------- busqueda

    private fun buscar(q: String) {
        val consulta = q.trim()
        if (consulta.isEmpty()) {
            Toast.makeText(this, "Escribe algo para buscar", Toast.LENGTH_SHORT).show()
            return
        }
        entrada.setText(consulta)
        Estado.guardarReciente(consulta)
        pila.clear()
        pila.add(Nivel.Buscar)
        mostrar(Nivel.Consulta(consulta))
    }

    private fun nivelConsulta(q: String) {
        consultaActual = q
        cabecera.text = q
        val mivia = version
        cargando = true
        cargando(getString(R.string.cargando))
        enSegundoPlano {
            // 40 resultados de golpe (2 paginas), el resto al bajar
            val primera = runCatching { Yt.hasta(q, Yt.VIDEOS, 40) }.getOrDefault(Yt.Pagina(emptyList(), emptyList(), null))
            val listas = runCatching { Yt.pagina(q, Yt.LISTAS, 0) }.getOrNull()
            runOnUiThread {
                if (mivia != version) return@runOnUiThread
                cargando = false
                siguientePagina = primera.siguiente
                val out = ArrayList<Row>()
                if (primera.tracks.isEmpty()) {
                    out.add(Row.carpeta(getString(R.string.sin_resultados), "Prueba con otras palabras"))
                } else {
                    out.add(Row.cabecera("Canciones"))
                    primera.tracks.forEach { t -> out.add(Row.pista(t, sub(t), null, Estado.esEscuchado(t.mediaId()))) }
                }
                val ls = listas?.listas ?: emptyList()
                if (ls.isNotEmpty()) {
                    out.add(Row.cabecera("Listas y álbumes de YouTube"))
                    ls.take(10).forEach { l ->
                        out.add(
                            Row.carpeta(l.titulo, "${l.n} vídeos · ${l.autor}", "") {
                                mostrar(Nivel.YtLista(l.url, l.titulo))
                            }
                        )
                    }
                }
                if (siguientePagina != null) out.add(Row(Row.Tipo.CARGA, "Desliza para más resultados…"))
                fijar(out, q)
            }
        }
    }

    private fun cargarPagina(indice: Int) {
        if (cargando || consultaActual.isBlank()) return
        val mivia = version
        cargando = true
        enSegundoPlano {
            val p = runCatching { Yt.pagina(consultaActual, Yt.VIDEOS, indice) }.getOrNull()
            runOnUiThread {
                if (mivia != version) return@runOnUiThread
                cargando = false
                if (p == null || p.tracks.isEmpty()) {
                    siguientePagina = null
                    val cargandoIdx = filas.indexOfFirst { it.tipo == Row.Tipo.CARGA }
                    if (cargandoIdx >= 0) filas.removeAt(cargandoIdx)
                    adaptador.notifyDataSetChanged()
                    return@runOnUiThread
                }
                siguientePagina = p.siguiente
                val cargandoIdx = filas.indexOfFirst { it.tipo == Row.Tipo.CARGA }
                if (cargandoIdx >= 0) filas.removeAt(cargandoIdx)
                val base = cargandoIdx.coerceAtLeast(filas.size)
                filas.addAll(base, p.tracks.map { t -> Row.pista(t, sub(t), null, Estado.esEscuchado(t.mediaId())) })
                if (siguientePagina != null) filas.add(Row(Row.Tipo.CARGA, "Desliza para más resultados…"))
                adaptador.notifyDataSetChanged()
            }
        }
    }

    private fun nivelYtLista(url: String, titulo: String) {
        cabecera.text = titulo
        val mivia = version
        cargando(getString(R.string.cargando))
        enSegundoPlano {
            val (nombre, videos) = runCatching { Yt.videosDeLista(url) }
                .getOrDefault(titulo to emptyList())
            runOnUiThread {
                if (mivia != version) return@runOnUiThread
                val out = ArrayList<Row>()
                if (videos.isEmpty()) {
                    out.add(Row.carpeta("No se pudo leer esta lista", "Puede ser privada o tener vídeos no disponibles"))
                } else {
                    out.add(Row.accion(getString(R.string.reproducir_todo), "${videos.size} canciones") { reproducirTodo() })
                    out.add(
                        Row.accion(
                            getString(R.string.guardar_lista_entera),
                            "Copia las ${videos.size} canciones en una lista tuya"
                        ) { guardarGrupo(nombre, videos) }
                    )
                    out.add(Row.cabecera(nombre))
                    videos.forEach { t -> out.add(Row.pista(t, sub(t), nombre, Estado.esEscuchado(t.mediaId()))) }
                }
                fijar(out, nombre)
            }
        }
    }

    // -------------------------------------------------------------- reproducir

    private fun reproducirCola(pistas: List<Track>, indice: Int, origen: String? = null) {
        if (pistas.isEmpty()) return
        val i = indice.coerceIn(0, pistas.size - 1)
        // La pista por la que se empieza usa la posicion de arranque; las demas
        // llevan su recorte si se quedaron a medias (podcasts).
        val items = pistas.mapIndexed { k, t ->
            if (k == i) t.toMediaItem(0L) else t.toMediaItem(Estado.progreso(t.mediaId()))
        }
        arrancar(items, i, Estado.progreso(pistas[i].mediaId()), origen ?: origenActual)
    }

    private fun reproducirTodo(origen: String? = null, indice: Int = 0) {
        val pistas = filas.mapNotNull { it.track }
        if (pistas.isEmpty()) return
        reproducirCola(pistas, indice, origen)
    }

    private fun reproducirFila(fila: Row) {
        val t = fila.track ?: return
        val pistas = filas.mapNotNull { it.track }
        val pos = pistas.indexOf(t).coerceAtLeast(0)
        reproducirCola(pistas, pos, origenActual)
    }

    private fun arrancar(items: List<MediaItem>, indice: Int, desde: Long = 0L, origen: String? = null) {
        if (items.isEmpty()) return
        Estado.colaOrigen = origen ?: ""
        val c = controlador
        if (c == null) {
            pendiente = Triple(items, indice, desde)
            conectar()
            Toast.makeText(this, "Preparando el reproductor…", Toast.LENGTH_SHORT).show()
            return
        }
        c.setMediaItems(items, indice, desde)
        c.prepare()
        c.play()
    }

    // ------------------------------------------------------------ conexion

    private fun conectar() {
        if (controlador != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        futuro = f
        f.addListener({
            runCatching {
                val c = f.get()
                controlador = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        pintarMini()
                    }
                })
                pendiente?.let { (items, indice, desde) ->
                    c.setMediaItems(items, indice, desde)
                    c.prepare()
                    c.play()
                    pendiente = null
                }
                pintarMini()
            }.onFailure {
                runCatching { MediaController.releaseFuture(f) }
                futuro = null
            }
        }, MoreExecutors.directExecutor())
    }

    // ------------------------------------------------------------- mini player

    /** Para la musica del todo, vacia la cola y quita el reproductor de la pantalla. */
    private fun parar() {
        val c = controlador
        if (c != null) {
            c.stop()
            c.clearMediaItems()
        }
        miniPlayer.visibility = View.GONE
        cerrarSheet()
        Toast.makeText(this, "Música parada", Toast.LENGTH_SHORT).show()
    }

    private fun pintarMini() {
        val c = controlador
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            miniPlayer.visibility = View.GONE
            return
        }
        miniPlayer.visibility = View.VISIBLE
        val md = item.mediaMetadata
        miniTitulo.text = md.title ?: ""
        miniSub.text = md.artist ?: ""
        pintarArte(miniArt, md.artworkUri?.toString())
        val icono = if (c.isPlaying) R.drawable.ic_pausa else R.drawable.ic_play
        miniPlay.setImageResource(icono)
        sheetPlay.setImageResource(icono)

        if (sheet.visibility == View.VISIBLE) {
            sheetTitulo.text = md.title ?: ""
            sheetSub.text = md.artist ?: ""
            pintarArte(sheetArt, md.artworkUri?.toString())
            sheetCola.text = "En cola: ${c.mediaItemCount} · ${c.currentMediaItemIndex + 1} de ${c.mediaItemCount}"
            sheetTotal.text = reloj(c.duration.coerceAtLeast(0))
        }
    }

    private fun arrancarReloj() {
        reloj.postDelayed(object : Runnable {
            override fun run() {
                val c = controlador
                if (c != null && sheet.visibility == View.VISIBLE) {
                    val dur = c.duration
                    val pos = c.currentPosition
                    if (dur > 0 && !ajustandoBarra) {
                        sheetBarra.progress = ((pos * 1000) / dur).toInt().coerceIn(0, 1000)
                    }
                    sheetTiempo.text = reloj(pos.coerceAtLeast(0))
                    sheetTotal.text = reloj(dur.coerceAtLeast(0))
                }
                reloj.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun abrirSheet() {
        if (controlador?.currentMediaItem == null) return
        sheet.visibility = View.VISIBLE
        pintarMini()
    }

    private fun cerrarSheet() {
        sheet.visibility = View.GONE
    }

    // ----------------------------------------------------------------- listas

    private fun preguntarNombre(t: Track?) {
        val campo = EditText(this)
        campo.hint = "Nombre de la lista"
        campo.setTextColor(ContextCompat.getColor(this, R.color.texto))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.nueva_lista))
            .setView(campo)
            .setPositiveButton("Crear") { _, _ ->
                val nombre = campo.text.toString().trim()
                if (nombre.isEmpty()) return@setPositiveButton
                if (Lib.store.buscar(nombre) == null) Lib.store.crear(nombre)
                t?.let { Lib.store.anadir(nombre, it) }
                Toast.makeText(this, "Lista \"$nombre\" creada", Toast.LENGTH_SHORT).show()
                pintar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Elegir lista destino (o crear una nueva). */
    private fun elegirLista(queHacer: (String) -> Unit) {
        val nombres = Lib.store.listas.map { it.nombre }
        val opciones = (nombres + getString(R.string.nueva_lista)).toTypedArray()
        if (nombres.isEmpty()) {
            val campo = EditText(this)
            campo.hint = "Nombre de la lista"
            campo.setTextColor(ContextCompat.getColor(this, R.color.texto))
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.nueva_lista))
                .setView(campo)
                .setPositiveButton("Crear") { _, _ ->
                    val nombre = campo.text.toString().trim()
                    if (nombre.isNotEmpty()) {
                        Lib.store.crear(nombre)
                        queHacer(nombre)
                        pintar()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.anadir_a_lista))
            .setItems(opciones) { _, i ->
                if (i < nombres.size) queHacer(nombres[i]) else preguntarNombre(null)
            }
            .show()
    }

    private fun menuPista(t: Track) {
        val enLista = pila.lastOrNull() as? Nivel.Lista
        val opciones = ArrayList<String>()
        opciones.add(getString(R.string.anadir_a_lista))
        opciones.add("Reproducir ahora")
        if (enLista != null) opciones.add("Quitar de \"${enLista.nombre}\"")
        AlertDialog.Builder(this)
            .setTitle(t.title)
            .setItems(opciones.toTypedArray()) { _, i ->
                when (i) {
                    0 -> elegirLista { nombre ->
                        Lib.store.anadir(nombre, t)
                        Toast.makeText(this, "Añadido a \"$nombre\"", Toast.LENGTH_SHORT).show()
                        pintar()
                    }
                    1 -> arrancar(listOf(t.toMediaItem()), 0)
                    else -> {
                        val l = enLista ?: return@setItems
                        val lista = Lib.store.buscar(l.nombre) ?: return@setItems
                        val idx = lista.pistas.indexOfFirst { it.kind == t.kind && it.id == t.id }
                        if (idx >= 0) {
                            Lib.store.quitar(l.nombre, idx)
                            pintar()
                        }
                    }
                }
            }
            .show()
    }

    /** Guarda todas las canciones de una lista de YouTube dentro de una lista propia. */
    private fun guardarGrupo(nombreGrupo: String, videos: List<Track>) {
        elegirLista { destino ->
            val total = Lib.store.anadirGrupo(destino, nombreGrupo, videos)
            Toast.makeText(
                this,
                "${videos.size} canciones de \"$nombreGrupo\" guardadas en \"$destino\" ($total en total)",
                Toast.LENGTH_LONG
            ).show()
            pintar()
        }
    }

    // ---------------------------------------------------------------- permisos

    private fun permisoAudioSiHaceFalta() {
        val permiso = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permiso) != PackageManager.PERMISSION_GRANTED) {
            pedirPermiso.launch(permiso)
        }
    }

    private fun permisoNotificacionesSiHaceFalta() {
        if (Build.VERSION.SDK_INT >= 33) {
            val p = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                runCatching { pedirPermiso.launch(p) }
            }
        }
    }

    // ------------------------------------------------------------------ textos

    /** Portada: imagen si hay, icono de nota musical si no. */
    private fun pintarArte(iv: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            iv.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.texto3))
            iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
            iv.setImageResource(R.drawable.ic_musica)
        } else {
            iv.imageTintList = null
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.load(url) {
                placeholder(R.drawable.ic_musica)
                error(R.drawable.ic_musica)
            }
        }
    }

    private fun sub(t: Track): String {
        val quien = t.subtitle.ifBlank { if (t.kind == "local") "Móvil" else "YouTube" }
        return if (t.durMs > 0) "$quien · ${reloj(t.durMs)}" else quien
    }

    private fun reloj(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val seg = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, seg)
        else String.format("%d:%02d", m, seg)
    }

    private fun enSegundoPlano(bloque: () -> Unit) {
        Thread(bloque).start()
    }

    // -------------------------------------------------------------- adaptador

    private inner class Adaptador : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            if (filas[position].tipo == Row.Tipo.CABECERA) 1 else 0

        override fun getItemCount(): Int = filas.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 1) {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_header, parent, false)
                VistaCabecera(v)
            } else {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
                VistaFila(v)
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val fila = filas[position]
            when (holder) {
                is VistaCabecera -> holder.titulo.text = fila.titulo
                is VistaFila -> {
                    holder.titulo.text = fila.titulo
                    holder.sub.text = fila.sub
                    holder.sub.visibility = if (fila.sub.isBlank()) View.GONE else View.VISIBLE
                    holder.flecha.visibility = if (fila.flecha || fila.tipo == Row.Tipo.CARGA) View.VISIBLE else View.GONE
                    holder.art.visibility = if (fila.tipo == Row.Tipo.CARGA) View.GONE else View.VISIBLE
                    pintarArte(holder.art, fila.art)
                    holder.check.visibility = if (fila.hecho) View.VISIBLE else View.GONE
                    holder.titulo.setTextColor(
                        if (fila.hecho) ContextCompat.getColor(this@MainActivity, R.color.texto2)
                        else ContextCompat.getColor(this@MainActivity, R.color.texto)
                    )
                    holder.itemView.setOnClickListener {
                        val tSel = fila.track
                        if (modoSeleccion && tSel != null && tSel.kind == "local") {
                            marcarSeleccion(position, tSel)
                            return@setOnClickListener
                        }
                        when {
                            fila.tipo == Row.Tipo.PISTA -> reproducirFila(fila)
                            fila.tipo == Row.Tipo.CARGA -> siguientePagina?.let { cargarPagina(it) }
                            else -> fila.accion?.invoke()
                        }
                    }
                    holder.itemView.setOnLongClickListener {
                        val t = fila.track
                        if (t != null) {
                            menuPista(t)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }
    }

    private class VistaFila(v: View) : RecyclerView.ViewHolder(v) {
        val art: ImageView = v.findViewById(R.id.rArt)
        val titulo: TextView = v.findViewById(R.id.tTitle)
        val sub: TextView = v.findViewById(R.id.tSub)
        val flecha: ImageView = v.findViewById(R.id.rFlecha)
        val check: ImageView = v.findViewById(R.id.rCheck)
    }

    private class VistaCabecera(v: View) : RecyclerView.ViewHolder(v) {
        val titulo: TextView = v.findViewById(R.id.hTitle)
    }
}
