package com.shizuri.videocoversetter

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.SimpleStorageHelper
import com.anggrayudi.storage.file.FileFullPath
import com.anggrayudi.storage.file.StorageId
import com.anggrayudi.storage.file.getAbsolutePath
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.shizuri.videocoversetter.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var currentVideoUri: Uri? = null
    private var storageHelper = SimpleStorageHelper(this)
    private var initialPath: FileFullPath = FileFullPath(this, StorageId.PRIMARY, "")
    private var toastUtil: ToastUtil = ToastUtil(this)
    private lateinit var sharedPrefs: SharedPreferences

    companion object {
        private const val REQUEST_SELECT_VIDEO = 1001
        private const val REQUEST_STORAGE_PERMISSION = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPrefs = getSharedPreferences("${packageName}_prefs", Context.MODE_PRIVATE)
        setupListeners()

        // 检查是否拥有全部存储权限
        // storageHelper.requestStorageAccess() // Request storage access for Android 11 and above
        if (!SimpleStorage.hasFullDiskAccess(this, StorageId.PRIMARY)) {
            toastUtil.showToast("Please grant full storage permission")
            storageHelper.storage.requestFullStorageAccess()
        }

        // 获取传入的视频 URI
        currentVideoUri = intent.data
        if (currentVideoUri != null) {
            storageHelper.storage.checkIfFileReceived(intent)
            Log.d("VideoCoverSetterDebug", "Received video file: ${currentVideoUri}")
            toastUtil.showToast("Received video file: ${currentVideoUri}")
            loadVideo(currentVideoUri!!)
        } else {
            toastUtil.showToast("No video file received, please select manually")
        }

        storageHelper.onFileSelected = { requestCode, files ->
            if (files.isNotEmpty()) {
                val file = files[0]
                Log.d("VideoCoverSetterDebug", "Selected video file: ${file.getAbsolutePath(this)}")
                toastUtil.showToast("Selected video file: ${file.getAbsolutePath(this)}")
                currentVideoUri = file.uri
                loadVideo(currentVideoUri!!)
            }
        }
    }

    private fun setupListeners() {
        binding.apply {
            // Time control buttons
            btnMinus10s.setOnClickListener { seekRelative(-10000) }
            btnMinus1s.setOnClickListener { seekRelative(-1000) }
            btnMinusPoint1s.setOnClickListener { seekRelative(-100) }
            btnPlusPoint1s.setOnClickListener { seekRelative(100) }
            btnPlus1s.setOnClickListener { seekRelative(1000) }
            btnPlus10s.setOnClickListener { seekRelative(10000) }

            // Video control buttons
            btnSelectVideo.setOnClickListener {
                if (!SimpleStorage.hasFullDiskAccess(this@MainActivity, StorageId.PRIMARY)) {
                    toastUtil.showToast("Please grant full storage permission")
                    storageHelper.storage.requestFullStorageAccess()
                }
                storageHelper.openFilePicker(initialPath = initialPath)
            }
            btnCaptureFrame.setOnClickListener { captureCurrentFrame() }
            btnSetCover.setOnClickListener { setThumbnailAsCover() }

            // Settings
            switchOverwrite.isChecked = sharedPrefs.getBoolean("KEY_SWITCH_STATE", false)
            switchOverwrite.setOnCheckedChangeListener { _, isChecked ->
                // 立即提交保存（apply() 是异步，commit() 是同步）
                sharedPrefs.edit {
                    putBoolean("KEY_SWITCH_STATE", isChecked)
                    commit()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        storageHelper.onSaveInstanceState(outState)
        Log.d("VideoCoverSetterDebug", "onSaveInstanceState: $outState")
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        Log.d("VideoCoverSetterDebug", "onRestoreInstanceState: $savedInstanceState")
        storageHelper.onRestoreInstanceState(savedInstanceState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Mandatory for direct subclasses of android.app.Activity,
        // but not for subclasses of androidx.fragment.app.Fragment, androidx.activity.ComponentActivity, androidx.appcompat.app.AppCompatActivity
        Log.d(
            "MainActivity",
            "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, data=$data"
        )
        storageHelper.storage.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Mandatory for direct subclasses of android.app.Activity,
        // but not for subclasses of androidx.fragment.app.Fragment, androidx.activity.ComponentActivity, androidx.appcompat.app.AppCompatActivity
        Log.d(
            "MainActivity",
            "onRequestPermissionsResult: requestCode=$requestCode, permissions=${permissions.contentToString()}, grantResults=${grantResults.contentToString()}"
        )
        storageHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)

    }

    private fun loadVideo(uri: Uri) {
        try {
            Log.d("VideoCoverSetterDebug", "Loading video: $uri")
            val mediaItem = MediaItem.fromUri(uri)
            player = ExoPlayer.Builder(this).build().apply {
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_OFF
                binding.playerView.player = this
                setMediaItem(mediaItem)
                prepare()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("MainActivity", "Failed to load video: $e")
            toastUtil.showToast(R.string.error_loading_video)
            return
        }
        Log.d("VideoCoverSetterDebug", "Video loaded successfully")
    }

    private fun seekRelative(timeMs: Long) {
        if (player?.mediaItemCount == 0) {
            toastUtil.showToast("No video loaded")
            return
        }
        player?.let {
            val current = it.currentPosition
            val newPosition = current + timeMs
            val duration = it.duration

            // Ensure position is within valid range
            val clampedPosition = newPosition.coerceIn(0, duration)
            it.seekTo(clampedPosition)
            toastUtil.showToast("Seeking to $clampedPosition ms", Toast.LENGTH_SHORT)
        }
    }

    private fun captureCurrentFrame(): Bitmap? {
        var bitmap: Bitmap? = null
        currentVideoUri?.let { uri ->
            try {
                val textureView = binding.playerView.videoSurfaceView as TextureView?
                // Extract the frame at the current position
                textureView?.bitmap?.let {
                    binding.ivCapturedFrame.setImageBitmap(it)
                    bitmap = it
                } ?: run {
                    toastUtil.showToast(R.string.error_capturing_frame)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                toastUtil.showToast(R.string.error_capturing_frame)
            }
        }
        return bitmap
    }

    private fun getRealPathFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Video.Media.DATA)
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, projection, null, null, null)
            cursor?.let {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    it.getString(columnIndex)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun setThumbnailAsCover() {
        if (currentVideoUri == null) {
            toastUtil.showToast("Video URI is null")
            return
        }
        if (player == null) {
            toastUtil.showToast("Player is null")
            return
        }
        val externalDir = getExternalFilesDir(null)
        var videoPath: String? = null

        DocumentFile.fromSingleUri(this, currentVideoUri!!)?.let {
            videoPath = it.getAbsolutePath(this)
        }

        if (videoPath == null || videoPath!!.trim().isEmpty()) {
            // Maybe you open this file from another app, such as System file manager.
            // That will return a SAF URI, and getAbsolutePath() will return null.
            // So we try to utilise contentResolver to get the real path.
            videoPath = getRealPathFromUri(currentVideoUri!!)
        }
        if (videoPath == null || videoPath!!.trim().isEmpty()) {
            toastUtil.showToast("Failed to get video path: $currentVideoUri")
            return
        }
        Log.d("VideoCoverSetterDebug", "Video path: $videoPath")
        try {
            player!!.pause()
            val currentPosition = player!!.currentPosition
            val bitmap = captureCurrentFrame()
            if (bitmap == null) {
                toastUtil.showToast("Failed to capture frame")
                return
            }
            val dateStr = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
            val coverFile = File(externalDir, "cover_${dateStr}.jpg")
            // 保存帧为图片
            try {
                FileOutputStream(coverFile).use { fos ->
                    bitmap.compress(
                        Bitmap.CompressFormat.JPEG, 100, fos
                    )
                }
            } catch (e: IOException) {
                e.printStackTrace()
                toastUtil.showToast("Failed to save frame as image: $e")
                return
            }
            createVideoWithCover(videoPath!!, coverFile, binding.switchOverwrite.isChecked)
            coverFile.delete()
            if (binding.switchOverwrite.isChecked) {
                // 由于覆盖了原视频，所以需要重新加载视频
                loadVideo(currentVideoUri!!)
                player!!.seekTo(currentPosition)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            toastUtil.showToast("Failed to set thumbnail as cover: $e")
        }
    }

    private fun createVideoWithCover(
        videoPath: String, coverFile: File, overwrite: Boolean = false
    ) {
        val videoFileName = videoPath.substring(videoPath.lastIndexOf("/") + 1)
        val lastDotIndex = videoFileName.lastIndexOf(".")
        val dateStr = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
        val newVideoFileName = if (lastDotIndex == -1) {
            videoFileName + "_${dateStr}_with_cover"
        } else {
            videoFileName.substring(
                0, lastDotIndex
            ) + "_${dateStr}_with_cover" + videoFileName.substring(
                lastDotIndex
            )
        }
        val outputVideoPath = videoPath.replace(videoFileName, newVideoFileName)
        toastUtil.showToast("Creating video with cover: $outputVideoPath")
        Log.d("VideoCoverSetterDebug", "videoFileName: $videoFileName")
        Log.d("VideoCoverSetterDebug", "newVideoFileName: $newVideoFileName")
        Log.d("VideoCoverSetterDebug", "videoPath: $videoPath")
        Log.d("VideoCoverSetterDebug", "outputVideoPath: $outputVideoPath")
        try {
            executeFFmpegCommands(File(videoPath), coverFile, outputVideoPath, overwrite)
        } catch (e: Exception) {
            e.printStackTrace()
            toastUtil.showToast("Failed to execute FFmpeg commands: $e")
        }
    }

    private fun executeFFmpegCommands(
        inputVideoFile: File, coverFile: File, outputVideoPath: String, overwrite: Boolean = false
    ) {
        val commands = arrayOf(
            "-y",
            "-i",
            inputVideoFile.absolutePath,
            "-i",
            coverFile.absolutePath,
            "-map",
            "0",
            "-map",
            "1",
            "-map",
            "-0:2",
            "-c",
            "copy",
            "-disposition:v:1",
            "attached_pic",
            outputVideoPath
        )
        // FFmpeg.executeAsync(commands, (executionId, returnCode) -> handleFFmpegResult(returnCode, inputVideoFile, coverFile, outputVideoPath));

        val session: FFmpegSession = FFmpegKit.executeWithArguments(commands)
        val returnCode = session.returnCode
        val finalVideoPath = if (overwrite) inputVideoFile.absolutePath else outputVideoPath
        if (ReturnCode.isSuccess(returnCode)) {
            if (overwrite) {
                inputVideoFile.delete()
                File(outputVideoPath).renameTo(inputVideoFile)
                Log.d("VideoCoverSetterDebug", "Overwritten video at: $finalVideoPath")
            } else {
                Log.d("VideoCoverSetterDebug", "New video without overwriting is saved at: $finalVideoPath")
            }
            // alert dialog
            AlertDialog.Builder(this).setTitle("Cover set successfully!")
                .setMessage("New video saved at: \n$finalVideoPath")
                .setNegativeButton("OK") { dialog, _ -> dialog.dismiss() }
                .setPositiveButton("Close App") { dialog, _ -> dialog.dismiss(); finish() }.show()
        } else {
            toastUtil.showToast("Failed to set cover, error code: $returnCode")
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}