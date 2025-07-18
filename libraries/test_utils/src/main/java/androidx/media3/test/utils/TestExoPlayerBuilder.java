/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.test.utils;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.SuitableOutputChecker;
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A builder of {@link ExoPlayer} instances for testing. */
@UnstableApi
public class TestExoPlayerBuilder {

  private final Context context;
  private Clock clock;
  private DefaultTrackSelector trackSelector;
  private LoadControl loadControl;
  private BandwidthMeter bandwidthMeter;
  @Nullable private Renderer[] renderers;
  @Nullable private RenderersFactory renderersFactory;
  @Nullable private MediaSource.Factory mediaSourceFactory;
  private boolean useLazyPreparation;
  private @MonotonicNonNull Looper looper;
  @Nullable private SuitableOutputChecker suitableOutputChecker;
  private long seekBackIncrementMs;
  private long seekForwardIncrementMs;
  private long maxSeekToPreviousPositionMs;
  private boolean deviceVolumeControlEnabled;
  private boolean suppressPlaybackWhenUnsuitableOutput;
  @Nullable private ExoPlayer.PreloadConfiguration preloadConfiguration;
  private boolean dynamicSchedulingEnabled;

  public TestExoPlayerBuilder(Context context) {
    this.context = context;
    clock = new FakeClock(/* isAutoAdvancing= */ true);
    trackSelector = new DefaultTrackSelector(context);
    loadControl = new DefaultLoadControl();
    bandwidthMeter = new DefaultBandwidthMeter.Builder(context).build();
    @Nullable Looper myLooper = Looper.myLooper();
    if (myLooper != null) {
      looper = myLooper;
    }
    seekBackIncrementMs = C.DEFAULT_SEEK_BACK_INCREMENT_MS;
    seekForwardIncrementMs = C.DEFAULT_SEEK_FORWARD_INCREMENT_MS;
    maxSeekToPreviousPositionMs = C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS;
    deviceVolumeControlEnabled = false;
  }

  /**
   * Sets whether to use lazy preparation.
   *
   * @param useLazyPreparation Whether to use lazy preparation.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setUseLazyPreparation(boolean useLazyPreparation) {
    this.useLazyPreparation = useLazyPreparation;
    return this;
  }

  /** Returns whether the player will use lazy preparation. */
  public boolean getUseLazyPreparation() {
    return useLazyPreparation;
  }

  /**
   * Sets a {@link DefaultTrackSelector}. The default value is a {@link DefaultTrackSelector} in its
   * initial configuration.
   *
   * @param trackSelector The {@link DefaultTrackSelector} to be used by the player.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setTrackSelector(DefaultTrackSelector trackSelector) {
    Assertions.checkNotNull(trackSelector);
    this.trackSelector = trackSelector;
    return this;
  }

  /** Returns the track selector used by the player. */
  public DefaultTrackSelector getTrackSelector() {
    return trackSelector;
  }

  /**
   * Sets a {@link LoadControl} to be used by the player. The default value is a {@link
   * DefaultLoadControl}.
   *
   * @param loadControl The {@link LoadControl} to be used by the player.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setLoadControl(LoadControl loadControl) {
    this.loadControl = loadControl;
    return this;
  }

  /** Returns the {@link LoadControl} that will be used by the player. */
  public LoadControl getLoadControl() {
    return loadControl;
  }

  /**
   * Sets the {@link BandwidthMeter}. The default value is a {@link DefaultBandwidthMeter} in its
   * default configuration.
   *
   * @param bandwidthMeter The {@link BandwidthMeter} to be used by the player.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setBandwidthMeter(BandwidthMeter bandwidthMeter) {
    Assertions.checkNotNull(bandwidthMeter);
    this.bandwidthMeter = bandwidthMeter;
    return this;
  }

  /** Returns the bandwidth meter used by the player. */
  public BandwidthMeter getBandwidthMeter() {
    return bandwidthMeter;
  }

  /**
   * Sets the {@link Renderer}s. If not set, the player will use a {@link FakeVideoRenderer} and a
   * {@link FakeAudioRenderer}. Setting the renderers is not allowed after a call to {@link
   * #setRenderersFactory(RenderersFactory)}.
   *
   * @param renderers A list of {@link Renderer}s to be used by the player.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setRenderers(Renderer... renderers) {
    assertThat(renderersFactory).isNull();
    this.renderers = renderers;
    return this;
  }

  /**
   * Sets the preload configuration.
   *
   * @see ExoPlayer#setPreloadConfiguration(ExoPlayer.PreloadConfiguration)
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setPreloadConfiguration(
      ExoPlayer.PreloadConfiguration preloadConfiguration) {
    this.preloadConfiguration = preloadConfiguration;
    return this;
  }

  /**
   * Returns the {@link Renderer Renderers} that have been set with {@link #setRenderers} or null if
   * no {@link Renderer Renderers} have been explicitly set. Note that these renderers may not be
   * the ones used by the built player, for example if a {@link #setRenderersFactory Renderer
   * factory} has been set.
   */
  @Nullable
  public Renderer[] getRenderers() {
    return renderers;
  }

  /**
   * Sets the {@link RenderersFactory}. The default factory creates all renderers set by {@link
   * #setRenderers(Renderer...)}. Setting the renderer factory is not allowed after a call to {@link
   * #setRenderers(Renderer...)}.
   *
   * @param renderersFactory A {@link RenderersFactory} to be used by the player.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setRenderersFactory(RenderersFactory renderersFactory) {
    assertThat(renderers).isNull();
    this.renderersFactory = renderersFactory;
    return this;
  }

  /**
   * Returns the {@link RenderersFactory} that has been set with {@link #setRenderersFactory} or
   * null if no factory has been explicitly set.
   */
  @Nullable
  public RenderersFactory getRenderersFactory() {
    return renderersFactory;
  }

  /**
   * Sets the {@link Clock} to be used by the player. The default value is an auto-advancing {@link
   * FakeClock}.
   *
   * @param clock A {@link Clock} to be used by the player.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setClock(Clock clock) {
    assertThat(clock).isNotNull();
    this.clock = clock;
    return this;
  }

  /** Returns the clock used by the player. */
  public Clock getClock() {
    return clock;
  }

  /**
   * Sets the {@link Looper} to be used for all calls to the player and for calling listeners.
   *
   * @param looper The {@link Looper} to be used for all calls to the player and for calling
   *     listeners.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setLooper(Looper looper) {
    this.looper = looper;
    return this;
  }

  /**
   * Sets the {@link SuitableOutputChecker} to check the suitability of the selected outputs for
   * playback.
   *
   * <p>If this method is not called, the library uses a default implementation based on framework
   * APIs.
   *
   * @return This builder.
   */
  @CanIgnoreReturnValue
  @RequiresApi(35)
  public TestExoPlayerBuilder setSuitableOutputChecker(
      SuitableOutputChecker suitableOutputChecker) {
    this.suitableOutputChecker = suitableOutputChecker;
    return this;
  }

  /**
   * Returns the {@link Looper} that will be used by the player, or null if no {@link Looper} has
   * been set yet and no default is available.
   */
  @Nullable
  public Looper getLooper() {
    return looper;
  }

  /**
   * Returns the {@link MediaSource.Factory} that will be used by the player, or null if no {@link
   * MediaSource.Factory} has been set yet and no default is available.
   */
  @Nullable
  public MediaSource.Factory getMediaSourceFactory() {
    return mediaSourceFactory;
  }

  /**
   * Sets the {@link MediaSource.Factory} to be used by the player.
   *
   * @param mediaSourceFactory The {@link MediaSource.Factory} to be used by the player.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setMediaSourceFactory(MediaSource.Factory mediaSourceFactory) {
    this.mediaSourceFactory = mediaSourceFactory;
    return this;
  }

  /**
   * Sets the seek back increment to be used by the player.
   *
   * @param seekBackIncrementMs The seek back increment to be used by the player.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setSeekBackIncrementMs(long seekBackIncrementMs) {
    this.seekBackIncrementMs = seekBackIncrementMs;
    return this;
  }

  /** Returns the seek back increment used by the player. */
  public long getSeekBackIncrementMs() {
    return seekBackIncrementMs;
  }

  /**
   * Sets the seek forward increment to be used by the player.
   *
   * @param seekForwardIncrementMs The seek forward increment to be used by the player.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setSeekForwardIncrementMs(long seekForwardIncrementMs) {
    this.seekForwardIncrementMs = seekForwardIncrementMs;
    return this;
  }

  /**
   * Sets the variable controlling player's ability to get/set device volume.
   *
   * @param deviceVolumeControlEnabled Whether the player can get/set device volume.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setDeviceVolumeControlEnabled(boolean deviceVolumeControlEnabled) {
    this.deviceVolumeControlEnabled = deviceVolumeControlEnabled;
    return this;
  }

  /** Returns the seek forward increment used by the player. */
  public long getSeekForwardIncrementMs() {
    return seekForwardIncrementMs;
  }

  /**
   * Sets the max seek to previous position, in milliseconds, to be used by the player.
   *
   * @param maxSeekToPreviousPositionMs The max seek to previous position to be used by the player.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setMaxSeekToPreviousPositionMs(long maxSeekToPreviousPositionMs) {
    this.maxSeekToPreviousPositionMs = maxSeekToPreviousPositionMs;
    return this;
  }

  /** Returns the max seek to previous position used by the player. */
  public long getMaxSeekToPreviousPosition() {
    return maxSeekToPreviousPositionMs;
  }

  /**
   * See {@link ExoPlayer.Builder#setSuppressPlaybackOnUnsuitableOutput(boolean)} for details.
   *
   * @param suppressPlaybackOnUnsuitableOutput Whether the player should suppress the playback when
   *     it is attempted on an unsuitable output.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setSuppressPlaybackOnUnsuitableOutput(
      boolean suppressPlaybackOnUnsuitableOutput) {
    this.suppressPlaybackWhenUnsuitableOutput = suppressPlaybackOnUnsuitableOutput;
    return this;
  }

  /**
   * See {@link ExoPlayer.Builder#experimentalSetDynamicSchedulingEnabled(boolean)} for details.
   *
   * @param dynamicSchedulingEnabled Whether the player should enable dynamically schedule its
   *     playback loop for when {@link Renderer} progress can be made.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public TestExoPlayerBuilder setDynamicSchedulingEnabled(boolean dynamicSchedulingEnabled) {
    this.dynamicSchedulingEnabled = dynamicSchedulingEnabled;
    return this;
  }

  /** Builds an {@link ExoPlayer} using the provided values or their defaults. */
  public ExoPlayer build() {
    Assertions.checkNotNull(
        looper, "TestExoPlayer builder run on a thread without Looper and no Looper specified.");
    // Do not update renderersFactory and renderers here, otherwise their getters may
    // return different values before and after build() is called, making them confusing.
    RenderersFactory playerRenderersFactory = renderersFactory;
    if (playerRenderersFactory == null) {
      playerRenderersFactory =
          (eventHandler,
              videoRendererEventListener,
              audioRendererEventListener,
              textRendererOutput,
              metadataRendererOutput) -> {
            HandlerWrapper clockAwareHandler =
                clock.createHandler(eventHandler.getLooper(), /* callback= */ null);
            return renderers != null
                ? renderers
                : new Renderer[] {
                  new FakeVideoRenderer(clockAwareHandler, videoRendererEventListener),
                  new FakeAudioRenderer(clockAwareHandler, audioRendererEventListener)
                };
          };
    }

    ExoPlayer.Builder builder =
        new ExoPlayer.Builder(context, playerRenderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setAnalyticsCollector(new DefaultAnalyticsCollector(clock))
            .setClock(clock)
            .setUseLazyPreparation(useLazyPreparation)
            .setLooper(looper)
            .setSeekBackIncrementMs(seekBackIncrementMs)
            .setSeekForwardIncrementMs(seekForwardIncrementMs)
            .setMaxSeekToPreviousPositionMs(maxSeekToPreviousPositionMs)
            .setDeviceVolumeControlEnabled(deviceVolumeControlEnabled)
            .setSuppressPlaybackOnUnsuitableOutput(suppressPlaybackWhenUnsuitableOutput)
            .experimentalSetDynamicSchedulingEnabled(dynamicSchedulingEnabled);
    if (suitableOutputChecker != null) {
      builder.setSuitableOutputChecker(suitableOutputChecker);
    }
    if (mediaSourceFactory != null) {
      builder.setMediaSourceFactory(mediaSourceFactory);
    }
    ExoPlayer exoPlayer = builder.build();
    if (preloadConfiguration != null) {
      exoPlayer.setPreloadConfiguration(preloadConfiguration);
    }
    return exoPlayer;
  }
}
