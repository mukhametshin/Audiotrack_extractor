package com.example.audioextract

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
    private lateinit var logView: TextView

    private fun appendLog(line: String) {
        logView.append((if (logView.text.isNullOrEmpty()) "" else "\n") + line)
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> handleIntent(intent) }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                ExtractService.ACTION_LOG -> {
                    val msg = intent.getStringExtra(ExtractService.EXTRA_MESSAGE) ?: return
                    appendLog(msg)
                }
                ExtractService.ACTION_PROGRESS -> {
                    val total = intent.getIntExtra(ExtractService.EXTRA_TOTAL, 0)
                    val current = intent.getIntExtra(ExtractService.EXTRA_CURRENT, 0)
                    val msg = intent.getStringExtra(ExtractService.EXTRA_MESSAGE) ?: ""
                    if (msg.isNotBlank()) appendLog(msg)
                    status.text = "Обработка: $current/$total"
                    progress.visibility = View.VISIBLE
                    progress.isIndeterminate = false
                    progress.max = if (total > 0) total else 1
                    progress.progress = current.coerceAtMost(progress.max)
                }
                ExtractService.ACTION_ERROR -> {
                    val msg = intent.getStringExtra(ExtractService.EXTRA_MESSAGE) ?: "Ошибка"
                    appendLog("ERROR: " + msg)
                    status.text = "Ошибка"
                    progress.visibility = View.GONE
                }
                ExtractService.ACTION_DONE -> {
                    val msg = intent.getStringExtra(ExtractService.EXTRA_MESSAGE) ?: "Готово"
                    appendLog(msg)
                    status.text = msg
                    progress.visibility = View.GONE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        logView = findViewById(R.id.logView)
        maybeAskNotifPermission { handleIntent(intent) }
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(ExtractService.ACTION_LOG)
            addAction(ExtractService.ACTION_PROGRESS)
            addAction(ExtractService.ACTION_ERROR)
            addAction(ExtractService.ACTION_DONE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(progressReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(progressReceiver, f)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(progressReceiver) } catch (_: Throwable) {}
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
                if (mode == "prompt") {
                    try {
                        appendLog("FFprobe: анализ входа $uri")
                        val info = FFprobeKit.getMediaInformation(uri.toString()).mediaInformation
                        val audios = info?.streams?.filter { it.type.equals("audio", true) } ?: emptyList()
                        appendLog("Найдено аудиодорожек: ${audios.size}")
                        if (audios.size <= 1) {
                            startExtractService(arrayListOf(uri), 0)
                        } else {
                            val items = audios.mapIndexed { idx, s -> "#$idx · ${(s.codec ?: "audio")}" }.toTypedArray()
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Выбери аудиодорожку")
                                .setItems(items) { _, which -> startExtractService(arrayListOf(uri), which) }
                                .setCancelable(true)
                                .show()
                        }
                    } catch (t: Throwable) {
                        appendLog("FFprobe ERROR: ${t.message}")
                        status.text = "Ошибка анализа входного файла"
                    }
                } else {
                    startExtractService(arrayListOf(uri), if (mode == "remember") prefs.getInt("remembered_index", 0) else 0)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (list.isNullOrEmpty()) { status.text = "Список пуст."; return }
                startExtractService(ArrayList(list), if (mode == "remember") prefs.getInt("remembered_index", 0) else 0)
            }
            else -> status.text = "Открой видео и поделись им сюда."
        }
    }

    private fun startExtractService(uris: ArrayList<Uri>, audioIndex: Int) {
        uris.forEach { grantUriPermission(packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val i = Intent(this, ExtractService::class.java)
            .putParcelableArrayListExtra(ExtractService.EXTRA_URI_LIST, uris)
            .putExtra(ExtractService.EXTRA_AUDIO_INDEX, audioIndex)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (uris.isNotEmpty()) {
            val clip = ClipData.newUri(contentResolver, "videos", uris.first())
            for (k in 1 until uris.size) clip.addItem(ClipData.Item(uris[k]))
            i.clipData = clip
        }

        status.text = "Запущено…"
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true

        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
}
