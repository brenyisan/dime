package com.dime.app

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

// PartState with observable properties so Compose updates when they change
class PartState(val index: Int) {
    var progress by mutableStateOf(0)       // 0..100
    var uploaded by mutableStateOf(false)
    var error by mutableStateOf(false)
}

class UploadMonitorViewModel(app: Application) : AndroidViewModel(app) {
    private val workManager = WorkManager.getInstance(app)
    private val observers = mutableMapOf<UUID, androidx.lifecycle.Observer<WorkInfo>>()

    // Observable UI state list
    private val _parts = mutableStateListOf<PartState>()
    val parts: List<PartState> get() = _parts

    var overallPercent by mutableStateOf(0)
        private set

    var currentWorkId: UUID? = null
        private set

    fun clear() {
        observers.forEach { (id, obs) ->
            val live = workManager.getWorkInfoByIdLiveData(id)
            live.removeObserver(obs)
        }
        observers.clear()
        _parts.clear()
        overallPercent = 0
        currentWorkId = null
    }

    fun startMonitoring(workId: UUID, totalParts: Int) {
        clear()
        currentWorkId = workId

        // initialize parts as observable objects
        for (i in 0 until totalParts) {
            _parts.add(PartState(index = i))
        }

        val live = workManager.getWorkInfoByIdLiveData(workId)
        val obs = androidx.lifecycle.Observer<WorkInfo> { info ->
            if (info == null) return@Observer
            val progressData = info.progress
            val overall = progressData.getInt("overall_percent", -1)
            if (overall >= 0) overallPercent = overall

            val uploadedBytes = progressData.getLong("uploaded_bytes", -1L)
            val totalBytes = progressData.getLong("total_bytes", -1L)
            if (uploadedBytes >= 0 && totalBytes > 0 && overall < 0) {
                overallPercent = ((uploadedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt()
            }

            val partIndex = progressData.getInt("part_index", -1)
            val partProgress = progressData.getInt("part_progress", -1)

            if (partIndex >= 0 && partIndex < _parts.size) {
                val p = _parts[partIndex]
                if (partProgress >= 0) {
                    p.progress = partProgress
                    p.uploaded = (partProgress >= 100)
                } else if (info.state.isFinished && p.progress < 100) {
                    p.progress = 100
                    p.uploaded = true
                }
            }

            if (info.state.isFinished) {
                if (info.state == WorkInfo.State.SUCCEEDED) {
                    _parts.forEach { part ->
                        if (!part.uploaded) {
                            part.progress = 100
                            part.uploaded = true
                        }
                    }
                    overallPercent = 100
                } else {
                    _parts.forEach { part ->
                        if (!part.uploaded) part.error = true
                    }
                }
            }
        }

        observers[workId] = obs
        live.observeForever(obs)
    }
}

// ---------------------------------------------------------------------
// Paleta "DIME" — misma identidad visual que index.html:
// fondo degradado negro -> rojo oscuro, acento rojo #E50914, tarjetas
// translúcidas con borde sutil, texto secundario gris #9CA3AF.
// ---------------------------------------------------------------------
private object DimeColors {
    val BgTop = Color(0xFF0F0F0F)
    val BgBottom = Color(0xFF1A0505)
    val Accent = Color(0xFFE50914)
    val AccentSoft = Color(0xFFB0060F)
    val CardBg = Color(0xE6141414)
    val CardBorder = Color(0x1FFFFFFF)
    val TextSecondary = Color(0xFF9CA3AF)
    val Success = Color(0xFF28A745)
    val ErrorC = Color(0xFFD9534F)
    val InfoBlue = Color(0xFF2A9DF4)
    val TrackDark = Color(0xFF232323)
}

private val DimeColorScheme = darkColorScheme(
    primary = DimeColors.Accent,
    onPrimary = Color.White,
    secondary = DimeColors.Accent,
    onSecondary = Color.White,
    background = DimeColors.BgTop,
    onBackground = Color.White,
    surface = DimeColors.CardBg,
    onSurface = Color.White,
    error = DimeColors.ErrorC,
    onError = Color.White
)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"

    private lateinit var viewModelFactory: ViewModelProvider.Factory
    private val uploadMonitor: UploadMonitorViewModel by viewModels {
        viewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModelFactory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)

        setContent {
            MaterialTheme(colorScheme = DimeColorScheme) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(DimeColors.BgTop, DimeColors.BgBottom)
                            )
                        )
                ) {
                    val ctx = LocalContext.current
                    val savedToken = SessionManager.getToken(ctx)
                    val savedServer = SessionManager.getServerUrl(ctx)

                    MainScreen(
                        initialToken = savedToken,
                        initialServer = savedServer,
                        onVerifyToken = { serverBase, token, onResult ->
                            verifyToken(serverBase, token, onResult)
                        },
                        onStartFFmpegAndUpload = { fileUri, token, customName, description, portadaUri ->
                            startFFmpegThenUpload(fileUri, token, customName, description, portadaUri)
                        },
                        uploadMonitor = uploadMonitor
                    )
                }
            }
        }
    }

    private fun verifyToken(serverBase: String, token: String, onResult: (Boolean, String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val base = serverBase.trimEnd('/')
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("$base/api/user/whoami?token=${token}")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { onResult(false, "HTTP ${resp.code}") }
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    val jo = JSONObject(body)
                    val user = jo.optJSONObject("user")
                    if (user != null) {
                        SessionManager.saveToken(applicationContext, token)
                        SessionManager.saveServerUrl(applicationContext, base)
                        withContext(Dispatchers.Main) { onResult(true, user.optString("name", user.optString("id", "User"))) }
                    } else {
                        withContext(Dispatchers.Main) { onResult(false, "no user") }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "error") }
            }
        }
    }

    // Get display name (filename with extension) from a content Uri - returns null if can't resolve
    private fun getDisplayNameFromUri(ctx: Context, uri: Uri): String? {
        var name: String? = null
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getDisplayNameFromUri error", e)
        }
        return name
    }

    private fun startFFmpegThenUpload(fileUri: Uri, token: String, customName: String, description: String, portadaUri: Uri?) {
        val workManager = WorkManager.getInstance(applicationContext)
        // enqueue FFmpegWorker
        val ffmpegInput = Data.Builder()
            .putString("FILE_URI", fileUri.toString())
            .putString("TOKEN", token)
            .putInt("SEG_SECONDS", 120)
            .build()

        val ffmpegWork = OneTimeWorkRequestBuilder<FFmpegWorker>()
            .setInputData(ffmpegInput)
            .build()

        workManager.enqueue(ffmpegWork)

        // observe ffmpeg completion
        workManager.getWorkInfoByIdLiveData(ffmpegWork.id).observe(this) { info ->
            if (info != null && info.state.isFinished) {
                if (info.state == WorkInfo.State.SUCCEEDED) {
                    val outputDir = info.outputData.getString("OUTPUT_DIR")
                    val outToken = info.outputData.getString("TOKEN") ?: token
                    if (outputDir.isNullOrEmpty()) {
                        Toast.makeText(this, "FFmpeg: no segments", Toast.LENGTH_LONG).show()
                        return@observe
                    }
                    val dir = File(outputDir)
                    val tsFiles = dir.listFiles { f -> f.name.endsWith(".ts") }?.sortedBy { it.name } ?: emptyList()
                    val totalParts = tsFiles.size.coerceAtLeast(1)

                    // EXACT behavior: if customName empty -> use original file's DISPLAY_NAME (with extension) if available
                    val finalName = if (customName.trim().isNotEmpty()) {
                        customName.trim()
                    } else {
                        getDisplayNameFromUri(applicationContext, fileUri)?.takeIf { it.isNotBlank() }
                            ?: (fileUri.lastPathSegment ?: "video_${System.currentTimeMillis()}")
                    }

                    val uploadInput = Data.Builder()
                        .putString("OUTPUT_DIR", outputDir)
                        .putString("TOKEN", outToken)
                        .putString("CUSTOM_NAME", finalName)
                        .putString("DESCRIPTION", description)
                        .putString("PORTADA_URI", portadaUri?.toString() ?: "")
                        .build()

                    val uploadWork = OneTimeWorkRequestBuilder<UploadWorker>()
                        .setInputData(uploadInput)
                        .build()

                    workManager.enqueue(uploadWork)
                    uploadMonitor.startMonitoring(uploadWork.id, totalParts)
                } else {
                    Toast.makeText(this, "FFmpeg failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Componentes de UI reutilizables (look premium coherente con index.html)
// ---------------------------------------------------------------------

@Composable
private fun PremiumCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(220)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DimeColors.CardBg),
        border = BorderStroke(1.dp, DimeColors.CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = DimeColors.TextSecondary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp
    )
}

@Composable
private fun DimeBrand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(listOf(DimeColors.Accent, DimeColors.AccentSoft))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("D", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "DIME",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp
            )
            Text(
                "Subidor de contenido",
                color = DimeColors.TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DimeColors.Accent,
            contentColor = Color.White,
            disabledContainerColor = DimeColors.Accent.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.6f)
        )
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, DimeColors.CardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
    ) {
        Text(text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

/**
 * Barra de sesión "permanente": si ya hay token verificado, se muestra un
 * chip compacto con punto verde + nombre + aviso de que la sesión quedó
 * guardada en el dispositivo (SessionManager). Si no, se muestra el
 * formulario de login. Toda la lógica de guardado/borrado de sesión es
 * exactamente la misma de antes (SessionManager.saveToken/getToken/clearToken).
 */
@Composable
private fun SessionSection(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    tokenVerifiedName: String?,
    onVerify: () -> Unit,
    onLogout: () -> Unit
) {
    PremiumCard {
        SectionLabel("Sesión")
        Spacer(Modifier.height(10.dp))

        AnimatedVisibility(visible = tokenVerifiedName != null) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(DimeColors.Success)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Conectado como ${tokenVerifiedName ?: ""}",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Sesión guardada en este dispositivo — no necesitas volver a ingresar el token",
                            color = DimeColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    TextButton(onClick = onLogout) {
                        Text("Cerrar sesión", color = DimeColors.Accent, fontSize = 13.sp)
                    }
                }
            }
        }

        AnimatedVisibility(visible = tokenVerifiedName == null) {
            Column {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = onServerUrlChange,
                    label = { Text("URL del servidor") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChange,
                    label = { Text("Token de acceso") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = "Verificar y guardar sesión",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onVerify
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Al verificar, el token queda guardado de forma permanente en el dispositivo.",
                    color = DimeColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    initialToken: String,
    initialServer: String,
    onVerifyToken: (String, String, (Boolean, String) -> Unit) -> Unit,
    onStartFFmpegAndUpload: (Uri, String, String, String, Uri?) -> Unit,
    uploadMonitor: UploadMonitorViewModel
) {
    val ctx = LocalContext.current
    var token by remember { mutableStateOf(initialToken) }
    var serverUrl by remember { mutableStateOf(initialServer) }
    var tokenVerifiedName by remember { mutableStateOf<String?>(null) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var selectedPortadaUri by remember { mutableStateOf<Uri?>(null) }
    var customName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    LaunchedEffect(initialToken, initialServer) {
        if (initialToken.isNotBlank()) {
            onVerifyToken(serverUrl, initialToken) { ok, info ->
                if (ok) tokenVerifiedName = info else tokenVerifiedName = null
            }
        }
    }

    val scrollState = rememberScrollState()
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> selectedVideoUri = uri }
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> selectedPortadaUri = uri }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        DimeBrand()
        Spacer(Modifier.height(20.dp))

        SessionSection(
            serverUrl = serverUrl,
            onServerUrlChange = { serverUrl = it },
            token = token,
            onTokenChange = { token = it },
            tokenVerifiedName = tokenVerifiedName,
            onVerify = {
                onVerifyToken(serverUrl, token) { ok, info ->
                    if (ok) {
                        tokenVerifiedName = info
                        SessionManager.saveToken(ctx, token)
                        SessionManager.saveServerUrl(ctx, serverUrl.trimEnd('/'))
                        Toast.makeText(ctx, "Conectado: $info", Toast.LENGTH_SHORT).show()
                    } else {
                        tokenVerifiedName = null
                        Toast.makeText(ctx, "Token inválido", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onLogout = {
                token = ""
                tokenVerifiedName = null
                SessionManager.clearToken(ctx)
            }
        )

        Spacer(Modifier.height(16.dp))

        PremiumCard {
            SectionLabel("Detalles del contenido")
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = customName,
                onValueChange = { customName = it },
                label = { Text("Nombre a mostrar (opcional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Descripción (opcional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))

        PremiumCard {
            SectionLabel("Archivos")
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(
                    text = if (selectedVideoUri == null) "Seleccionar video" else "✓ Video listo",
                    modifier = Modifier.weight(1f),
                    onClick = { filePickerLauncher.launch("video/*") }
                )
                Spacer(Modifier.width(10.dp))
                SecondaryButton(
                    text = if (selectedPortadaUri == null) "Seleccionar portada" else "✓ Portada lista",
                    modifier = Modifier.weight(1f),
                    onClick = { imagePickerLauncher.launch("image/*") }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        UploadProgressCard(uploadMonitor = uploadMonitor)

        Spacer(Modifier.height(20.dp))

        PrimaryButton(
            text = "Procesar y subir",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (selectedVideoUri == null || token.isBlank()) {
                    Toast.makeText(ctx, "Selecciona video y token", Toast.LENGTH_SHORT).show()
                    return@PrimaryButton
                }
                onStartFFmpegAndUpload(selectedVideoUri!!, token, customName, description, selectedPortadaUri)
            }
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun UploadProgressCard(uploadMonitor: UploadMonitorViewModel) {
    PremiumCard {
        SectionLabel("Progreso de subida")
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val overall = uploadMonitor.overallPercent
            val animatedProgress by animateFloatAsState(
                targetValue = (overall.coerceIn(0, 100)) / 100f,
                animationSpec = tween(350),
                label = "overallProgress"
            )
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                CircularProgressIndicator(
                    progress = 1f,
                    modifier = Modifier.size(64.dp),
                    color = DimeColors.TrackDark,
                    strokeWidth = 6.dp
                )
                CircularProgressIndicator(
                    progress = animatedProgress,
                    modifier = Modifier.size(64.dp),
                    color = DimeColors.Accent,
                    strokeWidth = 6.dp
                )
                Text("$overall%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Progreso global", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    "Subida fraccionada por partes — cada bloque se sube y confirma por separado",
                    color = DimeColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        val parts = uploadMonitor.parts
        if (parts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No hay subidas activas", color = DimeColors.TextSecondary, fontSize = 13.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .heightIn(min = 120.dp, max = 420.dp)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(4.dp)
            ) {
                itemsIndexed(parts) { _, part ->
                    PartBox(part = part)
                }
            }
        }
    }
}

@Composable
fun PartBox(part: PartState) {
    val accentColor = when {
        part.error -> DimeColors.ErrorC
        part.uploaded -> DimeColors.Success
        part.progress > 0 -> DimeColors.InfoBlue
        else -> DimeColors.TrackDark
    }
    Card(
        modifier = Modifier
            .padding(6.dp)
            .height(64.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
        border = BorderStroke(1.dp, DimeColors.CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor),
                contentAlignment = Alignment.Center
            ) {
                Text("${part.index + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Parte ${part.index + 1}", color = Color.White, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                val animatedPartProgress by animateFloatAsState(
                    targetValue = (part.progress.coerceIn(0, 100)) / 100f,
                    animationSpec = tween(250),
                    label = "partProgress"
                )
                LinearProgressIndicator(
                    progress = animatedPartProgress,
                    color = accentColor,
                    trackColor = DimeColors.TrackDark,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
            Spacer(Modifier.width(6.dp))
            Text("${part.progress}%", color = DimeColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
        }
    }
}
