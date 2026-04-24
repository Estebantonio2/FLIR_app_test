package com.example.flirapptest.main

import android.Manifest
import android.app.Activity
import android.os.Environment
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: FLIRViewModel
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val thermalBitmap by viewModel.thermalBitmap.collectAsState()
    val isRunning by viewModel.isAutoCaptureRunningState.collectAsState()

    var hasPermissions by remember { mutableStateOf(false) }

    val errorMessage by viewModel.errorMessage.collectAsState()

    // PREVENCIÓN DE APAGADO: Mantiene la pantalla activa durante el experimento
    DisposableEffect(isRunning) {
        val window = (context as? Activity)?.window
        if (isRunning) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // GESTIÓN DE PERMISOS BÁSICOS
    // El SDK de FLIR suele requerir permisos de ubicación genéricos para inicializar,
    // incluso por USB, dependiendo de la versión de Android.
    val permissionsToRequest = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPermissions = it.values.all { granted -> granted } }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!hasPermissions) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator()
                Text("Solicitando permisos para la investigación...", textAlign = TextAlign.Center)
            }
        } else {
            // 1. CABECERA DE ESTADO
            Text(
                text = if (connectionState == ConnectionState.CONNECTED) "Cámara Conectada (USB)" else "Estado: $statusMessage",
                style = MaterialTheme.typography.headlineSmall,
                color = if (connectionState == ConnectionState.CONNECTED) Color(0xFF4CAF50) else Color.Unspecified,
                textAlign = TextAlign.Center
            )

            // 2. VISOR TÉRMICO
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(Color.Black, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                thermalBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Stream térmico",
                        modifier = Modifier.fillMaxSize()
                    )
                } ?: Text("Esperando stream...", color = Color.Gray)
            }

            errorMessage?.let { errorTxt ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "Detalle Técnico del Error:",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = errorTxt,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.clearError() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                contentColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Entendido")
                        }
                    }
                }
            }

            // 3. PANEL DE CONTROL (Limpio y directo para USB)
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = statusMessage, textAlign = TextAlign.Center)

                    Spacer(modifier = Modifier.height(16.dp))

                    if (connectionState != ConnectionState.CONNECTED) {
                        if (connectionState == ConnectionState.DISCOVERING) {
                            // MIENTRAS BUSCA
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = { viewModel.stopDiscovery() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Cancelar Búsqueda")
                                }
                            }
                        } else {
                            // ESTADO INICIAL O ERROR: Botón para iniciar búsqueda USB
                            Button(
                                onClick = { viewModel.startDiscovery(context) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Buscar FLIR C5 (Por Cable USB)")
                            }
                        }
                    } else {
                        if (!isRunning) {
                            // CONTROLES DE PREPARACIÓN
                            OutlinedButton(
                                onClick = { viewModel.triggerCameraCapture() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("📸 Probar Captura Manual")
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(16.dp))

                            // CONTROLES DEL EXPERIMENTO

                            val outputDirectory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.absolutePath

                            Button(
                                onClick = {
                                    if (outputDirectory != null) {
                                        viewModel.startDynamicCaptureSequence(context)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("▶ Iniciar Secuencia (10 min)")
                            }
                        } else {
                            // BOTÓN DE PÁNICO / DETENER
                            Button(
                                onClick = { viewModel.stopSequence() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("⏹ Detener Experimento")
                            }
                        }
                    }
                }
            }
        }
    }
}