package com.dime.app

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val serverBaseNoApi = "https://inspection-sister-wondering-ask.trycloudflare.com"
    private val serverBaseApi = "$serverBaseNoApi/api"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UploadScreen(
                        initialToken = SessionManager.getToken(applicationContext),
                        onVerifyToken = { token, onResult ->
                            verifyToken(token, onResult)
                        },
                        onUploadRequested = { videoUri, isFolder, folderUri, token, customName, description, portadaUri ->
                            startUploadChain(videoUri, isFolder, folderUri, token, customName, description, portadaUri)
                        }
                    )
                }
            }
        }
    }

    private fun verifyToken(token: String, onResult: (Boolean, String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("$serverBaseApi/user/whoami?token=${token}")
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

    private fun startUploadChain(
        videoUri: Uri?,
        isFolder: Boolean,
        folderUri: Uri?,
        token: String,
        customName: String,
        description: String,
        portadaUri: Uri?
    ) {
        val workManager = WorkManager.getInstance(applicationContext)

        if (isFolder) {
            val input = Data.Builder()
                .putString("FOLDER_URI", folderUri?.toString() ?: "")
                .putString("TOKEN", token)
                .putString("CUSTOM_NAME", if (customName.isBlank()) "folder_${System.currentTimeMillis()}" else customName)
                .putString("DESCRIPTION", description)
                .putString("PORTADA_URI", portadaUri?.toString() ?: "")
                .build()

            val folderWork = OneTimeWorkRequestBuilder<FolderUploadWorker>().setInputData(input).build()
            workManager.enqueue(folderWork)
        } else {
            if (videoUri == null) {
                Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
                return
            }
            val ffmpegInput = Data.Builder()
                .putString("FILE_URI", videoUri.toString())
                .putString("TOKEN", token)
                .putInt("SEG_SECONDS", 120)
                .build()

            val ffmpegWork = OneTimeWorkRequestBuilder<FFmpegWorker>()
                .setInputData(ffmpegInput)
                .build()

            workManager.enqueue(ffmpegWork)

            workManager.getWorkInfoByIdLiveData(ffmpegWork.id).observe(this) { info ->
                if (info != null && info.state.isFinished) {
                    if (info.state == WorkInfo.State.SUCCEEDED) {
                        val outputDir = info.outputData.getString("OUTPUT_DIR")
                        val outToken = info.outputData.getString("TOKEN") ?: token
                        if (outputDir.isNullOrEmpty()) {
                            Toast.makeText(this, "FFmpeg: no segments", Toast.LENGTH_LONG).show()
                            return@observe
                        }
                        val uploadInput = Data.Builder()
                            .putString("OUTPUT_DIR", outputDir)
                            .putString("TOKEN", outToken)
                            .putString("CUSTOM_NAME", if (customName.isBlank()) (videoUri.lastPathSegment ?: "video_${System.currentTimeMillis()}.mp4") else customName)
                            .putString("DESCRIPTION", description)
                            .putString("PORTADA_URI", portadaUri?.toString() ?: "")
                            .build()

                        val uploadWork = OneTimeWorkRequestBuilder<UploadWorker>()
                            .setInputData(uploadInput)
                            .build()

                        workManager.enqueue(uploadWork)
                    } else {
                        Toast.makeText(this, "FFmpeg failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    initialToken: String,
    onVerifyToken: (String, (Boolean, String) -> Unit) -> Unit,
    onUploadRequested: (Uri?, Boolean, Uri?, String, String, String, Uri?) -> Unit
) {
    var token by remember { mutableStateOf(initialToken) }
    var tokenVerifiedName by remember { mutableStateOf<String?>(null) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFolderUri by remember { mutableStateOf<Uri?>(null) }
    var selectedPortadaUri by remember { mutableStateOf<Uri?>(null) }
    var customName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var modeIsFolder by remember { mutableStateOf(false) }

    val ctx = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> selectedVideoUri = uri }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> selectedFolderUri = uri }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> selectedPortadaUri = uri }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text(text = "DIME - FFmpeg Segmenter (Android)", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Token de Acceso") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))

        Row {
            Button(onClick = {
                onVerifyToken(token) { ok, info ->
                    if (ok) tokenVerifiedName = info else tokenVerifiedName = null
                }
            }) { Text("✅ Verificar token") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { token = ""; tokenVerifiedName = null; SessionManager.clearToken(ctx) }) { Text("🚪 Cerrar sesión") }
        }

        tokenVerifiedName?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Conectado como: $it", color = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row {
            Text("Modo:")
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = !modeIsFolder,
                onClick = { modeIsFolder = false },
                label = { Text("Video (FFmpeg)") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = modeIsFolder,
                onClick = { modeIsFolder = true },
                label = { Text("Carpeta") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(value = customName, onValueChange = { customName = it }, label = { Text("Nombre a mostrar (opcional)") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descripción (opcional)") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(12.dp))

        if (!modeIsFolder) {
            Button(onClick = { filePickerLauncher.launch("video/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(if (selectedVideoUri == null) "Seleccionar Video Original" else "Video seleccionado")
            }
        } else {
            Button(onClick = { folderPickerLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (selectedFolderUri == null) "Seleccionar Carpeta" else "Carpeta seleccionada")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            Text(if (selectedPortadaUri == null) "Seleccionar Portada (opcional)" else "Portada seleccionada")
        }
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (modeIsFolder) {
                    if (selectedFolderUri == null || token.isBlank()) return@Button
                    onUploadRequested(null, true, selectedFolderUri, token, customName, description, selectedPortadaUri)
                } else {
                    if (selectedVideoUri == null || token.isBlank()) return@Button
                    onUploadRequested(selectedVideoUri, false, null, token, customName, description, selectedPortadaUri)
                }
            },
            enabled = ((modeIsFolder && selectedFolderUri != null) || (!modeIsFolder && selectedVideoUri != null)) && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Procesar y Subir")
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text("La app segmenta (FFmpeg) y sube partes. Ver detalles en notificaciones o logs.")
    }
}
