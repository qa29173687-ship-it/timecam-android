package tw.timecam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.MediaScannerConnection
import android.location.*
import java.util.Locale
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tw.timecam.upload.UploadPrefs
import tw.timecam.upload.UploadRepo
import tw.timecam.upload.UploadScheduler
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.effects.OverlayEffect
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var wm: WatermarkView
    private lateinit var shutter: ShutterView
    private val bg = Executors.newSingleThreadExecutor()
    private val capturing = AtomicBoolean(false)
    private var shutterSound: MediaActionSound? = null
    private var thumbGen = 0
    private var provider: ProcessCameraProvider? = null
    private var extMgr: ExtensionsManager? = null
    private var camera: androidx.camera.core.Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var videoMode = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    private var torchOn = false
    private var extMode = ExtensionMode.NONE
    private var rotation = Surface.ROTATION_0
    private var lastTap = 0L
    @Volatile private var recData: WmData? = null
    private var lm: LocationManager? = null
    private var lastGeoLoc: Location? = null
    private var lastGeoAt = 0L
    private var lastMediaUri: Uri? = null
    private var lastMediaMime: String = "image/jpeg"
    private val locListener = LocationListener { onLoc(it) }
    private val effectThread = HandlerThread("overlay").apply { start() }
    private val overlay by lazy {
        OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, Handler(effectThread.looper)) { toast("浮水印錯誤：${it.message}") }.apply {
            setOnDrawListener { frame ->
                val c = frame.overlayCanvas
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                val w = frame.size.width.toFloat(); val h = frame.size.height.toFloat()
                val deg = frame.rotationDegrees
                val uw = if (deg % 180 == 0) w else h; val uh = if (deg % 180 == 0) h else w
                // 直立座標 → 緩衝區座標
                val m = Matrix(); m.postRotate(-deg.toFloat())
                val r = RectF(0f, 0f, uw, uh); m.mapRect(r); m.postTranslate(-r.left, -r.top)
                c.setMatrix(m)
                Watermark.stamp(c, uw, uh, recData ?: wm.data, Names.now())
                true
            }
        }
    }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(Manifest.permission.CAMERA)) startCamera() else toast("需要相機權限")
        startLocation()
    }
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) stampPicked(uri) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview); wm = findViewById(R.id.wm); shutter = findViewById(R.id.shutter)
        shutterSound = MediaActionSound().also { runCatching { it.load(MediaActionSound.SHUTTER_CLICK) } }
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        previewView.setBackgroundColor(0xFF1C1C1C.toInt())
        previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        val top = findViewById<View>(R.id.top); val ctrl = findViewById<View>(R.id.ctrl)
        // 工具列避開狀態列／瀏海，控制列避開導覽列（三鍵或手勢）
        EdgeInsets.pad(top, bottom = false) { top.post { updateFrame() } }
        EdgeInsets.pad(ctrl, top = false) { ctrl.post { updateFrame() } }
        ctrl.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> ctrl.post { updateFrame() } }
        top.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> top.post { updateFrame() } }
        findViewById<View>(R.id.mPhoto).setOnClickListener { setMode(false) }
        findViewById<View>(R.id.mVideo).setOnClickListener { setMode(true) }
        setMode(false, rebind = false)
        shutter.setOnClickListener { if (videoMode) toggleRec() else takePhoto() }
        findViewById<View>(R.id.settings).setOnClickListener { openTemplates() }
        findViewById<View>(R.id.btnEdit).setOnClickListener { openSheet() }
        findViewById<View>(R.id.btnWmEntry).setOnClickListener { openSheet() }
        findViewById<View>(R.id.btnPin).setOnClickListener { openSheet() }
        findViewById<View>(R.id.pick).setOnClickListener { openLastPhoto() }
        findViewById<View>(R.id.btnFlash).setOnClickListener { cycleFlash() }
        findViewById<View>(R.id.btnLens).setOnClickListener {
            if (recording != null) return@setOnClickListener
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            extMode = ExtensionMode.NONE; bind()
        }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showDrawer(true) }
        findViewById<View>(R.id.scrim).setOnClickListener { showDrawer(false) }
        findViewById<View>(R.id.dSettings).setOnClickListener { showDrawer(false); startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.dTorch).setOnClickListener { camera?.cameraControl?.enableTorch(!torchOn) }
        findViewById<View>(R.id.dExt).setOnClickListener { cycleExt() }
        findViewById<View>(R.id.dStamp).setOnClickListener {
            showDrawer(false)
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        val stage = findViewById<View>(R.id.stage)
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> stage.post { updateFrame() } }
        setupGestures(); setupSliders(); loadLastThumb()
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when (o) { in 45..134 -> Surface.ROTATION_270; in 135..224 -> Surface.ROTATION_180; in 225..314 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
                if (r != rotation) { rotation = r; imageCapture?.targetRotation = r; if (recording == null) videoCapture?.targetRotation = r }
            }
        }.enable()
        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT < 29) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (need.all { has(it) }) { startCamera(); startLocation() } else perms.launch(need.toTypedArray())
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    private fun refreshSite() { /* 社宅名稱改在浮水印／編輯頁顯示 */ }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = f.get()
            val ef = ExtensionsManager.getInstanceAsync(this, provider!!)
            ef.addListener({ extMgr = runCatching { ef.get() }.getOrNull(); bind() }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun baseSelector() = CameraSelector.Builder().requireLensFacing(lensFacing).build()
    private fun supportedExt(): List<Int> {
        val m = extMgr ?: return listOf(ExtensionMode.NONE)
        return listOf(ExtensionMode.NONE) + listOf(ExtensionMode.AUTO, ExtensionMode.HDR, ExtensionMode.NIGHT).filter { runCatching { m.isExtensionAvailable(baseSelector(), it) }.getOrDefault(false) }
    }
    private fun extName(m: Int) = when (m) { ExtensionMode.AUTO -> "自動"; ExtensionMode.HDR -> "HDR"; ExtensionMode.NIGHT -> "夜間"; else -> "標準" }

    private fun bind() {
        val p = provider ?: return
        p.unbindAll(); camera = null
        val ratio = if (videoMode) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val prevSize = if (videoMode) Size(1920, 1080) else Size(1920, 1440)
        val prs = ResolutionSelector.Builder().setAspectRatioStrategy(ratio)
            .setResolutionStrategy(ResolutionStrategy(prevSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
        val stillSize = Size(ImageMath.MAX_STILL_LONG_EDGE, ImageMath.MAX_STILL_LONG_EDGE * 3 / 4) // 4000x3000 ≈ 12MP
        val rs = ResolutionSelector.Builder().setAspectRatioStrategy(ratio)
            .setResolutionStrategy(ResolutionStrategy(stillSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
        val preview = Preview.Builder().setResolutionSelector(prs).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        try {
            if (!videoMode) {
                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setFlashMode(flashMode)
                    .setResolutionSelector(rs).setJpegQuality(95).setTargetRotation(rotation).build()
                imageCapture = ic; videoCapture = null
                val sel = if (extMode != ExtensionMode.NONE && extMgr != null) extMgr!!.getExtensionEnabledCameraSelector(baseSelector(), extMode) else baseSelector()
                val g = UseCaseGroup.Builder().setViewPort(viewPort()).addUseCase(preview).addUseCase(ic).build()
                camera = p.bindToLifecycle(this, sel, g)
            } else {
                fun tryBind(qs: List<Quality>, bitrate: Int) {
                    val rec = Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(qs, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .setTargetVideoEncodingBitRate(bitrate).build()
                    val vc = VideoCapture.withOutput(rec).also { it.targetRotation = rotation }
                    videoCapture = vc; imageCapture = null
                    val g = UseCaseGroup.Builder().setViewPort(viewPort()).addUseCase(preview).addUseCase(vc).addEffect(overlay).build()
                    camera = p.bindToLifecycle(this, baseSelector(), g)
                }
                try { tryBind(listOf(Quality.UHD, Quality.FHD, Quality.HD), 40_000_000) }
                catch (e: Exception) { p.unbindAll(); tryBind(listOf(Quality.FHD, Quality.HD), 20_000_000) }
            }
        } catch (e: Exception) { toast("相機啟動失敗：${e.message}"); return }
        observeCamera(); updateChips(); updateFrame()
    }

    /** 畫面寬高比（直式）：照片 3:4、錄影 9:16 */
    private fun aspect() = if (videoMode) 9f / 16f else 3f / 4f
    private fun viewPort() = ViewPort.Builder(if (videoMode) android.util.Rational(9, 16) else android.util.Rational(3, 4), Surface.ROTATION_0)
        .setScaleType(ViewPort.FIT).build()

    /** 預覽區域：上方工具列與下方控制列之間；浮水印放在實際影像框內 */
    private fun updateFrame() {
        val stage = findViewById<View>(R.id.stage)
        if (stage.width == 0 || stage.height == 0) return
        val r = ImageMath.fit(stage.width.toFloat(), stage.height.toFloat(), aspect())
        val lp = previewView.layoutParams as FrameLayout.LayoutParams
        val w = r[2].toInt(); val h = r[3].toInt(); val l = r[0].toInt(); val t = r[1].toInt()
        if (lp.width != w || lp.height != h || lp.leftMargin != l || lp.topMargin != t) {
            lp.width = w; lp.height = h; lp.leftMargin = l; lp.topMargin = t; lp.gravity = Gravity.NO_GRAVITY
            previewView.layoutParams = lp
        }
        wm.imageRect = RectF(r[0], r[1], r[0] + r[2], r[1] + r[3]); wm.invalidate()
    }

    private fun observeCamera() {
        val cam = camera ?: return
        cam.cameraInfo.zoomState.observe(this) { z -> renderZoom(z.minZoomRatio, z.maxZoomRatio, z.zoomRatio) }
        cam.cameraInfo.torchState.observe(this) { torchOn = it == TorchState.ON; updateChips() }
        val es = cam.cameraInfo.exposureState; val ev = findViewById<SeekBar>(R.id.ev)
        findViewById<View>(R.id.evRow).visibility = if (es.isExposureCompensationSupported) View.VISIBLE else View.GONE
        if (es.isExposureCompensationSupported) {
            ev.max = es.exposureCompensationRange.upper - es.exposureCompensationRange.lower
            ev.progress = es.exposureCompensationIndex - es.exposureCompensationRange.lower; updateEvLabel()
        }
    }
    private fun updateEvLabel() {
        val es = camera?.cameraInfo?.exposureState ?: return
        val v = es.exposureCompensationIndex * es.exposureCompensationStep.toFloat()
        findViewById<TextView>(R.id.evLbl).text = if (v == 0f) "EV 0" else String.format("EV %+.1f", v)
    }

    private fun setupSliders() {
        findViewById<SeekBar>(R.id.ev).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                val cam = camera ?: return; if (!user) return
                cam.cameraControl.setExposureCompensationIndex(p + cam.cameraInfo.exposureState.exposureCompensationRange.lower)
                    .addListener({ updateEvLabel() }, ContextCompat.getMainExecutor(this@MainActivity))
            }
            override fun onStartTrackingTouch(s: SeekBar) {}; override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false; val z = cam.cameraInfo.zoomState.value ?: return false
                cam.cameraControl.setZoomRatio((z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio)); return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val cam = camera ?: return false
                lastTap = SystemClock.elapsedRealtime()
                val pt = previewView.meteringPointFactory.createPoint(e.x, e.y)
                cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(4, TimeUnit.SECONDS).build())
                cam.cameraControl.setExposureCompensationIndex(0); findViewById<SeekBar>(R.id.ev).let { s -> cam.cameraInfo.exposureState.let { s.progress = -it.exposureCompensationRange.lower } }
                updateEvLabel()
                val f = findViewById<View>(R.id.focus)
                f.x = previewView.left + e.x - f.width / 2; f.y = previewView.top + e.y - f.height / 2
                f.scaleX = 1.4f; f.scaleY = 1.4f; f.alpha = 1f
                f.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction { f.animate().alpha(0f).setStartDelay(900).setDuration(300) }
                val evRow = findViewById<View>(R.id.evRow)
                evRow.visibility = View.VISIBLE
                evRow.animate().setStartDelay(2500).withEndAction { evRow.visibility = View.GONE }.start()
                return true
            }
        })
        previewView.setOnTouchListener { _, e -> scale.onTouchEvent(e); if (!scale.isInProgress) tap.onTouchEvent(e); true }
    }

    private fun setMode(video: Boolean, rebind: Boolean = true) {
        if (recording != null) return
        videoMode = video; shutter.video = video
        val on = findViewById<TextView>(if (video) R.id.mVideo else R.id.mPhoto); val off = findViewById<TextView>(if (video) R.id.mPhoto else R.id.mVideo)
        on.setTextColor(0xFF111111.toInt()); off.setTextColor(0xFF9A9A9A.toInt())
        on.paint.isUnderlineText = false; paintModeDot(on, true); paintModeDot(off, false)
        if (video) extMode = ExtensionMode.NONE
        if (rebind) bind()
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) { ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON; ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF; else -> ImageCapture.FLASH_MODE_AUTO }
        imageCapture?.flashMode = flashMode; updateChips()
    }
    private fun cycleExt() {
        if (videoMode) { toast("HDR／夜間僅適用於照片模式"); return }
        val list = supportedExt()
        if (list.size == 1) { toast("此裝置不支援 自動／HDR／夜間模式"); return }
        extMode = list[(list.indexOf(extMode) + 1) % list.size]; bind()
    }
    private fun updateChips() {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        findViewById<ImageButton>(R.id.btnFlash).apply {
            visibility = if (hasFlash && !videoMode) View.VISIBLE else View.INVISIBLE
            setImageResource(when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash
                ImageCapture.FLASH_MODE_OFF -> R.drawable.ic_flash_off
                else -> R.drawable.ic_flash_auto
            })
            contentDescription = "閃光燈：" + when (flashMode) { ImageCapture.FLASH_MODE_ON -> "開"; ImageCapture.FLASH_MODE_OFF -> "關"; else -> "自動" }
        }
        findViewById<TextView>(R.id.dTorch).apply {
            isEnabled = hasFlash
            text = "手電筒　　" + if (torchOn) "開" else "關"
        }
        findViewById<TextView>(R.id.dExt).text = "影像模式　　" + extName(extMode)
    }

    // ---------- 照片 ----------
    private fun shutterSoundOn() = WmData.prefs(this).getBoolean("shutterSound", true)

    /** 按下當下就給手感：震動 + 白屏 +（可選）快門聲。對焦或存檔都不必等。 */
    private fun shutterFeedback() {
        val fx = findViewById<View>(R.id.flashFx)
        fx.animate().cancel()
        fx.alpha = 0.8f
        fx.animate().alpha(0f).setDuration(350).start()
        val kind = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
        shutter.performHapticFeedback(kind)
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 29) {
                    val effect = runCatching { VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK) }
                        .getOrElse { VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) }
                    vib.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION") vib.vibrate(25)
                }
            }
        }
        if (shutterSoundOn()) {
            val mode = (getSystemService(AUDIO_SERVICE) as AudioManager).ringerMode
            if (mode == AudioManager.RINGER_MODE_NORMAL) runCatching { shutterSound?.play(MediaActionSound.SHUTTER_CLICK) }
        }
    }

    private fun kickAf() {
        val cam = camera ?: return
        if (SystemClock.elapsedRealtime() - lastTap <= 4000) return
        val w = previewView.width; val h = previewView.height
        if (w <= 0 || h <= 0) return
        val pt = previewView.meteringPointFactory.createPoint(w / 2f, h / 2f)
        runCatching {
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            )
        }
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        shutterFeedback()
        kickAf()
        // CameraX 仍在出圖時只給回饋、不再送第二次 takePicture
        if (!capturing.compareAndSet(false, true)) return
        capture(ic)
    }

    private fun capture(ic: ImageCapture) {
        val t = Names.now(); val d = wm.data; val loc = Geo.loc
        val src = File(cacheDir, "cap_${System.nanoTime()}.jpg")
        try {
            ic.takePicture(ImageCapture.OutputFileOptions.Builder(src).build(), bg, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    capturing.set(false)
                    val name = Names.base(t, d.case_) + ".jpg"
                    runCatching { showThumbFromFile(src) }
                    try { processFile(src, d, t, name, loc); toast("已儲存到相簿") }
                    catch (e: Throwable) { toast("儲存失敗：${e.message}") } finally { src.delete() }
                }
                override fun onError(e: ImageCaptureException) {
                    capturing.set(false)
                    src.delete(); toast("拍照失敗：${e.message}")
                }
            })
        } catch (e: Exception) {
            capturing.set(false)
            src.delete(); toast("拍照失敗：${e.message}")
        }
    }

    private fun tail(f: File, n: Int = 64): ByteArray = java.io.RandomAccessFile(f, "r").use { raf ->
        val len = raf.length(); val k = minOf(n.toLong(), len).toInt(); val b = ByteArray(k); raf.seek(len - k); raf.readFully(b); b }

    /** 解碼（必要時降到約 12MP）→ 依 EXIF 轉正 → 加浮水印 → 存檔並驗證 */
    private fun processFile(src: File, d: WmData, t: java.time.ZonedDateTime, name: String, loc: Location? = null) {
        var tries = 0
        while (!ImageMath.jpegComplete(tail(src)) && tries < 5) { Thread.sleep(150); tries++ }
        val o = ImageMath.orient(ExifInterface(src).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(src.path, bo)
        if (bo.outWidth <= 0 || bo.outHeight <= 0) error("無法讀取影像")
        val memPx = Runtime.getRuntime().maxMemory() / 4 / 3
        // 解碼時接近 12MP（必要時留一檔給 capSize 精準縮小），並受記憶體限制
        var sample = ImageMath.sampleForDecode(bo.outWidth, bo.outHeight, ImageMath.MAX_STILL_PIXELS, memPx)
        var decoded: Bitmap? = null
        while (decoded == null) {
            var bmp: Bitmap? = null
            try {
                bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    ?: error("無法解碼")
                if (!ImageMath.decodedOk(bo.outWidth, bo.outHeight, sample, bmp.width, bmp.height)) error("解碼尺寸不符（${bmp.width}x${bmp.height}）")
                decoded = orientInto(bmp, o)
            } catch (e: OutOfMemoryError) {
                if (sample >= 8) throw e; sample *= 2
            } finally { if (bmp != null && bmp !== decoded) bmp.recycle() }
        }
        var out: Bitmap = decoded
        if (ImageMath.needsCap(out.width, out.height)) {
            val (nw, nh) = ImageMath.capSize(out.width, out.height)
            val scaled = Bitmap.createScaledBitmap(out, nw, nh, true)
            if (scaled !== out) out.recycle()
            out = if (scaled.isMutable) scaled else {
                val copy = scaled.copy(Bitmap.Config.ARGB_8888, true) ?: error("無法複製影像")
                if (copy !== scaled) scaled.recycle()
                copy
            }
        } else if (!out.isMutable) {
            val copy = out.copy(Bitmap.Config.ARGB_8888, true) ?: error("無法複製影像")
            if (copy !== out) out.recycle()
            out = copy
        }
        Watermark.stamp(Canvas(out), out.width.toFloat(), out.height.toFloat(), d, t)
        showThumbFromBitmap(out)
        val tmp = File(cacheDir, "out_${System.nanoTime()}.jpg")
        java.io.FileOutputStream(tmp).use { fo -> out.compress(Bitmap.CompressFormat.JPEG, 95, fo); fo.flush(); fo.fd.sync() }
        val w = out.width; val h = out.height; out.recycle()
        if (!ImageMath.jpegComplete(tail(tmp))) error("輸出檔不完整")
        writeMeta(src, tmp, w, h, t, loc)
        publishJpeg(tmp, name, t.toInstant().toEpochMilli(), refreshThumb = false)
    }

    private fun orientInto(src: Bitmap, o: ImageMath.Orient): Bitmap {
        val (w, h) = ImageMath.rotatedSize(src.width, src.height, o.degrees)
        if (o.degrees == 0 && !o.flipH && src.isMutable) return src
        val m = Matrix()
        if (o.flipH) m.postScale(-1f, 1f, src.width / 2f, src.height / 2f)
        m.postRotate(o.degrees.toFloat())
        val r = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()); m.mapRect(r); m.postTranslate(-r.left, -r.top)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG)); return out
    }

    private val EXIF_TAGS = listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP)
    /** 一次寫入：複製原 EXIF、拍攝時間、GPS */
    private fun writeMeta(from: File, to: File, w: Int, h: Int, t: java.time.ZonedDateTime, loc: Location?) {
        runCatching {
            val a = ExifInterface(from); val b = ExifInterface(to)
            for (tag in EXIF_TAGS) a.getAttribute(tag)?.let { b.setAttribute(tag, it) }
            b.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            b.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, w.toString()); b.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, h.toString())
            b.setAttribute(ExifInterface.TAG_SOFTWARE, "時間相機 1.3.0")
            val dt = t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
            val off = t.format(java.time.format.DateTimeFormatter.ofPattern("xxx"))
            b.setAttribute(ExifInterface.TAG_DATETIME, dt); b.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt); b.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dt)
            b.setAttribute(ExifInterface.TAG_OFFSET_TIME, off); b.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, off); b.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, off)
            if (loc != null) b.setGpsInfo(loc)
            b.saveAttributes()
        }
    }


    private fun stampPicked(uri: Uri) = bg.execute {
        val src = File(cacheDir, "pick_${System.nanoTime()}.jpg")
        try {
            val t = Names.now(); val d = wm.data
            contentResolver.openInputStream(uri)!!.use { i -> src.outputStream().use { i.copyTo(it) } }
            val name = Names.base(t, d.case_) + ".jpg"; processFile(src, d, t, name, null); toast("已儲存到相簿")
        } catch (e: OutOfMemoryError) { toast("相片過大，無法處理") } catch (e: Throwable) { toast("處理失敗：${e.message}") } finally { src.delete() }
    }

    private val legacyDir get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "時間相機").apply { mkdirs() }

    /** 寫入相簿：完整中繼資料、IS_PENDING 生命週期、再掃描一次 */
    private fun publishJpeg(tmp: File, name: String, takenMs: Long = System.currentTimeMillis(), refreshThumb: Boolean = true) {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(tmp.path, bo)
        val nowS = System.currentTimeMillis() / 1000
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/時間相機")
                put(MediaStore.Images.Media.DATE_TAKEN, takenMs)              // 毫秒
                put(MediaStore.Images.Media.DATE_ADDED, nowS)                 // 秒
                put(MediaStore.Images.Media.DATE_MODIFIED, nowS)              // 秒
                put(MediaStore.Images.Media.WIDTH, bo.outWidth); put(MediaStore.Images.Media.HEIGHT, bo.outHeight)
                put(MediaStore.Images.Media.ORIENTATION, 0)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("無法建立相簿項目")
            try {
                contentResolver.openOutputStream(uri, "wt")!!.use { o: OutputStream -> tmp.inputStream().use { it.copyTo(o) }; o.flush() }
                val written = contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
                if (written in 0 until tmp.length()) error("寫入不完整（$written/${tmp.length()}）")
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0); put(MediaStore.Images.Media.DATE_TAKEN, takenMs) }
                if (contentResolver.update(uri, done, null, null) != 1) error("無法完成相簿登錄")
            } catch (e: Throwable) { runCatching { contentResolver.delete(uri, null, null) }; throw e }
            tmp.delete()
            rescan(uri, "image/jpeg")
            rememberMedia(uri, "image/jpeg", name, refreshThumb)
        } else {
            val f = File(legacyDir, name); tmp.copyTo(f, overwrite = true); tmp.delete()
            f.setLastModified(takenMs)
            MediaScannerConnection.scanFile(this, arrayOf(f.path), arrayOf("image/jpeg")) { _, u -> if (u != null) rememberMedia(u, "image/jpeg", name, refreshThumb) }
        }
    }

    /** 保險：以實際檔案路徑再請媒體掃描器掃一次 */
    private fun rescan(uri: Uri, mime: String) {
        runCatching {
            @Suppress("DEPRECATION")
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { p -> MediaScannerConnection.scanFile(this, arrayOf(p), arrayOf(mime), null) }
            }
        }
    }

    // ---------- 錄影 ----------
    @SuppressLint("MissingPermission")
    private fun toggleRec() {
        recording?.let { it.stop(); recording = null; return }
        val vc = videoCapture ?: return
        val name = Names.base(Names.now(), wm.data.case_) + ".mp4"
        val pending = if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/時間相機")
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            }
            vc.output.prepareRecording(this, MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(v).setLocation(Geo.loc).build())
        } else vc.output.prepareRecording(this, FileOutputOptions.Builder(File(legacyDir, name)).setLocation(Geo.loc).build())
        if (has(Manifest.permission.RECORD_AUDIO)) pending.withAudioEnabled() else toast("未取得麥克風權限，將錄製無聲影片")
        val recLbl = findViewById<View>(R.id.rec)
        recData = WmData.load(this)  // 錄影期間地址固定
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> { shutter.recording = true; recLbl.visibility = View.VISIBLE; findViewById<TextView>(R.id.recTime).text = "00:00" }
                is VideoRecordEvent.Status -> { val s = TimeUnit.NANOSECONDS.toSeconds(ev.recordingStats.recordedDurationNanos); findViewById<TextView>(R.id.recTime).text = String.format("%02d:%02d", s / 60, s % 60) }
                is VideoRecordEvent.Finalize -> {
                    shutter.recording = false; recLbl.visibility = View.GONE; recording = null; recData = null
                    if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) toast("錄影失敗（${ev.error}）")
                    else { toast("已儲存到相簿")
                        if (Build.VERSION.SDK_INT < 29) MediaScannerConnection.scanFile(this, arrayOf(File(legacyDir, name).path), arrayOf("video/mp4")) { _, u -> if (u != null) rememberMedia(u, "video/mp4", name) }
                        else { val u = ev.outputResults.outputUri
                            runCatching { contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
                            rescan(u, "video/mp4"); rememberMedia(u, "video/mp4", name) } }
                }
            }
        }
    }

    // ---------- 浮水印資訊 ----------
    private fun openSheet() {
        val dlg = BottomSheetDialog(this, R.style.SheetTheme)
        val v = layoutInflater.inflate(R.layout.sheet, null); dlg.setContentView(v)
        EdgeInsets.pad(v, top = false, ime = true)
        val d = wm.data
        listOf(R.id.fSite to "site", R.id.fCase to "case", R.id.fBld to "bld", R.id.fFlr to "flr", R.id.fNote to "note", R.id.fAddr to "addr").forEach { (id, key) ->
            val et = v.findViewById<EditText>(id)
            et.setText(WmData.prefs(this).getString(key, if (key == "site") "中和安邦社宅" else ""))
            et.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { WmData.save(this@MainActivity, key, s.toString()); wm.reload(); refreshSite(); renderTpls() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        sheetView = v
        val pr = WmData.prefs(this)
        v.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swAddr).apply { isChecked = pr.getBoolean("showAddr", true)
            setOnCheckedChangeListener { _, b -> pr.edit().putBoolean("showAddr", b).apply(); wm.reload(); renderTpls() } }
        v.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swGeo).apply { isChecked = pr.getBoolean("showGeo", true)
            setOnCheckedChangeListener { _, b -> pr.edit().putBoolean("showGeo", b).apply(); wm.reload(); renderTpls() } }
        v.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swExtra).apply { isChecked = pr.getBoolean("showExtra", false)
            setOnCheckedChangeListener { _, b -> pr.edit().putBoolean("showExtra", b).apply(); wm.reload(); renderTpls() } }
        v.findViewById<View>(R.id.refreshLoc).setOnClickListener { refreshLocation() }
        segRow(v.findViewById(R.id.posRow), WmData.POS, "pos", 0); segRow(v.findViewById(R.id.sizeRow), WmData.SIZES, "size", 1)
        renderTpls(); updateGeoStatus()
        v.findViewById<View>(R.id.done).setOnClickListener { dlg.dismiss() }
        dlg.setOnDismissListener { sheetView = null }
        dlg.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true
        dlg.show()
    }

    private var sheetView: View? = null

    private fun segRow(row: LinearLayout, items: List<String>, key: String, def: Int) {
        row.removeAllViews(); val pr = WmData.prefs(this)
        items.forEachIndexed { i, label ->
            val tv = TextView(this).apply { text = label; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(11), 0, dp(11), 0); setTypeface(typeface, Typeface.BOLD) }
            row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
            tv.setOnClickListener { pr.edit().putInt(key, i).apply(); wm.reload(); segRow(row, items, key, def) }
            val on = pr.getInt(key, def) == i
            tv.setTextColor(if (on) Color.WHITE else 0xFF111111.toInt()); tv.setBackgroundResource(if (on) R.drawable.done_dark else 0)
        }
    }

    private fun renderTpls() {
        val row = sheetView?.findViewById<LinearLayout>(R.id.tplRow) ?: return
        row.removeAllViews(); val d = WmData.load(this); val pr = WmData.prefs(this)
        WmData.TEMPLATES.forEachIndexed { i, name ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(3), dp(3), dp(3), dp(3)) }
            val img = ImageView(this).apply {
                setImageBitmap(Watermark.thumbnail(d, i, 480, 360)); scaleType = ImageView.ScaleType.FIT_CENTER
                val sel = pr.getInt("tpl", 0) == i
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setStroke(dp(if (sel) 3 else 1), if (sel) Watermark.AC else 0x33FFFFFF) }
                setPadding(dp(3), dp(3), dp(3), dp(3)); clipToOutline = true
            }
            col.addView(img, LinearLayout.LayoutParams(dp(128), dp(96)))
            col.addView(TextView(this).apply { text = name; textSize = 12f; setTextColor(if (pr.getInt("tpl", 0) == i) Watermark.AC else Color.WHITE); setPadding(0, dp(4), 0, 0) })
            col.setOnClickListener { pr.edit().putInt("tpl", i).apply(); wm.reload(); renderTpls() }
            row.addView(col)
        }
    }

    // ---------- 定位 ----------
    private fun hasLoc() = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLoc()) { Geo.status = "無法定位（未授權）"; updateGeoStatus(); return }
        val m = lm ?: getSystemService(LocationManager::class.java).also { lm = it }
        m.removeUpdates(locListener)
        val ps = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) && m.allProviders.contains(it) }
        if (ps.isEmpty()) { Geo.status = "無法定位（請開啟定位服務）"; updateGeoStatus(); return }
        if (Geo.loc == null) Geo.status = "定位中…"
        ps.forEach { pv -> runCatching { m.getLastKnownLocation(pv)?.let { if (System.currentTimeMillis() - it.time < 10 * 60_000) onLoc(it) }; m.requestLocationUpdates(pv, 5000L, 5f, locListener, mainLooper) } }
        updateGeoStatus()
    }

    @SuppressLint("MissingPermission")
    private fun refreshLocation() {
        if (!hasLoc()) { perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return }
        Geo.addrStatus = ""; lastGeoLoc = null; Geo.status = "定位中…"; toast("重新定位中…")
        val m = lm ?: getSystemService(LocationManager::class.java).also { lm = it }
        val pv = if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) m.getCurrentLocation(pv, null, mainExecutor) { l -> if (l != null) { Geo.loc = null; onLoc(l) } else startLocation() }
            else @Suppress("DEPRECATION") m.requestSingleUpdate(pv, { l -> Geo.loc = null; onLoc(l) }, mainLooper)
        }.onFailure { startLocation() }
        Geo.loc?.let { geocode(it) }
    }

    private fun onLoc(l: Location) {
        val cur = Geo.loc
        if (cur != null && l.accuracy > cur.accuracy * 1.5f && l.time - cur.time < 20_000) return
        Geo.loc = l; Geo.status = ""
        val now = SystemClock.elapsedRealtime()
        if (lastGeoLoc == null || l.distanceTo(lastGeoLoc!!) > 30 || now - lastGeoAt > 60_000) geocode(l)
        wm.reload(); updateGeoStatus()
    }

    private fun geocode(l: Location) {
        lastGeoLoc = l; lastGeoAt = SystemClock.elapsedRealtime()
        if (!Geocoder.isPresent()) { Geo.addrStatus = "此裝置無法查詢地址"; return }
        bg.execute {
            try {
                @Suppress("DEPRECATION")
                val a = Geocoder(this, Locale.TAIWAN).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.getAddressLine(0)
                if (a != null) { Geo.addr = cleanAddr(a); Geo.addrStatus = "" } else if (Geo.addr.isEmpty()) Geo.addrStatus = "查無地址"
            } catch (e: Exception) { if (Geo.addr.isEmpty()) Geo.addrStatus = "地址無法取得（離線）" }
            runOnUiThread { wm.reload(); updateGeoStatus() }
        }
    }

    private fun cleanAddr(s: String) = Geo.cleanAddr(s)

    private fun updateGeoStatus() {
        val tv = sheetView?.findViewById<TextView>(R.id.geoStatus) ?: return
        val l = Geo.loc
        tv.text = if (l == null) Geo.status else "自動：" + Geo.addr.ifBlank { Geo.addrStatus.ifBlank { "地址查詢中…" } } + "\n" + Geo.coords(l)
    }

    override fun onResume() { super.onResume(); if (hasLoc()) startLocation(); loadLastThumb() }
    override fun onPause() { lm?.removeUpdates(locListener); super.onPause() }


    private fun paintModeDot(tv: TextView, on: Boolean) {
        if (on) {
            val d = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF111111.toInt()); setSize(dp(5), dp(5)) }
            tv.setCompoundDrawablesWithIntrinsicBounds(null, null, null, d)
            tv.compoundDrawablePadding = dp(3)
        } else {
            tv.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        }
    }

    private fun renderZoom(min: Float, max: Float, ratio: Float) {
        val stack = findViewById<LinearLayout>(R.id.zoomStack)
        val stops = listOf(0.5f, 1f, 2f, 10f).filter { it >= min - 0.06f && it <= max + 0.05f }.ifEmpty { listOf(1f) }
        if (stack.tag != stops) {
            stack.removeAllViews(); stack.tag = stops
            stops.forEach { z ->
                val b = TextView(this).apply {
                    gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); tag = z
                    setOnClickListener { camera?.cameraControl?.setZoomRatio(z) }
                }
                stack.addView(b)
            }
        }
        val closest = stops.minBy { kotlin.math.abs(it - ratio) }
        for (i in 0 until stack.childCount) {
            val b = stack.getChildAt(i) as TextView
            val z = b.tag as Float
            val sel = z == closest
            val size = if (sel) dp(40) else dp(34)
            b.layoutParams = LinearLayout.LayoutParams(size, size).apply { bottomMargin = dp(8) }
            b.background = ContextCompat.getDrawable(this, if (sel) R.drawable.zoom_on else R.drawable.zoom_off)
            b.setTextColor(if (sel) 0xFF111111.toInt() else Color.WHITE)
            b.textSize = if (sel) 12f else 10f
            b.text = when { z == 1f -> "1×"; z < 1f -> "0.5"; z == z.toInt().toFloat() -> z.toInt().toString(); else -> String.format("%.1f", z) }
        }
    }

    private fun showDrawer(show: Boolean) {
        findViewById<View>(R.id.scrim).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.drawer).visibility = if (show) View.VISIBLE else View.GONE
        if (show) updateChips()
    }

    @Deprecated("deprecated in Android")
    override fun onBackPressed() {
        if (findViewById<View>(R.id.drawer).visibility == View.VISIBLE) { showDrawer(false); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun rememberMedia(uri: Uri, mime: String, name: String, refreshThumb: Boolean = true) {
        lastMediaUri = uri; lastMediaMime = mime
        WmData.prefs(this).edit().putString("lastUri", uri.toString()).putString("lastMime", mime).apply()
        if (refreshThumb) bindThumb(uri)
        lifecycleScope.launch {
            val up = UploadPrefs(this@MainActivity)
            if (up.enabled) {
                runCatching { UploadRepo(this@MainActivity).enqueue(uri, mime, name) }
                    .onFailure { android.util.Log.w("TimeCam", "queue upload: ${it.message}") }
                UploadScheduler.afterCapture(this@MainActivity)
            }
        }
    }

    private fun loadLastThumb() {
        val pr = WmData.prefs(this)
        lastMediaUri = pr.getString("lastUri", null)?.let { Uri.parse(it) }
        lastMediaMime = pr.getString("lastMime", "image/jpeg") ?: "image/jpeg"
        bindThumb(lastMediaUri)
    }

    private fun showThumbBitmap(bmp: Bitmap?) {
        runOnUiThread {
            val iv = findViewById<ImageView>(R.id.lastThumb)
            if (bmp == null) iv.setImageDrawable(null) else iv.setImageBitmap(bmp)
        }
    }

    private fun showThumbFromBitmap(src: Bitmap) {
        val (tw, th) = ImageMath.thumbSize(src.width, src.height)
        val thumb = if (tw == src.width && th == src.height) src.copy(Bitmap.Config.ARGB_8888, false) else Bitmap.createScaledBitmap(src, tw, th, true)
        thumbGen++
        showThumbBitmap(thumb)
    }

    private fun showThumbFromFile(f: File) {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bo)
        if (bo.outWidth <= 0 || bo.outHeight <= 0) return
        val sample = ImageMath.sampleFor(bo.outWidth, bo.outHeight, ImageMath.THUMB_PIXELS)
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        thumbGen++
        showThumbBitmap(bmp)
    }

    private fun bindThumb(uri: Uri?) {
        val gen = ++thumbGen
        if (uri == null) { showThumbBitmap(null); return }
        val mime = lastMediaMime
        bg.execute {
            val bmp = decodeThumb(uri, mime)
            if (gen == thumbGen) showThumbBitmap(bmp)
        }
    }

    private fun decodeThumb(uri: Uri, mime: String): Bitmap? = try {
        if (mime.startsWith("video")) {
            val m = android.media.MediaMetadataRetriever()
            val frame = runCatching {
                m.setDataSource(this, uri)
                m.frameAtTime
            }.getOrNull()
            m.release()
            frame
        } else {
            val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bo) }
            val sample = ImageMath.sampleFor(bo.outWidth, bo.outHeight, ImageMath.THUMB_PIXELS)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (_: Exception) { null }

    private fun openLastPhoto() {
        val uri = lastMediaUri ?: WmData.prefs(this).getString("lastUri", null)?.let { Uri.parse(it) }
        if (uri == null) { toast("還沒有拍攝紀錄，拍一張就會出現在這裡"); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, lastMediaMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { toast("打不開相簿：${e.message}") }
    }

    private fun openTemplates() {
        val dlg = BottomSheetDialog(this, R.style.SheetTheme)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.sheet_light)
            setPadding(dp(18), dp(8), dp(18), dp(24))
        }
        box.addView(View(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.grab_light)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) }
        })
        val hd = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } }
        hd.addView(TextView(this).apply { text = "浮水印範本"; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        val done = TextView(this).apply {
            text = "完成"; setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; setBackgroundResource(R.drawable.done_dark)
            setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { dlg.dismiss() }
        }
        hd.addView(done); box.addView(hd)
        box.addView(TextView(this).apply { text = "選一種樣式，會同時出現在預覽與存檔的照片、影片上。"; textSize = 12f; setTextColor(0xFF888888.toInt()); setPadding(0, 0, 0, dp(10)) })
        val grid = android.widget.GridLayout(this).apply { columnCount = 2 }
        val pr = WmData.prefs(this)
        fun paint() {
            grid.removeAllViews()
            val d = WmData.load(this)
            WmData.TEMPLATES.forEachIndexed { i, name ->
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(8)) }
                val img = ImageView(this).apply {
                    setImageBitmap(Watermark.thumbnail(d, i, 480, 360)); scaleType = ImageView.ScaleType.CENTER_CROP
                    val sel = pr.getInt("tpl", 5) == i
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat(); setStroke(dp(if (sel) 3 else 1), if (sel) 0xFF111111.toInt() else 0xFFECECEC.toInt()); setColor(0xFFF7F7F7.toInt())
                    }
                    setPadding(dp(3), dp(3), dp(3), dp(3)); clipToOutline = true
                }
                col.addView(img, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(110)))
                col.addView(TextView(this).apply { text = name; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()); setPadding(dp(4), dp(6), 0, 0) })
                col.setOnClickListener { pr.edit().putInt("tpl", i).apply(); wm.reload(); paint() }
                val lp = android.widget.GridLayout.LayoutParams().apply {
                    width = 0; height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                grid.addView(col, lp)
            }
        }
        paint(); box.addView(grid)
        dlg.setContentView(box)
        EdgeInsets.pad(box, top = false)
        dlg.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true
        dlg.show()
    }

    override fun onDestroy() { recording?.stop(); effectThread.quitSafely(); shutterSound?.release(); shutterSound = null; bg.shutdown(); super.onDestroy() }
}
