package com.example.flirapptest.main

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flir.thermalsdk.ErrorCode
import com.flir.thermalsdk.ErrorCodeException
import com.flir.thermalsdk.androidsdk.image.BitmapAndroid
import com.flir.thermalsdk.androidsdk.live.connectivity.UsbPermissionHandler
import com.flir.thermalsdk.live.Camera
import com.flir.thermalsdk.live.CommunicationInterface
import com.flir.thermalsdk.live.Identity
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener
import com.flir.thermalsdk.live.discovery.DiscoveredCamera
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener
import com.flir.thermalsdk.live.discovery.DiscoveryFactory
import com.flir.thermalsdk.live.remote.OnReceived
import com.flir.thermalsdk.live.remote.OnRemoteError
import com.flir.thermalsdk.live.streaming.ThermalStreamer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnectionState {
    DISCONNECTED, DISCOVERING, CONNECTING, CONNECTED, ERROR
}

class FLIRViewModel: ViewModel() {
    // Bandera visible entre hilos para coordinar el guardado
    @Volatile
    private var shouldTakeSnapshot = false

    // Contador para nombrar los archivos de tu experimento
    private var snapshotCounter = 0

    // Ruta base donde se guardarán las imágenes (Debe ser configurada desde la UI)
    private var currentOutputDirectory: String = ""

    private val usbPermissionHandler = UsbPermissionHandler()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Esperando...")
    val statusMessage = _statusMessage.asStateFlow()

    private val _thermalBitmap = MutableStateFlow<Bitmap?>(null)
    val thermalBitmap = _thermalBitmap.asStateFlow()

    private var thermalStreamer: ThermalStreamer? = null

    // Variable para controlar si la automatización está activa
    private var isAutoCaptureRunning = false
    private val _isAutoCaptureRunningState = MutableStateFlow(false)
    val isAutoCaptureRunningState = _isAutoCaptureRunningState.asStateFlow()

    // NUEVO: Estado para el log de errores en pantalla
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    // El objeto de la cámara que mantendremos vivo
    var flirCamera: Camera? = null
        private set

    private var appContext: Context? = null

    private fun writeLog(tag: String, message: String, throwable: Throwable? = null) {
        val context = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = context.getExternalFilesDir(null)
                val file = File(dir, "flir_debug_log.txt")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(
                    Date()
                )
                val logEntry = "[$timestamp] [$tag] $message\n${throwable?.stackTraceToString() ?: ""}\n---\n"

                FileOutputStream(file, true).use {
                    it.write(logEntry.toByteArray())
                }
                Log.d("FLIR_LOG_FILE", "Escrito en log: $message")
            } catch (e: Exception) {
                Log.e("FLIR_LOG_FILE", "Error escribiendo log", e)
            }
        }
    }

    // INICIAR LA BÚSQUEDA (DISCOVERY)
    fun startDiscovery(context: Context) {
        this.appContext = context.applicationContext // <--- Guarda el contexto aquí primero
        writeLog("INFO", "Iniciando búsqueda de cámara...")
        _errorMessage.value = null
        _connectionState.value = ConnectionState.DISCOVERING
        _statusMessage.value = "Buscando cámara FLIR por cable USB..."

        val discoveryListener = object : DiscoveryEventListener {
            override fun onCameraFound(discoveredCamera: DiscoveredCamera?) {
                discoveredCamera?.let { camera ->
                    val identity = camera.identity
                    Log.d("FLIR_TESIS", "¡Cámara encontrada!: ${identity?.deviceId}")

                    DiscoveryFactory.getInstance().stop(CommunicationInterface.USB)

                    identity?.let {
                        // Pasamos el contexto a la función de conexión
                        connectToCamera(context, it)
                    } ?: run {
                        _errorMessage.value = "Se encontró una cámara, pero su identidad es nula."
                    }
                }
            }

            override fun onDiscoveryError(commInterface: CommunicationInterface?, errorCode: ErrorCode?) {
                writeLog("ERROR_DISCOVERY", "Interface: $commInterface, Error: $errorCode")
                viewModelScope.launch {
                    _connectionState.value = ConnectionState.ERROR
                    _statusMessage.value = "Error de búsqueda"
                    _errorMessage.value = "Código de error SDK: $errorCode"
                }
            }
        }

        try {
            DiscoveryFactory.getInstance().scan(discoveryListener, CommunicationInterface.USB)
        } catch (e: Exception) {
            _errorMessage.value = "Excepción crítica al iniciar escaneo:\n${e.message}"
            _connectionState.value = ConnectionState.ERROR
        }
    }

    fun stopDiscovery() {
        // 1. Le decimos al hardware que deje de buscar
        DiscoveryFactory.getInstance().stop(CommunicationInterface.USB)

        // 2. Regresamos el estado a desconectado para limpiar la UI
        _connectionState.value = ConnectionState.DISCONNECTED
        _statusMessage.value = "Búsqueda detenida por el usuario."
        Log.d("FLIR_TESIS", "Búsqueda Wi-Fi cancelada manualmente.")
    }

    // CONECTARSE A LA CÁMARA
    private fun connectToCamera(context: Context, identity: Identity) {
        _statusMessage.value = "Verificando permisos USB..."

        // Verificamos si es una FLIR One y gestionamos el permiso
        if (UsbPermissionHandler.isFlirOne(identity)) {
            usbPermissionHandler.requestFlirOnePermisson(identity, context, object : UsbPermissionHandler.UsbPermissionListener {
                override fun permissionGranted(identity: Identity) {
                    executeConnection(identity)
                }

                override fun permissionDenied(identity: Identity) {
                    viewModelScope.launch {
                        _errorMessage.value = "Permiso USB denegado. No se puede conectar a la cámara."
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }

                override fun error(errorType: UsbPermissionHandler.UsbPermissionListener.ErrorType, identity: Identity) {
                    viewModelScope.launch {
                        _errorMessage.value = "Error al solicitar permiso USB: $errorType"
                        _connectionState.value = ConnectionState.ERROR
                    }
                }
            })
        } else {
            // Si es otro modelo (ej. red Wi-Fi), conectamos directo
            executeConnection(identity)
        }
    }

    private fun executeConnection(identity: Identity) {
        flirCamera?.disconnect()
        _connectionState.value = ConnectionState.CONNECTING
        _statusMessage.value = "Conectando al hardware..."

        flirCamera = Camera()

        val connectionListener = ConnectionStatusListener { errorCode ->
            writeLog("CONNECTION_STATUS", "Estado: $errorCode")
            viewModelScope.launch {
                _connectionState.value = ConnectionState.DISCONNECTED
                _statusMessage.value = "Se perdió la conexión: $errorCode"
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                writeLog("INFO", "Intentando connect() con la cámara...")
                flirCamera?.connect(identity, connectionListener, null)

                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.CONNECTED
                    _statusMessage.value = "¡Cámara lista!"
                    startStream()
                }
            } catch (e: Exception) {
                writeLog("CRITICAL_EXCEPTION", "Fallo en connect()", e)
                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.ERROR
                    _statusMessage.value = "Fallo al conectar: ${e.message}"
                }
            }
        }
    }

    // Limpieza al cerrar la app
    override fun onCleared() {
        super.onCleared()

        // 1. Detenemos cualquier automatización activa
        isAutoCaptureRunning = false

        // 2. NUEVO: Detenemos el flujo de video para liberar los listeners de memoria
        try {
            val streams = flirCamera?.streams
            if (!streams.isNullOrEmpty()) {
                streams[0].stop()
            }
        } catch (e: Exception) {
            Log.e("FLIR_TESIS", "Error al detener el stream: ${e.message}")
        }

        // 3. Desconectamos la cámara de forma segura
        flirCamera?.disconnect()

        // 4. Detenemos la búsqueda de red
        DiscoveryFactory.getInstance().stop(CommunicationInterface.NETWORK)

        Log.d("FLIR_TESIS", "ViewModel destruido y recursos nativos liberados.")
    }

    fun startStream() {
        val streams = flirCamera?.streams

        if (streams.isNullOrEmpty()) {
            _statusMessage.value = "No se encontraron flujos de video."
            Log.e("FLIR_TESIS", "No se encontraron flujos de video en esta cámara.")
            return
        }

        // Asegurarse de tomar un formato de stream térmico
        val videoStream = streams.find { it.isThermal } ?: streams[0]
        thermalStreamer = ThermalStreamer(videoStream)

        val onReceivedListener = OnReceived<Void> {
            // Enviar el procesamiento a un hilo de fondo (IO) para no bloquear la recepción
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    // 1. ACTUALIZAR EL STREAMER (Obligatorio)
                    try {
                        thermalStreamer?.update()
                    } catch (e: ErrorCodeException) {
                        // Ignorar errores de los primeros frames de radiometría
                        Log.w("FLIR_TESIS", "Error al actualizar frame: ${e.message}")
                        return@launch
                    } catch (e: NullPointerException) {
                        return@launch
                    }

                    // Obtener el buffer de la imagen actual
                    val imageBuffer = thermalStreamer?.image ?: return@launch

                    thermalStreamer?.withThermalImage { thermalImage ->
                        if (thermalImage == null) return@withThermalImage

                        // 2. Renderizado para la pantalla
                        try {
                            val bitmapWrapper = BitmapAndroid.createBitmap(imageBuffer)
                            val androidBitmap = bitmapWrapper?.bitMap

                            if (androidBitmap != null) {
                                // Actualizamos el estado Compose en el hilo principal
                                viewModelScope.launch(Dispatchers.Main) {
                                    _thermalBitmap.value = androidBitmap
                                }
                            }

                            // 3. Lógica de captura y guardado
                            if (shouldTakeSnapshot) {
                                shouldTakeSnapshot = false
                                snapshotCounter++
                                try {
                                    val fileName = "disipacion_huella_$snapshotCounter.jpg"
                                    val absolutePath = "$currentOutputDirectory/$fileName"

                                    thermalImage.saveAs(absolutePath)

                                    viewModelScope.launch(Dispatchers.Main) {
                                        _statusMessage.value = absolutePath
                                    }
                                    Log.d("FLIR_TESIS", "Snapshot guardado en: $absolutePath")
                                } catch (e: Exception) {
                                    Log.e("FLIR_TESIS", "Fallo al guardar snapshot", e)
                                }
                            }

                        } catch (e: IllegalArgumentException) {
                            // Ignorar frames corruptos o vacíos
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FLIR_TESIS", "Error crítico procesando frame", e)
                }
            }
        }

        val onErrorListener = OnRemoteError { errorCode ->
            Log.e("FLIR_TESIS", "Error en video: $errorCode")
            viewModelScope.launch(Dispatchers.Main) {
                _statusMessage.value = "Error crítico en el stream: $errorCode"
            }
        }

        try {
            videoStream.start(onReceivedListener, onErrorListener)
        } catch (e: Exception) {
            Log.e("FLIR_TESIS", "Excepción al iniciar stream", e)
        }
    }

    fun triggerCameraCapture() {
        // Para la FLIR One Pro, no usamos el control de almacenamiento interno.
        // Simplemente activamos la bandera para que el hilo de streaming
        // intercepte y guarde el próximo frame directamente en el celular.

        shouldTakeSnapshot = true

        // Registramos la acción en tus logs para depuración
        writeLog("INFO", "Señal manual enviada para capturar el próximo frame térmico.")
        Log.d("FLIR_TESIS", "Señal enviada para guardar el próximo frame térmico.")
    }

    // Recibe la ruta absoluta desde tu Activity/Fragment (ej. applicationContext.getExternalFilesDir(null)?.absolutePath)
    fun startDynamicCaptureSequence(context: Context) {
        if (isAutoCaptureRunning) return

        // Generamos una ruta segura en el almacenamiento del dispositivo
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        if (directory == null) {
            _errorMessage.value = "No se pudo acceder al directorio de almacenamiento."
            return
        }

        currentOutputDirectory = directory.absolutePath
        isAutoCaptureRunning = true
        _isAutoCaptureRunningState.value = true
        snapshotCounter = 0

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Tu lógica de bucles con delays aquí
                // En cada iteración: shouldTakeSnapshot = true

            } catch (e: Exception) {
                Log.e("FLIR_TESIS", "Secuencia interrumpida: ${e.message}")
            } finally {
                isAutoCaptureRunning = false
                _isAutoCaptureRunningState.value = false
            }
        }
    }

    fun stopSequence() {
        isAutoCaptureRunning = false
        _isAutoCaptureRunningState.value = false
        _statusMessage.value = "Secuencia detenida por el usuario."
    }
}