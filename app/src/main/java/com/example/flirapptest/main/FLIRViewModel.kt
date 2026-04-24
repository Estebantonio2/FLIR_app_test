package com.example.flirapptest.main

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flir.thermalsdk.ErrorCode
import com.flir.thermalsdk.androidsdk.image.BitmapAndroid
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
    fun startDiscovery() {
        // Limpiamos errores previos al iniciar nueva búsqueda
        _errorMessage.value = null

        _connectionState.value = ConnectionState.DISCOVERING
        _statusMessage.value = "Buscando cámara FLIR por cable USB..."

        val discoveryListener = object : DiscoveryEventListener {
            override fun onCameraFound(discoveredCamera: DiscoveredCamera?) {
                discoveredCamera?.let { camera ->
                    val identity = camera.identity
                    Log.d("FLIR_TESIS", "¡Cámara encontrada por USB!: ${identity?.deviceId}")

                    DiscoveryFactory.getInstance().stop(CommunicationInterface.USB)

                    identity?.let {
                        connectToCamera(it)
                    } ?: run {
                        // CAPTURAMOS EL ERROR
                        _errorMessage.value = "Se encontró una cámara, pero su identidad (Identity) es nula."
                    }
                }
            }

            override fun onDiscoveryError(
                commInterface: CommunicationInterface?,
                errorCode: ErrorCode?
            ) {
                viewModelScope.launch {
                    _connectionState.value = ConnectionState.ERROR
                    _statusMessage.value = "Error de búsqueda"
                    // CAPTURAMOS EL ERROR EXACTO DEL SDK
                    _errorMessage.value = "Código de error SDK: $errorCode\nFalla en la interfaz: $commInterface"
                }
            }
        }

        try {
            DiscoveryFactory.getInstance().scan(discoveryListener, CommunicationInterface.USB)
        } catch (e: Exception) {
            // CAPTURAMOS ERRORES DE ANDROID (Ej. Falta de permisos OTG)
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
    private fun connectToCamera(identity: Identity) {
        flirCamera?.disconnect()

        _connectionState.value = ConnectionState.CONNECTING
        _statusMessage.value = "Conectando a la cámara..."

        flirCamera = Camera()

        val connectionListener = ConnectionStatusListener { errorCode ->
            viewModelScope.launch {
                _connectionState.value = ConnectionState.DISCONNECTED
                _statusMessage.value = "Se perdió la conexión: $errorCode"
                Log.w("FLIR_TESIS", "Cámara desconectada. Razón: $errorCode")
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                flirCamera?.connect(identity, connectionListener, null)

                // --- AQUÍ ES DONDE LLAMAS A STARTSTREAM ---
                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.CONNECTED
                    _statusMessage.value = "¡Conectado exitosamente!"
                    Log.d("FLIR_TESIS", "¡Cámara conectada y lista para el stream!")

                    // Iniciamos el video automáticamente al conectar
                    startStream()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.ERROR
                    _statusMessage.value = "Fallo al conectar: ${e.message}"
                    Log.e("FLIR_TESIS", "Excepción al conectar", e)
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
            // 1. Le decimos al Streamer que "absorba" el nuevo paquete
            thermalStreamer?.update()

            // 2. Extraemos la imagen
            thermalStreamer?.withThermalImage { thermalImage ->
                val javaBuffer = thermalImage.image
                val androidBitmap = BitmapAndroid.createBitmap(javaBuffer).bitMap

                // 3. Actualizamos la UI
                _thermalBitmap.value = androidBitmap
            }
        }

        val onErrorListener = OnRemoteError { errorCode ->
            Log.e("FLIR_TESIS", "Error crítico en el stream de video: $errorCode")
            // Nota: Aquí podrías actualizar un StateFlow para mostrarle una alerta al usuario
        }

        try {
            // Le pasamos el objeto explícito a la función start
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

    fun startDynamicCaptureSequence() {
        if (isAutoCaptureRunning) return

        isAutoCaptureRunning = true
        _isAutoCaptureRunningState.value = true

        viewModelScope.launch(Dispatchers.Default) {
            try {
                // FASE 1: 5 minutos (300s) / 5s = 60 capturas
                _statusMessage.value = "Fase 1: Captura cada 5s (0-5 min)"
                for (i in 1..60) {
                    if (!isAutoCaptureRunning) break
                    triggerCameraCapture()
                    _statusMessage.value = "Fase 1: Foto $i de 60"
                    delay(5000)
                }

                // FASE 2: 5 minutos (300s) / 10s = 30 capturas
                if (isAutoCaptureRunning) {
                    _statusMessage.value = "Fase 2: Captura cada 10s (5-10 min)"
                    for (i in 1..30) {
                        if (!isAutoCaptureRunning) break
                        triggerCameraCapture()
                        _statusMessage.value = "Fase 2: Foto $i de 30"
                        delay(10000)
                    }
                }

                if (isAutoCaptureRunning) {
                    _statusMessage.value = "Experimento completado. 90 fotos guardadas."
                }

            } catch (e: Exception) {
                // Si ocurre cualquier error o se cancela la corrutina
                Log.e("FLIR_TESIS", "Secuencia interrumpida: ${e.message}")
            } finally {
                // --- NUEVO: Esto asegura que las variables siempre se reinicien ---
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