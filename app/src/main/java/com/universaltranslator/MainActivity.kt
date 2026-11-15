package com.universaltranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universaltranslator.capture.ScreenCaptureService

class MainActivity : ComponentActivity() {
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
            }
            startForegroundService(service)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TranslatorHome(
                    onStart = { startCapture() },
                    onStop = { startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP)) }
                )
            }
        }
    }

    private fun startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslatorHome(onStart: () -> Unit, onStop: () -> Unit) {
    var target by remember { mutableStateOf("id") }
    var mode by remember { mutableStateOf("both") }
    var endpoint by remember { mutableStateOf("https://libretranslate.de/translate") }
    var apiKey by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("translator", Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        target = prefs.getString("target", "id") ?: "id"
        mode = prefs.getString("mode", "both") ?: "both"
        endpoint = prefs.getString("endpoint", endpoint) ?: endpoint
        apiKey = prefs.getString("apiKey", "") ?: ""
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text("Universal Translator", style = MaterialTheme.typography.headlineSmall)
                    Text("Realtime screen translation", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Target language", style = MaterialTheme.typography.titleMedium)
                    var expanded by remember { mutableStateOf(false) }
                    val languages = listOf("id" to "Indonesia", "en" to "English", "es" to "Español", "ja" to "日本語", "ko" to "한국어", "zh" to "中文", "fr" to "Français", "de" to "Deutsch")
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = languages.firstOrNull { it.first == target }?.second ?: "Indonesia",
                            onValueChange = {}, readOnly = true, label = { Text("Translate to") },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            languages.forEach { (code, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    target = code; prefs.edit().putString("target", code).apply(); expanded = false
                                })
                            }
                        }
                    }
                    Text("Translation engine", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == "both", onClick = { mode = "both"; prefs.edit().putString("mode", mode).apply() }, label = { Text("Hybrid") })
                        FilterChip(selected = mode == "offline", onClick = { mode = "offline"; prefs.edit().putString("mode", mode).apply() }, label = { Text("Offline") })
                        FilterChip(selected = mode == "online", onClick = { mode = "online"; prefs.edit().putString("mode", mode).apply() }, label = { Text("Online") })
                    }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var gameMode by remember { mutableStateOf(prefs.getBoolean("gameMode", true)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Game mode", style = MaterialTheme.typography.titleMedium)
                            Text("Prioritas pembaruan layar. Mesin OCR dijalankan bergantian dan berhenti saat teks terdeteksi; beban bergantung pada bahasa dan isi layar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        Switch(checked = gameMode, onCheckedChange = {
                            gameMode = it
                            prefs.edit().putBoolean("gameMode", it).apply()
                        })
                    }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Overlay appearance", style = MaterialTheme.typography.titleMedium)
                    var textSize by remember { mutableFloatStateOf(prefs.getFloat("overlayTextSize", 16f)) }
                    var opacity by remember { mutableFloatStateOf(prefs.getFloat("overlayOpacity", 0.82f)) }
                    Text("Text size: ${textSize.toInt()} sp")
                    Slider(value = textSize, onValueChange = {
                        textSize = it
                        prefs.edit().putFloat("overlayTextSize", it).apply()
                    }, valueRange = 11f..30f)
                    Text("Cleanup patch opacity: ${(opacity * 100).toInt()}%")
                    Slider(value = opacity, onValueChange = {
                        opacity = it
                        prefs.edit().putFloat("overlayOpacity", it).apply()
                    }, valueRange = 0.15f..1f)
                    Text("Perubahan berlaku pada hasil terjemahan berikutnya.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Online translation endpoint", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(endpoint, { endpoint = it; prefs.edit().putString("endpoint", it).apply() }, label = { Text("LibreTranslate URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(apiKey, { apiKey = it; prefs.edit().putString("apiKey", it).apply() }, label = { Text("API key (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("Endpoint harus mendukung format API LibreTranslate. Gunakan server tepercaya; jangan masukkan API key yang tidak ingin dibagikan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Start screen translator") }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop translator") }
            Text("Cara pakai: izinkan tampil di atas aplikasi lain, tekan Start, lalu setujui perekaman layar Android. Bubble terjemahan akan muncul saat teks terdeteksi.", style = MaterialTheme.typography.bodySmall)
            Text("Catatan: beberapa game melindungi layarnya atau memblokir capture. Pada aplikasi seperti itu, OCR tidak dapat membaca konten.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
