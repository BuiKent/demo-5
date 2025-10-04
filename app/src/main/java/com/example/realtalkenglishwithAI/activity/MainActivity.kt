package com.example.realtalkenglishwithAI.activity

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.databinding.ActivityMainBinding
import com.example.realtalkenglishwithAI.viewmodel.VoskModelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logTag = "MainActivityVosk"
    private val voskModelViewModel: VoskModelViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* isGranted: Boolean -> */
            // Handle permission result if needed in the future.
        }

    companion object {
        private const val PREFS_NAME = "VoskModelPrefs"
        private const val KEY_MODEL_UNZIPPED_SUCCESSFULLY = "model_unzipped_successfully_v1" // Added _v1 for versioning
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        askNotificationPermission()
        setupNavigation()
        prepareVoskModelAssets()

        voskModelViewModel.errorMessage.observe(this) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(this, "Model Initialization Error: $it", Toast.LENGTH_LONG).show()
                Log.e(logTag, "VoskModelViewModel error: $it")
            }
        }
    }

    private fun setupNavigation() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        // The graph is now set via XML, and the NavController no longer needs special setup
        // for Compose, as it only navigates between Fragments.
        binding.bottomNav.setupWithNavController(navController)
    }

    private fun isModelUnzippedSuccessfully(): Boolean {
        return prefs.getBoolean(KEY_MODEL_UNZIPPED_SUCCESSFULLY, false)
    }

    private fun setModelUnzippedSuccessfully(success: Boolean) {
        prefs.edit().putBoolean(KEY_MODEL_UNZIPPED_SUCCESSFULLY, success).apply()
    }

    private fun prepareVoskModelAssets() {
        val modelDir = File(filesDir, "model")
        val modelAssetFileName = "vosk-model.zip"

        if (isModelUnzippedSuccessfully()) {
            Log.i(logTag, "Model previously unzipped (flagged in SharedPreferences). Triggering ViewModel to load/verify.")
            val amFile = File(modelDir, "am")
            val confFile = File(modelDir, "conf")
            if (modelDir.exists() && amFile.exists() && confFile.exists()) {
                voskModelViewModel.initModelAfterUnzip()
                return
            } else {
                Log.w(logTag, "SharedPreferences flag is true, but model files are missing. Will attempt to re-unzip.")
                setModelUnzippedSuccessfully(false) // Reset flag as files are missing
            }
        }

        val amFile = File(modelDir, "am")
        val confFile = File(modelDir, "conf")
        val areModelFilesPhysicallyPresentAndValid = modelDir.exists() && modelDir.isDirectory &&
                                                     amFile.exists() && amFile.isDirectory &&
                                                     confFile.exists() && confFile.isDirectory &&
                                                     (modelDir.listFiles()?.isNotEmpty() == true)

        if (!areModelFilesPhysicallyPresentAndValid) {
            Log.i(logTag, "Vosk model files at ${modelDir.absolutePath} are missing or incomplete. Attempting to unzip from assets: $modelAssetFileName")
            
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    unzipAssetToDirectory(applicationContext, modelAssetFileName, modelDir)
                    Log.i(logTag, "Successfully unzipped $modelAssetFileName. Setting SharedPreferences flag.")
                    setModelUnzippedSuccessfully(true)
                    withContext(Dispatchers.Main) {
                        voskModelViewModel.initModelAfterUnzip()
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "Critical error during Vosk model unzipping ($modelAssetFileName): ${e.message}", e)
                    setModelUnzippedSuccessfully(false) // Ensure flag is false on error
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to prepare speech model assets: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            Log.i(logTag, "Vosk model files appear to be physically present at ${modelDir.absolutePath}. Setting SharedPreferences flag and triggering ViewModel.")
            setModelUnzippedSuccessfully(true) // Ensure flag is set if files are already there
            voskModelViewModel.initModelAfterUnzip()
        }
    }

    @Throws(IOException::class)
    private fun unzipAssetToDirectory(context: Context, assetFileName: String, targetDirectory: File) {
        // Unzip logic remains the same
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
