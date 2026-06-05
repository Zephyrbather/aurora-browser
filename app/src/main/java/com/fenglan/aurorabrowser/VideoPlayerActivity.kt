package com.fenglan.aurorabrowser

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.fenglan.aurorabrowser.browser.LocalPlaylistServer
import com.fenglan.aurorabrowser.databinding.ActivityVideoPlayerBinding
import java.io.File
import java.util.Locale
import kotlin.math.abs

class VideoPlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVideoPlayerBinding
    private var localPlaylistServer: LocalPlaylistServer? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var seeking = false
    private var videoWidth = 0
    private var videoHeight = 0

    // Gesture state
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureStartPosition = 0
    private var gestureStartBrightness = 0f
    private var gestureStartVolume = 0
    private var gestureInProgress = false
    private var gestureType = GestureType.NONE
    private lateinit var gestureDetector: GestureDetector
    private var audioManager: AudioManager? = null

    private enum class GestureType { NONE, SEEK, BRIGHTNESS, VOLUME }

    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgressUi()
            uiHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val offset = if (e.x < binding.root.width / 2f) -SEEK_STEP_MS else SEEK_STEP_MS
                seekBy(offset)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControlsVisibility()
                return true
            }
        })

        binding.closeButton.setOnClickListener { finish() }
        binding.pipButton.setOnClickListener { enterPip() }
        binding.rotateButton.setOnClickListener { toggleOrientation() }
        binding.playPauseButton.setOnClickListener { togglePlayback() }
        binding.seekBackButton.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        binding.seekForwardButton.setOnClickListener { seekBy(SEEK_STEP_MS) }
        binding.topChrome.setOnClickListener { toggleControlsVisibility(show = true) }
        binding.bottomControls.setOnClickListener { toggleControlsVisibility(show = true) }
        binding.seekSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.currentTimeText.text = formatDuration(value.toInt())
            }
        }
        binding.seekSlider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                seeking = true
            }

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                seeking = false
                binding.videoView.seekTo(slider.value.toInt())
                updateProgressUi()
            }
        })
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                    } else {
                        finish()
                    }
                }
            },
        )

        val title = intent.getStringExtra(EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.video_player_title)
        binding.titleText.text = title
        playFromIntent()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (gestureDetector.onTouchEvent(event)) return true

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartX = event.x
                gestureStartY = event.y
                gestureStartPosition = binding.videoView.currentPosition
                gestureStartBrightness = window.attributes.screenBrightness
                gestureStartVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                gestureInProgress = false
                gestureType = GestureType.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - gestureStartX
                val dy = event.y - gestureStartY
                val viewWidth = binding.root.width
                val viewHeight = binding.root.height

                if (!gestureInProgress && (abs(dx) > 20f || abs(dy) > 20f)) {
                    gestureInProgress = true
                    gestureType = if (abs(dx) > abs(dy)) GestureType.SEEK
                    else if (gestureStartX < viewWidth / 2f) GestureType.BRIGHTNESS
                    else GestureType.VOLUME
                    if (gestureType != GestureType.SEEK) {
                        binding.topChrome.isVisible = false
                        binding.bottomControls.isVisible = false
                    }
                }

                if (!gestureInProgress) return super.dispatchTouchEvent(event)

                when (gestureType) {
                    GestureType.SEEK -> {
                        val duration = binding.videoView.duration.takeIf { it > 0 } ?: 0
                        if (duration > 0) {
                            val seekMs = (dx / viewWidth * duration * 0.3f).toInt()
                            val target = (gestureStartPosition + seekMs).coerceIn(0, duration)
                            binding.currentTimeText.text = formatDuration(target)
                            binding.durationText.text = formatDuration(duration)
                            binding.seekSlider.value = target.toFloat()
                        }
                    }

                    GestureType.BRIGHTNESS -> {
                        val attr = window.attributes
                        val delta = -dy / viewHeight
                        attr.screenBrightness = (gestureStartBrightness + delta).coerceIn(0.01f, 1f)
                        window.attributes = attr
                    }

                    GestureType.VOLUME -> {
                        val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                        val delta = (-dy / viewHeight * maxVolume).toInt()
                        val target = (gestureStartVolume + delta).coerceIn(0, maxVolume)
                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    }

                    GestureType.NONE -> {}
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureInProgress && gestureType == GestureType.SEEK) {
                    binding.videoView.seekTo(binding.seekSlider.value.toInt())
                    toggleControlsVisibility(show = true)
                }
                if (gestureType == GestureType.BRIGHTNESS || gestureType == GestureType.VOLUME) {
                    toggleControlsVisibility(show = true)
                }
                gestureInProgress = false
                gestureType = GestureType.NONE
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(progressTicker)
        runCatching { binding.videoView.stopPlayback() }
        localPlaylistServer?.stop()
        localPlaylistServer = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(progressTicker)
        uiHandler.post(progressTicker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(progressTicker)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.videoSurfaceContainer.post { resizeVideoSurface() }
        updateControlsForOrientation()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.topChrome.isVisible = false
            binding.bottomControls.isVisible = false
            binding.pipButton.isVisible = false
        } else {
            binding.pipButton.isVisible = true
            toggleControlsVisibility(show = true)
        }
    }

    override fun onUserLeaveHint() {
        if (binding.videoView.isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(videoWidth.coerceAtLeast(1), videoHeight.coerceAtLeast(1)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setAutoEnterEnabled(true)
                params.setSeamlessResizeEnabled(true)
            }
            enterPictureInPictureMode(params.build())
        } else {
            super.onUserLeaveHint()
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(videoWidth.coerceAtLeast(1), videoHeight.coerceAtLeast(1)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setAutoEnterEnabled(true)
                params.setSeamlessResizeEnabled(true)
            }
            super.enterPictureInPictureMode(params.build())
        } else {
            Toast.makeText(this, "画中画需要 Android 8.0 或更高版本", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playFromIntent() {
        val playlistUri = intent.getStringExtra(EXTRA_PLAYLIST_URI).orEmpty()
        if (playlistUri.isBlank()) {
            finish()
            return
        }
        val playlistFile = Uri.parse(playlistUri).path?.let(::File)
        if (playlistFile == null || !playlistFile.exists()) {
            Toast.makeText(this, R.string.video_player_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val rootDir = playlistFile.parentFile ?: run {
            Toast.makeText(this, R.string.video_player_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        localPlaylistServer?.stop()
        localPlaylistServer = LocalPlaylistServer(rootDir)
        val playbackUrl = "${requireNotNull(localPlaylistServer).baseUrl}${playlistFile.name}"
        binding.loadingIndicator.isVisible = true

        binding.videoView.setOnPreparedListener { mediaPlayer ->
            onPrepared(mediaPlayer)
        }
        binding.videoView.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> binding.loadingIndicator.isVisible = true
                MediaPlayer.MEDIA_INFO_BUFFERING_END,
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START,
                -> binding.loadingIndicator.isVisible = false
            }
            false
        }
        binding.videoView.setOnCompletionListener {
            binding.playPauseButton.setImageResource(R.drawable.ic_play)
            updateProgressUi()
            toggleControlsVisibility(show = true)
        }
        binding.videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.video_player_error, Toast.LENGTH_SHORT).show()
            finish()
            true
        }
        binding.videoView.setVideoURI(Uri.parse(playbackUrl))
        binding.videoView.requestFocus()
    }

    private fun onPrepared(mediaPlayer: MediaPlayer) {
        mediaPlayer.isLooping = false
        mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
            videoWidth = width
            videoHeight = height
            resizeVideoSurface()
            updateControlsForOrientation()
        }
        videoWidth = mediaPlayer.videoWidth
        videoHeight = mediaPlayer.videoHeight
        binding.loadingIndicator.isVisible = false
        binding.seekSlider.valueFrom = 0f
        binding.seekSlider.valueTo = binding.videoView.duration.toFloat().coerceAtLeast(1f)
        binding.durationText.text = formatDuration(binding.videoView.duration)
        updatePlayPauseIcon()
        updateProgressUi()
        resizeVideoSurface()
        toggleControlsVisibility(show = true)
        binding.videoView.start()
        updatePlayPauseIcon()
    }

    private fun togglePlayback() {
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
        } else {
            binding.videoView.start()
        }
        updatePlayPauseIcon()
        toggleControlsVisibility(show = true)
    }

    private fun seekBy(offsetMs: Int) {
        val duration = binding.videoView.duration.takeIf { it > 0 } ?: return
        val target = (binding.videoView.currentPosition + offsetMs).coerceIn(0, duration)
        binding.videoView.seekTo(target)
        updateProgressUi()
        toggleControlsVisibility(show = true)
    }

    private fun updateProgressUi() {
        val duration = binding.videoView.duration.takeIf { it > 0 } ?: 0
        val position = binding.videoView.currentPosition.coerceAtLeast(0)
        if (!seeking) {
            binding.seekSlider.valueTo = duration.toFloat().coerceAtLeast(1f)
            binding.seekSlider.value = position.toFloat().coerceAtMost(binding.seekSlider.valueTo)
            binding.currentTimeText.text = formatDuration(position)
            binding.durationText.text = formatDuration(duration)
        }
        updatePlayPauseIcon()
    }

    private fun updatePlayPauseIcon() {
        binding.playPauseButton.setImageResource(
            if (binding.videoView.isPlaying) {
                R.drawable.ic_pause
            } else {
                R.drawable.ic_play
            },
        )
    }

    private fun toggleControlsVisibility(show: Boolean = !controlsVisible) {
        controlsVisible = show
        binding.topChrome.isVisible = show
        binding.bottomControls.isVisible = show
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
        updateControlsForOrientation()
    }

    private fun updateControlsForOrientation() {
        toggleControlsVisibility(show = true)
    }

    private fun resizeVideoSurface() {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return
        }
        val containerWidth = binding.videoSurfaceContainer.width
        val containerHeight = binding.videoSurfaceContainer.height
        if (containerWidth <= 0 || containerHeight <= 0) {
            binding.videoSurfaceContainer.post { resizeVideoSurface() }
            return
        }

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (videoAspect > containerAspect) {
            targetWidth = containerWidth
            targetHeight = (containerWidth / videoAspect).toInt()
        } else {
            targetHeight = containerHeight
            targetWidth = (containerHeight * videoAspect).toInt()
        }

        val layoutParams = FrameLayout.LayoutParams(
            targetWidth.coerceAtLeast(1),
            targetHeight.coerceAtLeast(1),
            android.view.Gravity.CENTER,
        )
        binding.videoView.layoutParams = layoutParams
    }

    private fun formatDuration(durationMs: Int): String {
        val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAYLIST_URI = "playlist_uri"
        private const val SEEK_STEP_MS = 15_000
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L

        fun createIntent(
            context: Context,
            title: String,
            playlistUri: String,
        ): Intent {
            return Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PLAYLIST_URI, playlistUri)
            }
        }
    }
}
