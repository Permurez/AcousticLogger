package com.example.acousticlogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

class ResultsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_results)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.resultsRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val results = readResultsFromIntent()
        if (results == null) {
            Toast.makeText(this, R.string.results_missing_data, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        bindResults(results)

        findViewById<MaterialButton>(R.id.copyPathButton).setOnClickListener {
            copyPathToClipboard(results.sessionDirPath)
        }
        findViewById<MaterialButton>(R.id.newScanButton).setOnClickListener { finish() }
    }

    private fun readResultsFromIntent(): SessionResults? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_RESULTS, SessionResults::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_RESULTS) as? SessionResults
        }
    }

    private fun bindResults(results: SessionResults) {
        findViewById<TextView>(R.id.resultsPathText).text = results.sessionDirPath
        findViewById<TextView>(R.id.resultsRoomText).text = getString(
            R.string.results_room_format,
            results.roomWidthM,
            results.roomDepthM,
            results.roomHeightM,
            results.roomVolumeM3,
            results.pointCount,
        )
        findViewById<TextView>(R.id.resultsAcousticText).text = getString(
            R.string.results_acoustic_format,
            results.rt60BroadbandSec,
            results.sabineAverageAbsorption,
            results.edcDropDb,
        )
        findViewById<TextView>(R.id.resultsMaterialsText).text = getString(
            R.string.results_materials_format,
            results.topMaterialLabel,
            results.materialsSummary,
        )
        findViewById<TextView>(R.id.resultsFilesText).text = results.exportFilesSummary
    }

    private fun copyPathToClipboard(path: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("session_path", path))
        Toast.makeText(this, R.string.results_path_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_RESULTS = "extra_session_results"

        fun createIntent(context: Context, results: SessionResults): Intent {
            return Intent(context, ResultsActivity::class.java).apply {
                putExtra(EXTRA_RESULTS, results)
            }
        }
    }
}
