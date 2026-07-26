Instrucciones rápidas

1) Dependencias: build.gradle.kts ya incluye OkHttp, FFmpegKit, WorkManager, DocumentFile, Compose.
2) Ajusta target/baseUrl si es necesario. Por defecto está configurado a:
   https://inspection-sister-wondering-ask.trycloudflare.com/api
3) Compilar y ejecutar. Selecciona Token, verifica (whoami), elige modo Video o Carpeta.
4) En Video mode: se lanza FFmpeg (FFmpegKit) para generar .ts; luego sube partes .ts exactamente igual al cliente PC.
5) En Folder mode: sube archivos por chunks ("raw") tal como el cliente PC hizo para carpetas.
6) La app sube la portada primero y referencia la portada en assignments usando existing:<saved_as>.
7) Reanudado: antes de subir cada archivo/segmento se consulta /api/upload/chunk/status para evitar reenvíos.
8) Progreso por parte: UploadWorker y FolderUploadWorker usan setProgress y el UI puede observar el progreso WorkInfo.progress.
