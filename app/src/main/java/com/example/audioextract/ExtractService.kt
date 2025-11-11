package com.example.audioextract

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.*
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExtractService : Service() {

    companion object {
        const val EXTRA_URI_LIST = "uriList"
        const val EXTRA_AUDIO_INDEX = "audioIndex"
        private const val NOTIF_ID = 1001

        const val ACTION_LOG = "com.example.audioextract.LOG"
        const val ACTION_PROGRESS = "com.example.audioextract.PROGRESS"
        const val ACTION_ERROR = "com.example.audioextract.ERROR"
        const val ACTION_DONE = "com.example.audioextract.DONE"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_LOG_URI = "log_uri"
    }

    private val sb = StringBuilder()
    private var ok = 0
    private var err = 0

    private fun log(msg: String) {
        Log.d("ExtractService", msg)
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$ts] $msg"
        sb.append(line).append('\n')
        sendBroadcast(Intent(ACTION_LOG).setPackage(packageName).putExtra(EXTRA_MESSAGE, line))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("ExtractService", "Service started")
        val uris = intent?.getParcelableArrayListExtra<Uri>(EXTRA_URI_LIST) ?: arrayListOf()
        val audioIndex = intent?.getIntExtra(EXTRA_AUDIO_INDEX, 0) ?: 0
        if (uris.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        NotificationUtils.ensureChannel(this)
        val notif = NotificationCompat.Builder(this, NotificationUtils.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Извлечение аудио")
            .setContentText("Подготовка…")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)

        FFmpegKitConfig.enableLogCallback { l ->
            try { log(" > " + (l.message ?: "")) } catch (_: Throwable) {}
        }

        sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName)
            .putExtra(EXTRA_TOTAL, uris.size).putExtra(EXTRA_CURRENT, 0).putExtra(EXTRA_MESSAGE, "Подготовка…"))
        log("Файлов в очереди: ${uris.size}")

        Thread {
            uris.forEachIndexed { idx, uri ->
                try {
                    processOne(uri, idx, uris.size, audioIndex)
                    ok++
                    sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName)
                        .putExtra(EXTRA_TOTAL, uris.size)
                        .putExtra(EXTRA_CURRENT, idx + 1)
                        .putExtra(EXTRA_MESSAGE, "Готово: ${idx + 1}/${uris.size}"))
                } catch (t: Throwable) {
                    err++
                    val em = t.message ?: t::class.java.simpleName
                    log("ОШИБКА: $em")
                    sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName)
                        .putExtra(EXTRA_MESSAGE, "Файл ${idx + 1}: $em"))
                }
            }
            val logUri = saveLogToDownloads()
            val summary = "Завершено. Успехов=$ok, ошибок=$err. Лог: ${logUri ?: "недоступен"}"
            sendBroadcast(Intent(ACTION_DONE).setPackage(packageName)
                .putExtra(EXTRA_MESSAGE, summary)
                .putExtra(EXTRA_LOG_URI, logUri?.toString()))
            stopSelf()
        }.start()

        return START_NOT_STICKY
    }

    private fun processOne(src: Uri, index: Int, total: Int, audioIndex: Int) {
        log("==== Файл ${index + 1}/$total ====")
        log("Вход: $src")

        val pfd = contentResolver.openFileDescriptor(src, "r")
            ?: throw IllegalStateException("Не удалось открыть дескриптор")
        val inPath = "/proc/self/fd/${pfd.fd}"
        log("FD path: $inPath")

        val displayName = queryDisplayName(src) ?: "audio"
        log("Имя в медиатеке: $displayName")

        log("FFprobe: анализ дорожек")
        val probe = FFprobeKit.getMediaInformation(inPath)
        if (!ReturnCode.isSuccess(probe.returnCode)) {
            pfd.close()
            throw IllegalStateException("FFprobe return=${probe.returnCode}, state=${probe.state}")
        }
        val info = probe.mediaInformation ?: throw IllegalStateException("FFprobe: нет информации")
        val audioStreams = info.streams.filter { it.type.equals("audio", true) }
        log("FFprobe: аудиодорожек=${audioStreams.size}")
        if (audioStreams.isEmpty()) {
            pfd.close()
            throw IllegalStateException("Аудиодорожек не найдено")
        }
        val chosen = if (audioStreams.getOrNull(audioIndex) != null) audioIndex else 0
        val first = audioStreams[chosen]
        val codec = (first.codec ?: "").lowercase(Locale.US)
        log("Выбрана дорожка #$chosen, codec=$codec")

        val mapping = chooseExtAndMime(codec)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val template = prefs.getString("name_template", "{name}.{ext}") ?: "{name}.{ext}"
        val subfolder = prefs.getString("subfolder", "AudioExtracted") ?: "AudioExtracted"
        val dirUriStr = prefs.getString("dir_uri", null)

        val base = displayName.substringBeforeLast('.', displayName)
        val outName = sanitizeName(
            applyTemplate(template, base, mapping.ext, mapping.mime.substringAfter("/"), "", "", "")
        )
        log("Итоговый файл: $outName (${mapping.mime})")

        val outTmp = File.createTempFile("out_", ".${mapping.ext}", cacheDir)

        val args = arrayOf(
            "-hide_banner", "-y",
            "-i", inPath,
            "-map", "0:a:$chosen",
            "-c", "copy",
            outTmp.absolutePath
        )
        log("FFmpeg: " + args.joinToString(" "))

        val session = FFmpegKit.executeWithArguments(args)
        val rc = session.returnCode
        log("FFmpeg: return=$rc, state=${session.state}")

        if (!ReturnCode.isSuccess(rc)) {
            val st = session.failStackTrace
            if (st != null) log("failStackTrace: $st")
            pfd.close()
            outTmp.delete()
            throw IllegalStateException("FFmpeg error: $rc")
        }

        val outSize = outTmp.length()
        log("Временный файл: ${outTmp.absolutePath} (${outSize} байт)")
        if (outSize <= 0) {
            pfd.close()
            outTmp.delete()
            throw IllegalStateException("FFmpeg создал пустой файл")
        }

        val saved = if (dirUriStr != null)
            saveToSAF(outTmp, dirUriStr, subfolder, outName, mapping.mime)
        else
            saveToDownloads(outTmp, outName, mapping.mime)
        log("Сохранено: $saved")

        try {
            contentResolver.openAssetFileDescriptor(saved, "r")?.use { afd ->
                log("Размер сохранённого файла: ${afd.length} байт")
            }
        } catch (_: Throwable) {}

        pfd.close()
        outTmp.delete()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class ExtMime(val ext: String, val mime: String)
    private fun chooseExtAndMime(codec: String): ExtMime = when {
        codec.contains("aac") || codec == "mp4a" -> ExtMime("m4a", "audio/mp4")
        codec.contains("opus") -> ExtMime("opus", "audio/ogg")
        codec.contains("vorbis") -> ExtMime("ogg", "audio/ogg")
        codec == "mp3" || codec.contains("layer3") -> ExtMime("mp3", "audio/mpeg")
        codec == "flac" -> ExtMime("flac", "audio/flac")
        codec == "ac3" -> ExtMime("ac3", "audio/ac3")
        codec == "eac3" || codec.contains("ec3") -> ExtMime("eac3", "audio/eac3")
        codec.startsWith("pcm") -> ExtMime("wav", "audio/wav")
        codec.contains("amr") -> ExtMime("amr", "audio/amr")
        codec.contains("dts") -> ExtMime("dts", "audio/vnd.dts")
        codec.contains("truehd") -> ExtMime("mka", "audio/x-matroska")
        else -> ExtMime("mka", "audio/x-matroska")
    }

    private fun sanitizeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun applyTemplate(tpl: String, base: String, ext: String, codec: String, sr: String, ch: String, lang: String): String =
        tpl.replace("{name}", base)
            .replace("{ext}", ext)
            .replace("{codec}", codec)
            .replace("{sr}", sr)
            .replace("{channels}", ch)
            .replace("{lang}", lang)

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) return cursor.getString(idx)
        }
        return null
    }

    private fun saveToSAF(srcFile: File, treeUriStr: String, subfolder: String, displayName: String, mime: String): Uri {
        val tree = DocumentFile.fromTreeUri(this, Uri.parse(treeUriStr))
            ?: throw IllegalStateException("Выбранная папка недоступна")
        val targetDir = if (subfolder.isNotBlank()) {
            tree.findFile(subfolder) ?: tree.createDirectory(subfolder) ?: tree
        } else tree
        val outDoc = targetDir.createFile(mime, displayName) ?: throw IllegalStateException("Не удалось создать файл в папке")
        contentResolver.openOutputStream(outDoc.uri)?.use { out ->
            srcFile.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("Не удалось открыть выходной поток")
        return outDoc.uri
    }

    private fun saveToDownloads(srcFile: File, displayName: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AudioExtracted")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Не удалось создать файл в Загрузках")
        resolver.openOutputStream(uri)?.use { out ->
            srcFile.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("Не удалось открыть целевой поток")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun saveLogToDownloads(): Uri? {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "AudioExtract_log_$ts.txt"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AudioExtracted/logs")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Throwable) { null }
    }
}
