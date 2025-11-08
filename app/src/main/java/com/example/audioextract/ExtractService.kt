
package com.example.audioextract

import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.*
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class ExtractService : Service() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_AUDIO_INDEX = "audioIndex"
        private const val NOTIF_ID = 1001
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val strUris = intent?.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
        val audioIndex = intent?.getIntExtra(EXTRA_AUDIO_INDEX, 0) ?: 0
        if (strUris.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

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
                val src = Uri.parse(sUri)
                try {
                    processOne(src, idx, strUris.size, audioIndex)
                } catch (e: Exception) {
                    e.printStackTrace()
                    NotificationUtils.done(this, NOTIF_ID, "Извлечение аудио", "Ошибка на файле ${idx + 1}/${strUris.size}")
                }
            }
            stopSelf()
        }.start()

        return START_NOT_STICKY
    }

    private fun sanitizeName(s: String): String =
        s.replace(Regex("[\\/:*?\"<>|]"), "_")

    private fun applyTemplate(template: String, base: String, ext: String, codec: String, sr: String, ch: String, lang: String): String {
        return template
            .replace("{name}", base)
            .replace("{ext}", ext)
            .replace("{codec}", codec)
            .replace("{sr}", sr)
            .replace("{channels}", ch)
            .replace("{lang}", lang)
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
        val chosen = audioStreams.getOrNull(audioIndex) ?: audioStreams.firstOrNull()
            ?: throw IllegalStateException("Аудиодорожка не найдена")
        val durationSec = info.duration?.toDoubleOrNull() ?: 0.0

        val codec = (chosen.codec ?: chosen.codecName ?: "").lowercase(Locale.US)
        val mapping = chooseExtAndMime(codec)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val template = prefs.getString("name_template", "{name}.{ext}") ?: "{name}.{ext}"
        val subfolder = prefs.getString("subfolder", "AudioExtracted") ?: "AudioExtracted"
        val dirUriStr = prefs.getString("dir_uri", null)

        val lang = chosen.tags?.get("language") ?: chosen.language ?: "und"
        val sr = chosen.sampleRate ?: ""
        val ch = (chosen.channels ?: 0).toString()
        val displayOut = sanitizeName(applyTemplate(template, base, mapping.ext, mapping.mime.substringAfter("/"), sr, ch, lang))

        val outTmp = File.createTempFile("out_", ".${mapping.ext}", cacheDir)

        val cmd = listOf(
            "-hide_banner", "-y",
            "-i", inTmp.absolutePath,
            "-map", "0:a:$audioIndex",
            "-c", "copy",
            outTmp.absolutePath
        ).joinToString(" ")

        val session = FFmpegKit.executeAsync(cmd,
            { completed: FFmpegSession ->
                if (ReturnCode.isSuccess(completed.returnCode)) {
                    val saved = if (dirUriStr != null)
                        saveToSAF(outTmp, dirUriStr, subfolder, displayOut, mapping.mime)
                    else
                        saveToDownloads(outTmp, displayOut, mapping.mime)

                    val openPi = PendingIntent.getActivity(
                        this, 0,
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(saved, mapping.mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    NotificationUtils.done(this, NOTIF_ID, "Извлечение аудио", "Готово: ${index + 1}/$total", openPi)
                } else {
                    NotificationUtils.done(this, NOTIF_ID, "Извлечение аудио", "Ошибка на файле ${index + 1}/$total")
                }
                inTmp.delete()
                outTmp.delete()
            },
            { _ -> },
            { stats: Statistics ->
                val tMs = stats.time.toDouble()
                val pctFile = if (durationSec > 0.0) ((tMs/1000.0) / durationSec * 100.0) else 0.0
                val pctOverall = (((index) / total.toDouble())*100.0 + (pctFile/total)).roundToInt()
                NotificationUtils.withProgress(this, NOTIF_ID, "Извлечение: ${index + 1}/$total", "$displayOut", pctOverall.coerceIn(0, 100))
            }
        )
        session.await()
    }

    private data class ExtMime(val ext: String, val mime: String)

    private fun chooseExtAndMime(codec: String): ExtMime {
        return when {
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
    }

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
            val existing = tree.findFile(subfolder) ?: tree.createDirectory(subfolder)
            existing ?: tree
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

    override fun onBind(intent: Intent?): IBinder? = null
}
