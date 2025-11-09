package com.example.audioextract

import android.app.PendingIntent
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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale

class ExtractService : Service() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_AUDIO_INDEX = "audioIndex"
        private const val NOTIF_ID = 1001
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val strUris = intent?.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
        val audioIndex = intent?.getIntExtra(EXTRA_AUDIO_INDEX, 0) ?: 0
        if (strUris.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        NotificationUtils.ensureChannel(this)
        val notif = NotificationCompat.Builder(this, NotificationUtils.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Извлечение аудио")
            .setContentText("Подготовка…")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)

        Thread {
            strUris.forEachIndexed { idx, sUri ->
                try {
                    processOne(Uri.parse(sUri), idx, strUris.size, audioIndex)
                } catch (_: Throwable) {
                    NotificationUtils.done(this, NOTIF_ID, "Извлечение аудио", "Ошибка на файле ${idx + 1}/${strUris.size}")
                }
            }
            stopSelf()
        }.start()

        return START_NOT_STICKY
    }

    private fun processOne(src: Uri, index: Int, total: Int, audioIndex: Int) {
        val inTmp = File.createTempFile("in_", ".bin", cacheDir)
        contentResolver.openInputStream(src)?.use { input ->
            inTmp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Не удалось открыть входной поток")

        val displayName = queryDisplayName(src) ?: inTmp.name
        val base = displayName.substringBeforeLast('.', displayName)

        val info = FFprobeKit.getMediaInformation(inTmp.absolutePath).mediaInformation
            ?: throw IllegalStateException("FFprobe не получил информацию")
        val audioStreams = info.streams.filter { it.type.equals("audio", true) }
        val chosenExists = audioStreams.getOrNull(audioIndex) != null
        val first = audioStreams.firstOrNull() ?: throw IllegalStateException("Аудиодорожка не найдена")

        val codec = (first.codec ?: "").lowercase(Locale.US)
        val mapping = chooseExtAndMime(codec)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val template = prefs.getString("name_template", "{name}.{ext}") ?: "{name}.{ext}"
        val subfolder = prefs.getString("subfolder", "AudioExtracted") ?: "AudioExtracted"
        val dirUriStr = prefs.getString("dir_uri", null)

        // плейсхолдеры {sr}/{channels}/{lang} заполним пустыми — они не обязательны
        val outName = sanitizeName(
            applyTemplate(template, base, mapping.ext, mapping.mime.substringAfter("/"), "", "", "")
        )

        val outTmp = File.createTempFile("out_", ".${mapping.ext}", cacheDir)
        val mapArg = if (chosenExists) "0:a:$audioIndex" else "0:a:0"
        val cmd = listOf("-hide_banner", "-y", "-i", inTmp.absolutePath, "-map", mapArg, "-c", "copy", outTmp.absolutePath)
            .joinToString(" ")

        val session = FFmpegKit.execute(cmd)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            inTmp.delete(); outTmp.delete()
            throw IllegalStateException("FFmpeg error: ${session.returnCode}")
        }

        val saved = if (dirUriStr != null)
            saveToSAF(outTmp, dirUriStr, subfolder, outName, mapping.mime)
        else
            saveToDownloads(outTmp, outName, mapping.mime)

        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(saved, mapping.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        NotificationUtils.done(this, NOTIF_ID, "Извлечение аудио", "Готово: ${index + 1}/$total", openPi)

        inTmp.delete()
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
        else -> ExtMime("mka", "audio/x-matroska")
    }

    private fun sanitizeName(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|]"""), "_")

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
}
