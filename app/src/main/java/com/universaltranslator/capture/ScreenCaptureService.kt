package com.universaltranslator.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.*
import com.google.mlkit.common.model.DownloadConditions
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.universaltranslator.START"
        const val ACTION_STOP = "com.universaltranslator.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "screen_translation"
        private const val NOTIFICATION_ID = 41
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayRoot: FrameLayout? = null
    private var windowManager: WindowManager? = null
    private val processing = AtomicBoolean(false)
    private var lastRun = 0L
    private var generation = 0
    private var lastFrameSignature = ""
    private data class OcrItem(val block: Text.TextBlock, val background: Int, val patch: Bitmap)
    private var screenWidth = 1
    private var screenHeight = 1
    private val recognizers by lazy {
        listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        )
    }
    private val translationCache by lazy { TranslationCache(applicationContext) }
    private val languageId by lazy { LanguageIdentification.getClient() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_START) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, notification())
            startProjection(intent)
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Screen translation", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setContentTitle("Universal Translator active")
        .setContentText("Detecting and translating screen text")
        .setSmallIcon(android.R.drawable.ic_menu_search)
        .setOngoing(true).build()

    private fun startProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                   else intent.getParcelableExtra(EXTRA_DATA) as? Intent
        if (data == null || resultCode != Activity.RESULT_OK) { stopSelf(); return }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        thread = HandlerThread("translator-capture").also { it.start() }
        handler = Handler(thread!!.looper)
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ source ->
            val prefs = getSharedPreferences("translator", MODE_PRIVATE)
            val gameMode = prefs.getBoolean("gameMode", true)
            val intervalMs = if (gameMode) 900L else 1600L
            val now = System.currentTimeMillis()
            if (now - lastRun < intervalMs || !processing.compareAndSet(false, true)) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastRun = now
            val image = source.acquireLatestImage()
            if (image == null) { processing.set(false); return@setOnImageAvailableListener }
            try {
                val plane = image.planes[0]
                val bitmap = android.graphics.Bitmap.createBitmap(
                    image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride,
                    image.height, android.graphics.Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                image.close()
                recognize(cropped)
            } catch (_: Exception) {
                try { image.close() } catch (_: Exception) {}
                processing.set(false)
            }
        }, handler)

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        virtualDisplay = projection?.createVirtualDisplay(
            "UniversalTranslatorDisplay", screenWidth, screenHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler
        )
        ensureOverlay()
    }

    private fun recognize(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)
        // Run OCR engines sequentially. Most Latin-language screens finish on the
        // first engine; CJK/Devanagari engines are only tried when the prior one
        // returns no usable text. This avoids five simultaneous full-screen OCRs.
        recognizeWithFallback(input, 0) { result ->
            try {
                val unique = LinkedHashMap<String, OcrItem>()
                result?.textBlocks?.forEach blockLoop@ { block ->
                    val box = block.boundingBox ?: return@blockLoop
                    val raw = block.text.trim()
                    if (raw.length < 2 || box.width() < 8) return@blockLoop
                    val key = raw.replace(Regex("\\s+"), " ").lowercase()
                    if (!unique.containsKey(key)) {
                        unique[key] = OcrItem(block, estimateBackground(bitmap, box), ComicPatch.create(bitmap, box))
                    }
                }
                val signature = unique.values.joinToString("|") { item ->
                    val r = item.block.boundingBox
                    "${item.block.text.trim()}@${r?.left},${r?.top},${r?.right},${r?.bottom}"
                }
                if (signature != lastFrameSignature) {
                    lastFrameSignature = signature
                    val currentGeneration = ++generation
                    clearOverlay()
                    unique.values.forEach { item ->
                        translateBlock(item.block, item.background, item.patch, currentGeneration)
                    }
                }
            } catch (_: Exception) {
                // A bad frame must not terminate the capture service.
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                processing.set(false)
            }
        }
    }

    private fun recognizeWithFallback(
        input: InputImage,
        index: Int,
        onComplete: (Text?) -> Unit
    ) {
        if (index >= recognizers.size) {
            onComplete(null)
            return
        }
        try {
            recognizers[index].process(input)
                .addOnSuccessListener { result ->
                    if (result.textBlocks.any { it.text.trim().length >= 2 }) {
                        onComplete(result)
                    } else {
                        recognizeWithFallback(input, index + 1, onComplete)
                    }
                }
                .addOnFailureListener {
                    recognizeWithFallback(input, index + 1, onComplete)
                }
        } catch (_: Exception) {
            recognizeWithFallback(input, index + 1, onComplete)
        }
    }

    /**
     * Estimates the comic/game background from pixels immediately around the
     * OCR rectangle. A rounded patch placed at the same coordinates masks
     * glyphs while preserving most speech-balloon/background edges.
     */
    private fun estimateBackground(bitmap: Bitmap, rect: Rect): Int {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        val samples = ArrayList<Int>()
        val stepX = max(1, (right - left) / 12)
        val stepY = max(1, (bottom - top) / 8)
        for (x in left until right step stepX) {
            if (top > 1) samples.add(bitmap.getPixel(x, top - 1))
            if (bottom < bitmap.height - 1) samples.add(bitmap.getPixel(x, bottom))
        }
        for (y in top until bottom step stepY) {
            if (left > 1) samples.add(bitmap.getPixel(left - 1, y))
            if (right < bitmap.width - 1) samples.add(bitmap.getPixel(right, y))
        }
        if (samples.isEmpty()) return Color.WHITE
        fun median(selector: (Int) -> Int): Int {
            val values = samples.map(selector).sorted()
            return values[values.size / 2]
        }
        return Color.rgb(median { Color.red(it) }, median { Color.green(it) }, median { Color.blue(it) })
    }

    private fun translateBlock(block: Text.TextBlock, backgroundColor: Int, patch: Bitmap, currentGeneration: Int) {
        val raw = block.text.trim()
        val box = block.boundingBox ?: return
        val prefs = getSharedPreferences("translator", MODE_PRIVATE)
        val target = prefs.getString("target", "id") ?: "id"
        val mode = prefs.getString("mode", "both") ?: "both"
        val endpoint = prefs.getString("endpoint", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""

        languageId.identifyLanguage(raw).addOnSuccessListener { detected ->
            val source = if (detected == "und") "en" else detected
            val cached = translationCache.get(raw, source, target, mode)
            if (cached != null) {
                showTranslatedBlock(cached, box, backgroundColor, patch, currentGeneration)
                return@addOnSuccessListener
            }
            if (source == target) {
                translationCache.put(raw, source, target, mode, raw)
                showTranslatedBlock(raw, box, backgroundColor, patch, currentGeneration)
                return@addOnSuccessListener
            }
            if (mode != "offline" && endpoint.isNotBlank()) {
                Thread {
                    val translated = onlineTranslate(endpoint, apiKey, raw, source, target)
                    if (!translated.isNullOrBlank()) {
                        translationCache.put(raw, source, target, mode, translated)
                        showTranslatedBlock(translated, box, backgroundColor, patch, currentGeneration)
                    } else if (mode == "both") {
                        offlineTranslate(raw, source, target, mode, box, backgroundColor, patch, currentGeneration)
                    } else {
                        showTranslatedBlock(raw, box, backgroundColor, patch, currentGeneration)
                    }
                }.start()
            } else if (mode == "online") {
                showTranslatedBlock("Atur endpoint online", box, backgroundColor, patch, currentGeneration)
            } else {
                offlineTranslate(raw, source, target, mode, box, backgroundColor, patch, currentGeneration)
            }
        }.addOnFailureListener {
            offlineTranslate(raw, "en", target, mode, box, backgroundColor, patch, currentGeneration)
        }
    }

    private fun onlineTranslate(endpoint: String, key: String, text: String, source: String, target: String): String? {
        return try {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 7000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().put("q", text).put("source", source).put("target", target).put("format", "text")
            if (key.isNotBlank()) body.put("api_key", key)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val response = if (connection.responseCode in 200..299)
                connection.inputStream.bufferedReader().readText() else ""
            connection.disconnect()
            JSONObject(response).optString("translatedText").takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun offlineTranslate(text: String, source: String, target: String, mode: String, box: Rect, backgroundColor: Int, patch: Bitmap, currentGeneration: Int) {
        val from = TranslateLanguage.fromLanguageTag(source) ?: TranslateLanguage.ENGLISH
        val to = TranslateLanguage.fromLanguageTag(target) ?: TranslateLanguage.INDONESIAN
        if (from == to) { showTranslatedBlock(text, box, backgroundColor, patch, currentGeneration); return }
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translationCache.put(text, source, target, mode, it)
                        showTranslatedBlock(it, box, backgroundColor, patch, currentGeneration) }
                    .addOnFailureListener { showTranslatedBlock(text, box, backgroundColor, patch, currentGeneration) }
                    .addOnCompleteListener { translator.close() }
            }
            .addOnFailureListener {
                showTranslatedBlock("Model belum tersedia", box, backgroundColor, patch, currentGeneration)
                translator.close()
            }
    }

    private fun ensureOverlay() {
        if (overlayRoot != null || !Settings.canDrawOverlays(this)) return
        mainHandler.post {
            if (overlayRoot != null) return@post
            val root = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            try {
                windowManager?.addView(root, params)
                overlayRoot = root
            } catch (_: Exception) { overlayRoot = null }
        }
    }

    private fun clearOverlay() {
        mainHandler.post { overlayRoot?.removeAllViews() }
    }

    private fun showTranslatedBlock(text: String, bounds: Rect, backgroundColor: Int, patch: Bitmap, currentGeneration: Int) {
        mainHandler.post {
            if (currentGeneration != generation) return@post
            ensureOverlay()
            val root = overlayRoot ?: return@post
            val prefs = getSharedPreferences("translator", MODE_PRIVATE)
            val overlaySize = prefs.getFloat("overlayTextSize", 16f)
            val opacity = prefs.getFloat("overlayOpacity", 0.82f).coerceIn(0.15f, 1f)
            val density = resources.displayMetrics.density
            val left = bounds.left.coerceIn(0, screenWidth - 1)
            val top = bounds.top.coerceIn(0, screenHeight - 1)
            val width = min(screenWidth - left, max(bounds.width(), (120 * density).toInt()))
            val patchView = ImageView(this).apply {
                setImageBitmap(patch)
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = opacity
            }
            val patchLayout = FrameLayout.LayoutParams(
                max(1, bounds.width()), max(1, bounds.height())
            ).apply {
                leftMargin = bounds.left.coerceIn(0, screenWidth - 1)
                topMargin = bounds.top.coerceIn(0, screenHeight - 1)
                gravity = Gravity.TOP or Gravity.START
            }
            root.addView(patchView, patchLayout)
            val label = TextView(this).apply {
                this.text = text
                val luminance = (0.299 * Color.red(backgroundColor) + 0.587 * Color.green(backgroundColor) + 0.114 * Color.blue(backgroundColor))
                setTextColor(if (luminance > 150) Color.BLACK else Color.WHITE)
                textSize = overlaySize
                setPadding((6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
                setBackgroundColor(Color.TRANSPARENT)
                minHeight = max(bounds.height(), (overlaySize * 1.5f * density).toInt())
                maxLines = 5
                elevation = 12f
            }
            val layout = FrameLayout.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = left
                topMargin = top
                gravity = Gravity.TOP or Gravity.START
            }
            root.addView(label, layout)
        }
    }

    override fun onDestroy() {
        try { windowManager?.removeView(overlayRoot) } catch (_: Exception) {}
        overlayRoot = null
        lastFrameSignature = ""
        virtualDisplay?.release()
        reader?.close()
        projection?.stop()
        recognizers.forEach { it.close() }
        translationCache.close()
        languageId.close()
        thread?.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
