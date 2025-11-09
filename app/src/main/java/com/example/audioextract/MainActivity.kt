package com.example.audioextract

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.arthenica.ffmpegkit.FFprobeKit

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> handleIntent(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        maybeAskNotifPermission { handleIntent(intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeAskNotifPermission { handleIntent(intent) }
}

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun maybeAskNotifPermission(after: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        after()
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val mode = prefs.getString("track_mode", "prompt")

        when (action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: run {
                    status.text = "Не получил файл."; return
                }
                val inTmp = CacheUtils.copyToCache(this, uri) ?: run {
                    status.text = "Не удалось открыть входной файл."; return
                }
                val info = FFprobeKit.getMediaInformation(inTmp.absolutePath).mediaInformation
                val audios = info?.streams?.filter { it.type.equals("audio", true) } ?: emptyList()

                if (mode == "first" || audios.size <= 1) {
                    startExtractService(arrayListOf(uri), 0)
                } else if (mode == "remember") {
                    val remembered = prefs.getInt("remembered_index", 0)
                    startExtractService(arrayListOf(uri), remembered)
                } else {
                    val items = audios.mapIndexed { idx, s ->
                        val codec = s.codec ?: "audio"
                        "#$idx · $codec"
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Выбери аудиодорожку")
                        .setItems(items) { _, which ->
                            if (mode == "remember") {
                                prefs.edit().putInt("remembered_index", which).apply()
                            }
                            startExtractService(arrayListOf(uri), which)
                        }
                        .setCancelable(false)
                        .show()
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (list.isNullOrEmpty()) { status.text = "Список пуст."; return }
                val inTmp = CacheUtils.copyToCache(this, list.first()) ?: run {
                    startExtractService(ArrayList(list), 0); return
                }
                val info = FFprobeKit.getMediaInformation(inTmp.absolutePath).mediaInformation
                val audios = info?.streams?.filter { it.type.equals("audio", true) } ?: emptyList()

                if (mode == "first" || audios.size <= 1) {
                    startExtractService(ArrayList(list), 0)
                } else if (mode == "remember") {
                    val remembered = prefs.getInt("remembered_index", 0)
                    startExtractService(ArrayList(list), remembered)
                } else {
                    val items = audios.mapIndexed { idx, s ->
                        val codec = s.codec ?: "audio"
                        "#$idx · $codec"
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Выбери аудиодорожку (для всех файлов)")
                        .setItems(items) { _, which ->
                            if (mode == "remember") {
                                prefs.edit().putInt("remembered_index", which).apply()
                            }
                            startExtractService(ArrayList(list), which)
                        }
                        .setCancelable(false)
                        .show()
                }
            }

            else -> status.text = "Открой видео и поделись им сюда."
        }
    }

    private fun startExtractService(uris: ArrayList<Uri>, audioIndex: Int) {
        progress.progress = 0
        status.text = "Запущено…"
        val strUris = ArrayList<String>(uris.size).apply { uris.forEach { add(it.toString()) } }
        val i = Intent(this, ExtractService::class.java)
            .putStringArrayListExtra(ExtractService.EXTRA_URIS, strUris)
            .putExtra(ExtractService.EXTRA_AUDIO_INDEX, audioIndex)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
}

object CacheUtils {
    fun copyToCache(ctx: android.content.Context, src: Uri): java.io.File? {
        return try {
            val f = java.io.File.createTempFile("probe_", ".bin", ctx.cacheDir)
            ctx.contentResolver.openInputStream(src)?.use { `in` ->
                f.outputStream().use { out -> `in`.copyTo(out) }
            } ?: return null
            f
        } catch (_: Throwable) {
            null
        }
    }
}
