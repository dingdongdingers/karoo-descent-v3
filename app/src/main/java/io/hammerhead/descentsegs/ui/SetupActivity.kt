package io.hammerhead.descentsegs.ui

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.hammerhead.descentsegs.R
import io.hammerhead.descentsegs.data.SegmentRepository
import io.hammerhead.descentsegs.data.StravaCredentials
import io.hammerhead.descentsegs.data.WORK_NAME
import io.hammerhead.descentsegs.data.scheduleMonthlySync
import io.hammerhead.descentsegs.data.syncNow
import io.hammerhead.descentsegs.segment.isDebugEnabled
import io.hammerhead.descentsegs.segment.setDebugEnabled
import java.io.File

class SetupActivity : AppCompatActivity() {

    private lateinit var creds: StravaCredentials
    private lateinit var repo: SegmentRepository
    private lateinit var etClientId: EditText
    private lateinit var etClientSecret: EditText
    private lateinit var etRefreshToken: EditText
    private lateinit var btnSave: Button
    private lateinit var btnSyncNow: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvSegmentList: TextView
    private lateinit var cbDebug: CheckBox
    private lateinit var btnClearLog: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        creds = StravaCredentials(this)
        repo = SegmentRepository(this)

        etClientId = findViewById(R.id.et_client_id)
        etClientSecret = findViewById(R.id.et_client_secret)
        etRefreshToken = findViewById(R.id.et_refresh_token)
        btnSave = findViewById(R.id.btn_save)
        btnSyncNow = findViewById(R.id.btn_sync_now)
        tvStatus = findViewById(R.id.tv_status)
        tvSegmentList = findViewById(R.id.tv_segment_list)
        cbDebug = findViewById(R.id.cb_debug)
        btnClearLog = findViewById(R.id.btn_clear_log)

        if (creds.isConfigured()) {
            etClientId.setText(creds.clientId)
            etClientSecret.hint = "••••• (saved)"
            etRefreshToken.hint = "••••• (saved)"
        }

        cbDebug.isChecked = isDebugEnabled(this)
        cbDebug.setOnCheckedChangeListener { _, checked ->
            setDebugEnabled(this, checked)
            tvStatus.text = if (checked) "Debug logging ON" else "Debug logging OFF"
        }

        btnClearLog.setOnClickListener {
            File(filesDir, "app-log.txt").delete()
            tvStatus.text = "Log cleared"
        }

        btnSave.setOnClickListener { saveAndSync() }
        btnSyncNow.setOnClickListener {
            if (!creds.isConfigured()) { tvStatus.text = "Save credentials first."; return@setOnClickListener }
            syncNow(this)
            tvStatus.text = getString(R.string.status_syncing)
            observeSync()
        }

        refreshList()
    }

    private fun saveAndSync() {
        val id = etClientId.text.toString().trim()
        val secret = etClientSecret.text.toString().trim()
        val token = etRefreshToken.text.toString().trim()
        if (id.isBlank() || secret.isBlank() || token.isBlank()) {
            tvStatus.text = "All three fields are required."
            return
        }
        creds.clientId = id
        creds.clientSecret = secret
        creds.refreshToken = token
        scheduleMonthlySync(this)
        syncNow(this)
        tvStatus.text = getString(R.string.status_syncing)
        observeSync()
    }

    private fun observeSync() {
        btnSave.isEnabled = false
        btnSyncNow.isEnabled = false
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(WORK_NAME)
            .observe(this) { infos ->
                val latest = infos?.lastOrNull() ?: return@observe
                when (latest.state) {
                    WorkInfo.State.SUCCEEDED -> { refreshList(); btnSave.isEnabled = true; btnSyncNow.isEnabled = true }
                    WorkInfo.State.FAILED -> {
                        tvStatus.text = getString(R.string.status_error, "Check credentials")
                        btnSave.isEnabled = true; btnSyncNow.isEnabled = true
                    }
                    WorkInfo.State.RUNNING -> tvStatus.text = getString(R.string.status_syncing)
                    else -> {}
                }
            }
    }

    private fun refreshList() {
        val segs = repo.getSegments()
        tvStatus.text = if (segs.isEmpty()) getString(R.string.status_idle)
                        else getString(R.string.status_ok, segs.size)
        tvSegmentList.text = if (segs.isEmpty()) "None yet — save credentials and tap Sync Now."
        else segs.joinToString("\n") { s ->
            val pr = s.prSeconds?.let { " | PR ${fmt(it)}" } ?: ""
            val kom = s.komSeconds?.let { " | KOM ${fmt(it)}" } ?: " | KOM N/A"
            "↓ ${s.name}$pr$kom"
        }
    }

    private fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
}
