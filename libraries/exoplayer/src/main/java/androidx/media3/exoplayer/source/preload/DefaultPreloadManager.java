/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.source.preload;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static java.lang.Math.abs;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.os.Looper;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilitiesList;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import com.google.common.base.Predicate;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Comparator;

/**
 * A preload manager that preloads with the {@link PreloadMediaSource} to load the media data into
 * the {@link SampleQueue}.
 */
@UnstableApi
public final class DefaultPreloadManager extends BasePreloadManager<Integer> {

  /**
   * An implementation of {@link TargetPreloadStatusControl.PreloadStatus} that describes the
   * preload status of the {@link PreloadMediaSource}.
   */
  public static class Status implements TargetPreloadStatusControl.PreloadStatus {

    /**
     * Stages for the preload status. One of {@link #STAGE_SOURCE_PREPARED}, {@link
     * #STAGE_TRACKS_SELECTED} or {@link #STAGE_LOADED_FOR_DURATION_MS}.
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target(TYPE_USE)
    @IntDef(
        value = {
          STAGE_SOURCE_PREPARED,
          STAGE_TRACKS_SELECTED,
          STAGE_LOADED_FOR_DURATION_MS,
        })
    public @interface Stage {}

    /** The {@link PreloadMediaSource} has completed preparation. */
    public static final int STAGE_SOURCE_PREPARED = 0;

    /** The {@link PreloadMediaSource} has tracks selected. */
    public static final int STAGE_TRACKS_SELECTED = 1;

    /**
     * The {@link PreloadMediaSource} is loaded for a specific duration from the default start
     * position, in milliseconds.
     */
    public static final int STAGE_LOADED_FOR_DURATION_MS = 2;

    private final @Stage int stage;
    private final long value;

    public Status(@Stage int stage, long value) {
      this.stage = stage;
      this.value = value;
    }

    public Status(@Stage int stage) {
      this(stage, C.TIME_UNSET);
    }

    @Override
    public @Stage int getStage() {
      return stage;
    }

    @Override
    public long getValue() {
      return value;
    }
  }

  private final RendererCapabilitiesList rendererCapabilitiesList;
  private final PreloadMediaSource.Factory preloadMediaSourceFactory;

  /**
   * Constructs a new instance.
   *
   * @param targetPreloadStatusControl The {@link TargetPreloadStatusControl}.
   * @param mediaSourceFactory The {@link MediaSource.Factory}.
   * @param trackSelector The {@link TrackSelector}. The instance passed should be {@link
   *     TrackSelector#init(TrackSelector.InvalidationListener, BandwidthMeter) initialized}.
   * @param bandwidthMeter The {@link BandwidthMeter}. It should be the same bandwidth meter of the
   *     {@link ExoPlayer} that will play the managed {@link PreloadMediaSource}.
   * @param rendererCapabilitiesListFactory The {@link RendererCapabilitiesList.Factory}. To make
   *     preloading work properly, it must create a {@link RendererCapabilitiesList} holding an
   *     {@linkplain RendererCapabilitiesList#getRendererCapabilities() array of renderer
   *     capabilities} that matches the {@linkplain ExoPlayer#getRendererCount() count} and the
   *     {@linkplain ExoPlayer#getRendererType(int) renderer types} of the array of {@linkplain
   *     Renderer renderers} created by the {@link RenderersFactory} used by the {@link ExoPlayer}
   *     that will play the managed {@link PreloadMediaSource}.
   * @param allocator The {@link Allocator}. It should be the same allocator of the {@link
   *     ExoPlayer} that will play the managed {@link PreloadMediaSource}.
   * @param preloadLooper The {@link Looper} that will be used for preloading. It should be the same
   *     playback looper of the {@link ExoPlayer} that will play the managed {@link
   *     PreloadMediaSource}.
   */
  public DefaultPreloadManager(
      TargetPreloadStatusControl<Integer> targetPreloadStatusControl,
      MediaSource.Factory mediaSourceFactory,
      TrackSelector trackSelector,
      BandwidthMeter bandwidthMeter,
      RendererCapabilitiesList.Factory rendererCapabilitiesListFactory,
      Allocator allocator,
      Looper preloadLooper) {
    super(new RankingDataComparator(), targetPreloadStatusControl, mediaSourceFactory);
    this.rendererCapabilitiesList =
        rendererCapabilitiesListFactory.createRendererCapabilitiesList();
    preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            new SourcePreloadControl(),
            trackSelector,
            bandwidthMeter,
            rendererCapabilitiesList.getRendererCapabilities(),
            allocator,
            preloadLooper);
  }

  /**
   * Sets the index of the current playing media.
   *
   * @param currentPlayingIndex The index of current playing media.
   */
  public void setCurrentPlayingIndex(int currentPlayingIndex) {
    RankingDataComparator rankingDataComparator =
        (RankingDataComparator) this.rankingDataComparator;
    rankingDataComparator.currentPlayingIndex = currentPlayingIndex;
  }

  @Override
  public MediaSource createMediaSourceForPreloading(MediaSource mediaSource) {
    return preloadMediaSourceFactory.createMediaSource(mediaSource);
  }

  @Override
  protected void preloadSourceInternal(MediaSource mediaSource, long startPositionsUs) {
    checkArgument(mediaSource instanceof PreloadMediaSource);
    ((PreloadMediaSource) mediaSource).preload(startPositionsUs);
  }

  @Override
  protected void clearSourceInternal(MediaSource mediaSource) {
    checkArgument(mediaSource instanceof PreloadMediaSource);
    ((PreloadMediaSource) mediaSource).clear();
  }

  @Override
  protected void releaseSourceInternal(MediaSource mediaSource) {
    checkArgument(mediaSource instanceof PreloadMediaSource);
    ((PreloadMediaSource) mediaSource).releasePreloadMediaSource();
  }

  @Override
  protected void releaseInternal() {
    rendererCapabilitiesList.release();
  }

  private static final class RankingDataComparator implements Comparator<Integer> {

    public int currentPlayingIndex;

    public RankingDataComparator() {
      this.currentPlayingIndex = C.INDEX_UNSET;
    }

    @Override
    public int compare(Integer o1, Integer o2) {
      return Integer.compare(abs(o1 - currentPlayingIndex), abs(o2 - currentPlayingIndex));
    }
  }

  private final class SourcePreloadControl implements PreloadMediaSource.PreloadControl {
    @Override
    public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
      // The PreloadMediaSource may have more data preloaded than the target preload status if it
      // has been preloaded before, thus we set `clearExceededDataFromTargetPreloadStatus` to
      // `true` to clear the exceeded data.
      return continueOrCompletePreloading(
          mediaSource,
          /* continueLoadingPredicate= */ status ->
              status.getStage() > Status.STAGE_SOURCE_PREPARED,
          /* clearExceededDataFromTargetPreloadStatus= */ true);
    }

    @Override
    public boolean onTracksSelected(PreloadMediaSource mediaSource) {
      // Set `clearExceededDataFromTargetPreloadStatus` to `false` as clearing the exceeded data
      // from the status STAGE_TRACKS_SELECTED is not supported.
      return continueOrCompletePreloading(
          mediaSource,
          /* continueLoadingPredicate= */ status ->
              status.getStage() > Status.STAGE_TRACKS_SELECTED,
          /* clearExceededDataFromTargetPreloadStatus= */ false);
    }

    @Override
    public boolean onContinueLoadingRequested(
        PreloadMediaSource mediaSource, long bufferedDurationUs) {
      // Set `clearExceededDataFromTargetPreloadStatus` to `false` as clearing the exceeded data
      // from the status STAGE_LOADED_FOR_DURATION_MS is not supported.
      return continueOrCompletePreloading(
          mediaSource,
          /* continueLoadingPredicate= */ status ->
              status.getStage() == Status.STAGE_LOADED_FOR_DURATION_MS
                  && status.getValue() > Util.usToMs(bufferedDurationUs),
          /* clearExceededDataFromTargetPreloadStatus= */ false);
    }

    @Override
    public void onUsedByPlayer(PreloadMediaSource mediaSource) {
      DefaultPreloadManager.this.onPreloadSkipped(mediaSource);
    }

    @Override
    public void onLoadedToTheEndOfSource(PreloadMediaSource mediaSource) {
      DefaultPreloadManager.this.onPreloadCompleted(mediaSource);
    }

    @Override
    public void onPreloadError(PreloadException error, PreloadMediaSource mediaSource) {
      DefaultPreloadManager.this.onPreloadError(error, mediaSource);
    }

    private boolean continueOrCompletePreloading(
        PreloadMediaSource mediaSource,
        Predicate<Status> continueLoadingPredicate,
        boolean clearExceededDataFromTargetPreloadStatus) {
      @Nullable
      TargetPreloadStatusControl.PreloadStatus targetPreloadStatus =
          getTargetPreloadStatus(mediaSource);
      if (targetPreloadStatus != null) {
        Status status = (Status) targetPreloadStatus;
        if (continueLoadingPredicate.apply(checkNotNull(status))) {
          return true;
        }
        if (clearExceededDataFromTargetPreloadStatus) {
          clearSourceInternal(mediaSource);
        }
        DefaultPreloadManager.this.onPreloadCompleted(mediaSource);
      } else {
        DefaultPreloadManager.this.onPreloadSkipped(mediaSource);
      }
      return false;
    }
  }
}
