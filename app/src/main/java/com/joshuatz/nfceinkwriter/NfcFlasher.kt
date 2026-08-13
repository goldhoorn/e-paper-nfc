package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class NfcFlasher : AppCompatActivity(), NfcAdapter.ReaderCallback {
    private var mIsFlashing = false
        set(isFlashing) {
            field = isFlashing
            mWhileFlashingArea?.visibility =
                if (isFlashing) View.VISIBLE else View.GONE
            setControlsEnabled(!isFlashing)
            if (isFlashing) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                mProgressVal = 0
            }
        }
    private var mNfcAdapter: NfcAdapter? = null
    private var mProgressBar: ProgressBar? = null
    private var mProgressVal: Int = 0
    private var mSourceBitmap: Bitmap? = null
    private var mProcessedBitmap: Bitmap? = null
    private var mWhileFlashingArea: ConstraintLayout? = null
    private var mStatusView: TextView? = null
    private var mFlashStatusView: TextView? = null
    private var mPreviewView: ImageView? = null
    private var mThresholdLabel: TextView? = null
    private var mThresholdSeek: SeekBar? = null
    private var mSoftenLabel: TextView? = null
    private var mSoftenSeek: SeekBar? = null
    private var mRenderSpinner: Spinner? = null
    private var mInvertSwitch: SwitchCompat? = null
    private var mImgFilePath: String? = null
    private var mImgFileUri: Uri? = null
    private var mUiReady = false
    private val mUiHandler = Handler(Looper.getMainLooper())
    private val mFlashInFlight = AtomicBoolean(false)
    private lateinit var mPrefs: Preferences

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (mImgFileUri != null) {
            outState.putString("serializedGeneratedImgUri", mImgFileUri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_flasher)
        mPrefs = Preferences(this)

        val savedUriStr = savedInstanceState?.getString("serializedGeneratedImgUri")
        if (savedUriStr != null) {
            mImgFileUri = Uri.parse(savedUriStr)
        } else {
            mImgFilePath = intent.extras?.getString(IntentKeys.GeneratedImgPath)
            if (mImgFilePath != null) {
                mImgFileUri = Uri.fromFile(getFileStreamPath(mImgFilePath))
            }
        }
        if (mImgFileUri == null) {
            mImgFileUri = Uri.fromFile(getFileStreamPath(GeneratedImageFilename))
        }

        val opts = BitmapFactory.Options()
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        if (mImgFileUri != null) {
            mSourceBitmap = BitmapFactory.decodeFile(mImgFileUri!!.path, opts)
        }

        mPreviewView = findViewById(R.id.previewImageView)
        mWhileFlashingArea = findViewById(R.id.whileFlashingArea)
        mProgressBar = findViewById(R.id.nfcFlashProgressbar)
        mStatusView = findViewById(R.id.flashStatusText)
        mFlashStatusView = findViewById(R.id.pleaseWaitText)
        mThresholdLabel = findViewById(R.id.thresholdLabel)
        mThresholdSeek = findViewById(R.id.thresholdSeek)
        mSoftenLabel = findViewById(R.id.softenLabel)
        mSoftenSeek = findViewById(R.id.softenSeek)
        mRenderSpinner = findViewById(R.id.renderModeSpinner)
        mInvertSwitch = findViewById(R.id.invertSwitch)

        bindRenderControls()
        refreshProcessedPreview()

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (mNfcAdapter == null) {
            Toast.makeText(this, "NFC is not available on this device.", Toast.LENGTH_LONG).show()
            setIdleStatus("NFC is not available on this device")
        } else {
            setIdleStatus("Ready — lock the field, then hold still")
        }
    }

    private fun bindRenderControls() {
        val settings = mPrefs.getRenderSettings()
        val modes = RenderMode.values()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        mRenderSpinner?.adapter = adapter
        mRenderSpinner?.setSelection(modes.indexOf(settings.mode).coerceAtLeast(0), false)
        mInvertSwitch?.isChecked = settings.invert
        mThresholdSeek?.progress = settings.threshold
        mSoftenSeek?.progress = settings.soften
        updateThresholdLabel(settings.threshold)
        updateSoftenLabel(settings.soften)
        updateThresholdEnabled(settings.mode)

        mRenderSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!mUiReady) return
                val mode = RenderMode.values()[position]
                mPrefs.setRenderMode(mode)
                updateThresholdEnabled(mode)
                refreshProcessedPreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        mInvertSwitch?.setOnCheckedChangeListener { _, checked ->
            if (!mUiReady) return@setOnCheckedChangeListener
            mPrefs.setInvert(checked)
            refreshProcessedPreview()
        }
        mThresholdSeek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateThresholdLabel(progress)
                if (!mUiReady || !fromUser) return
                mPrefs.setThreshold(progress)
                refreshProcessedPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        mSoftenSeek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSoftenLabel(progress)
                if (!mUiReady || !fromUser) return
                mPrefs.setSoften(progress)
                refreshProcessedPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        mUiReady = true
    }

    private fun updateThresholdEnabled(mode: RenderMode) {
        val on = mode == RenderMode.THRESHOLD
        mThresholdSeek?.isEnabled = on
        mThresholdLabel?.alpha = if (on) 1f else 0.4f
    }

    private fun updateThresholdLabel(value: Int) {
        mThresholdLabel?.text = getString(R.string.threshold_label_fmt, value)
    }

    private fun updateSoftenLabel(value: Int) {
        mSoftenLabel?.text = getString(R.string.soften_label_fmt, value)
    }

    private fun refreshProcessedPreview() {
        val src = mSourceBitmap
        if (src == null) {
            setIdleStatus("No image loaded")
            return
        }
        val processed = ImageRenderer.render(src, mPrefs.getRenderSettings())
        val previous = mProcessedBitmap
        mProcessedBitmap = processed
        mPreviewView?.setImageBitmap(processed)
        if (previous != null && previous != processed && previous != src) {
            previous.recycle()
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        mRenderSpinner?.isEnabled = enabled
        mInvertSwitch?.isEnabled = enabled
        val thresholdOn = enabled && mPrefs.getRenderSettings().mode == RenderMode.THRESHOLD
        mThresholdSeek?.isEnabled = thresholdOn
        mSoftenSeek?.isEnabled = enabled
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
    }

    override fun onResume() {
        super.onResume()
        enableReaderMode()
    }

    override fun onTagDiscovered(tag: Tag) {
        val bitmap = mProcessedBitmap
        if (bitmap == null) {
            Log.w(TAG, "Tag seen but no bitmap loaded")
            return
        }

        val techList = tag.techList
        if (!techList.contains(NfcA::class.java.name)) {
            Log.w(TAG, "Ignoring non-NfcA tag: ${techList.joinToString()}")
            runOnUiThread { setIdleStatus("Not a WaveShare e-paper tag") }
            return
        }

        val tagId = try {
            String(tag.id, StandardCharsets.US_ASCII)
        } catch (_: Exception) {
            ""
        }
        if (tagId.isNotEmpty() && !KNOWN_UIDS.any { tagId.startsWith(it) }) {
            Log.w(TAG, "Unexpected tag UID '$tagId'")
        }

        if (!mFlashInFlight.compareAndSet(false, true)) {
            Log.i(TAG, "Already flashing, ignoring rediscovery")
            return
        }

        val screenSizeEnum = Preferences(this).getScreenSizeEnum()
        Log.i(TAG, "Tag $tagId matched, starting flash (size enum=$screenSizeEnum)")
        lifecycleScope.launch {
            try {
                flashBitmap(tag, bitmap, screenSizeEnum)
            } finally {
                mFlashInFlight.set(false)
            }
        }
    }

    private suspend fun flashBitmap(tag: Tag, bitmap: Bitmap, screenSizeEnum: Int) {
        withContext(Dispatchers.Main) {
            mIsFlashing = true
            updateProgressBar(0)
            setIdleStatus(getString(R.string.pleaseWaitWhileFlashingText))
        }

        val writer = WaveshareEpaperWriter()
        val progressTicker = object : Runnable {
            override fun run() {
                if (mIsFlashing) {
                    updateProgressBar(writer.progress)
                    val phase = writer.status
                    if (phase.isNotEmpty()) {
                        mFlashStatusView?.text = phase
                        mStatusView?.text = phase
                    }
                    mUiHandler.postDelayed(this, 80L)
                }
            }
        }
        mUiHandler.post(progressTicker)

        val result = withContext(Dispatchers.IO) {
            val nfca = NfcA.get(tag)
            try {
                writer.sendBitmap(nfca, screenSizeEnum, bitmap)
            } finally {
                try {
                    if (nfca.isConnected) nfca.close()
                } catch (e: IOException) {
                    Log.w(TAG, "close failed", e)
                }
            }
        }

        mUiHandler.removeCallbacks(progressTicker)
        withContext(Dispatchers.Main) {
            updateProgressBar(if (result.success) 100 else writer.progress)
            if (result.success) {
                setIdleStatus("Success — display updated")
                Toast.makeText(applicationContext, "Success! Flashed display!", Toast.LENGTH_LONG).show()
            } else {
                val msg = result.errMessage.ifBlank { "FAILED to Flash" }
                setIdleStatus(msg)
                Toast.makeText(applicationContext, "FAILED: $msg", Toast.LENGTH_LONG).show()
            }
            mIsFlashing = false
        }
    }

    private fun enableReaderMode() {
        val adapter = mNfcAdapter ?: return
        val extras = Bundle()
        extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 300000)
        adapter.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            extras
        )
        Log.i(TAG, "Reader mode enabled")
    }

    private fun disableReaderMode() {
        try {
            mNfcAdapter?.disableReaderMode(this)
        } catch (e: Exception) {
            Log.w(TAG, "disableReaderMode", e)
        }
    }

    private fun updateProgressBar(updated: Int) {
        mProgressVal = updated
        mProgressBar?.progress = updated
    }

    private fun setIdleStatus(text: String) {
        mStatusView?.text = text
    }

    companion object {
        private const val TAG = "NfcFlasher"
        private val KNOWN_UIDS = arrayOf("WSDZ", "FSTN")
    }
}
