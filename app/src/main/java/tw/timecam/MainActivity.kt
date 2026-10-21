package tw.timecam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
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

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var wm: WatermarkView
    private lateinit var shutter: ShutterView
    private val bg = Executors.newSingleThreadExecutor()
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
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview); wm = findViewById(R.id.wm); shutter = findViewById(R.id.shutter)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        val top = findViewById<View>(R.id.top); val ctrl = findViewById<View>(R.id.ctrl)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            top.setPadding(top.paddingLeft, b.top + dp(12), top.paddingRight, top.paddingBottom)
            ctrl.setPadding(ctrl.paddingLeft, ctrl.paddingTop, ctrl.paddingRight, b.bottom + dp(18)); ins
        }
        ctrl.addOnLayoutChangeListener { _, _, t, _, _, _, _, _, _ -> wm.bottomInset = (findViewById<View>(R.id.root).height - t).toFloat(); wm.invalidate() }
        top.addOnLayoutChangeListener { _, _, _, _, b, _, _, _, _ -> wm.topInset = b.toFloat(); wm.invalidate() }
        refreshSite()
        findViewById<View>(R.id.mPhoto).setOnClickListener { setMode(false) }
        findViewById<View>(R.id.mVideo).setOnClickListener { setMode(true) }
        setMode(false, rebind = false)
        shutter.setOnClickListener { if (videoMode) toggleRec() else takePhoto() }
        findViewById<View>(R.id.settings).setOnClickListener { openSheet() }
        findViewById<View>(R.id.pick).setOnClickListener { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        findViewById<TextView>(R.id.btnFlash).setOnClickListener { cycleFlash() }
        findViewById<TextView>(R.id.btnTorch).setOnClickListener { camera?.cameraControl?.enableTorch(!torchOn) }
        findViewById<TextView>(R.id.btnLens).setOnClickListener {
            if (recording != null) return@setOnClickListener
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            extMode = ExtensionMode.NONE; bind()
        }
        findViewById<TextView>(R.id.btnExt).setOnClickListener { cycleExt() }
        setupGestures(); setupSliders()
        findViewById<View>(R.id.btnUpload).setOnClickListener { lastSaved?.let { upload(listOf(it)) } }
        findViewById<View>(R.id.btnMulti).setOnClickListener { pickRecent() }
        findViewById<View>(R.id.btnClose).setOnClickListener { findViewById<View>(R.id.lastBar).visibility = View.GONE }
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
    private fun refreshSite() { findViewById<TextView>(R.id.siteChip).text = "●  " + wm.data.site.ifBlank { "時間相機" } }

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
        val rs = ResolutionSelector.Builder().setAspectRatioStrategy(ratio).setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE).build()
        val preview = Preview.Builder().setResolutionSelector(prs).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        try {
            if (!videoMode) {
                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setFlashMode(flashMode)
                    .setResolutionSelector(rs).setJpegQuality(100).setTargetRotation(rotation).build()
                imageCapture = ic; videoCapture = null
                val sel = if (extMode != ExtensionMode.NONE && extMgr != null) extMgr!!.getExtensionEnabledCameraSelector(baseSelector(), extMode) else baseSelector()
                camera = p.bindToLifecycle(this, sel, preview, ic)
            } else {
                fun tryBind(qs: List<Quality>, bitrate: Int) {
                    val rec = Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(qs, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .setTargetVideoEncodingBitRate(bitrate).build()
                    val vc = VideoCapture.withOutput(rec).also { it.targetRotation = rotation }
                    videoCapture = vc; imageCapture = null
                    val g = UseCaseGroup.Builder().addUseCase(preview).addUseCase(vc).addEffect(overlay).build()
                    camera = p.bindToLifecycle(this, baseSelector(), g)
                }
                try { tryBind(listOf(Quality.UHD, Quality.FHD, Quality.HD), 40_000_000) }
                catch (e: Exception) { p.unbindAll(); tryBind(listOf(Quality.FHD, Quality.HD), 20_000_000) }
            }
        } catch (e: Exception) { toast("相機啟動失敗：${e.message}"); return }
        observeCamera(); updateChips()
    }

    private fun observeCamera() {
        val cam = camera ?: return
        cam.cameraInfo.zoomState.observe(this) { z ->
            findViewById<TextView>(R.id.zoomLbl).text = String.format("%.1fx", z.zoomRatio)
            findViewById<SeekBar>(R.id.zoom).progress = (z.linearZoom * 100).toInt()
        }
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
        findViewById<SeekBar>(R.id.zoom).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) camera?.cameraControl?.setLinearZoom(p / 100f) }
            override fun onStartTrackingTouch(s: SeekBar) {}; override fun onStopTrackingTouch(s: SeekBar) {}
        })
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
                f.x = e.x - f.width / 2; f.y = e.y - f.height / 2; f.scaleX = 1.4f; f.scaleY = 1.4f; f.alpha = 1f
                f.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction { f.animate().alpha(0f).setStartDelay(900).setDuration(300) }
                return true
            }
        })
        previewView.setOnTouchListener { _, e -> scale.onTouchEvent(e); if (!scale.isInProgress) tap.onTouchEvent(e); true }
    }

    private fun setMode(video: Boolean, rebind: Boolean = true) {
        if (recording != null) return
        videoMode = video; shutter.video = video
        val on = findViewById<TextView>(if (video) R.id.mVideo else R.id.mPhoto); val off = findViewById<TextView>(if (video) R.id.mPhoto else R.id.mVideo)
        on.setBackgroundResource(R.drawable.seg_on); on.setTextColor(Watermark.AC); off.background = null; off.setTextColor(0xB3FFFFFF.toInt())
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
        findViewById<TextView>(R.id.btnFlash).apply {
            visibility = if (hasFlash && !videoMode) View.VISIBLE else View.GONE
            text = when (flashMode) { ImageCapture.FLASH_MODE_ON -> "⚡ 開"; ImageCapture.FLASH_MODE_OFF -> "⚡ 關"; else -> "⚡ 自動" }
        }
        findViewById<TextView>(R.id.btnTorch).apply {
            visibility = if (hasFlash) View.VISIBLE else View.GONE
            text = if (torchOn) "🔦 開" else "🔦 關"; setTextColor(if (torchOn) Watermark.AC else Color.WHITE)
        }
        findViewById<TextView>(R.id.btnExt).apply {
            visibility = if (videoMode) View.GONE else View.VISIBLE
            text = extName(extMode); setTextColor(if (extMode != ExtensionMode.NONE) Watermark.AC else Color.WHITE)
        }
    }

    // ---------- 照片 ----------
    private fun takePhoto() {
        val ic = imageCapture ?: return
        val cam = camera
        // 使用者剛點擊對焦則沿用；否則先對畫面中央對焦測光（最多等 800ms）
        if (cam != null && SystemClock.elapsedRealtime() - lastTap > 4000) {
            val pt = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
            val fut = cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build())
            shutter.isEnabled = false
            var fired = false
            val go = { if (!fired) { fired = true; shutter.isEnabled = true; capture(ic) } }
            fut.addListener({ go() }, ContextCompat.getMainExecutor(this))
            previewView.postDelayed({ go() }, 800)
        } else capture(ic)
    }

    private fun capture(ic: ImageCapture) {
        val fx = findViewById<View>(R.id.flashFx); fx.alpha = 0.8f; fx.animate().alpha(0f).setDuration(350)
        val t = Names.now(); val d = wm.data; val loc = Geo.loc
        ic.takePicture(bg, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bytes: ByteArray
                    if (image.format == ImageFormat.JPEG) {
                        val buf = image.planes[0].buffer; bytes = ByteArray(buf.remaining()); buf.get(bytes); image.close()
                    } else {
                        val bmp = image.toBitmap(); val rot = image.imageInfo.rotationDegrees; image.close()
                        val out = rotateInto(bmp, rot); bmp.recycle()
                        Watermark.stamp(Canvas(out), out.width.toFloat(), out.height.toFloat(), d, t)
                        val name = Names.base(t, d.case_) + ".jpg"; val tmp = File(cacheDir, "o.jpg")
                        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 98, it) }; out.recycle()
                        writeGps(tmp, loc); publishJpeg(tmp, name); toast("已儲存 $name"); return
                    }
                    val name = Names.base(t, d.case_) + ".jpg"
                    processJpeg(bytes, d, t, name, loc); toast("已儲存 $name")
                } catch (e: Throwable) { toast("儲存失敗：${e.message}") }
            }
            override fun onError(e: ImageCaptureException) { toast("拍照失敗：${e.message}") }
        })
    }

    /** 原尺寸解碼 → 依 EXIF 轉正 → 加浮水印 → 品質 98 重新編碼 → 複製原 EXIF */
    private fun processJpeg(bytes: ByteArray, d: WmData, t: java.time.ZonedDateTime, name: String, loc: Location? = null) {
        val src = File(cacheDir, "src.jpg"); src.writeBytes(bytes)
        val rot = ExifInterface(src).rotationDegrees
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 1; inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = rot == 0 })
            ?: error("無法解碼")
        val out = if (rot == 0 && bmp.isMutable) bmp else rotateInto(bmp, rot).also { bmp.recycle() }
        Watermark.stamp(Canvas(out), out.width.toFloat(), out.height.toFloat(), d, t)
        val tmp = File(cacheDir, "out.jpg")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 98, it) }
        val w = out.width; val h = out.height; out.recycle()
        copyExif(src, tmp, w, h); src.delete(); writeGps(tmp, loc)
        publishJpeg(tmp, name)
    }

    private val EXIF_SKIP = setOf(ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION, ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT, ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH)
    private val EXIF_TAGS: List<String> by lazy {
        ExifInterface::class.java.fields.filter { it.name.startsWith("TAG_") && it.type == String::class.java }.mapNotNull { it.get(null) as? String }.filter { it !in EXIF_SKIP }.distinct()
    }
    private fun copyExif(from: File, to: File, w: Int, h: Int) {
        runCatching {
            val a = ExifInterface(from); val b = ExifInterface(to)
            for (tag in EXIF_TAGS) a.getAttribute(tag)?.let { b.setAttribute(tag, it) }
            b.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            b.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, w.toString()); b.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, h.toString())
            b.setAttribute(ExifInterface.TAG_SOFTWARE, "時間相機 1.1.1")
            b.saveAttributes()
        }
    }

    private fun writeGps(f: File, loc: Location?) {
        if (loc == null) return
        runCatching { ExifInterface(f).apply { setGpsInfo(loc); saveAttributes() } }
    }

    private fun rotateInto(src: Bitmap, rot: Int): Bitmap {
        val sw = src.width; val sh = src.height
        val (w, h) = if (rot % 180 == 0) sw to sh else sh to sw
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply { postRotate(rot.toFloat()); val r = RectF(0f, 0f, sw.toFloat(), sh.toFloat()); mapRect(r); postTranslate(-r.left, -r.top) }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG)); return out
    }

    private fun stampPicked(uri: Uri) = bg.execute {
        try {
            val t = Names.now(); val d = wm.data
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val name = Names.base(t, d.case_) + ".jpg"; processJpeg(bytes, d, t, name); toast("已儲存 $name")
        } catch (e: OutOfMemoryError) { toast("相片過大，無法處理") } catch (e: Throwable) { toast("處理失敗：${e.message}") }
    }

    private val legacyDir get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "時間相機").apply { mkdirs() }

    private fun publishJpeg(tmp: File, name: String) {
        var out: Uri? = null
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/時間相機"); put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("無法建立檔案")
            contentResolver.openOutputStream(uri)!!.use { o: OutputStream -> tmp.inputStream().use { it.copyTo(o) } }
            v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0); contentResolver.update(uri, v, null, null); out = uri
        } else {
            val f = File(legacyDir, name); tmp.copyTo(f, overwrite = true)
            MediaScannerConnection.scanFile(this, arrayOf(f.path), arrayOf("image/jpeg")) { _, u -> if (u != null) onSaved(u, "image/jpeg", name) }
        }
        tmp.delete()
        out?.let { onSaved(it, "image/jpeg", name) }
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
            }
            vc.output.prepareRecording(this, MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(v).setLocation(Geo.loc).build())
        } else vc.output.prepareRecording(this, FileOutputOptions.Builder(File(legacyDir, name)).setLocation(Geo.loc).build())
        if (has(Manifest.permission.RECORD_AUDIO)) pending.withAudioEnabled() else toast("未取得麥克風權限，將錄製無聲影片")
        val recLbl = findViewById<TextView>(R.id.rec)
        recData = WmData.load(this)  // 錄影期間地址固定
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> { shutter.recording = true; recLbl.visibility = View.VISIBLE; recLbl.text = "●  00:00" }
                is VideoRecordEvent.Status -> { val s = TimeUnit.NANOSECONDS.toSeconds(ev.recordingStats.recordedDurationNanos); recLbl.text = String.format("●  %02d:%02d", s / 60, s % 60) }
                is VideoRecordEvent.Finalize -> {
                    shutter.recording = false; recLbl.visibility = View.GONE; recording = null; recData = null
                    if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) toast("錄影失敗（${ev.error}）")
                    else { toast("已儲存 $name")
                        if (Build.VERSION.SDK_INT < 29) MediaScannerConnection.scanFile(this, arrayOf(File(legacyDir, name).path), arrayOf("video/mp4")) { _, u -> if (u != null) onSaved(u, "video/mp4", name) }
                        else onSaved(ev.outputResults.outputUri, "video/mp4", name) }
                }
            }
        }
    }

    // ---------- 浮水印資訊 ----------
    private fun openSheet() {
        val dlg = BottomSheetDialog(this, R.style.SheetTheme)
        val v = layoutInflater.inflate(R.layout.sheet, null); dlg.setContentView(v)
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

    // ---------- 上傳（交給 Google 雲端硬碟） ----------
    data class Saved(val uri: Uri, val mime: String, val name: String)
    private var lastSaved: Saved? = null
    private fun onSaved(uri: Uri, mime: String, name: String) = runOnUiThread {
        val s = Saved(uri, mime, name); lastSaved = s
        val pr = WmData.prefs(this)
        val list = (listOf("$uri|$mime|$name") + (pr.getString("recent", "") ?: "").split("\n").filter { it.isNotBlank() && !it.startsWith("$uri|") }).take(30)
        pr.edit().putString("recent", list.joinToString("\n")).apply()
        findViewById<TextView>(R.id.lastName).text = name
        findViewById<View>(R.id.lastBar).visibility = View.VISIBLE
    }
    private fun recent(): List<Saved> = (WmData.prefs(this).getString("recent", "") ?: "").split("\n").mapNotNull {
        val p = it.split("|"); if (p.size < 3) null else Saved(Uri.parse(p[0]), p[1], p.drop(2).joinToString("|"))
    }.filter { s -> runCatching { contentResolver.openFileDescriptor(s.uri, "r")?.use { true } ?: false }.getOrDefault(false) }

    private fun pickRecent() {
        val items = recent()
        if (items.isEmpty()) { toast("沒有最近的拍攝紀錄"); return }
        val checked = BooleanArray(items.size) { it == 0 }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("選擇要上傳的檔案")
            .setMultiChoiceItems(items.map { (if (it.mime.startsWith("video")) "🎬 " else "📷 ") + it.name }.toTypedArray(), checked) { _, i, b -> checked[i] = b }
            .setPositiveButton("上傳") { _, _ -> val sel = items.filterIndexed { i, _ -> checked[i] }; if (sel.isEmpty()) toast("未選擇檔案") else upload(sel) }
            .setNegativeButton("取消", null).show()
    }

    private fun upload(files: List<Saved>) {
        val mime = when { files.all { it.mime.startsWith("image") } -> "image/jpeg"; files.all { it.mime.startsWith("video") } -> "video/mp4"; else -> "*/*" }
        val send = if (files.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, files[0].uri) }
            else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.uri })) }
        send.type = mime; send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(files[0].name, files[0].uri).apply { files.drop(1).forEach { addItem(android.content.ClipData.Item(it.uri)) } }
        val drive = "com.google.android.apps.docs"
        val hasDrive = runCatching { packageManager.getPackageInfo(drive, 0); true }.getOrDefault(false)
        if (hasDrive) {
            try { startActivity(Intent(send).setPackage(drive)); return } catch (e: Exception) { }
        }
        toast("未安裝 Google 雲端硬碟，改用分享")
        startActivity(Intent.createChooser(send, "上傳／分享"))
    }

    private fun segRow(row: LinearLayout, items: List<String>, key: String, def: Int) {
        row.removeAllViews(); val pr = WmData.prefs(this)
        items.forEachIndexed { i, label ->
            val tv = TextView(this).apply { text = label; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(11), 0, dp(11), 0); setTypeface(typeface, Typeface.BOLD) }
            row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
            tv.setOnClickListener { pr.edit().putInt(key, i).apply(); wm.reload(); segRow(row, items, key, def) }
            val on = pr.getInt(key, def) == i
            tv.setTextColor(if (on) Watermark.AC else 0xB3FFFFFF.toInt()); if (on) tv.setBackgroundResource(R.drawable.seg_on)
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

    private fun cleanAddr(s: String) = s.trim().replace(Regex("^\\d{3,6}"), "").replace(Regex("^(台灣|臺灣)"), "").replace(Regex("^\\d{3,6}"), "").trim()

    private fun updateGeoStatus() {
        val tv = sheetView?.findViewById<TextView>(R.id.geoStatus) ?: return
        val l = Geo.loc
        tv.text = if (l == null) Geo.status else "自動：" + Geo.addr.ifBlank { Geo.addrStatus.ifBlank { "地址查詢中…" } } + "\n" + Geo.coords(l)
    }

    override fun onResume() { super.onResume(); if (hasLoc()) startLocation() }
    override fun onPause() { lm?.removeUpdates(locListener); super.onPause() }

    override fun onDestroy() { recording?.stop(); effectThread.quitSafely(); bg.shutdown(); super.onDestroy() }
}
