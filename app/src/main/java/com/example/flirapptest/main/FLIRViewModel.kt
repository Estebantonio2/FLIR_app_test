package com.example.flirapptest.main

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flir.thermalsdk.ErrorCode
import com.flir.thermalsdk.ErrorCodeException
import com.flir.thermalsdk.androidsdk.image.BitmapAndroid
import com.flir.thermalsdk.androidsdk.live.connectivity.UsbPermissionHandler
import com.flir.thermalsdk.image.Palette
import com.flir.thermalsdk.image.PaletteManager
import com.flir.thermalsdk.image.fusion.FusionMode
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnectionState {
    DISCONNECTED, DISCOVERING, CONNECTING, CONNECTED, ERROR
}

class FLIRViewModel : ViewModel() {
    @Volatile
    private var snapshotRequested = false
    private var snapshotCounter = 0
    private var currentOutputDirectory: String? = null
    private val usbPermissionHandler = UsbPermissionHandler()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Esperando...")
    val statusMessage = _statusMessage.asStateFlow()

    private val _thermalBitmap = MutableStateFlow<Bitmap?>(null)
    val thermalBitmap = _thermalBitmap.asStateFlow()

    private var thermalStreamer: ThermalStreamer? = null

    private var isAutoCaptureRunning = false
    private val _isAutoCaptureRunningState = MutableStateFlow(false)
    val isAutoCaptureRunningState = _isAutoCaptureRunningState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    var flirCamera: Camera? = null
        private set

    private var appContext: Context? = null

    private var currentPalette: Palette? = null

    init {
        currentPalette = PaletteManager.getDefaultPalettes().find {
            it.name.equals("iron", ignoreCase = true)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun startDiscovery(context: Context) {
        this.appContext = context.applicationContext

        val baseDir = context.filesDir
        val experimentDir = java.io.File(baseDir, "TesisFLIR")
        if (!experimentDir.exists()) {
            experimentDir.mkdirs()
        }
        currentOutputDirectory = experimentDir.absolutePath

        _errorMessage.value = null
        _connectionState.value = ConnectionState.DISCOVERING
        _statusMessage.value = "Buscando cámara FLIR por cable USB..."

        val discoveryListener = object : DiscoveryEventListener {
            override fun onCameraFound(discoveredCamera: DiscoveredCamera?) {
                discoveredCamera?.let { camera ->
                    val identity = camera.identity
                    DiscoveryFactory.getInstance().stop(CommunicationInterface.USB)
                    connectToCamera(context, identity)
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
            _errorMessage.value = "Excepción al iniciar escaneo:\n${e.message}"
            _connectionState.value = ConnectionState.ERROR
        }
    }

    fun stopDiscovery() {
        DiscoveryFactory.getInstance().stop(CommunicationInterface.USB)
        _connectionState.value = ConnectionState.DISCONNECTED
        _statusMessage.value = "Búsqueda detenida"
    }

    private fun connectToCamera(context: Context, identity: Identity) {
        _statusMessage.value = "Verificando permisos USB..."

        if (UsbPermissionHandler.isFlirOne(identity)) {
            usbPermissionHandler.requestFlirOnePermisson(identity, context, object : UsbPermissionHandler.UsbPermissionListener {
                override fun permissionGranted(identity: Identity) {
                    executeConnection(identity)
                }
                override fun permissionDenied(identity: Identity) {
                    viewModelScope.launch {
                        _errorMessage.value = "Permiso USB denegado."
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
                    _statusMessage.value = "Cámara lista"
                    startStream()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.ERROR
                    _statusMessage.value = "Fallo al conectar"
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        isAutoCaptureRunning = false
        try {
            val streams = flirCamera?.streams
            if (!streams.isNullOrEmpty()) {
                streams[0].stop()
            }
        } catch (e: Exception) {
            Log.e("FLIR_TESIS", "Error al detener stream", e)
        }
        flirCamera?.disconnect()
        DiscoveryFactory.getInstance().stop(CommunicationInterface.NETWORK)
    }

    fun startStream() {
        val streams = flirCamera?.streams

        if (streams.isNullOrEmpty()) {
            _statusMessage.value = "No se encontraron flujos de video"
            return
        }

        val videoStream = streams.find { it.isThermal } ?: streams[0]
        thermalStreamer = ThermalStreamer(videoStream)

        val onReceivedListener = OnReceived<Void> {
            viewModelScope.launch(Dispatchers.IO) {
                refreshThermalFrame()
            }
        }

        val onErrorListener = OnRemoteError { errorCode ->
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

    @Synchronized
    private fun refreshThermalFrame() {
        try {
            try {
                thermalStreamer?.update()
            } catch (e: ErrorCodeException) {
                return
            } catch (e: NullPointerException) {
                return
            }

            val imageBuffer = thermalStreamer?.image ?: return

            thermalStreamer?.withThermalImage { thermalImage ->
                if (thermalImage == null) return@withThermalImage

                currentPalette?.let {
                    thermalImage.palette = it
                }

                thermalImage.fusion?.setFusionMode(FusionMode.THERMAL_ONLY)

                if (snapshotRequested) {
                    try {
                        snapshotRequested = false
                        val outputDir = currentOutputDirectory ?: return@withThermalImage

                        snapshotCounter++
                        val timeStampFormat = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss-SSS", Locale.getDefault())
                        val currentTime = timeStampFormat.format(Date())

                        val fileName = "${currentTime}__$snapshotCounter.jpg"
                        val file = java.io.File(outputDir, fileName)

                        thermalImage.saveAs(file.absolutePath)

                        appContext?.let { context ->
                            exportToPublicStorage(context, file)

                            if (!isAutoCaptureRunning) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    _statusMessage.value = "Guardado y Exportado: $fileName"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FLIR_TESIS", "Error al guardar", e)
                    }
                }

                try {
                    val bmp = BitmapAndroid.createBitmap(imageBuffer).bitMap
                    if (bmp != null) {
                        viewModelScope.launch(Dispatchers.Main) {
                            _thermalBitmap.value = bmp
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    // Ignorar
                }
            }
        } catch (e: Exception) {
            Log.e("FLIR_TESIS", "Error procesando frame", e)
        }
    }

    private fun exportToPublicStorage(context: Context, privateFile: java.io.File) {
        val resolver = context.contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, privateFile.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TesisFLIR")
        }

        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let { destinationUri ->
            try {
                resolver.openOutputStream(destinationUri)?.use { outputStream ->
                    privateFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(privateFile.absolutePath),
                    null,
                    null
                )
            } catch (e: java.io.IOException) {
                Log.e("FLIR_EXPORT", "Error al exportar", e)
            }
        }
    }

    fun triggerCameraCapture() {
        snapshotRequested = true
    }

    fun startDynamicCaptureSequence(intervalSeconds: Int, durationMinutes: Int) {
        if (isAutoCaptureRunning) return

        if (currentOutputDirectory == null) {
            _errorMessage.value = "Directorio no inicializado"
            return
        }

        isAutoCaptureRunning = true
        _isAutoCaptureRunningState.value = true
        snapshotCounter = 0

        val totalDurationSeconds = durationMinutes * 60
        val totalCaptures = totalDurationSeconds / intervalSeconds

        viewModelScope.launch(Dispatchers.IO) {
            try {
                for (i in 1..totalCaptures) {
                    if (!isAutoCaptureRunning) break

                    snapshotRequested = true

                    viewModelScope.launch(Dispatchers.Main) {
                        _statusMessage.value = "Captura automática $i de $totalCaptures"
                    }

                    if (i < totalCaptures) {
                        delay(intervalSeconds * 1000L)
                    }
                }

                if (isAutoCaptureRunning) {
                    viewModelScope.launch(Dispatchers.Main) {
                        _statusMessage.value = "Secuencia completada"
                    }
                }
            } catch (e: Exception) {
                Log.e("FLIR_TESIS", "Error en secuencia", e)
            } finally {
                isAutoCaptureRunning = false
                _isAutoCaptureRunningState.value = false
            }
        }
    }

    fun stopSequence() {
        isAutoCaptureRunning = false
        _isAutoCaptureRunningState.value = false
        _statusMessage.value = "Secuencia detenida"
    }
}