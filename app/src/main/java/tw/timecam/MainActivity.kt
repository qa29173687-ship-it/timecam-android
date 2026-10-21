package tw.timecam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.media.MediaScannerConnection
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
                Watermark.stamp(c, uw, uh, wm.data, Names.now())
                true
            }
        }
    }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(Manifest.permission.CAMERA)) startCamera() else toast("需要相機權限")
    }
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) stampPicked(uri) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview); wm = findViewById(R.id.wm); shutter = findViewById(R.id.shutter)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        val top = findViewById<View>(R.id.top); val ctrl = findViewById<View>(R.id.ctrl)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            top.setPadding(top.paddingLeft, b.top + dp(12), top.paddingRight, top.paddingBottom)
            ctrl.setPadding(ctrl.paddingLeft, ctrl.paddingTop, ctrl.paddingRight, b.bottom + dp(18)); ins
        }
        ctrl.addOnLayoutChangeListener { v, _, t, _, _, _, _, _, _ -> wm.bottomInset = (findViewById<View>(R.id.root).height - t).toFloat(); wm.invalidate() }
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
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when (o) { in 45..134 -> Surface.ROTATION_270; in 135..224 -> Surface.ROTATION_180; in 225..314 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
                if (r != rotation) { rotation = r; imageCapture?.targetRotation = r; if (recording == null) videoCapture?.targetRotation = r }
            }
        }.enable()
        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < 29) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (need.all { has(it) }) startCamera() else perms.launch(need.toTypedArray())
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
        return listOf(ExtensionMode.NONE) + listOf(ExtensionMode.HDR, ExtensionMode.NIGHT).filter { runCatching { m.isExtensionAvailable(baseSelector(), it) }.getOrDefault(false) }
    }
    private fun extName(m: Int) = when (m) { ExtensionMode.HDR -> "HDR"; ExtensionMode.NIGHT -> "夜間"; else -> "標準" }

    private fun bind() {
        val p = provider ?: return
        p.unbindAll(); camera = null
        val ratio = if (videoMode) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val rs = ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()
        val preview = Preview.Builder().setResolutionSelector(rs).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        try {
            if (!videoMode) {
                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setFlashMode(flashMode)
                    .setResolutionSelector(rs).setTargetRotation(rotation).build()
                imageCapture = ic; videoCapture = null
                val sel = if (extMode != ExtensionMode.NONE && extMgr != null) extMgr!!.getExtensionEnabledCameraSelector(baseSelector(), extMode) else baseSelector()
                camera = p.bindToLifecycle(this, sel, preview, ic)
            } else {
                val rec = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                val vc = VideoCapture.withOutput(rec).also { it.targetRotation = rotation }
                videoCapture = vc; imageCapture = null
                val g = UseCaseGroup.Builder().addUseCase(preview).addUseCase(vc).addEffect(overlay).build()
                camera = p.bindToLifecycle(this, baseSelector(), g)
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
        if (list.size == 1) { toast("此裝置不支援 HDR／夜間模式"); return }
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
        val fx = findViewById<View>(R.id.flashFx); fx.alpha = 0.8f; fx.animate().alpha(0f).setDuration(350)
        val t = Names.now(); val d = wm.data
        ic.takePicture(bg, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rot = image.imageInfo.rotationDegrees; val src = image.toBitmap(); image.close()
                    val out = rotateInto(src, rot); src.recycle()
                    Watermark.stamp(Canvas(out), out.width.toFloat(), out.height.toFloat(), d, t)
                    val name = Names.base(t, d.case_) + ".jpg"
                    saveJpeg(out, name); out.recycle(); toast("已儲存 $name")
                } catch (e: Throwable) { toast("儲存失敗：${e.message}") }
            }
            override fun onError(e: ImageCaptureException) { toast("拍照失敗：${e.message}") }
        })
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
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opt) }
            var sample = 1; while (maxOf(opt.outWidth, opt.outHeight) / sample > 5000) sample *= 2
            val src = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
            val rot = contentResolver.openInputStream(uri)!!.use { ExifInterface(it).rotationDegrees }
            val out = rotateInto(src, rot); src.recycle()
            Watermark.stamp(Canvas(out), out.width.toFloat(), out.height.toFloat(), d, t)
            val name = Names.base(t, d.case_) + ".jpg"; saveJpeg(out, name); out.recycle(); toast("已儲存 $name")
        } catch (e: Throwable) { toast("處理失敗：${e.message}") }
    }

    private val legacyDir get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "時間相機").apply { mkdirs() }

    private fun saveJpeg(b: Bitmap, name: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/時間相機"); put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("無法建立檔案")
            contentResolver.openOutputStream(uri)!!.use { o: OutputStream -> b.compress(Bitmap.CompressFormat.JPEG, 95, o) }
            v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0); contentResolver.update(uri, v, null, null)
        } else {
            val f = File(legacyDir, name); f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            MediaScannerConnection.scanFile(this, arrayOf(f.path), arrayOf("image/jpeg"), null)
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
            }
            vc.output.prepareRecording(this, MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(v).build())
        } else vc.output.prepareRecording(this, FileOutputOptions.Builder(File(legacyDir, name)).build())
        if (has(Manifest.permission.RECORD_AUDIO)) pending.withAudioEnabled() else toast("未取得麥克風權限，將錄製無聲影片")
        val recLbl = findViewById<TextView>(R.id.rec)
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> { shutter.recording = true; recLbl.visibility = View.VISIBLE; recLbl.text = "●  00:00" }
                is VideoRecordEvent.Status -> { val s = TimeUnit.NANOSECONDS.toSeconds(ev.recordingStats.recordedDurationNanos); recLbl.text = String.format("●  %02d:%02d", s / 60, s % 60) }
                is VideoRecordEvent.Finalize -> {
                    shutter.recording = false; recLbl.visibility = View.GONE; recording = null
                    if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) toast("錄影失敗（${ev.error}）")
                    else { toast("已儲存 $name"); if (Build.VERSION.SDK_INT < 29) MediaScannerConnection.scanFile(this, arrayOf(File(legacyDir, name).path), arrayOf("video/mp4"), null) }
                }
            }
        }
    }

    // ---------- 浮水印資訊 ----------
    private fun openSheet() {
        val dlg = BottomSheetDialog(this, R.style.SheetTheme)
        val v = layoutInflater.inflate(R.layout.sheet, null); dlg.setContentView(v)
        val d = wm.data
        listOf(R.id.fSite to "site", R.id.fCase to "case", R.id.fBld to "bld", R.id.fFlr to "flr", R.id.fNote to "note").forEach { (id, key) ->
            val et = v.findViewById<EditText>(id)
            et.setText(when (key) { "site" -> d.site; "case" -> d.case_; "bld" -> d.bld; "flr" -> d.flr; else -> d.note })
            et.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { WmData.save(this@MainActivity, key, s.toString()); wm.data = WmData.load(this@MainActivity); wm.invalidate(); refreshSite() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        v.findViewById<View>(R.id.done).setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    override fun onDestroy() { recording?.stop(); effectThread.quitSafely(); bg.shutdown(); super.onDestroy() }
}
