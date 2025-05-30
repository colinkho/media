/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.exoplayer.dash.offline;

import static androidx.media3.common.util.Util.castNonNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.RunnableFutureTask;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.dash.BaseUrlExclusionList;
import androidx.media3.exoplayer.dash.DashSegmentIndex;
import androidx.media3.exoplayer.dash.DashUtil;
import androidx.media3.exoplayer.dash.DashWrappingSegmentIndex;
import androidx.media3.exoplayer.dash.manifest.AdaptationSet;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.dash.manifest.RangedUri;
import androidx.media3.exoplayer.dash.manifest.Representation;
import androidx.media3.exoplayer.offline.DownloadException;
import androidx.media3.exoplayer.offline.SegmentDownloader;
import androidx.media3.exoplayer.upstream.ParsingLoadable.Parser;
import androidx.media3.extractor.ChunkIndex;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A downloader for DASH streams.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * SimpleCache cache = new SimpleCache(downloadFolder, new NoOpCacheEvictor(), databaseProvider);
 * CacheDataSource.Factory cacheDataSourceFactory =
 *     new CacheDataSource.Factory()
 *         .setCache(cache)
 *         .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory());
 * // Create a downloader for the first representation of the first adaptation set of the first
 * // period.
 * DashDownloader dashDownloader =
 *     new DashDownloader.Factory(cacheDataSourceFactory)
 *             .create(new MediaItem.Builder()
 *               .setUri(manifestUrl)
 *               .setStreamKeys(ImmutableList.of(new StreamKey(0, 0, 0)))
 *               .build());
 * // Perform the download.
 * dashDownloader.download(progressListener);
 * // Use the downloaded data for playback.
 * DashMediaSource mediaSource =
 *     new DashMediaSource.Factory(cacheDataSourceFactory).createMediaSource(mediaItem);
 * }</pre>
 */
@UnstableApi
public final class DashDownloader extends SegmentDownloader<DashManifest> {

  /** A factory for {@linkplain DashDownloader DASH downloaders}. */
  public static final class Factory extends BaseFactory<DashManifest> {

    /**
     * Creates a factory for {@link DashDownloader}.
     *
     * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
     *     download will be written.
     */
    public Factory(CacheDataSource.Factory cacheDataSourceFactory) {
      super(cacheDataSourceFactory, new DashManifestParser());
    }

    /**
     * Sets a parser for DASH manifests.
     *
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setManifestParser(DashManifestParser manifestParser) {
      this.manifestParser = manifestParser;
      return this;
    }

    /**
     * Sets the {@link Executor} used to make requests for the media being downloaded. Providing an
     * {@link Executor} that uses multiple threads will speed up the download by allowing parts of
     * it to be executed in parallel.
     *
     * @return This factory, for convenience.
     */
    @Override
    @CanIgnoreReturnValue
    public Factory setExecutor(Executor executor) {
      super.setExecutor(executor);
      return this;
    }

    /**
     * Sets the maximum difference of the start time of two segments, up to which the segments (of
     * the same URI) should be merged into a single download segment, in milliseconds.
     *
     * @return This factory, for convenience.
     */
    @Override
    @CanIgnoreReturnValue
    public Factory setMaxMergedSegmentStartTimeDiffMs(long maxMergedSegmentStartTimeDiffMs) {
      super.setMaxMergedSegmentStartTimeDiffMs(maxMergedSegmentStartTimeDiffMs);
      return this;
    }

    /**
     * Sets the start position in microseconds that the download should start from.
     *
     * @return This factory, for convenience.
     */
    @Override
    @CanIgnoreReturnValue
    public Factory setStartPositionUs(long startPositionUs) {
      super.setStartPositionUs(startPositionUs);
      return this;
    }

    /**
     * Sets the duration in microseconds from the {@code startPositionUs} to be downloaded, or
     * {@link C#TIME_UNSET} if the media should be downloaded to the end.
     *
     * @return This factory, for convenience.
     */
    @Override
    @CanIgnoreReturnValue
    public Factory setDurationUs(long durationUs) {
      super.setDurationUs(durationUs);
      return this;
    }

    /** Creates {@linkplain DashDownloader DASH downloaders}. */
    @Override
    public DashDownloader create(MediaItem mediaItem) {
      return new DashDownloader(
          mediaItem,
          manifestParser,
          cacheDataSourceFactory,
          executor,
          maxMergedSegmentStartTimeDiffMs,
          startPositionUs,
          durationUs);
    }
  }

  private final BaseUrlExclusionList baseUrlExclusionList;

  /**
   * @deprecated Use {@link DashDownloader.Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public DashDownloader(MediaItem mediaItem, CacheDataSource.Factory cacheDataSourceFactory) {
    this(mediaItem, cacheDataSourceFactory, /* executor= */ Runnable::run);
  }

  /**
   * @deprecated Use {@link DashDownloader.Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public DashDownloader(
      MediaItem mediaItem, CacheDataSource.Factory cacheDataSourceFactory, Executor executor) {
    this(
        mediaItem,
        new DashManifestParser(),
        cacheDataSourceFactory,
        executor,
        DEFAULT_MAX_MERGED_SEGMENT_START_TIME_DIFF_MS,
        /* startPositionUs= */ 0,
        /* durationUs= */ C.TIME_UNSET);
  }

  /**
   * Creates a new instance.
   *
   * @param mediaItem The {@link MediaItem} to be downloaded.
   * @param manifestParser A parser for DASH manifests.
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
   *     download will be written.
   * @param executor An {@link Executor} used to make requests for the media being downloaded.
   *     Providing an {@link Executor} that uses multiple threads will speed up the download by
   *     allowing parts of it to be executed in parallel.
   * @param maxMergedSegmentStartTimeDiffMs The maximum difference of the start time of two
   *     segments, up to which the segments (of the same URI) should be merged into a single
   *     download segment, in milliseconds.
   * @param startPositionUs The start position in microseconds that the download should start from.
   * @param durationUs The duration in microseconds from the {@code startPositionUs} to be
   *     downloaded, or {@link C#TIME_UNSET} if the media should be downloaded to the end.
   */
  private DashDownloader(
      MediaItem mediaItem,
      Parser<DashManifest> manifestParser,
      CacheDataSource.Factory cacheDataSourceFactory,
      Executor executor,
      long maxMergedSegmentStartTimeDiffMs,
      long startPositionUs,
      long durationUs) {
    super(
        mediaItem,
        manifestParser,
        cacheDataSourceFactory,
        executor,
        maxMergedSegmentStartTimeDiffMs,
        startPositionUs,
        durationUs);
    baseUrlExclusionList = new BaseUrlExclusionList();
  }

  @Override
  protected List<Segment> getSegments(
      DataSource dataSource, DashManifest manifest, boolean removing)
      throws IOException, InterruptedException {
    ArrayList<Segment> segments = new ArrayList<>();
    for (int i = 0; i < manifest.getPeriodCount(); i++) {
      Period period = manifest.getPeriod(i);
      long periodStartUs = Util.msToUs(period.startMs);
      long periodDurationUs = manifest.getPeriodDurationUs(i);
      if (periodDurationUs != C.TIME_UNSET && periodStartUs + periodDurationUs <= startPositionUs) {
        continue;
      }
      if (durationUs != C.TIME_UNSET && periodStartUs >= startPositionUs + durationUs) {
        break;
      }
      List<AdaptationSet> adaptationSets = period.adaptationSets;
      for (int j = 0; j < adaptationSets.size(); j++) {
        addSegmentsForAdaptationSet(
            dataSource, adaptationSets.get(j), periodStartUs, periodDurationUs, removing, segments);
      }
    }
    return segments;
  }

  private void addSegmentsForAdaptationSet(
      DataSource dataSource,
      AdaptationSet adaptationSet,
      long periodStartUs,
      long periodDurationUs,
      boolean removing,
      ArrayList<Segment> out)
      throws IOException, InterruptedException {
    for (int i = 0; i < adaptationSet.representations.size(); i++) {
      Representation representation = adaptationSet.representations.get(i);
      DashSegmentIndex index;
      try {
        index = getSegmentIndex(dataSource, adaptationSet.type, representation, removing);
        if (index == null) {
          // Loading succeeded but there was no index.
          throw new DownloadException("Missing segment index");
        }
      } catch (IOException e) {
        if (!removing) {
          throw e;
        }
        // Generating an incomplete segment list is allowed. Advance to the next representation.
        continue;
      }

      long segmentCount = index.getSegmentCount(periodDurationUs);
      if (segmentCount == DashSegmentIndex.INDEX_UNBOUNDED) {
        throw new DownloadException("Unbounded segment index");
      }

      String baseUrl = castNonNull(baseUrlExclusionList.selectBaseUrl(representation.baseUrls)).url;
      @Nullable RangedUri initializationUri = representation.getInitializationUri();
      if (initializationUri != null) {
        out.add(createSegment(representation, baseUrl, periodStartUs, initializationUri));
      }
      @Nullable RangedUri indexUri = representation.getIndexUri();
      if (indexUri != null) {
        out.add(createSegment(representation, baseUrl, periodStartUs, indexUri));
      }
      long startPositionInPeriodUs = startPositionUs - periodStartUs;
      long endPositionInPeriodUs =
          durationUs != C.TIME_UNSET ? startPositionInPeriodUs + durationUs : C.TIME_UNSET;
      long firstSegmentNum =
          removing || startPositionInPeriodUs <= 0
              ? index.getFirstSegmentNum()
              : index.getSegmentNum(startPositionInPeriodUs, periodDurationUs);
      long lastSegmentNum =
          endPositionInPeriodUs == C.TIME_UNSET
                  || removing
                  || endPositionInPeriodUs >= periodStartUs + periodDurationUs
              ? index.getFirstSegmentNum() + segmentCount - 1
              : index.getSegmentNum(endPositionInPeriodUs, periodDurationUs);
      for (long j = firstSegmentNum; j <= lastSegmentNum; j++) {
        out.add(
            createSegment(
                representation,
                baseUrl,
                periodStartUs + index.getTimeUs(j),
                index.getSegmentUrl(j)));
      }
    }
  }

  private Segment createSegment(
      Representation representation, String baseUrl, long startTimeUs, RangedUri rangedUri) {
    DataSpec dataSpec =
        DashUtil.buildDataSpec(
            representation,
            baseUrl,
            rangedUri,
            /* flags= */ 0,
            /* httpRequestHeaders= */ ImmutableMap.of());
    return new Segment(startTimeUs, dataSpec);
  }

  @Nullable
  private DashSegmentIndex getSegmentIndex(
      DataSource dataSource, int trackType, Representation representation, boolean removing)
      throws IOException, InterruptedException {
    DashSegmentIndex index = representation.getIndex();
    if (index != null) {
      return index;
    }
    RunnableFutureTask<@NullableType ChunkIndex, IOException> runnable =
        new RunnableFutureTask<@NullableType ChunkIndex, IOException>() {
          @Override
          protected @NullableType ChunkIndex doWork() throws IOException {
            return DashUtil.loadChunkIndex(dataSource, trackType, representation);
          }
        };
    @Nullable ChunkIndex seekMap = execute(runnable, removing);
    return seekMap == null
        ? null
        : new DashWrappingSegmentIndex(seekMap, representation.presentationTimeOffsetUs);
  }
}
