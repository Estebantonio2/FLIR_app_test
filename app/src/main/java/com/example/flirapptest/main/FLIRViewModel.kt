package com.example.flirapptest.main

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flir.thermalsdk.ErrorCode
import com.flir.thermalsdk.ErrorCodeException
import com.flir.thermalsdk.androidsdk.image.BitmapAndroid
import com.flir.thermalsdk.androidsdk.live.connectivity.UsbPermissionHandler
import com.flir.thermalsdk.image.Palette
import com.flir.thermalsdk.image.PaletteManager
import com.flir.thermalsdk.image.TemperatureUnit
import com.flir.thermalsdk.image.ThermalValue
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
import com.flir.thermalsdk.live.streaming.Stream
import com.flir.thermalsdk.live.streaming.ThermalStreamer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

enum class ConnectionState {
    DISCONNECTED, DISCOVERING, CONNECTING, CONNECTED, ERROR
}

class FLIRViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "FLIR_TESIS"
        private const val ALERT_REPEAT_COUNT = 5
        private const val ALERT_REPEAT_DELAY_MS = 900L
        private const val ALERT_THROTTLE_MS = 10_000L
    }

    private val appContext: Context
        get() = getApplication<Application>().applicationContext

    private val pendingSnapshotRequests = AtomicInteger(0)
    private var snapshotCounter = 0
    private var currentOutputDirectory: String? = null
    private val usbPermissionHandler = UsbPermissionHandler()
    private var usbPermissionRetryCount = 0

    private val frameSignalChannel = Channel<Unit>(capacity = Channel.CONFLATED)
    private var frameProcessingJob: Job? = null

    private val ironPalette: Palette by lazy {
        PaletteManager.getDefaultPalettes().firstOrNull {
            it.name.equals("iron", ignoreCase = true)
        } ?: PaletteManager.getDefaultPalettes().first()
    }
    private val minScaleTemp = ThermalValue(18.0, TemperatureUnit.CELSIUS)
    private val maxScaleTemp = ThermalValue(32.0, TemperatureUnit.CELSIUS)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Esperando...")
    val statusMessage = _statusMessage.asStateFlow()

    private val _thermalBitmap = MutableStateFlow<Bitmap?>(null)
    val thermalBitmap = _thermalBitmap.asStateFlow()

    private var thermalStreamer: ThermalStreamer? = null
    private var connectedStream: Stream? = null
    private var alertPlayer: MediaPlayer? = null
    private var isAlertPlaying = false
    private var lastAlertTime = 0L

    @Volatile
    private var isAutoCaptureRunning = false
    private var autoCaptureJob: Job? = null
    private var currentSessionFolder = "capturas_manuales"
    private val _isAutoCaptureRunningState = MutableStateFlow(false)
    val isAutoCaptureRunningState = _isAutoCaptureRunningState.asStateFlow()

    private val _handPlacementMessage = MutableStateFlow<String?>(null)
    val handPlacementMessage = _handPlacementMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    var flirCamera: Camera? = null
        private set

    fun clearError() {
        _errorMessage.value = null
    }

    fun startDiscovery(context: Context = appContext) {
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
        usbPermissionRetryCount = 0

        if (UsbPermissionHandler.isFlirOne(identity)) {
            requestFlirOnePermission(context, identity)
        } else {
            executeConnection(identity)
        }
    }

    private fun requestFlirOnePermission(context: Context, identity: Identity) {
        usbPermissionHandler.requestFlirOnePermisson(identity, context, object : UsbPermissionHandler.UsbPermissionListener {
            override fun permissionGranted(identity: Identity) {
                usbPermissionRetryCount = 0
                executeConnection(identity)
            }

            override fun permissionDenied(identity: Identity) {
                viewModelScope.launch {
                    _errorMessage.value = "Permiso USB denegado."
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }

            override fun error(errorType: UsbPermissionHandler.UsbPermissionListener.ErrorType, identity: Identity) {
                if (
                    errorType == UsbPermissionHandler.UsbPermissionListener.ErrorType.DEVICE_UNAVAILABLE_WHEN_ASKED_PERMISSION &&
                    usbPermissionRetryCount == 0
                ) {
                    usbPermissionRetryCount++
                    requestFlirOnePermission(context, identity)
                    return
                }

                viewModelScope.launch {
                    _errorMessage.value = "Error al solicitar permiso USB: $errorType"
                    _connectionState.value = ConnectionState.ERROR
                }
            }
        })
    }

    private fun executeConnection(identity: Identity) {
        closeCamera()
        _connectionState.value = ConnectionState.CONNECTING
        _statusMessage.value = "Conectando al hardware..."

        flirCamera = Camera()

        val connectionListener = ConnectionStatusListener { errorCode ->
            viewModelScope.launch {
                handleConnectionLost("Se perdió la conexión: $errorCode")
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
        autoCaptureJob?.cancel()
        frameProcessingJob?.cancel()
        pendingSnapshotRequests.set(0)
        stopAlert()
        closeCamera()
        DiscoveryFactory.getInstance().stop(CommunicationInterface.USB)
    }

    private fun closeCamera() {
        stopConnectedStream()
        try {
            flirCamera?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error al desconectar cámara", e)
        } finally {
            flirCamera = null
            thermalStreamer = null
        }
    }

    private fun stopConnectedStream() {
        frameProcessingJob?.cancel()
        frameProcessingJob = null
        try {
            connectedStream?.let { stream ->
                if (stream.isStreaming) {
                    stream.stop()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener stream", e)
        } finally {
            connectedStream = null
        }
    }

    fun startStream() {
        stopConnectedStream()
        val streams = flirCamera?.streams

        if (streams.isNullOrEmpty()) {
            _statusMessage.value = "No se encontraron flujos de video"
            return
        }

        val videoStream = streams.find { it.isThermal } ?: streams[0]
        connectedStream = videoStream
        thermalStreamer = ThermalStreamer(videoStream)

        frameProcessingJob = viewModelScope.launch(Dispatchers.IO) {
            for (signal in frameSignalChannel) {
                refreshThermalFrame()
            }
        }

        val onReceivedListener = OnReceived<Void> {
            frameSignalChannel.trySend(Unit)
        }

        val onErrorListener = OnRemoteError { errorCode ->
            viewModelScope.launch(Dispatchers.Main) {
                handleConnectionLost("Error crítico en el stream: $errorCode")
            }
        }

        try {
            videoStream.start(onReceivedListener, onErrorListener)
        } catch (e: Exception) {
            connectedStream = null
            Log.e(TAG, "Excepción al iniciar stream", e)
        }
    }

    private fun handleConnectionLost(message: String) {
        stopSequence(updateStatus = false)
        _connectionState.value = ConnectionState.DISCONNECTED
        _statusMessage.value = message
        _errorMessage.value = message
        _thermalBitmap.value = null
        pendingSnapshotRequests.set(0)
        viewModelScope.launch(Dispatchers.IO) {
            closeCamera()
        }
        playConnectionAlert()
    }

    private fun playConnectionAlert() {
        val context = appContext
        val now = System.currentTimeMillis()
        if (isAlertPlaying || now - lastAlertTime < ALERT_THROTTLE_MS) return

        lastAlertTime = now
        isAlertPlaying = true

        viewModelScope.launch(Dispatchers.Main) {
            repeat(ALERT_REPEAT_COUNT) { index ->
                playSingleAlarmTone(context)
                if (index < ALERT_REPEAT_COUNT - 1) {
                    delay(ALERT_REPEAT_DELAY_MS.milliseconds)
                }
            }
            isAlertPlaying = false
        }
    }

    private fun playSingleAlarmTone(context: Context) {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        try {
            alertPlayer?.release()
            alertPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                setVolume(1.0f, 1.0f)
                setOnCompletionListener { player ->
                    player.release()
                    if (alertPlayer === player) {
                        alertPlayer = null
                    }
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo reproducir la alarma de desconexión", e)
            alertPlayer?.release()
            alertPlayer = null
        }
    }

    private fun stopAlert() {
        isAlertPlaying = false
        alertPlayer?.release()
        alertPlayer = null
    }

    private fun refreshThermalFrame() {
        try {
            try {
                thermalStreamer?.update()
            } catch (e: ErrorCodeException) {
                return
            } catch (e: NullPointerException) {
                return
            }

            thermalStreamer?.isAutoScale = false

            val imageBuffer = thermalStreamer?.image ?: return

            thermalStreamer?.withThermalImage { thermalImage ->
                if (thermalImage == null) return@withThermalImage

                thermalImage.fusion?.setFusionMode(FusionMode.THERMAL_ONLY)
                thermalImage.palette = ironPalette
                thermalImage.scale?.setRange(minScaleTemp, maxScaleTemp)

                if (pendingSnapshotRequests.get() > 0) {
                    var snapshotRequestConsumed = false
                    try {
                        val outputDir = currentOutputDirectory ?: error("Directorio no inicializado")

                        snapshotCounter++
                        val timeStampFormat = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss-SSS", Locale.getDefault())
                        val currentTime = timeStampFormat.format(Date())

                        val fileName = "${currentTime}__$snapshotCounter.jpg"
                        val file = java.io.File(outputDir, fileName)

                        thermalImage.saveAs(file.absolutePath)
                        pendingSnapshotRequests.decrementAndGet()
                        snapshotRequestConsumed = true

                        val context = appContext
                        val sessionFolder = currentSessionFolder
                        viewModelScope.launch(Dispatchers.IO) {
                            exportToPublicStorage(context, file, sessionFolder)
                        }

                        if (!isAutoCaptureRunning) {
                            viewModelScope.launch(Dispatchers.Main) {
                                _statusMessage.value = "Guardado y Exportado: $fileName"
                            }
                        }
                    } catch (e: Exception) {
                        if (!snapshotRequestConsumed) {
                            pendingSnapshotRequests.decrementAndGet()
                        }
                        Log.e(TAG, "Error al guardar", e)
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
            Log.e(TAG, "Error procesando frame", e)
        }
    }

    fun startDynamicCaptureSequence() {
        if (isAutoCaptureRunning) return

        if (!prepareCaptureSession("test")) return

        autoCaptureJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                runCaptureSchedule(
                    captureSchedule = createFifteenMinuteCaptureSchedule(),
                    statusPrefix = "Captura",
                    timeLabel = "T"
                )

                if (isAutoCaptureRunning) {
                    viewModelScope.launch(Dispatchers.Main) {
                        _statusMessage.value = "Secuencia completada"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error en secuencia", e)
            } finally {
                isAutoCaptureRunning = false
                _isAutoCaptureRunningState.value = false
                _handPlacementMessage.value = null
            }
        }
    }

    fun startPreHandCaptureSequence() {
        if (isAutoCaptureRunning) return

        if (!prepareCaptureSession("test_pre_mano")) return

        autoCaptureJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val baselineSchedule = (0..120 step 5).toList()
                runCaptureSchedule(
                    captureSchedule = baselineSchedule,
                    statusPrefix = "Antes de mano",
                    timeLabel = "T-"
                )

                for (remainingSeconds in 10 downTo 1) {
                    if (!isAutoCaptureRunning) break
                    withContext(Dispatchers.Main) {
                        _handPlacementMessage.value = "Coloca la mano ahora: $remainingSeconds s"
                        _statusMessage.value = "Mantén la mano colocada durante 10 segundos"
                    }
                    delay(1000L.milliseconds)
                }

                if (isAutoCaptureRunning) {
                    withContext(Dispatchers.Main) {
                        _handPlacementMessage.value = null
                        _statusMessage.value = "Retira la mano. Iniciando secuencia de 15 minutos"
                    }
                }

                runCaptureSchedule(
                    captureSchedule = createFifteenMinuteCaptureSchedule(),
                    statusPrefix = "Después de mano",
                    timeLabel = "T"
                )

                if (isAutoCaptureRunning) {
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = "Secuencia con pre-mano completada"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error en secuencia con pre-mano", e)
            } finally {
                isAutoCaptureRunning = false
                _isAutoCaptureRunningState.value = false
                _handPlacementMessage.value = null
            }
        }
    }

    private fun prepareCaptureSession(folderPrefix: String): Boolean {
        val timeStampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        currentSessionFolder = "${folderPrefix}_" + timeStampFormat.format(Date())

        appContext?.let { context ->
            val baseDir = context.filesDir
            val sessionDir = java.io.File(baseDir, "TesisFLIR/$currentSessionFolder")
            if (!sessionDir.exists()) {
                sessionDir.mkdirs()
            }
            currentOutputDirectory = sessionDir.absolutePath
        }

        if (currentOutputDirectory == null) {
            _errorMessage.value = "Directorio no inicializado"
            return false
        }

        autoCaptureJob?.cancel()
        isAutoCaptureRunning = true
        _isAutoCaptureRunningState.value = true
        _handPlacementMessage.value = null
        snapshotCounter = 0
        pendingSnapshotRequests.set(0)
        return true
    }

    private fun createFifteenMinuteCaptureSchedule(): List<Int> {
        val captureSchedule = mutableListOf<Int>()
        for (t in 0..60 step 5) captureSchedule.add(t)       // 0 a 1 min: cada 5s (13 capturas)
        for (t in 70..240 step 10) captureSchedule.add(t)   // 1 a 4 min: cada 10s (18 capturas)
        for (t in 260..480 step 20) captureSchedule.add(t)  // 4 a 8 min: cada 20s (12 capturas)
        for (t in 510..900 step 30) captureSchedule.add(t)  // 8 a 15 min: cada 30s (14 capturas)
        return captureSchedule
    }

    private suspend fun runCaptureSchedule(
        captureSchedule: List<Int>,
        statusPrefix: String,
        timeLabel: String
    ) {
        var previousCaptureTime = 0
        val totalCaptures = captureSchedule.size

        for (i in captureSchedule.indices) {
            if (!isAutoCaptureRunning) break

            val currentCaptureTime = captureSchedule[i]
            val delaySeconds = currentCaptureTime - previousCaptureTime

            if (delaySeconds > 0) {
                delay((delaySeconds * 1000L).milliseconds)
            }

            if (!isAutoCaptureRunning) break

            pendingSnapshotRequests.incrementAndGet()

            withContext(Dispatchers.Main) {
                _statusMessage.value = "$statusPrefix ${i + 1} de $totalCaptures ($timeLabel=${currentCaptureTime}s)"
            }

            previousCaptureTime = currentCaptureTime
        }
    }

    private fun exportToPublicStorage(context: Context, privateFile: java.io.File, sessionFolder: String) {
        val resolver = context.contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, privateFile.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TesisFLIR/$sessionFolder")
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
        pendingSnapshotRequests.incrementAndGet()
    }

    fun stopSequence(updateStatus: Boolean = true) {
        isAutoCaptureRunning = false
        autoCaptureJob?.cancel()
        autoCaptureJob = null
        _isAutoCaptureRunningState.value = false
        _handPlacementMessage.value = null
        pendingSnapshotRequests.set(0)
        if (updateStatus) {
            _statusMessage.value = "Secuencia detenida"
        }
    }
}
