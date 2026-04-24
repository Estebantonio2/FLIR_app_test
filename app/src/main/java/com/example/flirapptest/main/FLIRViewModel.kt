package com.example.flirapptest.main

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flir.thermalsdk.ErrorCode
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

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

    // INICIAR LA BÚSQUEDA (DISCOVERY)
    fun startDiscovery(context: Context) {
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

    fun connectByIpAddress(ipString: String = "192.168.0.1") { // Revisa la IP en la config de la C5
        flirCamera?.disconnect()
        _connectionState.value = ConnectionState.CONNECTING
        _statusMessage.value = "Conectando directo a IP: $ipString..."

        flirCamera = Camera()

        val connectionListener = ConnectionStatusListener { errorCode ->
            viewModelScope.launch {
                _connectionState.value = ConnectionState.DISCONNECTED
                _statusMessage.value = "Desconectado: $errorCode"
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val address = InetAddress.getByName(ipString)

                flirCamera?.connect(address, connectionListener, null)

                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.CONNECTED
                    _statusMessage.value = "¡Conectado por IP!"
                    startStream()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.ERROR
                    _statusMessage.value = "Error IP: ${e.message}"
                    Log.e("FLIR_TESIS", "Fallo al conectar por IP", e)
                }
            }
        }
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
            viewModelScope.launch {
                _connectionState.value = ConnectionState.DISCONNECTED
                _statusMessage.value = "Se perdió la conexión: $errorCode"
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                flirCamera?.connect(identity, connectionListener, null)

                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.CONNECTED
                    _statusMessage.value = "¡Cámara lista!"
                    startStream()
                }
            } catch (e: Exception) {
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
            Log.e("FLIR_TESIS", "No se encontraron flujos de video (Streams) en esta cámara.")
            return
        }

        val videoStream = streams[0]
        thermalStreamer = ThermalStreamer(videoStream)

        val onReceivedListener = OnReceived<Void> {
            thermalStreamer?.update()

            // withThermalImage proporciona acceso seguro a la imagen (thread-safe)
            thermalStreamer?.withThermalImage { thermalImage ->
                // 1. Renderizado para la pantalla
                val javaBuffer = thermalImage.image
                val androidBitmap = BitmapAndroid.createBitmap(javaBuffer).bitMap
                _thermalBitmap.value = androidBitmap

                // 2. Lógica de captura y guardado en memoria del celular
                if (shouldTakeSnapshot) {
                    // Apagamos la bandera inmediatamente para evitar capturas dobles
                    shouldTakeSnapshot = false
                    snapshotCounter++

                    try {
                        // Generar la ruta absoluta del archivo
                        val fileName = "disipacion_huella_$snapshotCounter.jpg"
                        val absolutePath = "$currentOutputDirectory/$fileName"

                        // Guarda la imagen térmica radiométrica
                        thermalImage.saveAs(absolutePath)
                        Log.d("FLIR_TESIS", "Snapshot térmico guardado exitosamente en: $absolutePath")
                    } catch (e: Exception) {
                        Log.e("FLIR_TESIS", "Error al guardar el snapshot térmico: ${e.message}")
                    }
                }
            }
        }

        val onErrorListener = OnRemoteError { errorCode ->
            Log.e("FLIR_TESIS", "Error crítico en el stream de video: $errorCode")
        }

        try {
            videoStream.start(onReceivedListener, onErrorListener)
            Log.d("FLIR_TESIS", "Suscripción al stream exitosa.")
        } catch (e: Exception) {
            Log.e("FLIR_TESIS", "Error al iniciar el stream: ${e.message}")
        }
    }

    fun triggerCameraCapture() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Accedemos al Control Remoto
                val remote = flirCamera?.remoteControl

                // 2. Obtenemos el componente de Almacenamiento (Storage)
                val storage = remote?.storage

                // 3. Ejecutamos el snapshot
                // Esto ordena a la C5 capturar y procesar el archivo interno
                storage?.snapshot()

                Log.d("FLIR_TESIS", "Orden de snapshot enviada a la memoria de la cámara.")
            } catch (e: Exception) {
                Log.e("FLIR_TESIS", "Error al ejecutar snapshot: ${e.message}")
            }
        }
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