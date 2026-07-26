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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
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
            .padding(16.dp)
    ) {
        Text("DIME - Subidor", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("URL del servidor (base, sin /api)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Token") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = {
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
            }) { Text("Verificar") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                token = ""
                tokenVerifiedName = null
                SessionManager.clearToken(ctx)
            }) { Text("Cerrar sesión") }
        }
        tokenVerifiedName?.let {
            Text("Conectado: $it", color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = customName, onValueChange = { customName = it }, label = { Text("Nombre a mostrar (opcional)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descripción (opcional)") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { filePickerLauncher.launch("video/*") }, modifier = Modifier.weight(1f)) {
                Text(if (selectedVideoUri == null) "Seleccionar video" else "Video seleccionado")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                Text(if (selectedPortadaUri == null) "Seleccionar portada" else "Portada seleccionada")
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val overall = uploadMonitor.overallPercent
                    CircularProgressIndicator(progress = (overall.coerceIn(0,100))/100f, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Progreso global", style = MaterialTheme.typography.titleMedium)
                        Text("$overall%", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(Modifier.height(12.dp))

                val parts = uploadMonitor.parts
                if (parts.isEmpty()) {
                    Text("No hay subidas activas", color = Color.Gray)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .heightIn(min = 120.dp, max = 400.dp)
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

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                if (selectedVideoUri == null || token.isBlank()) {
                    Toast.makeText(ctx, "Selecciona video y token", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                onStartFFmpegAndUpload(selectedVideoUri!!, token, customName, description, selectedPortadaUri)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Procesar y Subir")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun PartBox(part: PartState) {
    val bgColor = when {
        part.error -> Color(0xFFD9534F)
        part.uploaded -> Color(0xFF28A745)
        part.progress > 0 -> Color(0xFF2A9DF4)
        else -> Color(0xFF3A3A3A)
    }
    Card(
        modifier = Modifier
            .padding(6.dp)
            .height(56.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier
                .size(36.dp)
                .background(bgColor, shape = RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center) {
                Text("${part.index+1}", color = Color.White, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Parte ${part.index+1}", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = (part.progress.coerceIn(0,100))/100f, modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("${part.progress}%", modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
        }
    }
}
