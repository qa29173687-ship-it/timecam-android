package tw.timecam

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tw.timecam.upload.DriveAuth
import tw.timecam.upload.UploadPrefs
import tw.timecam.upload.UploadRepo
import tw.timecam.upload.UploadScheduler
import tw.timecam.upload.UploadTimes

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: UploadPrefs
    private val connectLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != RESULT_OK) { toast("已取消連結"); return@registerForActivityResult }
        when (val o = DriveAuth.resultFromIntent(this, res.data)) {
            is DriveAuth.Outcome.Token -> onLinked(o)
            is DriveAuth.Outcome.Fail -> onAuthFail(o)
            is DriveAuth.Outcome.NeedsUi -> toast(DriveAuth.SETUP_MSG)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
        }
        setContentView(R.layout.activity_settings)
        prefs = UploadPrefs(this)
        EdgeInsets.pad(findViewById(R.id.settingsTop), bottom = false)
        EdgeInsets.pad(findViewById(R.id.settingsBody), left = false, top = false, right = false, bottom = true, ime = true)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        bind()
        refresh()
    }

    private fun bind() {
        findViewById<SwitchCompat>(R.id.swShutterSound).setOnCheckedChangeListener { _, on ->
            WmData.prefs(this).edit().putBoolean("shutterSound", on).apply()
        }
        findViewById<SwitchCompat>(R.id.swUpload).setOnCheckedChangeListener { _, on ->
            if (on && !prefs.granted) {
                findViewById<SwitchCompat>(R.id.swUpload).isChecked = false
                toast("請先連結 Google 帳號")
                return@setOnCheckedChangeListener
            }
            prefs.enabled = on
            UploadScheduler.applySchedule(this, prefs)
            refreshOpts()
        }
        findViewById<RadioGroup>(R.id.whenGroup).setOnCheckedChangeListener { _, id ->
            prefs.whenMode = if (id == R.id.whenDaily) "daily" else "immediate"
            UploadScheduler.applySchedule(this, prefs)
            refreshOpts()
        }
        findViewById<View>(R.id.btnTime).setOnClickListener {
            val (h, m) = UploadTimes.parseHm(prefs.time)
            TimePickerDialog(this, { _, hh, mm ->
                prefs.time = UploadTimes.formatHm(hh, mm)
                UploadScheduler.applySchedule(this, prefs)
                refreshOpts()
            }, h, m, true).show()
        }
        findViewById<SwitchCompat>(R.id.swWifi).setOnCheckedChangeListener { _, on ->
            prefs.wifiOnly = on; UploadScheduler.applySchedule(this, prefs)
        }
        findViewById<SwitchCompat>(R.id.swCharge).setOnCheckedChangeListener { _, on ->
            prefs.chargingOnly = on; UploadScheduler.applySchedule(this, prefs)
        }
        findViewById<EditText>(R.id.fFolder).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val name = s?.toString()?.trim().orEmpty()
                if (name != prefs.folder) { prefs.folder = name; prefs.folderId = "" }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<View>(R.id.btnConnect).setOnClickListener { startConnect() }
        findViewById<View>(R.id.btnDisconnect).setOnClickListener {
            DriveAuth.signOut(prefs)
            prefs.enabled = false
            UploadScheduler.applySchedule(this, prefs)
            toast("已中斷連結")
            refresh()
        }
        findViewById<View>(R.id.btnUploadNow).setOnClickListener {
            if (!prefs.granted) { toast("請先連結 Google 帳號"); return@setOnClickListener }
            lifecycleScope.launch {
                val n = UploadRepo(this@SettingsActivity).openCount()
                if (n == 0) { toast("沒有待上傳的檔案"); return@launch }
                UploadScheduler.enqueueNow(this@SettingsActivity, prefs)
                toast("已開始上傳 $n 個檔案（會等 Wi‑Fi／充電條件滿足）")
            }
        }
    }

    private fun startConnect() {
        lifecycleScope.launch {
            toast("正在連結…")
            when (val o = DriveAuth.authorize(this@SettingsActivity)) {
                is DriveAuth.Outcome.Token -> onLinked(o)
                is DriveAuth.Outcome.NeedsUi -> connectLauncher.launch(IntentSenderRequest.Builder(o.pendingIntent).build())
                is DriveAuth.Outcome.Fail -> onAuthFail(o)
            }
        }
    }

    private fun onLinked(o: DriveAuth.Outcome.Token) {
        prefs.granted = true
        prefs.accountEmail = o.email.orEmpty()
        prefs.lastError = ""
        toast("已連結" + (o.email?.let { "：$it" } ?: ""))
        refresh()
    }

    private fun onAuthFail(o: DriveAuth.Outcome.Fail) {
        prefs.lastError = o.message
        toast(o.message)
        refresh()
    }

    private fun refresh() {
        findViewById<SwitchCompat>(R.id.swShutterSound).isChecked = WmData.prefs(this).getBoolean("shutterSound", true)
        findViewById<SwitchCompat>(R.id.swUpload).isChecked = prefs.enabled
        findViewById<RadioButton>(if (prefs.whenMode == "daily") R.id.whenDaily else R.id.whenImmediate).isChecked = true
        findViewById<SwitchCompat>(R.id.swWifi).isChecked = prefs.wifiOnly
        findViewById<SwitchCompat>(R.id.swCharge).isChecked = prefs.chargingOnly
        findViewById<EditText>(R.id.fFolder).setText(prefs.folder)
        findViewById<TextView>(R.id.btnTime).text = prefs.time
        val linked = prefs.granted
        findViewById<TextView>(R.id.acctLabel).text = if (linked) prefs.accountEmail.ifBlank { "已連結 Google 帳號" } else "尚未連結 Google 帳號"
        findViewById<TextView>(R.id.acctSub).text = if (linked) "已授權上傳到你的雲端硬碟（僅此 App 建立的資料夾）" else "連結後才會上傳到雲端硬碟"
        findViewById<TextView>(R.id.btnConnect).text = if (linked) "重新連結" else "連結 Google 帳號"
        findViewById<View>(R.id.btnDisconnect).visibility = if (linked) View.VISIBLE else View.GONE
        refreshOpts()
        lifecycleScope.launch {
            val n = UploadRepo(this@SettingsActivity).openCount()
            findViewById<TextView>(R.id.stPending).text = "待上傳 $n 張"
            findViewById<TextView>(R.id.stLast).text = prefs.lastUploadLabel()
            val err = prefs.lastError
            findViewById<TextView>(R.id.stError).apply {
                text = err
                visibility = if (err.isBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun refreshOpts() {
        val on = prefs.enabled
        findViewById<View>(R.id.uploadOpts).alpha = if (on) 1f else 0.45f
        findViewById<View>(R.id.timeRow).alpha = if (prefs.whenMode == "daily") 1f else 0.4f
        findViewById<View>(R.id.btnTime).isEnabled = prefs.whenMode == "daily"
        findViewById<TextView>(R.id.btnTime).text = prefs.time
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
