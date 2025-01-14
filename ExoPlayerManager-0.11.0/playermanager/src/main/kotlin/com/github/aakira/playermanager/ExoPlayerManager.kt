package com.github.aakira.playermanager

import android.content.Context
import android.os.Handler
import com.google.android.exoplayer2.BuildConfig
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory
import com.google.android.exoplayer2.upstream.TransferListener
import okhttp3.OkHttpClient
import java.io.IOException

class ExoPlayerManager(private val context: Context, private val debugLogger: Boolean = BuildConfig.DEBUG) {

    var player: SimpleExoPlayer? = null
        private set
    /**
     * Reset player position when player resets
     */
    var prepareResetPosition: Boolean = false
    /**
     * Reset player state when player resets
     */
    var prepareResetState: Boolean = false

    private val bandwidthMeter = LimitBandwidthMeter()
    private var eventLogger: EventLogger? = null
    private var eventProxy = EventProxy()
    private val mainHandler = Handler()
    private var mediaSource: MediaSource? = null
    private var playerNeedsPrepare = false
    private var playerView: PlayerView? = null
    private var trackSelector: DefaultTrackSelector? = null

    private val onMediaSourceLoadErrorListeners = ArrayList<MediaSourceLoadErrorListener>()
    private var onAudioCapabilitiesChangedListeners = ArrayList<AudioCapabilitiesChangedListener>()
    private val onMetadataListeners = ArrayList<MetadataListener>()
    private val onPlaybackParametersListeners = ArrayList<PlaybackParametersChangedListener>()
    private val onPlayerErrorListeners = ArrayList<PlayerErrorListener>()
    private val onPlayerStateChangedListeners = ArrayList<PlayerStateChangedListener>()
    private val onRepeatModeChangedListeners = ArrayList<RepeatModeChangedListener>()
    private var onTracksChangedListeners = ArrayList<TracksChangedListener>()
    private val onVideoSizeChangedListeners = ArrayList<VideoSizeChangedListener>()
    private val onVideoRenderedListeners = ArrayList<VideoRenderedListener>()

    init {
        eventProxy.onMediaSourceLoadErrorListener = { dataSpec: DataSpec?, dataType: Int, trackType: Int, trackFormat: Format?,
                                                      trackSelectionReason: Int, trackSelectionData: Any?,
                                                      mediaStartTimeMs: Long, mediaEndTimeMs: Long, elapsedRealtimeMs: Long,
                                                      loadDurationMs: Long, bytesLoaded: Long, error: IOException?, wasCanceled: Boolean ->

            onMediaSourceLoadErrorListeners.forEach {
                it.invoke(dataSpec, dataType, trackType, trackFormat, trackSelectionReason,
                        trackSelectionData, mediaStartTimeMs, mediaEndTimeMs, elapsedRealtimeMs,
                        loadDurationMs, bytesLoaded, error, wasCanceled)
            }
        }
        eventProxy.onAudioCapabilitiesChangedListener = {
            onAudioCapabilitiesChangedListeners.forEach { listener ->
                listener.invoke(it)
            }
        }
        eventProxy.onMetadataListener = {
            onMetadataListeners.forEach { listener -> listener.invoke(it) }
        }
        eventProxy.onPlaybackParametersChangedListener = {
            onPlaybackParametersListeners.forEach { listener -> listener.invoke(it) }
        }
        eventProxy.onPlayerErrorListener = {
            playerNeedsPrepare = true
            onPlayerErrorListeners.forEach { listener -> listener.invoke(it) }
        }
        eventProxy.onPlayerStateChangedListener = { playWhenReady: Boolean, playbackState: Int ->
            onPlayerStateChangedListeners.forEach { it.invoke(playWhenReady, playbackState) }
        }
        eventProxy.onRepeatModeChangedListener = {
            onRepeatModeChangedListeners.forEach { listener -> listener.invoke(it) }
        }
        eventProxy.onTracksChangedListener = {
            onTracksChangedListeners.forEach { listener -> listener.invoke(it) }
        }
        eventProxy.onVideoSizeChangedListener = { width, height, unappliedRotationDegrees, pixelWidthHeightRatio ->
            onVideoSizeChangedListeners.forEach {
                it.invoke(width, height, unappliedRotationDegrees, pixelWidthHeightRatio)
            }
        }
        eventProxy.onVideoRenderedListener = {
            onVideoRenderedListeners.forEach { listener -> listener.invoke(it) }
        }

        trackSelector = DefaultTrackSelector(AdaptiveTrackSelection.Factory(bandwidthMeter))

        initializePlayer()
    }

    fun injectView(playerView: PlayerView) {
        playerView.player = player
        this.playerView = playerView
    }

    fun setHlsSource(dataSourceCreator: DataSourceCreator) {
        val dataSource = buildDataSourceFactory(dataSourceCreator.userAgent, bandwidthMeter, dataSourceCreator.okHttpClient)

        trackSelector?.parameters = DefaultTrackSelector.ParametersBuilder()
                .setPreferredAudioLanguage(dataSourceCreator.preferredAudioLanguage)
                .setPreferredTextLanguage(dataSourceCreator.preferredTextLanguage)
                .setSelectUndeterminedTextLanguage(dataSourceCreator.selectUndeterminedTextLanguage)
                .setDisabledTextTrackSelectionFlags(dataSourceCreator.disabledTextTrackSelectionFlags)
                .setForceLowestBitrate(dataSourceCreator.forceLowestBitrate)
                .setAllowMixedMimeAdaptiveness(dataSourceCreator.allowMixedMimeAdaptiveness)
                .setAllowNonSeamlessAdaptiveness(dataSourceCreator.allowNonSeamlessAdaptiveness)
                .setMaxVideoSize(dataSourceCreator.maxVideoWidth, dataSourceCreator.maxVideoHeight)
                .setMaxVideoBitrate(dataSourceCreator.maxVideoBitrate)
                .setExceedVideoConstraintsIfNecessary(dataSourceCreator.exceedVideoConstraintsIfNecessary)
                .setExceedRendererCapabilitiesIfNecessary(dataSourceCreator.exceedRendererCapabilitiesIfNecessary)
                .setViewportSize(dataSourceCreator.viewportWidth, dataSourceCreator.viewportHeight, dataSourceCreator.viewportOrientationMayChange)
                .build()

        mediaSource = HlsMediaSource.Factory(
                dataSourceCreator.dataSourceCreatorInterface?.let {
                    dataSourceCreator.dataSourceCreatorInterface.create(context, bandwidthMeter, dataSource)
                } ?: dataSource
        )
                .createMediaSource(dataSourceCreator.uri, mainHandler, eventProxy)

        playerNeedsPrepare = true
    }

    fun setExtractorMediaSource(dataSourceCreator: DataSourceCreator) {
        val dataSource = buildDataSourceFactory(dataSourceCreator.userAgent, bandwidthMeter, dataSourceCreator.okHttpClient)

        trackSelector?.parameters = DefaultTrackSelector.ParametersBuilder()
                .setPreferredAudioLanguage(dataSourceCreator.preferredAudioLanguage)
                .setPreferredTextLanguage(dataSourceCreator.preferredTextLanguage)
                .setSelectUndeterminedTextLanguage(dataSourceCreator.selectUndeterminedTextLanguage)
                .setDisabledTextTrackSelectionFlags(dataSourceCreator.disabledTextTrackSelectionFlags)
                .setForceLowestBitrate(dataSourceCreator.forceLowestBitrate)
                .setAllowMixedMimeAdaptiveness(dataSourceCreator.allowMixedMimeAdaptiveness)
                .setAllowNonSeamlessAdaptiveness(dataSourceCreator.allowNonSeamlessAdaptiveness)
                .setMaxVideoSize(dataSourceCreator.maxVideoWidth, dataSourceCreator.maxVideoHeight)
                .setMaxVideoBitrate(dataSourceCreator.maxVideoBitrate)
                .setExceedVideoConstraintsIfNecessary(dataSourceCreator.exceedVideoConstraintsIfNecessary)
                .setExceedRendererCapabilitiesIfNecessary(dataSourceCreator.exceedRendererCapabilitiesIfNecessary)
                .setViewportSize(dataSourceCreator.viewportWidth, dataSourceCreator.viewportHeight, dataSourceCreator.viewportOrientationMayChange)
                .build()

        mediaSource = ExtractorMediaSource.Factory(
                dataSourceCreator.dataSourceCreatorInterface?.let {
                    dataSourceCreator.dataSourceCreatorInterface.create(context, bandwidthMeter, dataSource)
                } ?: dataSource
        )
                .setExtractorsFactory(DefaultExtractorsFactory())
                .createMediaSource(dataSourceCreator.uri, mainHandler, eventProxy)

        playerNeedsPrepare = true
    }

    fun release() {
        player?.release()
        clearExoPlayerListeners()
        player = null
        playerView = null
    }

    fun play() {
        mediaSource ?: return

        if (playerNeedsPrepare) {
            player?.prepare(mediaSource, prepareResetPosition, prepareResetState)
            playerNeedsPrepare = false
        }

        player?.playWhenReady = true
    }

    fun pause() {
        player?.playWhenReady = false
    }

    fun stop() {
        player?.stop()
        playerNeedsPrepare = true
    }

    /**
     * init player and reconnect to a video stream
     */
    fun restart() {
        player?.release()
        clearExoPlayerListeners()
        player = null

        initializePlayer()
        playerView?.player = player
        play()
    }

    fun restartCurrentPosition() {
        val positionMs = getCurrentPosition()
        restart()
        seekTo(positionMs)
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun getDuration() = player?.duration ?: 0

    fun getCurrentPosition() = player?.currentPosition ?: 0

    fun getBufferedPercentage() = player?.bufferedPercentage ?: 0

    fun getBufferedPosition() = player?.bufferedPosition ?: 0

    fun isPlaying() = player?.playWhenReady ?: false

    fun getPlaybackState() = player?.playbackState ?: Player.STATE_IDLE

    fun getVolume() = player?.volume ?: 0f

    fun getPlayBackParameters() = player?.playbackParameters

    fun toMute() {
        player?.volume = 0f
    }

    fun toUnMute() {
        player?.volume = 1f
    }

    /**
     * @see [com.google.android.exoplayer2.PlaybackParameters]
     *
     * @param speed The factor by which playback will be sped up.
     * @param pitch The factor by which the audio pitch will be scaled.
     */
    fun setPlaybackParameters(speed: Float, pitch: Float) {
        player?.playbackParameters = PlaybackParameters(speed, pitch)
    }

    fun getPlaybackParameters() = player?.playbackParameters

    fun setMaxVideoBitrate(maxVideoBitrate: Long) {
        bandwidthMeter.setLimitBitrate(maxVideoBitrate)
    }

    fun addOnMediaSourceLoadErrorListener(listener: MediaSourceLoadErrorListener) {
        onMediaSourceLoadErrorListeners.add(listener)
    }

    fun removeMediaSourceErrorListener(listener: MediaSourceLoadErrorListener) {
        onMediaSourceLoadErrorListeners.remove(listener)
    }

    fun clearMediaSourceErrorListeners() {
        onMediaSourceLoadErrorListeners.clear()
    }

    fun addOnAudioCapabilitiesChangedListener(listener: AudioCapabilitiesChangedListener) {
        onAudioCapabilitiesChangedListeners.add(listener)
    }

    fun removeAudioCapabilitiesReceiverListener(listener: AudioCapabilitiesChangedListener) {
        onAudioCapabilitiesChangedListeners.remove(listener)
    }

    fun clearAudioCapabilitiesReceiverListeners() {
        onAudioCapabilitiesChangedListeners.clear()
    }

    fun addOnMetadataListener(listener: MetadataListener) {
        onMetadataListeners.add(listener)
    }

    fun removeMetadataListener(listener: MetadataListener) {
        onMetadataListeners.remove(listener)
    }

    fun clearOnMetadataListeners() {
        onMetadataListeners.clear()
    }

    fun addOnPlaybackParametersChangedListeners(listener: PlaybackParametersChangedListener) {
        onPlaybackParametersListeners.add(listener)
    }

    fun removePlaybackParametersChangedListeners(listener: PlaybackParametersChangedListener) {
        onPlaybackParametersListeners.remove(listener)
    }

    fun clearPlaybackParametersChangedListeners() {
        onPlaybackParametersListeners.clear()
    }

    fun addOnRepeatModeChangedListeners(listener: RepeatModeChangedListener) {
        onRepeatModeChangedListeners.add(listener)
    }

    fun removeRepeatModeChangedListeners(listener: RepeatModeChangedListener) {
        onRepeatModeChangedListeners.remove(listener)
    }

    fun clearRepeatModeChangedListeners() {
        onRepeatModeChangedListeners.clear()
    }

    fun addOnPlayerErrorListener(listener: PlayerErrorListener) {
        onPlayerErrorListeners.add(listener)
    }

    fun removePlayerErrorListener(listener: PlayerErrorListener) {
        onPlayerErrorListeners.remove(listener)
    }

    fun clearPlayerErrorListeners() {
        onPlayerErrorListeners.clear()
    }

    fun addOnStateChangedListener(listener: PlayerStateChangedListener) {
        onPlayerStateChangedListeners.add(listener)
    }

    fun removeStateChangedListener(listener: PlayerStateChangedListener) {
        onPlayerStateChangedListeners.remove(listener)
    }

    fun clearStateChangedListeners() {
        onPlayerStateChangedListeners.clear()
    }

    fun addOnTracksChangedListener(listener: TracksChangedListener) {
        onTracksChangedListeners.add(listener)
    }

    fun removeTracksChangedListener(listener: TracksChangedListener) {
        onTracksChangedListeners.remove(listener)
    }

    fun clearTracksChangedListeners() {
        onTracksChangedListeners.clear()
    }

    fun addOnVideoSizeChangedListener(listener: VideoSizeChangedListener) {
        onVideoSizeChangedListeners.add(listener)
    }

    fun removeVideoSizeChangedListener(listener: VideoSizeChangedListener) {
        onVideoSizeChangedListeners.remove(listener)
    }

    fun clearVideoSizeChangedListeners() {
        onVideoSizeChangedListeners.clear()
    }

    /**
     * @see [addOnMediaSourceLoadErrorListener]
     */
    @Deprecated("Merge MediaSourceLoadErrorListener")
    fun addOnExtractorMediaSourceLoadErrorListener(listener: ExtractorMediaSourceLoadErrorListener) {
    }

    /**
     * @see [removeMediaSourceErrorListener]
     */
    @Deprecated("Merge MediaSourceLoadErrorListener")
    fun removeExtractorMediaSourceLoadErrorListener(listener: ExtractorMediaSourceLoadErrorListener) {
    }

    /**
     * @see [clearMediaSourceErrorListeners]
     */
    @Deprecated("Merge MediaSourceLoadErrorListener")
    fun clearExtractorMediaSourceLoadErrorListeners() {
    }

    fun addOnVideoRenderedListener(listener: VideoRenderedListener) {
        onVideoRenderedListeners.add(listener)
    }

    fun removeVideoRenderedListener(listener: VideoRenderedListener) {
        onVideoRenderedListeners.remove(listener)
    }

    fun clearVideoRenderedListeners() {
        onVideoRenderedListeners.clear()
    }

    private fun initializePlayer() {
        player = ExoPlayerFactory.newSimpleInstance(context, trackSelector).apply {
            addListener(eventProxy)
            addVideoListener(eventProxy)
            addMetadataOutput(eventProxy)
            addVideoDebugListener(eventProxy)

            if (debugLogger) {
                eventLogger = EventLogger(trackSelector).also {
                    addListener(it)
                    addAudioDebugListener(it)
                    addVideoDebugListener(it)
                    addMetadataOutput(it)
                }
            }
        }
        playerNeedsPrepare = true
    }

    private fun clearExoPlayerListeners() {
        player?.run {
            removeListener(eventProxy)
            removeVideoListener(eventProxy)
            removeMetadataOutput(eventProxy)
            removeVideoDebugListener(eventProxy)

            eventLogger?.let {
                removeListener(it)
                removeAudioDebugListener(it)
                addVideoDebugListener(it)
                addMetadataOutput(it)
            }
        }
    }

    private fun buildDataSourceFactory(userAgent: String,
                                       bandwidthMeter: TransferListener<in DataSource>,
                                       okHttpClient: OkHttpClient?) = okHttpClient?.let {
        buildOkHttpDataSourceFactory(userAgent, bandwidthMeter, okHttpClient)
    } ?: buildDefaultHttpDataSourceFactory(userAgent, bandwidthMeter)

    private fun buildDefaultHttpDataSourceFactory(userAgent: String, listener: TransferListener<in DataSource>,
                                                  allowCrossProtocolRedirects: Boolean = false) =
            DefaultHttpDataSourceFactory(userAgent, listener,
                    DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                    DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS, allowCrossProtocolRedirects)

    private fun buildOkHttpDataSourceFactory(userAgent: String, listener: TransferListener<in DataSource>,
                                             okHttpClient: OkHttpClient) =
            OkHttpDataSourceFactory(okHttpClient, userAgent, listener)
}
