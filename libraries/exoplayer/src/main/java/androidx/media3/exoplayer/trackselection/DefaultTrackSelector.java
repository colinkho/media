/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.trackselection;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED;
import static androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.exoplayer.RendererCapabilities.AUDIO_OFFLOAD_GAPLESS_SUPPORTED;
import static androidx.media3.exoplayer.RendererCapabilities.AUDIO_OFFLOAD_NOT_SUPPORTED;
import static androidx.media3.exoplayer.RendererCapabilities.AUDIO_OFFLOAD_SPEED_CHANGE_SUPPORTED;
import static androidx.media3.exoplayer.RendererCapabilities.isFormatSupported;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.util.Collections.max;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Point;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.Spatializer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.accessibility.CaptioningManager;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.C.RoleFlags;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences;
import androidx.media3.common.audio.AudioManagerCompat;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.BundleCollectionUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererCapabilities.AdaptiveSupport;
import androidx.media3.exoplayer.RendererCapabilities.Capabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import com.google.common.base.Predicate;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Ints;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.InlineMe;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A default {@link TrackSelector} suitable for most use cases.
 *
 * <h2>Modifying parameters</h2>
 *
 * Track selection parameters should be modified by obtaining a {@link
 * TrackSelectionParameters.Builder} initialized with the current {@link TrackSelectionParameters}
 * from the player. The desired modifications can be made on the builder, and the resulting {@link
 * TrackSelectionParameters} can then be built and set on the player:
 *
 * <pre>{@code
 * player.setTrackSelectionParameters(
 *     player.getTrackSelectionParameters()
 *         .buildUpon()
 *         .setMaxVideoSizeSd()
 *         .setPreferredAudioLanguage("de")
 *         .build());
 * }</pre>
 *
 * Some specialized parameters are only available in the extended {@link Parameters} class, which
 * can be retrieved and modified in a similar way by calling methods directly on this class:
 *
 * <pre>{@code
 * defaultTrackSelector.setParameters(
 *     defaultTrackSelector.getParameters()
 *         .buildUpon()
 *         .setTunnelingEnabled(true)
 *         .build());
 * }</pre>
 */
@UnstableApi
public class DefaultTrackSelector extends MappingTrackSelector
    implements RendererCapabilities.Listener {

  private static final String TAG = "DefaultTrackSelector";
  private static final String AUDIO_CHANNEL_COUNT_CONSTRAINTS_WARN_MESSAGE =
      "Audio channel count constraints cannot be applied without reference to Context. Build the"
          + " track selector instance with one of the non-deprecated constructors that take a"
          + " Context argument.";

  /**
   * @deprecated Use {@link Parameters.Builder} instead.
   */
  @Deprecated
  public static final class ParametersBuilder extends TrackSelectionParameters.Builder {

    private final Parameters.Builder delegate;

    /** Creates a builder with default initial values. */
    public ParametersBuilder() {
      delegate = new Parameters.Builder();
    }

    /**
     * Creates a builder with default initial values.
     *
     * @param context Any context.
     */
    @SuppressWarnings({"deprecation"}) // Supporting deprecated builder pattern
    public ParametersBuilder(Context context) {
      delegate = new Parameters.Builder(context);
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    protected ParametersBuilder set(TrackSelectionParameters parameters) {
      delegate.set(parameters);
      return this;
    }

    // Video

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public DefaultTrackSelector.ParametersBuilder setMaxVideoSizeSd() {
      delegate.setMaxVideoSizeSd();
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public DefaultTrackSelector.ParametersBuilder clearVideoSizeConstraints() {
      delegate.clearVideoSizeConstraints();
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public DefaultTrackSelector.ParametersBuilder setMaxVideoSize(
        int maxVideoWidth, int maxVideoHeight) {
      delegate.setMaxVideoSize(maxVideoWidth, maxVideoHeight);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public DefaultTrackSelector.ParametersBuilder setMaxVideoFrameRate(int maxVideoFrameRate) {
      delegate.setMaxVideoFrameRate(maxVideoFrameRate);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public DefaultTrackSelector.ParametersBuilder setMaxVideoBitrate(int maxVideoBitrate) {
      delegate.setMaxVideoBitrate(maxVideoBitrate);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public DefaultTrackSelector.ParametersBuilder setMinVideoSize(
        int minVideoWidth, int minVideoHeight) {
      delegate.setMinVideoSize(minVideoWidth, minVideoHeight);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public DefaultTrackSelector.ParametersBuilder setMinVideoFrameRate(int minVideoFrameRate) {
      delegate.setMinVideoFrameRate(minVideoFrameRate);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public DefaultTrackSelector.ParametersBuilder setMinVideoBitrate(int minVideoBitrate) {
      delegate.setMinVideoBitrate(minVideoBitrate);
      return this;
    }

    /**
     * Sets whether to exceed the {@link #setMaxVideoBitrate}, {@link #setMaxVideoSize(int, int)}
     * and {@link #setMaxVideoFrameRate} constraints when no selection can be made otherwise.
     *
     * @param exceedVideoConstraintsIfNecessary Whether to exceed video constraints when no
     *     selection can be made otherwise.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setExceedVideoConstraintsIfNecessary(
        boolean exceedVideoConstraintsIfNecessary) {
      delegate.setExceedVideoConstraintsIfNecessary(exceedVideoConstraintsIfNecessary);
      return this;
    }

    /**
     * Sets whether to allow adaptive video selections containing mixed MIME types.
     *
     * <p>Adaptations between different MIME types may not be completely seamless, in which case
     * {@link #setAllowVideoNonSeamlessAdaptiveness(boolean)} also needs to be {@code true} for
     * mixed MIME type selections to be made.
     *
     * @param allowVideoMixedMimeTypeAdaptiveness Whether to allow adaptive video selections
     *     containing mixed MIME types.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setAllowVideoMixedMimeTypeAdaptiveness(
        boolean allowVideoMixedMimeTypeAdaptiveness) {
      delegate.setAllowVideoMixedMimeTypeAdaptiveness(allowVideoMixedMimeTypeAdaptiveness);
      return this;
    }

    /**
     * Sets whether to allow adaptive video selections where adaptation may not be completely
     * seamless.
     *
     * @param allowVideoNonSeamlessAdaptiveness Whether to allow adaptive video selections where
     *     adaptation may not be completely seamless.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setAllowVideoNonSeamlessAdaptiveness(
        boolean allowVideoNonSeamlessAdaptiveness) {
      delegate.setAllowVideoNonSeamlessAdaptiveness(allowVideoNonSeamlessAdaptiveness);
      return this;
    }

    /**
     * Sets whether to allow adaptive video selections with mixed levels of {@link
     * RendererCapabilities.DecoderSupport} and {@link
     * RendererCapabilities.HardwareAccelerationSupport}.
     *
     * @param allowVideoMixedDecoderSupportAdaptiveness Whether to allow adaptive video selections
     *     with mixed levels of decoder and hardware acceleration support.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setAllowVideoMixedDecoderSupportAdaptiveness(
        boolean allowVideoMixedDecoderSupportAdaptiveness) {
      delegate.setAllowVideoMixedDecoderSupportAdaptiveness(
          allowVideoMixedDecoderSupportAdaptiveness);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setViewportSizeToPhysicalDisplaySize(
        boolean viewportOrientationMayChange) {
      delegate.setViewportSizeToPhysicalDisplaySize(viewportOrientationMayChange);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setViewportSizeToPhysicalDisplaySize(
        Context context, boolean viewportOrientationMayChange) {
      delegate.setViewportSizeToPhysicalDisplaySize(context, viewportOrientationMayChange);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder clearViewportSizeConstraints() {
      delegate.clearViewportSizeConstraints();
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setViewportSize(
        int viewportWidth, int viewportHeight, boolean viewportOrientationMayChange) {
      delegate.setViewportSize(viewportWidth, viewportHeight, viewportOrientationMayChange);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredVideoMimeType(@Nullable String mimeType) {
      delegate.setPreferredVideoMimeType(mimeType);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredVideoMimeTypes(String... mimeTypes) {
      delegate.setPreferredVideoMimeTypes(mimeTypes);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredVideoLanguage(@Nullable String preferredVideoLanguage) {
      super.setPreferredVideoLanguage(preferredVideoLanguage);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredVideoLanguages(String... preferredVideoLanguages) {
      super.setPreferredVideoLanguages(preferredVideoLanguages);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public DefaultTrackSelector.ParametersBuilder setPreferredVideoRoleFlags(
        @RoleFlags int preferredVideoRoleFlags) {
      delegate.setPreferredVideoRoleFlags(preferredVideoRoleFlags);
      return this;
    }

    // Audio

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredAudioLanguage(@Nullable String preferredAudioLanguage) {
      delegate.setPreferredAudioLanguage(preferredAudioLanguage);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredAudioLanguages(String... preferredAudioLanguages) {
      delegate.setPreferredAudioLanguages(preferredAudioLanguages);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredAudioRoleFlags(@C.RoleFlags int preferredAudioRoleFlags) {
      delegate.setPreferredAudioRoleFlags(preferredAudioRoleFlags);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setMaxAudioChannelCount(int maxAudioChannelCount) {
      delegate.setMaxAudioChannelCount(maxAudioChannelCount);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setMaxAudioBitrate(int maxAudioBitrate) {
      delegate.setMaxAudioBitrate(maxAudioBitrate);
      return this;
    }

    /**
     * Sets whether to exceed the {@link #setMaxAudioChannelCount(int)} and {@link
     * #setMaxAudioBitrate(int)} constraints when no selection can be made otherwise.
     *
     * @param exceedAudioConstraintsIfNecessary Whether to exceed audio constraints when no
     *     selection can be made otherwise.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setExceedAudioConstraintsIfNecessary(
        boolean exceedAudioConstraintsIfNecessary) {
      delegate.setExceedAudioConstraintsIfNecessary(exceedAudioConstraintsIfNecessary);
      return this;
    }

    /**
     * Sets whether to allow adaptive audio selections containing mixed MIME types.
     *
     * <p>Adaptations between different MIME types may not be completely seamless, in which case
     * {@link Parameters.Builder#setAllowAudioNonSeamlessAdaptiveness(boolean)} also needs to be
     * {@code true} for mixed MIME type selections to be made.
     *
     * @param allowAudioMixedMimeTypeAdaptiveness Whether to allow adaptive audio selections
     *     containing mixed MIME types.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setAllowAudioMixedMimeTypeAdaptiveness(
        boolean allowAudioMixedMimeTypeAdaptiveness) {
      delegate.setAllowAudioMixedMimeTypeAdaptiveness(allowAudioMixedMimeTypeAdaptiveness);
      return this;
    }

    /**
     * Sets whether to allow adaptive audio selections containing mixed sample rates.
     *
     * <p>Adaptations between different sample rates may not be completely seamless.
     *
     * @param allowAudioMixedSampleRateAdaptiveness Whether to allow adaptive audio selections
     *     containing mixed sample rates.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setAllowAudioMixedSampleRateAdaptiveness(
        boolean allowAudioMixedSampleRateAdaptiveness) {
      delegate.setAllowAudioMixedSampleRateAdaptiveness(allowAudioMixedSampleRateAdaptiveness);
      return this;
    }

    /**
     * Sets whether to allow adaptive audio selections containing mixed channel counts.
     *
     * <p>Adaptations between different channel counts may not be completely seamless.
     *
     * @param allowAudioMixedChannelCountAdaptiveness Whether to allow adaptive audio selections
     *     containing mixed channel counts.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setAllowAudioMixedChannelCountAdaptiveness(
        boolean allowAudioMixedChannelCountAdaptiveness) {
      delegate.setAllowAudioMixedChannelCountAdaptiveness(allowAudioMixedChannelCountAdaptiveness);
      return this;
    }

    /**
     * Sets whether to allow adaptive audio selections with mixed levels of {@link
     * RendererCapabilities.DecoderSupport} and {@link
     * RendererCapabilities.HardwareAccelerationSupport}.
     *
     * @param allowAudioMixedDecoderSupportAdaptiveness Whether to allow adaptive audio selections
     *     with mixed levels of decoder and hardware acceleration support.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setAllowAudioMixedDecoderSupportAdaptiveness(
        boolean allowAudioMixedDecoderSupportAdaptiveness) {
      delegate.setAllowAudioMixedDecoderSupportAdaptiveness(
          allowAudioMixedDecoderSupportAdaptiveness);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredAudioMimeType(@Nullable String mimeType) {
      delegate.setPreferredAudioMimeType(mimeType);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredAudioMimeTypes(String... mimeTypes) {
      delegate.setPreferredAudioMimeTypes(mimeTypes);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setAudioOffloadPreferences(
        AudioOffloadPreferences audioOffloadPreferences) {
      delegate.setAudioOffloadPreferences(audioOffloadPreferences);
      return this;
    }

    // Text

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setSelectTextByDefault(boolean selectTextByDefault) {
      delegate.setSelectTextByDefault(selectTextByDefault);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings() {
      delegate.setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings();
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings(
        Context context) {
      delegate.setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings(context);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredTextLanguage(@Nullable String preferredTextLanguage) {
      delegate.setPreferredTextLanguage(preferredTextLanguage);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredTextLanguages(String... preferredTextLanguages) {
      delegate.setPreferredTextLanguages(preferredTextLanguages);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPreferredTextRoleFlags(@C.RoleFlags int preferredTextRoleFlags) {
      delegate.setPreferredTextRoleFlags(preferredTextRoleFlags);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setIgnoredTextSelectionFlags(
        @C.SelectionFlags int ignoredTextSelectionFlags) {
      delegate.setIgnoredTextSelectionFlags(ignoredTextSelectionFlags);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setSelectUndeterminedTextLanguage(
        boolean selectUndeterminedTextLanguage) {
      delegate.setSelectUndeterminedTextLanguage(selectUndeterminedTextLanguage);
      return this;
    }

    /**
     * @deprecated Use {@link #setIgnoredTextSelectionFlags}.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Deprecated
    public ParametersBuilder setDisabledTextTrackSelectionFlags(
        @C.SelectionFlags int disabledTextTrackSelectionFlags) {
      delegate.setDisabledTextTrackSelectionFlags(disabledTextTrackSelectionFlags);
      return this;
    }

    // Image
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setPrioritizeImageOverVideoEnabled(
        boolean isPrioritizeImageOverVideoEnabled) {
      delegate.setPrioritizeImageOverVideoEnabled(isPrioritizeImageOverVideoEnabled);
      return this;
    }

    // General

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setForceLowestBitrate(boolean forceLowestBitrate) {
      delegate.setForceLowestBitrate(forceLowestBitrate);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setForceHighestSupportedBitrate(boolean forceHighestSupportedBitrate) {
      delegate.setForceHighestSupportedBitrate(forceHighestSupportedBitrate);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder addOverride(TrackSelectionOverride override) {
      delegate.addOverride(override);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder clearOverride(TrackGroup trackGroup) {
      delegate.clearOverride(trackGroup);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setOverrideForType(TrackSelectionOverride override) {
      delegate.setOverrideForType(override);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder clearOverridesOfType(@C.TrackType int trackType) {
      delegate.clearOverridesOfType(trackType);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder clearOverrides() {
      delegate.clearOverrides();
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    public ParametersBuilder setDisabledTrackTypes(Set<@C.TrackType Integer> disabledTrackTypes) {
      delegate.setDisabledTrackTypes(disabledTrackTypes);
      return this;
    }

    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Override
    public ParametersBuilder setTrackTypeDisabled(@C.TrackType int trackType, boolean disabled) {
      delegate.setTrackTypeDisabled(trackType, disabled);
      return this;
    }

    /**
     * Sets whether to exceed renderer capabilities when no selection can be made otherwise.
     *
     * <p>This parameter applies when all of the tracks available for a renderer exceed the
     * renderer's reported capabilities. If the parameter is {@code true} then the lowest quality
     * track will still be selected. Playback may succeed if the renderer has under-reported its
     * true capabilities. If {@code false} then no track will be selected.
     *
     * @param exceedRendererCapabilitiesIfNecessary Whether to exceed renderer capabilities when no
     *     selection can be made otherwise.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setExceedRendererCapabilitiesIfNecessary(
        boolean exceedRendererCapabilitiesIfNecessary) {
      delegate.setExceedRendererCapabilitiesIfNecessary(exceedRendererCapabilitiesIfNecessary);
      return this;
    }

    /**
     * Sets whether to enable tunneling if possible. Tunneling will only be enabled if it's
     * supported by the audio and video renderers for the selected tracks.
     *
     * <p>Tunneling is known to have many device specific issues and limitations. Manual testing is
     * strongly recommended to check that the media plays correctly when this option is enabled.
     * See:
     *
     * <ul>
     *   <li><a href="https://github.com/google/ExoPlayer/issues/9661">google/ExoPlayer#9661</a>
     *   <li><a href="https://github.com/google/ExoPlayer/issues/9133">google/ExoPlayer#9133</a>
     *   <li><a href="https://github.com/google/ExoPlayer/issues/9317">google/ExoPlayer#9317</a>
     *   <li><a href="https://github.com/google/ExoPlayer/issues/9502">google/ExoPlayer#9502</a>
     * </ul>
     *
     * @param tunnelingEnabled Whether to enable tunneling if possible.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setTunnelingEnabled(boolean tunnelingEnabled) {
      delegate.setTunnelingEnabled(tunnelingEnabled);
      return this;
    }

    /**
     * Sets whether multiple adaptive selections with more than one track are allowed.
     *
     * @param allowMultipleAdaptiveSelections Whether multiple adaptive selections are allowed.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setAllowMultipleAdaptiveSelections(
        boolean allowMultipleAdaptiveSelections) {
      delegate.setAllowMultipleAdaptiveSelections(allowMultipleAdaptiveSelections);
      return this;
    }

    // Overrides

    /**
     * Sets whether the renderer at the specified index is disabled. Disabling a renderer prevents
     * the selector from selecting any tracks for it.
     *
     * @param rendererIndex The renderer index.
     * @param disabled Whether the renderer is disabled.
     * @return This builder.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    public ParametersBuilder setRendererDisabled(int rendererIndex, boolean disabled) {
      delegate.setRendererDisabled(rendererIndex, disabled);
      return this;
    }

    /**
     * Overrides the track selection for the renderer at the specified index.
     *
     * <p>When the {@link TrackGroupArray} mapped to the renderer matches the one provided, the
     * override is applied. When the {@link TrackGroupArray} does not match, the override has no
     * effect. The override replaces any previous override for the specified {@link TrackGroupArray}
     * for the specified {@link Renderer}.
     *
     * <p>Passing a {@code null} override will cause the renderer to be disabled when the {@link
     * TrackGroupArray} mapped to it matches the one provided. When the {@link TrackGroupArray} does
     * not match a {@code null} override has no effect. Hence a {@code null} override differs from
     * disabling the renderer using {@link #setRendererDisabled(int, boolean)} because the renderer
     * is disabled conditionally on the {@link TrackGroupArray} mapped to it, where-as {@link
     * #setRendererDisabled(int, boolean)} disables the renderer unconditionally.
     *
     * <p>To remove overrides use {@link #clearSelectionOverride(int, TrackGroupArray)}, {@link
     * #clearSelectionOverrides(int)} or {@link #clearSelectionOverrides()}.
     *
     * @param rendererIndex The renderer index.
     * @param groups The {@link TrackGroupArray} for which the override should be applied.
     * @param override The override.
     * @return This builder.
     * @deprecated Use {@link TrackSelectionParameters.Builder#addOverride(TrackSelectionOverride)}.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Deprecated
    public ParametersBuilder setSelectionOverride(
        int rendererIndex, TrackGroupArray groups, @Nullable SelectionOverride override) {
      delegate.setSelectionOverride(rendererIndex, groups, override);
      return this;
    }

    /**
     * Clears a track selection override for the specified renderer and {@link TrackGroupArray}.
     *
     * @param rendererIndex The renderer index.
     * @param groups The {@link TrackGroupArray} for which the override should be cleared.
     * @return This builder.
     * @deprecated Use {@link TrackSelectionParameters.Builder#clearOverride(TrackGroup)}.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Deprecated
    public ParametersBuilder clearSelectionOverride(int rendererIndex, TrackGroupArray groups) {
      delegate.clearSelectionOverride(rendererIndex, groups);
      return this;
    }

    /**
     * Clears all track selection overrides for the specified renderer.
     *
     * @param rendererIndex The renderer index.
     * @return This builder.
     * @deprecated Use {@link TrackSelectionParameters.Builder#clearOverridesOfType(int)}.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Deprecated
    public ParametersBuilder clearSelectionOverrides(int rendererIndex) {
      delegate.clearSelectionOverrides(rendererIndex);
      return this;
    }

    /**
     * Clears all track selection overrides for all renderers.
     *
     * @return This builder.
     * @deprecated Use {@link TrackSelectionParameters.Builder#clearOverrides()}.
     */
    @SuppressWarnings("deprecation") // Intentionally returning deprecated type
    @CanIgnoreReturnValue
    @Deprecated
    public ParametersBuilder clearSelectionOverrides() {
      delegate.clearSelectionOverrides();
      return this;
    }

    /** Builds a {@link Parameters} instance with the selected values. */
    @Override
    public Parameters build() {
      return delegate.build();
    }
  }

  /**
   * Extends {@link Parameters} by adding fields that are specific to {@link DefaultTrackSelector}.
   */
  public static final class Parameters extends TrackSelectionParameters {

    /**
     * A builder for {@link Parameters}. See the {@link Parameters} documentation for explanations
     * of the parameters that can be configured using this builder.
     */
    public static final class Builder extends TrackSelectionParameters.Builder {

      // Video
      private boolean exceedVideoConstraintsIfNecessary;
      private boolean allowVideoMixedMimeTypeAdaptiveness;
      private boolean allowVideoNonSeamlessAdaptiveness;
      private boolean allowVideoMixedDecoderSupportAdaptiveness;
      // Audio
      private boolean exceedAudioConstraintsIfNecessary;
      private boolean allowAudioMixedMimeTypeAdaptiveness;
      private boolean allowAudioMixedSampleRateAdaptiveness;
      private boolean allowAudioMixedChannelCountAdaptiveness;
      private boolean allowAudioMixedDecoderSupportAdaptiveness;
      private boolean allowAudioNonSeamlessAdaptiveness;
      private boolean constrainAudioChannelCountToDeviceCapabilities;
      // General
      private boolean exceedRendererCapabilitiesIfNecessary;
      private boolean tunnelingEnabled;
      private boolean allowMultipleAdaptiveSelections;
      private boolean allowInvalidateSelectionsOnRendererCapabilitiesChange;
      // Overrides
      private final SparseArray<Map<TrackGroupArray, @NullableType SelectionOverride>>
          selectionOverrides;
      private final SparseBooleanArray rendererDisabledFlags;

      /** Creates a builder with default initial values. */
      public Builder() {
        super();
        selectionOverrides = new SparseArray<>();
        rendererDisabledFlags = new SparseBooleanArray();
        init();
      }

      /**
       * @deprecated Use {@link #Builder()} instead.
       */
      @Deprecated
      @InlineMe(replacement = "this()")
      public Builder(Context context) {
        this();
      }

      /**
       * @param initialValues The {@link Parameters} from which the initial values of the builder
       *     are obtained.
       */
      private Builder(Parameters initialValues) {
        super(initialValues);
        // Video
        exceedVideoConstraintsIfNecessary = initialValues.exceedVideoConstraintsIfNecessary;
        allowVideoMixedMimeTypeAdaptiveness = initialValues.allowVideoMixedMimeTypeAdaptiveness;
        allowVideoNonSeamlessAdaptiveness = initialValues.allowVideoNonSeamlessAdaptiveness;
        allowVideoMixedDecoderSupportAdaptiveness =
            initialValues.allowVideoMixedDecoderSupportAdaptiveness;
        // Audio
        exceedAudioConstraintsIfNecessary = initialValues.exceedAudioConstraintsIfNecessary;
        allowAudioMixedMimeTypeAdaptiveness = initialValues.allowAudioMixedMimeTypeAdaptiveness;
        allowAudioMixedSampleRateAdaptiveness = initialValues.allowAudioMixedSampleRateAdaptiveness;
        allowAudioMixedChannelCountAdaptiveness =
            initialValues.allowAudioMixedChannelCountAdaptiveness;
        allowAudioMixedDecoderSupportAdaptiveness =
            initialValues.allowAudioMixedDecoderSupportAdaptiveness;
        allowAudioNonSeamlessAdaptiveness = initialValues.allowAudioNonSeamlessAdaptiveness;
        constrainAudioChannelCountToDeviceCapabilities =
            initialValues.constrainAudioChannelCountToDeviceCapabilities;
        // General
        exceedRendererCapabilitiesIfNecessary = initialValues.exceedRendererCapabilitiesIfNecessary;
        tunnelingEnabled = initialValues.tunnelingEnabled;
        allowMultipleAdaptiveSelections = initialValues.allowMultipleAdaptiveSelections;
        allowInvalidateSelectionsOnRendererCapabilitiesChange =
            initialValues.allowInvalidateSelectionsOnRendererCapabilitiesChange;
        // Overrides
        selectionOverrides = cloneSelectionOverrides(initialValues.selectionOverrides);
        rendererDisabledFlags = initialValues.rendererDisabledFlags.clone();
      }

      @SuppressWarnings("method.invocation") // Only setter are invoked.
      private Builder(Bundle bundle) {
        super(bundle);
        init();
        Parameters defaultValue = Parameters.DEFAULT;
        // Video
        setExceedVideoConstraintsIfNecessary(
            bundle.getBoolean(
                Parameters.FIELD_EXCEED_VIDEO_CONSTRAINTS_IF_NECESSARY,
                defaultValue.exceedVideoConstraintsIfNecessary));
        setAllowVideoMixedMimeTypeAdaptiveness(
            bundle.getBoolean(
                Parameters.FIELD_ALLOW_VIDEO_MIXED_MIME_TYPE_ADAPTIVENESS,
                defaultValue.allowVideoMixedMimeTypeAdaptiveness));
        setAllowVideoNonSeamlessAdaptiveness(
            bundle.getBoolean(
                Parameters.FIELD_ALLOW_VIDEO_NON_SEAMLESS_ADAPTIVENESS,
                defaultValue.allowVideoNonSeamlessAdaptiveness));
        setAllowVideoMixedDecoderSupportAdaptiveness(
            bundle.getBoolean(
                Parameters.FIELD_ALLOW_VIDEO_MIXED_DECODER_SUPPORT_ADAPTIVENESS,
                defaultValue.allowVideoMixedDecoderSupportAdaptiveness));
        // Audio
        setExceedAudioConstraintsIfNecessary(
            bundle.getBoolean(
                Parameters.FIELD_EXCEED_AUDIO_CONSTRAINTS_IF_NECESSARY,
                defaultValue.exceedAudioConstraintsIfNecessary));
        setAllowAudioMixedMimeTypeAdaptiveness(
            bundle.getBoolean(
                Parameters.FIELD_ALLOW_AUDIO_MIXED_MIME_TYPE_ADAPTIVENESS,
                defaultValue.allowAudioMixedMimeTypeAdaptiveness));
        setAllowAudioMixedSampleRateAdaptiveness(
            bundle.getBoolean(
                Parameters.FIELD_ALLOW_AUDIO_MIXED_SAMPLE_RATE_ADAPTIVENESS,
                defaultValue.allowAudioMixedSampleRateAdaptiveness));
        setAllowAudioMixedChannelCountAdaptiveness(
            bundle.getBoolean(
                Parameters.FIELD_ALLOW_AUDIO_MIXED_CHANNEL_COUNT_ADAPTIVENESS,
                defaultValue.allowAudioMixedChannelCountAdaptiveness));
        setAllowAudioMixedDecoderSupportAdaptiveness(
            bundle.getBoolean(
                Parameters.FIELD_ALLOW_AUDIO_MIXED_DECODER_SUPPORT_ADAPTIVENESS,
                defaultValue.allowAudioMixedDecoderSupportAdaptiveness));
        setAllowAudioNonSeamlessAdaptiveness(
            bundle.getBoolean(
                Parameters.FIELD_ALLOW_AUDIO_NON_SEAMLESS_ADAPTIVENESS,
                defaultValue.allowAudioNonSeamlessAdaptiveness));
        setConstrainAudioChannelCountToDeviceCapabilities(
            bundle.getBoolean(
                Parameters.FIELD_CONSTRAIN_AUDIO_CHANNEL_COUNT_TO_DEVICE_CAPABILITIES,
                defaultValue.constrainAudioChannelCountToDeviceCapabilities));
        // General
        setExceedRendererCapabilitiesIfNecessary(
            bundle.getBoolean(
                Parameters.FIELD_EXCEED_RENDERER_CAPABILITIES_IF_NECESSARY,
                defaultValue.exceedRendererCapabilitiesIfNecessary));
        setTunnelingEnabled(
            bundle.getBoolean(Parameters.FIELD_TUNNELING_ENABLED, defaultValue.tunnelingEnabled));
        setAllowMultipleAdaptiveSelections(
            bundle.getBoolean(
                Parameters.FIELD_ALLOW_MULTIPLE_ADAPTIVE_SELECTIONS,
                defaultValue.allowMultipleAdaptiveSelections));
        setAllowInvalidateSelectionsOnRendererCapabilitiesChange(
            bundle.getBoolean(
                Parameters.FIELD_ALLOW_INVALIDATE_SELECTIONS_ON_RENDERER_CAPABILITIES_CHANGE,
                defaultValue.allowInvalidateSelectionsOnRendererCapabilitiesChange));
        // Overrides
        selectionOverrides = new SparseArray<>();
        setSelectionOverridesFromBundle(bundle);
        rendererDisabledFlags =
            makeSparseBooleanArrayFromTrueKeys(
                bundle.getIntArray(Parameters.FIELD_RENDERER_DISABLED_INDICES));
      }

      @CanIgnoreReturnValue
      @Override
      protected Builder set(TrackSelectionParameters parameters) {
        super.set(parameters);
        return this;
      }

      // Video

      @CanIgnoreReturnValue
      @Override
      public Builder setMaxVideoSizeSd() {
        super.setMaxVideoSizeSd();
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder clearVideoSizeConstraints() {
        super.clearVideoSizeConstraints();
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setMaxVideoSize(int maxVideoWidth, int maxVideoHeight) {
        super.setMaxVideoSize(maxVideoWidth, maxVideoHeight);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setMaxVideoFrameRate(int maxVideoFrameRate) {
        super.setMaxVideoFrameRate(maxVideoFrameRate);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setMaxVideoBitrate(int maxVideoBitrate) {
        super.setMaxVideoBitrate(maxVideoBitrate);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setMinVideoSize(int minVideoWidth, int minVideoHeight) {
        super.setMinVideoSize(minVideoWidth, minVideoHeight);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setMinVideoFrameRate(int minVideoFrameRate) {
        super.setMinVideoFrameRate(minVideoFrameRate);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setMinVideoBitrate(int minVideoBitrate) {
        super.setMinVideoBitrate(minVideoBitrate);
        return this;
      }

      /**
       * Sets whether to exceed the {@link #setMaxVideoBitrate}, {@link #setMaxVideoSize(int, int)}
       * and {@link #setMaxVideoFrameRate} constraints when no selection can be made otherwise.
       *
       * @param exceedVideoConstraintsIfNecessary Whether to exceed video constraints when no
       *     selection can be made otherwise.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setExceedVideoConstraintsIfNecessary(
          boolean exceedVideoConstraintsIfNecessary) {
        this.exceedVideoConstraintsIfNecessary = exceedVideoConstraintsIfNecessary;
        return this;
      }

      /**
       * Sets whether to allow adaptive video selections containing mixed MIME types.
       *
       * <p>Adaptations between different MIME types may not be completely seamless, in which case
       * {@link #setAllowVideoNonSeamlessAdaptiveness(boolean)} also needs to be {@code true} for
       * mixed MIME type selections to be made.
       *
       * @param allowVideoMixedMimeTypeAdaptiveness Whether to allow adaptive video selections
       *     containing mixed MIME types.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setAllowVideoMixedMimeTypeAdaptiveness(
          boolean allowVideoMixedMimeTypeAdaptiveness) {
        this.allowVideoMixedMimeTypeAdaptiveness = allowVideoMixedMimeTypeAdaptiveness;
        return this;
      }

      /**
       * Sets whether to allow adaptive video selections where adaptation may not be completely
       * seamless.
       *
       * @param allowVideoNonSeamlessAdaptiveness Whether to allow adaptive video selections where
       *     adaptation may not be completely seamless.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setAllowVideoNonSeamlessAdaptiveness(
          boolean allowVideoNonSeamlessAdaptiveness) {
        this.allowVideoNonSeamlessAdaptiveness = allowVideoNonSeamlessAdaptiveness;
        return this;
      }

      /**
       * Sets whether to allow adaptive video selections with mixed levels of {@link
       * RendererCapabilities.DecoderSupport} and {@link
       * RendererCapabilities.HardwareAccelerationSupport}.
       *
       * @param allowVideoMixedDecoderSupportAdaptiveness Whether to allow adaptive video selections
       *     with mixed levels of decoder and hardware acceleration support.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setAllowVideoMixedDecoderSupportAdaptiveness(
          boolean allowVideoMixedDecoderSupportAdaptiveness) {
        this.allowVideoMixedDecoderSupportAdaptiveness = allowVideoMixedDecoderSupportAdaptiveness;
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setViewportSizeToPhysicalDisplaySize(boolean viewportOrientationMayChange) {
        super.setViewportSizeToPhysicalDisplaySize(viewportOrientationMayChange);
        return this;
      }

      @CanIgnoreReturnValue
      @Deprecated
      @Override
      public Builder setViewportSizeToPhysicalDisplaySize(
          Context context, boolean viewportOrientationMayChange) {
        super.setViewportSizeToPhysicalDisplaySize(context, viewportOrientationMayChange);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder clearViewportSizeConstraints() {
        super.clearViewportSizeConstraints();
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setViewportSize(
          int viewportWidth, int viewportHeight, boolean viewportOrientationMayChange) {
        super.setViewportSize(viewportWidth, viewportHeight, viewportOrientationMayChange);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredVideoMimeType(@Nullable String mimeType) {
        super.setPreferredVideoMimeType(mimeType);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredVideoMimeTypes(String... mimeTypes) {
        super.setPreferredVideoMimeTypes(mimeTypes);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredVideoLanguage(@Nullable String preferredVideoLanguage) {
        super.setPreferredVideoLanguage(preferredVideoLanguage);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredVideoLanguages(String... preferredVideoLanguages) {
        super.setPreferredVideoLanguages(preferredVideoLanguages);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredVideoRoleFlags(@RoleFlags int preferredVideoRoleFlags) {
        super.setPreferredVideoRoleFlags(preferredVideoRoleFlags);
        return this;
      }

      // Audio

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredAudioLanguage(@Nullable String preferredAudioLanguage) {
        super.setPreferredAudioLanguage(preferredAudioLanguage);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredAudioLanguages(String... preferredAudioLanguages) {
        super.setPreferredAudioLanguages(preferredAudioLanguages);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredAudioRoleFlags(@C.RoleFlags int preferredAudioRoleFlags) {
        super.setPreferredAudioRoleFlags(preferredAudioRoleFlags);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setMaxAudioChannelCount(int maxAudioChannelCount) {
        super.setMaxAudioChannelCount(maxAudioChannelCount);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setMaxAudioBitrate(int maxAudioBitrate) {
        super.setMaxAudioBitrate(maxAudioBitrate);
        return this;
      }

      /**
       * Sets whether to exceed the {@link #setMaxAudioChannelCount(int)} and {@link
       * #setMaxAudioBitrate(int)} constraints when no selection can be made otherwise.
       *
       * @param exceedAudioConstraintsIfNecessary Whether to exceed audio constraints when no
       *     selection can be made otherwise.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setExceedAudioConstraintsIfNecessary(
          boolean exceedAudioConstraintsIfNecessary) {
        this.exceedAudioConstraintsIfNecessary = exceedAudioConstraintsIfNecessary;
        return this;
      }

      /**
       * Sets whether to allow adaptive audio selections containing mixed MIME types.
       *
       * <p>Adaptations between different MIME types may not be completely seamless, in which case
       * {@link #setAllowAudioNonSeamlessAdaptiveness(boolean)} also needs to be {@code true} for
       * mixed MIME type selections to be made.
       *
       * @param allowAudioMixedMimeTypeAdaptiveness Whether to allow adaptive audio selections
       *     containing mixed MIME types.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setAllowAudioMixedMimeTypeAdaptiveness(
          boolean allowAudioMixedMimeTypeAdaptiveness) {
        this.allowAudioMixedMimeTypeAdaptiveness = allowAudioMixedMimeTypeAdaptiveness;
        return this;
      }

      /**
       * Sets whether to allow adaptive audio selections containing mixed sample rates.
       *
       * <p>Adaptations between different sample rates may not be completely seamless.
       *
       * @param allowAudioMixedSampleRateAdaptiveness Whether to allow adaptive audio selections
       *     containing mixed sample rates.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setAllowAudioMixedSampleRateAdaptiveness(
          boolean allowAudioMixedSampleRateAdaptiveness) {
        this.allowAudioMixedSampleRateAdaptiveness = allowAudioMixedSampleRateAdaptiveness;
        return this;
      }

      /**
       * Sets whether to allow adaptive audio selections containing mixed channel counts.
       *
       * <p>Adaptations between different channel counts may not be completely seamless.
       *
       * @param allowAudioMixedChannelCountAdaptiveness Whether to allow adaptive audio selections
       *     containing mixed channel counts.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setAllowAudioMixedChannelCountAdaptiveness(
          boolean allowAudioMixedChannelCountAdaptiveness) {
        this.allowAudioMixedChannelCountAdaptiveness = allowAudioMixedChannelCountAdaptiveness;
        return this;
      }

      /**
       * Sets whether to allow adaptive audio selections with mixed levels of {@link
       * RendererCapabilities.DecoderSupport} and {@link
       * RendererCapabilities.HardwareAccelerationSupport}.
       *
       * @param allowAudioMixedDecoderSupportAdaptiveness Whether to allow adaptive audio selections
       *     with mixed levels of decoder and hardware acceleration support.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setAllowAudioMixedDecoderSupportAdaptiveness(
          boolean allowAudioMixedDecoderSupportAdaptiveness) {
        this.allowAudioMixedDecoderSupportAdaptiveness = allowAudioMixedDecoderSupportAdaptiveness;
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredAudioMimeType(@Nullable String mimeType) {
        super.setPreferredAudioMimeType(mimeType);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredAudioMimeTypes(String... mimeTypes) {
        super.setPreferredAudioMimeTypes(mimeTypes);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setAudioOffloadPreferences(AudioOffloadPreferences audioOffloadPreferences) {
        super.setAudioOffloadPreferences(audioOffloadPreferences);
        return this;
      }

      /**
       * Sets whether to allow adaptive audio selections where adaptation may not be completely
       * seamless.
       *
       * @param allowAudioNonSeamlessAdaptiveness Whether to allow adaptive audio selections where
       *     adaptation may not be completely seamless.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setAllowAudioNonSeamlessAdaptiveness(
          boolean allowAudioNonSeamlessAdaptiveness) {
        this.allowAudioNonSeamlessAdaptiveness = allowAudioNonSeamlessAdaptiveness;
        return this;
      }

      /**
       * Whether to only select audio tracks with channel counts that don't exceed the device's
       * output capabilities. The default value is {@code true}.
       *
       * <p>When enabled, the track selector will prefer stereo/mono audio tracks over multichannel
       * if the audio cannot be spatialized or the device is outputting stereo audio. For example,
       * on a mobile device that outputs non-spatialized audio to its speakers. Dolby surround sound
       * formats are excluded from these constraints because some Dolby decoders are known to
       * spatialize multichannel audio on Android OS versions that don't support the {@link
       * Spatializer} API.
       *
       * <p>For devices with Android 12L+ that support {@linkplain Spatializer audio
       * spatialization}, when this is enabled the track selector will trigger a new track selection
       * everytime a change in {@linkplain Spatializer.OnSpatializerStateChangedListener
       * spatialization properties} is detected.
       *
       * <p>The constraints do not apply on devices with <a
       * href="https://developer.android.com/guide/topics/resources/providing-resources#UiModeQualifier">{@code
       * television} UI mode</a>.
       *
       * <p>The constraints do not apply when the track selector is created without a reference to a
       * {@link Context} via the deprecated {@link
       * DefaultTrackSelector#DefaultTrackSelector(TrackSelectionParameters,
       * ExoTrackSelection.Factory)} constructor.
       */
      @CanIgnoreReturnValue
      public Builder setConstrainAudioChannelCountToDeviceCapabilities(boolean enabled) {
        constrainAudioChannelCountToDeviceCapabilities = enabled;
        return this;
      }

      // Text

      @CanIgnoreReturnValue
      @Override
      public Builder setSelectTextByDefault(boolean selectTextByDefault) {
        super.setSelectTextByDefault(selectTextByDefault);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings() {
        super.setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings();
        return this;
      }

      @CanIgnoreReturnValue
      @Deprecated
      @SuppressWarnings("deprecation") // Calling deprecated super method.
      @Override
      public Builder setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings(
          Context context) {
        super.setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings(context);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredTextLanguage(@Nullable String preferredTextLanguage) {
        super.setPreferredTextLanguage(preferredTextLanguage);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredTextLanguages(String... preferredTextLanguages) {
        super.setPreferredTextLanguages(preferredTextLanguages);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setPreferredTextRoleFlags(@C.RoleFlags int preferredTextRoleFlags) {
        super.setPreferredTextRoleFlags(preferredTextRoleFlags);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setIgnoredTextSelectionFlags(@C.SelectionFlags int ignoredTextSelectionFlags) {
        super.setIgnoredTextSelectionFlags(ignoredTextSelectionFlags);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setSelectUndeterminedTextLanguage(boolean selectUndeterminedTextLanguage) {
        super.setSelectUndeterminedTextLanguage(selectUndeterminedTextLanguage);
        return this;
      }

      /**
       * @deprecated Use {@link #setIgnoredTextSelectionFlags}.
       */
      @CanIgnoreReturnValue
      @Deprecated
      public Builder setDisabledTextTrackSelectionFlags(
          @C.SelectionFlags int disabledTextTrackSelectionFlags) {
        return setIgnoredTextSelectionFlags(disabledTextTrackSelectionFlags);
      }

      // Image

      @CanIgnoreReturnValue
      @Override
      public Builder setPrioritizeImageOverVideoEnabled(boolean isPrioritizeImageOverVideoEnabled) {
        super.setPrioritizeImageOverVideoEnabled(isPrioritizeImageOverVideoEnabled);
        return this;
      }

      // General

      @CanIgnoreReturnValue
      @Override
      public Builder setForceLowestBitrate(boolean forceLowestBitrate) {
        super.setForceLowestBitrate(forceLowestBitrate);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setForceHighestSupportedBitrate(boolean forceHighestSupportedBitrate) {
        super.setForceHighestSupportedBitrate(forceHighestSupportedBitrate);
        return this;
      }

      /**
       * Sets whether to allow to invalidate selections on renderer capabilities change.
       *
       * @param allowInvalidateSelectionsOnRendererCapabilitiesChange Whether to allow to invalidate
       *     selections.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setAllowInvalidateSelectionsOnRendererCapabilitiesChange(
          boolean allowInvalidateSelectionsOnRendererCapabilitiesChange) {
        this.allowInvalidateSelectionsOnRendererCapabilitiesChange =
            allowInvalidateSelectionsOnRendererCapabilitiesChange;
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder addOverride(TrackSelectionOverride override) {
        super.addOverride(override);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder clearOverride(TrackGroup trackGroup) {
        super.clearOverride(trackGroup);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setOverrideForType(TrackSelectionOverride override) {
        super.setOverrideForType(override);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder clearOverridesOfType(@C.TrackType int trackType) {
        super.clearOverridesOfType(trackType);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder clearOverrides() {
        super.clearOverrides();
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setDisabledTrackTypes(Set<@C.TrackType Integer> disabledTrackTypes) {
        super.setDisabledTrackTypes(disabledTrackTypes);
        return this;
      }

      @CanIgnoreReturnValue
      @Override
      public Builder setTrackTypeDisabled(@C.TrackType int trackType, boolean disabled) {
        super.setTrackTypeDisabled(trackType, disabled);
        return this;
      }

      /**
       * Sets whether to exceed renderer capabilities when no selection can be made otherwise.
       *
       * <p>This parameter applies when all of the tracks available for a renderer exceed the
       * renderer's reported capabilities. If the parameter is {@code true} then the lowest quality
       * track will still be selected. Playback may succeed if the renderer has under-reported its
       * true capabilities. If {@code false} then no track will be selected.
       *
       * @param exceedRendererCapabilitiesIfNecessary Whether to exceed renderer capabilities when
       *     no selection can be made otherwise.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setExceedRendererCapabilitiesIfNecessary(
          boolean exceedRendererCapabilitiesIfNecessary) {
        this.exceedRendererCapabilitiesIfNecessary = exceedRendererCapabilitiesIfNecessary;
        return this;
      }

      /**
       * Sets whether to enable tunneling if possible. Tunneling will only be enabled if it's
       * supported by the audio and video renderers for the selected tracks.
       *
       * <p>Tunneling is known to have many device specific issues and limitations. Manual testing
       * is strongly recommended to check that the media plays correctly when this option is
       * enabled. See:
       *
       * <ul>
       *   <li><a href="https://github.com/google/ExoPlayer/issues/9661">google/ExoPlayer#9661</a>
       *   <li><a href="https://github.com/google/ExoPlayer/issues/9133">google/ExoPlayer#9133</a>
       *   <li><a href="https://github.com/google/ExoPlayer/issues/9317">google/ExoPlayer#9317</a>
       *   <li><a href="https://github.com/google/ExoPlayer/issues/9502">google/ExoPlayer#9502</a>
       * </ul>
       *
       * @param tunnelingEnabled Whether to enable tunneling if possible.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setTunnelingEnabled(boolean tunnelingEnabled) {
        this.tunnelingEnabled = tunnelingEnabled;
        return this;
      }

      /**
       * Sets whether multiple adaptive selections with more than one track are allowed.
       *
       * @param allowMultipleAdaptiveSelections Whether multiple adaptive selections are allowed.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setAllowMultipleAdaptiveSelections(boolean allowMultipleAdaptiveSelections) {
        this.allowMultipleAdaptiveSelections = allowMultipleAdaptiveSelections;
        return this;
      }

      // Overrides

      /**
       * Sets whether the renderer at the specified index is disabled. Disabling a renderer prevents
       * the selector from selecting any tracks for it.
       *
       * @param rendererIndex The renderer index.
       * @param disabled Whether the renderer is disabled.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setRendererDisabled(int rendererIndex, boolean disabled) {
        if (rendererDisabledFlags.get(rendererIndex) == disabled) {
          // The disabled flag is unchanged.
          return this;
        }
        // Only true values are placed in the array to make it easier to check for equality.
        if (disabled) {
          rendererDisabledFlags.put(rendererIndex, true);
        } else {
          rendererDisabledFlags.delete(rendererIndex);
        }
        return this;
      }

      /**
       * Overrides the track selection for the renderer at the specified index.
       *
       * <p>When the {@link TrackGroupArray} mapped to the renderer matches the one provided, the
       * override is applied. When the {@link TrackGroupArray} does not match, the override has no
       * effect. The override replaces any previous override for the specified {@link
       * TrackGroupArray} for the specified {@link Renderer}.
       *
       * <p>Passing a {@code null} override will cause the renderer to be disabled when the {@link
       * TrackGroupArray} mapped to it matches the one provided. When the {@link TrackGroupArray}
       * does not match a {@code null} override has no effect. Hence a {@code null} override differs
       * from disabling the renderer using {@link #setRendererDisabled(int, boolean)} because the
       * renderer is disabled conditionally on the {@link TrackGroupArray} mapped to it, where-as
       * {@link #setRendererDisabled(int, boolean)} disables the renderer unconditionally.
       *
       * <p>To remove overrides use {@link #clearSelectionOverride(int, TrackGroupArray)}, {@link
       * #clearSelectionOverrides(int)} or {@link #clearSelectionOverrides()}.
       *
       * @param rendererIndex The renderer index.
       * @param groups The {@link TrackGroupArray} for which the override should be applied.
       * @param override The override.
       * @return This builder.
       * @deprecated Use {@link
       *     TrackSelectionParameters.Builder#addOverride(TrackSelectionOverride)}.
       */
      @CanIgnoreReturnValue
      @Deprecated
      public Builder setSelectionOverride(
          int rendererIndex, TrackGroupArray groups, @Nullable SelectionOverride override) {
        Map<TrackGroupArray, @NullableType SelectionOverride> overrides =
            selectionOverrides.get(rendererIndex);
        if (overrides == null) {
          overrides = new HashMap<>();
          selectionOverrides.put(rendererIndex, overrides);
        }
        if (overrides.containsKey(groups) && Objects.equals(overrides.get(groups), override)) {
          // The override is unchanged.
          return this;
        }
        overrides.put(groups, override);
        return this;
      }

      /**
       * Clears a track selection override for the specified renderer and {@link TrackGroupArray}.
       *
       * @param rendererIndex The renderer index.
       * @param groups The {@link TrackGroupArray} for which the override should be cleared.
       * @return This builder.
       * @deprecated Use {@link TrackSelectionParameters.Builder#clearOverride(TrackGroup)}.
       */
      @CanIgnoreReturnValue
      @Deprecated
      public Builder clearSelectionOverride(int rendererIndex, TrackGroupArray groups) {
        Map<TrackGroupArray, @NullableType SelectionOverride> overrides =
            selectionOverrides.get(rendererIndex);
        if (overrides == null || !overrides.containsKey(groups)) {
          // Nothing to clear.
          return this;
        }
        overrides.remove(groups);
        if (overrides.isEmpty()) {
          selectionOverrides.remove(rendererIndex);
        }
        return this;
      }

      /**
       * Clears all track selection overrides for the specified renderer.
       *
       * @param rendererIndex The renderer index.
       * @return This builder.
       * @deprecated Use {@link TrackSelectionParameters.Builder#clearOverridesOfType(int)}.
       */
      @CanIgnoreReturnValue
      @Deprecated
      public Builder clearSelectionOverrides(int rendererIndex) {
        Map<TrackGroupArray, @NullableType SelectionOverride> overrides =
            selectionOverrides.get(rendererIndex);
        if (overrides == null || overrides.isEmpty()) {
          // Nothing to clear.
          return this;
        }
        selectionOverrides.remove(rendererIndex);
        return this;
      }

      /**
       * Clears all track selection overrides for all renderers.
       *
       * @return This builder.
       * @deprecated Use {@link TrackSelectionParameters.Builder#clearOverrides()}.
       */
      @CanIgnoreReturnValue
      @Deprecated
      public Builder clearSelectionOverrides() {
        if (selectionOverrides.size() == 0) {
          // Nothing to clear.
          return this;
        }
        selectionOverrides.clear();
        return this;
      }

      /** Builds a {@link Parameters} instance with the selected values. */
      @Override
      public Parameters build() {
        return new Parameters(this);
      }

      private void init(Builder this) {
        // Video
        exceedVideoConstraintsIfNecessary = true;
        allowVideoMixedMimeTypeAdaptiveness = false;
        allowVideoNonSeamlessAdaptiveness = true;
        allowVideoMixedDecoderSupportAdaptiveness = false;
        // Audio
        exceedAudioConstraintsIfNecessary = true;
        allowAudioMixedMimeTypeAdaptiveness = false;
        allowAudioMixedSampleRateAdaptiveness = false;
        allowAudioMixedChannelCountAdaptiveness = false;
        allowAudioMixedDecoderSupportAdaptiveness = false;
        allowAudioNonSeamlessAdaptiveness = true;
        constrainAudioChannelCountToDeviceCapabilities = true;
        // General
        exceedRendererCapabilitiesIfNecessary = true;
        tunnelingEnabled = false;
        allowMultipleAdaptiveSelections = true;
        allowInvalidateSelectionsOnRendererCapabilitiesChange = false;
      }

      private static SparseArray<Map<TrackGroupArray, @NullableType SelectionOverride>>
          cloneSelectionOverrides(
              SparseArray<Map<TrackGroupArray, @NullableType SelectionOverride>>
                  selectionOverrides) {
        SparseArray<Map<TrackGroupArray, @NullableType SelectionOverride>> clone =
            new SparseArray<>();
        for (int i = 0; i < selectionOverrides.size(); i++) {
          clone.put(selectionOverrides.keyAt(i), new HashMap<>(selectionOverrides.valueAt(i)));
        }
        return clone;
      }

      @SuppressWarnings("deprecation") // Filling deprecated field from Bundle
      private void setSelectionOverridesFromBundle(Bundle bundle) {
        @Nullable
        int[] rendererIndices =
            bundle.getIntArray(Parameters.FIELD_SELECTION_OVERRIDES_RENDERER_INDICES);
        @Nullable
        ArrayList<Bundle> trackGroupArrayBundles =
            bundle.getParcelableArrayList(Parameters.FIELD_SELECTION_OVERRIDES_TRACK_GROUP_ARRAYS);
        List<TrackGroupArray> trackGroupArrays =
            trackGroupArrayBundles == null
                ? ImmutableList.of()
                : BundleCollectionUtil.fromBundleList(
                    TrackGroupArray::fromBundle, trackGroupArrayBundles);
        @Nullable
        SparseArray<Bundle> selectionOverrideBundles =
            bundle.getSparseParcelableArray(Parameters.FIELD_SELECTION_OVERRIDES);
        SparseArray<SelectionOverride> selectionOverrides =
            selectionOverrideBundles == null
                ? new SparseArray<>()
                : BundleCollectionUtil.fromBundleSparseArray(
                    SelectionOverride::fromBundle, selectionOverrideBundles);

        if (rendererIndices == null || rendererIndices.length != trackGroupArrays.size()) {
          return; // Incorrect format, ignore all overrides.
        }
        for (int i = 0; i < rendererIndices.length; i++) {
          int rendererIndex = rendererIndices[i];
          TrackGroupArray groups = trackGroupArrays.get(i);
          @Nullable SelectionOverride selectionOverride = selectionOverrides.get(i);
          setSelectionOverride(rendererIndex, groups, selectionOverride);
        }
      }

      private SparseBooleanArray makeSparseBooleanArrayFromTrueKeys(@Nullable int[] trueKeys) {
        if (trueKeys == null) {
          return new SparseBooleanArray();
        }
        SparseBooleanArray sparseBooleanArray = new SparseBooleanArray(trueKeys.length);
        for (int trueKey : trueKeys) {
          sparseBooleanArray.append(trueKey, true);
        }
        return sparseBooleanArray;
      }
    }

    /** An instance with default values. */
    public static final Parameters DEFAULT = new Builder().build();

    /**
     * @deprecated Use {@link #DEFAULT} instead.
     */
    @Deprecated public static final Parameters DEFAULT_WITHOUT_CONTEXT = DEFAULT;

    /**
     * @deprecated Use {@link #DEFAULT} instead.
     */
    @Deprecated
    public static Parameters getDefaults(Context context) {
      return DEFAULT;
    }

    // Video

    /**
     * Whether to exceed the {@link #maxVideoWidth}, {@link #maxVideoHeight} and {@link
     * #maxVideoBitrate} constraints when no selection can be made otherwise. The default value is
     * {@code true}.
     */
    public final boolean exceedVideoConstraintsIfNecessary;

    /**
     * Whether to allow adaptive video selections containing mixed MIME types. Adaptations between
     * different MIME types may not be completely seamless, in which case {@link
     * #allowVideoNonSeamlessAdaptiveness} also needs to be {@code true} for mixed MIME type
     * selections to be made. The default value is {@code false}.
     */
    public final boolean allowVideoMixedMimeTypeAdaptiveness;

    /**
     * Whether to allow adaptive video selections where adaptation may not be completely seamless.
     * The default value is {@code true}.
     */
    public final boolean allowVideoNonSeamlessAdaptiveness;

    /**
     * Whether to allow adaptive video selections with mixed levels of {@link
     * RendererCapabilities.DecoderSupport} and {@link
     * RendererCapabilities.HardwareAccelerationSupport}.
     */
    public final boolean allowVideoMixedDecoderSupportAdaptiveness;

    // Audio

    /**
     * Whether to exceed the {@link #maxAudioChannelCount} and {@link #maxAudioBitrate} constraints
     * when no selection can be made otherwise. The default value is {@code true}.
     */
    public final boolean exceedAudioConstraintsIfNecessary;

    /**
     * Whether to allow adaptive audio selections containing mixed MIME types. Adaptations between
     * different MIME types may not be completely seamless, in which case {@link
     * #allowAudioNonSeamlessAdaptiveness} also needs to be {@code true} for mixed MIME type
     * selections to be made. The default value is {@code false}.
     */
    public final boolean allowAudioMixedMimeTypeAdaptiveness;

    /**
     * Whether to allow adaptive audio selections containing mixed sample rates. Adaptations between
     * different sample rates may not be completely seamless. The default value is {@code false}.
     */
    public final boolean allowAudioMixedSampleRateAdaptiveness;

    /**
     * Whether to allow adaptive audio selections containing mixed channel counts. Adaptations
     * between different channel counts may not be completely seamless. The default value is {@code
     * false}.
     */
    public final boolean allowAudioMixedChannelCountAdaptiveness;

    /**
     * Whether to allow adaptive audio selections with mixed levels of {@link
     * RendererCapabilities.DecoderSupport} and {@link
     * RendererCapabilities.HardwareAccelerationSupport}.
     */
    public final boolean allowAudioMixedDecoderSupportAdaptiveness;

    /**
     * Whether to allow adaptive audio selections where adaptation may not be completely seamless.
     * The default value is {@code true}.
     */
    public final boolean allowAudioNonSeamlessAdaptiveness;

    /**
     * Whether to constrain audio track selection so that the selected track's channel count does
     * not exceed the device's output capabilities. The default value is {@code true}.
     */
    public final boolean constrainAudioChannelCountToDeviceCapabilities;

    // General

    /**
     * Whether to exceed renderer capabilities when no selection can be made otherwise.
     *
     * <p>This parameter applies when all of the tracks available for a renderer exceed the
     * renderer's reported capabilities. If the parameter is {@code true} then the lowest quality
     * track will still be selected. Playback may succeed if the renderer has under-reported its
     * true capabilities. If {@code false} then no track will be selected. The default value is
     * {@code true}.
     */
    public final boolean exceedRendererCapabilitiesIfNecessary;

    /** Whether to enable tunneling if possible. */
    public final boolean tunnelingEnabled;

    /**
     * Whether multiple adaptive selections with more than one track are allowed. The default value
     * is {@code true}.
     *
     * <p>Note that tracks are only eligible for adaptation if they define a bitrate, the renderers
     * support the tracks and allow adaptation between them, and they are not excluded based on
     * other track selection parameters.
     */
    public final boolean allowMultipleAdaptiveSelections;

    /**
     * Whether to allow to invalidate selections on renderer capabilities change. The default value
     * is {@code false}.
     */
    public final boolean allowInvalidateSelectionsOnRendererCapabilitiesChange;

    // Overrides
    private final SparseArray<Map<TrackGroupArray, @NullableType SelectionOverride>>
        selectionOverrides;
    private final SparseBooleanArray rendererDisabledFlags;

    private Parameters(Builder builder) {
      super(builder);
      // Video
      exceedVideoConstraintsIfNecessary = builder.exceedVideoConstraintsIfNecessary;
      allowVideoMixedMimeTypeAdaptiveness = builder.allowVideoMixedMimeTypeAdaptiveness;
      allowVideoNonSeamlessAdaptiveness = builder.allowVideoNonSeamlessAdaptiveness;
      allowVideoMixedDecoderSupportAdaptiveness = builder.allowVideoMixedDecoderSupportAdaptiveness;
      // Audio
      exceedAudioConstraintsIfNecessary = builder.exceedAudioConstraintsIfNecessary;
      allowAudioMixedMimeTypeAdaptiveness = builder.allowAudioMixedMimeTypeAdaptiveness;
      allowAudioMixedSampleRateAdaptiveness = builder.allowAudioMixedSampleRateAdaptiveness;
      allowAudioMixedChannelCountAdaptiveness = builder.allowAudioMixedChannelCountAdaptiveness;
      allowAudioMixedDecoderSupportAdaptiveness = builder.allowAudioMixedDecoderSupportAdaptiveness;
      allowAudioNonSeamlessAdaptiveness = builder.allowAudioNonSeamlessAdaptiveness;
      constrainAudioChannelCountToDeviceCapabilities =
          builder.constrainAudioChannelCountToDeviceCapabilities;
      // General
      exceedRendererCapabilitiesIfNecessary = builder.exceedRendererCapabilitiesIfNecessary;
      tunnelingEnabled = builder.tunnelingEnabled;
      allowMultipleAdaptiveSelections = builder.allowMultipleAdaptiveSelections;
      allowInvalidateSelectionsOnRendererCapabilitiesChange =
          builder.allowInvalidateSelectionsOnRendererCapabilitiesChange;
      // Overrides
      selectionOverrides = builder.selectionOverrides;
      rendererDisabledFlags = builder.rendererDisabledFlags;
    }

    /**
     * Returns whether the renderer is disabled.
     *
     * @param rendererIndex The renderer index.
     * @return Whether the renderer is disabled.
     */
    public boolean getRendererDisabled(int rendererIndex) {
      return rendererDisabledFlags.get(rendererIndex);
    }

    /**
     * Returns whether there is an override for the specified renderer and {@link TrackGroupArray}.
     *
     * @param rendererIndex The renderer index.
     * @param groups The {@link TrackGroupArray}.
     * @return Whether there is an override.
     * @deprecated Only works to retrieve the overrides set with the deprecated {@link
     *     Builder#setSelectionOverride(int, TrackGroupArray, SelectionOverride)}. Use {@link
     *     TrackSelectionParameters#overrides} instead.
     */
    @Deprecated
    public boolean hasSelectionOverride(int rendererIndex, TrackGroupArray groups) {
      @Nullable
      Map<TrackGroupArray, @NullableType SelectionOverride> overrides =
          selectionOverrides.get(rendererIndex);
      return overrides != null && overrides.containsKey(groups);
    }

    /**
     * Returns the override for the specified renderer and {@link TrackGroupArray}.
     *
     * @param rendererIndex The renderer index.
     * @param groups The {@link TrackGroupArray}.
     * @return The override, or null if no override exists.
     * @deprecated Only works to retrieve the overrides set with the deprecated {@link
     *     Builder#setSelectionOverride(int, TrackGroupArray, SelectionOverride)}. Use {@link
     *     TrackSelectionParameters#overrides} instead.
     */
    @Deprecated
    @Nullable
    public SelectionOverride getSelectionOverride(int rendererIndex, TrackGroupArray groups) {
      @Nullable
      Map<TrackGroupArray, @NullableType SelectionOverride> overrides =
          selectionOverrides.get(rendererIndex);
      return overrides != null ? overrides.get(groups) : null;
    }

    /** Creates a new {@link Parameters.Builder}, copying the initial values from this instance. */
    @Override
    public Parameters.Builder buildUpon() {
      return new Parameters.Builder(this);
    }

    @SuppressWarnings(
        "EqualsGetClass") // Class extends TrackSelectionParameters for backwards compatibility.
    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      Parameters other = (Parameters) obj;
      return super.equals(other)
          // Video
          && exceedVideoConstraintsIfNecessary == other.exceedVideoConstraintsIfNecessary
          && allowVideoMixedMimeTypeAdaptiveness == other.allowVideoMixedMimeTypeAdaptiveness
          && allowVideoNonSeamlessAdaptiveness == other.allowVideoNonSeamlessAdaptiveness
          && allowVideoMixedDecoderSupportAdaptiveness
              == other.allowVideoMixedDecoderSupportAdaptiveness
          // Audio
          && exceedAudioConstraintsIfNecessary == other.exceedAudioConstraintsIfNecessary
          && allowAudioMixedMimeTypeAdaptiveness == other.allowAudioMixedMimeTypeAdaptiveness
          && allowAudioMixedSampleRateAdaptiveness == other.allowAudioMixedSampleRateAdaptiveness
          && allowAudioMixedChannelCountAdaptiveness
              == other.allowAudioMixedChannelCountAdaptiveness
          && allowAudioMixedDecoderSupportAdaptiveness
              == other.allowAudioMixedDecoderSupportAdaptiveness
          && allowAudioNonSeamlessAdaptiveness == other.allowAudioNonSeamlessAdaptiveness
          && constrainAudioChannelCountToDeviceCapabilities
              == other.constrainAudioChannelCountToDeviceCapabilities
          // General
          && exceedRendererCapabilitiesIfNecessary == other.exceedRendererCapabilitiesIfNecessary
          && tunnelingEnabled == other.tunnelingEnabled
          && allowMultipleAdaptiveSelections == other.allowMultipleAdaptiveSelections
          && allowInvalidateSelectionsOnRendererCapabilitiesChange
              == other.allowInvalidateSelectionsOnRendererCapabilitiesChange
          // Overrides
          && areRendererDisabledFlagsEqual(rendererDisabledFlags, other.rendererDisabledFlags)
          && areSelectionOverridesEqual(selectionOverrides, other.selectionOverrides);
    }

    @Override
    public int hashCode() {
      int result = 1;
      result = 31 * result + super.hashCode();
      // Video
      result = 31 * result + (exceedVideoConstraintsIfNecessary ? 1 : 0);
      result = 31 * result + (allowVideoMixedMimeTypeAdaptiveness ? 1 : 0);
      result = 31 * result + (allowVideoNonSeamlessAdaptiveness ? 1 : 0);
      result = 31 * result + (allowVideoMixedDecoderSupportAdaptiveness ? 1 : 0);
      // Audio
      result = 31 * result + (exceedAudioConstraintsIfNecessary ? 1 : 0);
      result = 31 * result + (allowAudioMixedMimeTypeAdaptiveness ? 1 : 0);
      result = 31 * result + (allowAudioMixedSampleRateAdaptiveness ? 1 : 0);
      result = 31 * result + (allowAudioMixedChannelCountAdaptiveness ? 1 : 0);
      result = 31 * result + (allowAudioMixedDecoderSupportAdaptiveness ? 1 : 0);
      result = 31 * result + (allowAudioNonSeamlessAdaptiveness ? 1 : 0);
      result = 31 * result + (constrainAudioChannelCountToDeviceCapabilities ? 1 : 0);
      // General
      result = 31 * result + (exceedRendererCapabilitiesIfNecessary ? 1 : 0);
      result = 31 * result + (tunnelingEnabled ? 1 : 0);
      result = 31 * result + (allowMultipleAdaptiveSelections ? 1 : 0);
      result = 31 * result + (allowInvalidateSelectionsOnRendererCapabilitiesChange ? 1 : 0);
      // Overrides (omitted from hashCode).
      return result;
    }

    private static final String FIELD_EXCEED_VIDEO_CONSTRAINTS_IF_NECESSARY =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE);
    private static final String FIELD_ALLOW_VIDEO_MIXED_MIME_TYPE_ADAPTIVENESS =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 1);
    private static final String FIELD_ALLOW_VIDEO_NON_SEAMLESS_ADAPTIVENESS =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 2);
    private static final String FIELD_EXCEED_AUDIO_CONSTRAINTS_IF_NECESSARY =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 3);
    private static final String FIELD_ALLOW_AUDIO_MIXED_MIME_TYPE_ADAPTIVENESS =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 4);
    private static final String FIELD_ALLOW_AUDIO_MIXED_SAMPLE_RATE_ADAPTIVENESS =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 5);
    private static final String FIELD_ALLOW_AUDIO_MIXED_CHANNEL_COUNT_ADAPTIVENESS =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 6);
    private static final String FIELD_EXCEED_RENDERER_CAPABILITIES_IF_NECESSARY =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 7);
    private static final String FIELD_TUNNELING_ENABLED =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 8);
    private static final String FIELD_ALLOW_MULTIPLE_ADAPTIVE_SELECTIONS =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 9);
    private static final String FIELD_SELECTION_OVERRIDES_RENDERER_INDICES =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 10);
    private static final String FIELD_SELECTION_OVERRIDES_TRACK_GROUP_ARRAYS =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 11);
    private static final String FIELD_SELECTION_OVERRIDES =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 12);
    private static final String FIELD_RENDERER_DISABLED_INDICES =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 13);
    private static final String FIELD_ALLOW_VIDEO_MIXED_DECODER_SUPPORT_ADAPTIVENESS =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 14);
    private static final String FIELD_ALLOW_AUDIO_MIXED_DECODER_SUPPORT_ADAPTIVENESS =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 15);
    private static final String FIELD_CONSTRAIN_AUDIO_CHANNEL_COUNT_TO_DEVICE_CAPABILITIES =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 16);
    private static final String FIELD_ALLOW_INVALIDATE_SELECTIONS_ON_RENDERER_CAPABILITIES_CHANGE =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 17);
    private static final String FIELD_ALLOW_AUDIO_NON_SEAMLESS_ADAPTIVENESS =
        Util.intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 18);

    @Override
    public Bundle toBundle() {
      Bundle bundle = super.toBundle();

      // Video
      bundle.putBoolean(
          FIELD_EXCEED_VIDEO_CONSTRAINTS_IF_NECESSARY, exceedVideoConstraintsIfNecessary);
      bundle.putBoolean(
          FIELD_ALLOW_VIDEO_MIXED_MIME_TYPE_ADAPTIVENESS, allowVideoMixedMimeTypeAdaptiveness);
      bundle.putBoolean(
          FIELD_ALLOW_VIDEO_NON_SEAMLESS_ADAPTIVENESS, allowVideoNonSeamlessAdaptiveness);
      bundle.putBoolean(
          FIELD_ALLOW_VIDEO_MIXED_DECODER_SUPPORT_ADAPTIVENESS,
          allowVideoMixedDecoderSupportAdaptiveness);
      // Audio
      bundle.putBoolean(
          FIELD_EXCEED_AUDIO_CONSTRAINTS_IF_NECESSARY, exceedAudioConstraintsIfNecessary);
      bundle.putBoolean(
          FIELD_ALLOW_AUDIO_MIXED_MIME_TYPE_ADAPTIVENESS, allowAudioMixedMimeTypeAdaptiveness);
      bundle.putBoolean(
          FIELD_ALLOW_AUDIO_MIXED_SAMPLE_RATE_ADAPTIVENESS, allowAudioMixedSampleRateAdaptiveness);
      bundle.putBoolean(
          FIELD_ALLOW_AUDIO_MIXED_CHANNEL_COUNT_ADAPTIVENESS,
          allowAudioMixedChannelCountAdaptiveness);
      bundle.putBoolean(
          FIELD_ALLOW_AUDIO_MIXED_DECODER_SUPPORT_ADAPTIVENESS,
          allowAudioMixedDecoderSupportAdaptiveness);
      bundle.putBoolean(
          FIELD_ALLOW_AUDIO_NON_SEAMLESS_ADAPTIVENESS, allowAudioNonSeamlessAdaptiveness);
      bundle.putBoolean(
          FIELD_CONSTRAIN_AUDIO_CHANNEL_COUNT_TO_DEVICE_CAPABILITIES,
          constrainAudioChannelCountToDeviceCapabilities);
      // General
      bundle.putBoolean(
          FIELD_EXCEED_RENDERER_CAPABILITIES_IF_NECESSARY, exceedRendererCapabilitiesIfNecessary);
      bundle.putBoolean(FIELD_TUNNELING_ENABLED, tunnelingEnabled);
      bundle.putBoolean(FIELD_ALLOW_MULTIPLE_ADAPTIVE_SELECTIONS, allowMultipleAdaptiveSelections);
      bundle.putBoolean(
          FIELD_ALLOW_INVALIDATE_SELECTIONS_ON_RENDERER_CAPABILITIES_CHANGE,
          allowInvalidateSelectionsOnRendererCapabilitiesChange);

      putSelectionOverridesToBundle(bundle, selectionOverrides);
      // Only true values are put into rendererDisabledFlags.
      bundle.putIntArray(
          FIELD_RENDERER_DISABLED_INDICES, getKeysFromSparseBooleanArray(rendererDisabledFlags));

      return bundle;
    }

    /** Restores a {@code Parameters} from a {@link Bundle}. */
    public static Parameters fromBundle(Bundle bundle) {
      return new Parameters.Builder(bundle).build();
    }

    /**
     * Bundles selection overrides in 3 arrays of equal length. Each triplet of matching indices is:
     * the selection override (stored in a sparse array as they can be null), the trackGroupArray of
     * that override, the rendererIndex of that override.
     */
    private static void putSelectionOverridesToBundle(
        Bundle bundle,
        SparseArray<Map<TrackGroupArray, @NullableType SelectionOverride>> selectionOverrides) {
      ArrayList<Integer> rendererIndices = new ArrayList<>();
      ArrayList<TrackGroupArray> trackGroupArrays = new ArrayList<>();
      SparseArray<SelectionOverride> selections = new SparseArray<>();

      for (int i = 0; i < selectionOverrides.size(); i++) {
        int rendererIndex = selectionOverrides.keyAt(i);
        for (Map.Entry<TrackGroupArray, @NullableType SelectionOverride> override :
            selectionOverrides.valueAt(i).entrySet()) {
          @Nullable SelectionOverride selection = override.getValue();
          if (selection != null) {
            selections.put(trackGroupArrays.size(), selection);
          }
          trackGroupArrays.add(override.getKey());
          rendererIndices.add(rendererIndex);
        }
        bundle.putIntArray(
            FIELD_SELECTION_OVERRIDES_RENDERER_INDICES, Ints.toArray(rendererIndices));
        bundle.putParcelableArrayList(
            FIELD_SELECTION_OVERRIDES_TRACK_GROUP_ARRAYS,
            BundleCollectionUtil.toBundleArrayList(trackGroupArrays, TrackGroupArray::toBundle));
        bundle.putSparseParcelableArray(
            FIELD_SELECTION_OVERRIDES,
            BundleCollectionUtil.toBundleSparseArray(selections, SelectionOverride::toBundle));
      }
    }

    private static int[] getKeysFromSparseBooleanArray(SparseBooleanArray sparseBooleanArray) {
      int[] keys = new int[sparseBooleanArray.size()];
      for (int i = 0; i < sparseBooleanArray.size(); i++) {
        keys[i] = sparseBooleanArray.keyAt(i);
      }
      return keys;
    }

    private static boolean areRendererDisabledFlagsEqual(
        SparseBooleanArray first, SparseBooleanArray second) {
      int firstSize = first.size();
      if (second.size() != firstSize) {
        return false;
      }
      // Only true values are put into rendererDisabledFlags, so we don't need to compare values.
      for (int indexInFirst = 0; indexInFirst < firstSize; indexInFirst++) {
        if (second.indexOfKey(first.keyAt(indexInFirst)) < 0) {
          return false;
        }
      }
      return true;
    }

    private static boolean areSelectionOverridesEqual(
        SparseArray<Map<TrackGroupArray, @NullableType SelectionOverride>> first,
        SparseArray<Map<TrackGroupArray, @NullableType SelectionOverride>> second) {
      int firstSize = first.size();
      if (second.size() != firstSize) {
        return false;
      }
      for (int indexInFirst = 0; indexInFirst < firstSize; indexInFirst++) {
        int indexInSecond = second.indexOfKey(first.keyAt(indexInFirst));
        if (indexInSecond < 0
            || !areSelectionOverridesEqual(
                first.valueAt(indexInFirst), second.valueAt(indexInSecond))) {
          return false;
        }
      }
      return true;
    }

    private static boolean areSelectionOverridesEqual(
        Map<TrackGroupArray, @NullableType SelectionOverride> first,
        Map<TrackGroupArray, @NullableType SelectionOverride> second) {
      int firstSize = first.size();
      if (second.size() != firstSize) {
        return false;
      }
      for (Map.Entry<TrackGroupArray, @NullableType SelectionOverride> firstEntry :
          first.entrySet()) {
        TrackGroupArray key = firstEntry.getKey();
        if (!second.containsKey(key) || !Objects.equals(firstEntry.getValue(), second.get(key))) {
          return false;
        }
      }
      return true;
    }
  }

  /** A track selection override. */
  public static final class SelectionOverride {

    public final int groupIndex;
    public final int[] tracks;
    public final int length;
    public final @TrackSelection.Type int type;

    /**
     * Constructs a {@code SelectionOverride} to override tracks of a group.
     *
     * @param groupIndex The overriding track group index.
     * @param tracks The overriding track indices within the track group.
     */
    public SelectionOverride(int groupIndex, int... tracks) {
      this(groupIndex, tracks, TrackSelection.TYPE_UNSET);
    }

    /**
     * Constructs a {@code SelectionOverride} of the given type to override tracks of a group.
     *
     * @param groupIndex The overriding track group index.
     * @param tracks The overriding track indices within the track group.
     * @param type The type that will be returned from {@link TrackSelection#getType()}.
     */
    @UnstableApi
    public SelectionOverride(int groupIndex, int[] tracks, @TrackSelection.Type int type) {
      this.groupIndex = groupIndex;
      this.tracks = Arrays.copyOf(tracks, tracks.length);
      this.length = tracks.length;
      this.type = type;
      Arrays.sort(this.tracks);
    }

    /** Returns whether this override contains the specified track index. */
    public boolean containsTrack(int track) {
      for (int overrideTrack : tracks) {
        if (overrideTrack == track) {
          return true;
        }
      }
      return false;
    }

    @Override
    public int hashCode() {
      int hash = 31 * groupIndex + Arrays.hashCode(tracks);
      return 31 * hash + type;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      SelectionOverride other = (SelectionOverride) obj;
      return groupIndex == other.groupIndex
          && Arrays.equals(tracks, other.tracks)
          && type == other.type;
    }

    private static final String FIELD_GROUP_INDEX = Util.intToStringMaxRadix(0);
    private static final String FIELD_TRACKS = Util.intToStringMaxRadix(1);
    private static final String FIELD_TRACK_TYPE = Util.intToStringMaxRadix(2);

    @UnstableApi
    public Bundle toBundle() {
      Bundle bundle = new Bundle();
      bundle.putInt(FIELD_GROUP_INDEX, groupIndex);
      bundle.putIntArray(FIELD_TRACKS, tracks);
      bundle.putInt(FIELD_TRACK_TYPE, type);
      return bundle;
    }

    /** Restores a {@code SelectionOverride} from a {@link Bundle}. */
    @UnstableApi
    public static SelectionOverride fromBundle(Bundle bundle) {
      int groupIndex = bundle.getInt(FIELD_GROUP_INDEX, -1);
      @Nullable int[] tracks = bundle.getIntArray(FIELD_TRACKS);
      int trackType = bundle.getInt(FIELD_TRACK_TYPE, -1);
      Assertions.checkArgument(groupIndex >= 0 && trackType >= 0);
      Assertions.checkNotNull(tracks);
      return new SelectionOverride(groupIndex, tracks, trackType);
    }
  }

  /**
   * The extent to which tracks are eligible for selection. One of {@link
   * #SELECTION_ELIGIBILITY_NO}, {@link #SELECTION_ELIGIBILITY_FIXED} or {@link
   * #SELECTION_ELIGIBILITY_ADAPTIVE}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({SELECTION_ELIGIBILITY_NO, SELECTION_ELIGIBILITY_FIXED, SELECTION_ELIGIBILITY_ADAPTIVE})
  protected @interface SelectionEligibility {}

  /** Track is not eligible for selection. */
  protected static final int SELECTION_ELIGIBILITY_NO = 0;

  /** Track is eligible for a fixed selection with one track. */
  protected static final int SELECTION_ELIGIBILITY_FIXED = 1;

  /**
   * Track is eligible for both a fixed selection and as part of an adaptive selection with multiple
   * tracks.
   */
  protected static final int SELECTION_ELIGIBILITY_ADAPTIVE = 2;

  /**
   * If a dimension (i.e. width or height) of a video is greater or equal to this fraction of the
   * corresponding viewport dimension, then the video is considered as filling the viewport (in that
   * dimension).
   */
  private static final float FRACTION_TO_CONSIDER_FULLSCREEN = 0.98f;

  /** Ordering of two format values. A known value is considered greater than Format#NO_VALUE. */
  private static final Ordering<Integer> FORMAT_VALUE_ORDERING =
      Ordering.from(
          (first, second) ->
              first == Format.NO_VALUE
                  ? (second == Format.NO_VALUE ? 0 : -1)
                  : (second == Format.NO_VALUE ? 1 : (first - second)));

  private final Object lock;
  @Nullable public final Context context;
  private final ExoTrackSelection.Factory trackSelectionFactory;

  @GuardedBy("lock")
  private Parameters parameters;

  @GuardedBy("lock")
  @Nullable
  private Thread playbackThread;

  @Nullable private SpatializerWrapperV32 spatializer;
  private AudioAttributes audioAttributes;
  private @MonotonicNonNull Boolean deviceIsTV;

  /**
   * @param context Any {@link Context}.
   */
  public DefaultTrackSelector(Context context) {
    this(context, new AdaptiveTrackSelection.Factory());
  }

  /**
   * @param context Any {@link Context}.
   * @param trackSelectionFactory A factory for {@link ExoTrackSelection}s.
   */
  public DefaultTrackSelector(Context context, ExoTrackSelection.Factory trackSelectionFactory) {
    this(context, Parameters.DEFAULT, trackSelectionFactory);
  }

  /**
   * @param context Any {@link Context}.
   * @param parameters Initial {@link TrackSelectionParameters}.
   */
  public DefaultTrackSelector(Context context, TrackSelectionParameters parameters) {
    this(context, parameters, new AdaptiveTrackSelection.Factory());
  }

  /**
   * @deprecated Use {@link #DefaultTrackSelector(Context, TrackSelectionParameters,
   *     ExoTrackSelection.Factory)}
   */
  // TODO: When this constructor is deleted, this.context can be made non-null and all
  //       null-conditional can be removed (including the warning logging when it's null).
  @Deprecated
  public DefaultTrackSelector(
      TrackSelectionParameters parameters, ExoTrackSelection.Factory trackSelectionFactory) {
    this(parameters, trackSelectionFactory, /* context= */ null);
  }

  /**
   * @param context Any {@link Context}.
   * @param parameters Initial {@link TrackSelectionParameters}.
   * @param trackSelectionFactory A factory for {@link ExoTrackSelection}s.
   */
  public DefaultTrackSelector(
      Context context,
      TrackSelectionParameters parameters,
      ExoTrackSelection.Factory trackSelectionFactory) {
    this(parameters, trackSelectionFactory, context);
  }

  /**
   * Exists for backwards compatibility so that the deprecated constructor {@link
   * #DefaultTrackSelector(TrackSelectionParameters, ExoTrackSelection.Factory)} can initialize
   * {@code context} with {@code null} while we don't have a public constructor with a {@code
   * Nullable context}.
   *
   * @param context Any {@link Context}.
   * @param parameters Initial {@link TrackSelectionParameters}.
   * @param trackSelectionFactory A factory for {@link ExoTrackSelection}s.
   */
  private DefaultTrackSelector(
      TrackSelectionParameters parameters,
      ExoTrackSelection.Factory trackSelectionFactory,
      @Nullable Context context) {
    this.lock = new Object();
    this.context = context != null ? context.getApplicationContext() : null;
    this.trackSelectionFactory = trackSelectionFactory;
    if (parameters instanceof Parameters) {
      this.parameters = (Parameters) parameters;
    } else {
      this.parameters = Parameters.DEFAULT.buildUpon().set(parameters).build();
    }
    this.audioAttributes = AudioAttributes.DEFAULT;
    if (this.parameters.constrainAudioChannelCountToDeviceCapabilities && context == null) {
      Log.w(TAG, AUDIO_CHANNEL_COUNT_CONSTRAINTS_WARN_MESSAGE);
    }
  }

  @Override
  public void release() {
    synchronized (lock) {
      if (playbackThread != null) {
        checkState(
            playbackThread == Thread.currentThread(),
            "DefaultTrackSelector is accessed on the wrong thread.");
      }
    }
    if (SDK_INT >= 32 && spatializer != null) {
      spatializer.release();
      spatializer = null;
    }
    super.release();
  }

  @Override
  public Parameters getParameters() {
    synchronized (lock) {
      return parameters;
    }
  }

  @Override
  public boolean isSetParametersSupported() {
    return true;
  }

  @Override
  public void setParameters(TrackSelectionParameters parameters) {
    if (parameters instanceof Parameters) {
      setParametersInternal((Parameters) parameters);
    }
    // Only add the fields of `TrackSelectionParameters` to `parameters`.
    Parameters mergedParameters = new Parameters.Builder(getParameters()).set(parameters).build();
    setParametersInternal(mergedParameters);
  }

  @Override
  public void setAudioAttributes(AudioAttributes audioAttributes) {
    if (this.audioAttributes.equals(audioAttributes)) {
      return;
    }
    this.audioAttributes = audioAttributes;
    maybeInvalidateForAudioChannelCountConstraints();
  }

  /**
   * @deprecated Use {@link #setParameters(Parameters.Builder)} instead.
   */
  @Deprecated
  @SuppressWarnings("deprecation") // Allow setting the deprecated builder
  public void setParameters(ParametersBuilder parametersBuilder) {
    setParametersInternal(parametersBuilder.build());
  }

  /**
   * Atomically sets the provided parameters for track selection.
   *
   * @param parametersBuilder A builder from which to obtain the parameters for track selection.
   */
  public void setParameters(Parameters.Builder parametersBuilder) {
    setParametersInternal(parametersBuilder.build());
  }

  /** Returns a new {@link Parameters.Builder} initialized with the current selection parameters. */
  public Parameters.Builder buildUponParameters() {
    return getParameters().buildUpon();
  }

  /**
   * Atomically sets the provided {@link Parameters} for track selection.
   *
   * @param parameters The parameters for track selection.
   */
  private void setParametersInternal(Parameters parameters) {
    Assertions.checkNotNull(parameters);
    boolean parametersChanged;
    synchronized (lock) {
      parametersChanged = !this.parameters.equals(parameters);
      this.parameters = parameters;
    }

    if (parametersChanged) {
      if (parameters.constrainAudioChannelCountToDeviceCapabilities && context == null) {
        Log.w(TAG, AUDIO_CHANNEL_COUNT_CONSTRAINTS_WARN_MESSAGE);
      }
      invalidate();
    }
  }

  @Override
  @Nullable
  public RendererCapabilities.Listener getRendererCapabilitiesListener() {
    return this;
  }

  // RendererCapabilities.Listener implementation.

  @Override
  public void onRendererCapabilitiesChanged(Renderer renderer) {
    maybeInvalidateForRendererCapabilitiesChange(renderer);
  }

  // MappingTrackSelector implementation.

  @Override
  protected final Pair<@NullableType RendererConfiguration[], @NullableType ExoTrackSelection[]>
      selectTracks(
          MappedTrackInfo mappedTrackInfo,
          @Capabilities int[][][] rendererFormatSupports,
          @AdaptiveSupport int[] rendererMixedMimeTypeAdaptationSupport,
          MediaPeriodId mediaPeriodId,
          Timeline timeline)
          throws ExoPlaybackException {
    Parameters parameters;
    synchronized (lock) {
      playbackThread = Thread.currentThread();
      parameters = this.parameters;
    }
    if (deviceIsTV == null && context != null) {
      deviceIsTV = Util.isTv(context);
    }
    if (parameters.constrainAudioChannelCountToDeviceCapabilities
        && SDK_INT >= 32
        && spatializer == null) {
      spatializer =
          new SpatializerWrapperV32(context, /* defaultTrackSelector= */ this, deviceIsTV);
    }
    int rendererCount = mappedTrackInfo.getRendererCount();
    ExoTrackSelection.@NullableType Definition[] definitions =
        selectAllTracks(
            mappedTrackInfo,
            rendererFormatSupports,
            rendererMixedMimeTypeAdaptationSupport,
            parameters);

    applyTrackSelectionOverrides(mappedTrackInfo, parameters, definitions);
    applyLegacyRendererOverrides(mappedTrackInfo, parameters, definitions);

    // Disable renderers if needed.
    for (int i = 0; i < rendererCount; i++) {
      @C.TrackType int rendererType = mappedTrackInfo.getRendererType(i);
      if (parameters.getRendererDisabled(i)
          || parameters.disabledTrackTypes.contains(rendererType)) {
        definitions[i] = null;
      }
    }

    @NullableType
    ExoTrackSelection[] rendererTrackSelections =
        trackSelectionFactory.createTrackSelections(
            definitions, getBandwidthMeter(), mediaPeriodId, timeline);

    // Initialize the renderer configurations to the default configuration for all renderers with
    // selections, and null otherwise.
    @NullableType
    RendererConfiguration[] rendererConfigurations = new RendererConfiguration[rendererCount];
    for (int i = 0; i < rendererCount; i++) {
      @C.TrackType int rendererType = mappedTrackInfo.getRendererType(i);
      boolean forceRendererDisabled =
          parameters.getRendererDisabled(i) || parameters.disabledTrackTypes.contains(rendererType);
      boolean rendererEnabled =
          !forceRendererDisabled
              && (mappedTrackInfo.getRendererType(i) == C.TRACK_TYPE_NONE
                  || rendererTrackSelections[i] != null);
      rendererConfigurations[i] = rendererEnabled ? RendererConfiguration.DEFAULT : null;
    }

    // Configure audio and video renderers to use tunneling if appropriate.
    if (parameters.tunnelingEnabled) {
      maybeConfigureRenderersForTunneling(
          mappedTrackInfo, rendererFormatSupports, rendererConfigurations, rendererTrackSelections);
    }

    // Configure audio renderer to use offload if appropriate.
    if (parameters.audioOffloadPreferences.audioOffloadMode != AUDIO_OFFLOAD_MODE_DISABLED) {
      maybeConfigureRendererForOffload(
          parameters,
          mappedTrackInfo,
          rendererFormatSupports,
          rendererConfigurations,
          rendererTrackSelections);
    }

    return Pair.create(rendererConfigurations, rendererTrackSelections);
  }

  // Track selection prior to overrides and disabled flags being applied.

  /**
   * Called from {@link #selectTracks(MappedTrackInfo, int[][][], int[], MediaPeriodId, Timeline)}
   * to make a track selection for each renderer, prior to overrides and disabled flags being
   * applied.
   *
   * <p>The implementation should not account for overrides and disabled flags. Track selections
   * generated by this method will be overridden to account for these properties.
   *
   * <p>If selection parameters include {@link Parameters#audioOffloadPreferences} with {@link
   * AudioOffloadPreferences#audioOffloadMode} set to {@link
   * AudioOffloadPreferences#AUDIO_OFFLOAD_MODE_REQUIRED} then only audio tracks will be selected.
   * If no audio track is supported in offload, then no track will be selected.
   *
   * @param mappedTrackInfo Mapped track information.
   * @param rendererFormatSupports The {@link Capabilities} for each mapped track, indexed by
   *     renderer, track group and track (in that order).
   * @param rendererMixedMimeTypeAdaptationSupports The {@link AdaptiveSupport} for mixed MIME type
   *     adaptation for the renderer.
   * @param params The parameters to use for the track selection.
   * @return The {@link ExoTrackSelection.Definition}s for the renderers. A null entry indicates no
   *     selection was made.
   * @throws ExoPlaybackException If an error occurs while selecting the tracks.
   */
  protected ExoTrackSelection.@NullableType Definition[] selectAllTracks(
      MappedTrackInfo mappedTrackInfo,
      @Capabilities int[][][] rendererFormatSupports,
      @AdaptiveSupport int[] rendererMixedMimeTypeAdaptationSupports,
      Parameters params)
      throws ExoPlaybackException {
    int rendererCount = mappedTrackInfo.getRendererCount();
    ExoTrackSelection.@NullableType Definition[] definitions =
        new ExoTrackSelection.Definition[rendererCount];

    @Nullable
    Pair<ExoTrackSelection.Definition, Integer> selectedAudio =
        selectAudioTrack(
            mappedTrackInfo,
            rendererFormatSupports,
            rendererMixedMimeTypeAdaptationSupports,
            params);
    if (selectedAudio != null) {
      definitions[selectedAudio.second] = selectedAudio.first;
    }
    @Nullable
    String selectedAudioLanguage =
        selectedAudio == null
            ? null
            : selectedAudio.first.group.getFormat(selectedAudio.first.tracks[0]).language;

    @Nullable
    Pair<ExoTrackSelection.Definition, Integer> selectedVideo =
        selectVideoTrack(
            mappedTrackInfo,
            rendererFormatSupports,
            rendererMixedMimeTypeAdaptationSupports,
            params,
            selectedAudioLanguage);

    @Nullable
    Pair<ExoTrackSelection.Definition, Integer> selectedImage =
        params.isPrioritizeImageOverVideoEnabled || selectedVideo == null
            ? selectImageTrack(mappedTrackInfo, rendererFormatSupports, params)
            : null;

    if (selectedImage != null) {
      definitions[selectedImage.second] = selectedImage.first;
    } else if (selectedVideo != null) {
      definitions[selectedVideo.second] = selectedVideo.first;
    }

    @Nullable
    Pair<ExoTrackSelection.Definition, Integer> selectedText =
        selectTextTrack(mappedTrackInfo, rendererFormatSupports, params, selectedAudioLanguage);
    if (selectedText != null) {
      definitions[selectedText.second] = selectedText.first;
    }

    for (int i = 0; i < rendererCount; i++) {
      int trackType = mappedTrackInfo.getRendererType(i);
      if (trackType != C.TRACK_TYPE_VIDEO
          && trackType != C.TRACK_TYPE_AUDIO
          && trackType != C.TRACK_TYPE_TEXT
          && trackType != C.TRACK_TYPE_IMAGE) {
        definitions[i] =
            selectOtherTrack(
                trackType, mappedTrackInfo.getTrackGroups(i), rendererFormatSupports[i], params);
      }
    }
    return definitions;
  }

  // Video track selection implementation.

  /**
   * Called by {@link #selectAllTracks(MappedTrackInfo, int[][][], int[], Parameters)} to create a
   * {@link ExoTrackSelection.Definition} for a video track selection.
   *
   * @param mappedTrackInfo Mapped track information.
   * @param rendererFormatSupports The {@link Capabilities} for each mapped track, indexed by
   *     renderer, track group and track (in that order).
   * @param mixedMimeTypeSupports The {@link AdaptiveSupport} for mixed MIME type adaptation for the
   *     renderer.
   * @param params The selector's current constraint parameters.
   * @param selectedAudioLanguage The language of the selected audio track. May be null if the
   *     selected audio track declares no language or no audio track was selected.
   * @return A pair of the selected {@link ExoTrackSelection.Definition} and the corresponding
   *     renderer index, or null if no selection was made.
   * @throws ExoPlaybackException If an error occurs while selecting the tracks.
   */
  @Nullable
  protected Pair<ExoTrackSelection.Definition, Integer> selectVideoTrack(
      MappedTrackInfo mappedTrackInfo,
      @Capabilities int[][][] rendererFormatSupports,
      @AdaptiveSupport int[] mixedMimeTypeSupports,
      Parameters params,
      @Nullable String selectedAudioLanguage)
      throws ExoPlaybackException {
    if (params.audioOffloadPreferences.audioOffloadMode == AUDIO_OFFLOAD_MODE_REQUIRED) {
      return null;
    }
    @Nullable
    Point viewportSizeFromDisplay =
        params.isViewportSizeLimitedByPhysicalDisplaySize && context != null
            ? Util.getCurrentDisplayModeSize(context)
            : null;
    return selectTracksForType(
        C.TRACK_TYPE_VIDEO,
        mappedTrackInfo,
        rendererFormatSupports,
        (int rendererIndex, TrackGroup group, @Capabilities int[] support) ->
            VideoTrackInfo.createForTrackGroup(
                rendererIndex,
                group,
                params,
                support,
                selectedAudioLanguage,
                mixedMimeTypeSupports[rendererIndex],
                viewportSizeFromDisplay),
        VideoTrackInfo::compareSelections);
  }

  // Audio track selection implementation.

  /**
   * Called by {@link #selectAllTracks(MappedTrackInfo, int[][][], int[], Parameters)} to create a
   * {@link ExoTrackSelection.Definition} for an audio track selection.
   *
   * @param mappedTrackInfo Mapped track information.
   * @param rendererFormatSupports The {@link Capabilities} for each mapped track, indexed by
   *     renderer, track group and track (in that order).
   * @param rendererMixedMimeTypeAdaptationSupports The {@link AdaptiveSupport} for mixed MIME type
   *     adaptation for the renderer.
   * @param params The selector's current constraint parameters.
   * @return A pair of the selected {@link ExoTrackSelection.Definition} and the corresponding
   *     renderer index, or null if no selection was made.
   * @throws ExoPlaybackException If an error occurs while selecting the tracks.
   */
  @Nullable
  protected Pair<ExoTrackSelection.Definition, Integer> selectAudioTrack(
      MappedTrackInfo mappedTrackInfo,
      @Capabilities int[][][] rendererFormatSupports,
      @AdaptiveSupport int[] rendererMixedMimeTypeAdaptationSupports,
      Parameters params)
      throws ExoPlaybackException {
    boolean hasVideoRendererWithMappedTracks = false;
    for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
      if (C.TRACK_TYPE_VIDEO == mappedTrackInfo.getRendererType(i)
          && mappedTrackInfo.getTrackGroups(i).length > 0) {
        hasVideoRendererWithMappedTracks = true;
        break;
      }
    }
    boolean hasVideoRendererWithMappedTracksFinal = hasVideoRendererWithMappedTracks;
    return selectTracksForType(
        C.TRACK_TYPE_AUDIO,
        mappedTrackInfo,
        rendererFormatSupports,
        (int rendererIndex, TrackGroup group, @Capabilities int[] support) ->
            AudioTrackInfo.createForTrackGroup(
                rendererIndex,
                group,
                params,
                support,
                hasVideoRendererWithMappedTracksFinal,
                format -> isAudioFormatWithinAudioChannelCountConstraints(format, params),
                rendererMixedMimeTypeAdaptationSupports[rendererIndex]),
        AudioTrackInfo::compareSelections);
  }

  /**
   * Returns whether an audio format is within the audio channel count constraints.
   *
   * <p>This method returns {@code true} if one of the following holds:
   *
   * <ul>
   *   <li>Audio channel count constraints are not applicable (all formats are considered within
   *       constraints).
   *   <li>The device has a <a
   *       href="https://developer.android.com/guide/topics/resources/providing-resources#UiModeQualifier">{@code
   *       television} UI mode</a>.
   *   <li>{@code format} has up to 2 channels.
   *   <li>The device does not support audio spatialization and the format is {@linkplain
   *       #isDolbyAudio(Format) a Dolby one}.
   *   <li>Audio spatialization is applicable and {@code format} can be spatialized.
   * </ul>
   */
  private boolean isAudioFormatWithinAudioChannelCountConstraints(
      Format format, Parameters parameters) {
    return !parameters.constrainAudioChannelCountToDeviceCapabilities
        || (deviceIsTV != null && deviceIsTV)
        || (format.channelCount == Format.NO_VALUE || format.channelCount <= 2)
        || (isDolbyAudio(format)
            && (SDK_INT < 32 || spatializer == null || !spatializer.isSpatializationSupported()))
        || (SDK_INT >= 32
            && spatializer != null
            && spatializer.isSpatializationSupported()
            && spatializer.isAvailable()
            && spatializer.isEnabled()
            && spatializer.canBeSpatialized(audioAttributes, format));
  }

  // Text track selection implementation.

  /**
   * Called by {@link #selectAllTracks(MappedTrackInfo, int[][][], int[], Parameters)} to create a
   * {@link ExoTrackSelection.Definition} for a text track selection.
   *
   * @param mappedTrackInfo Mapped track information.
   * @param rendererFormatSupports The {@link Capabilities} for each mapped track, indexed by
   *     renderer, track group and track (in that order).
   * @param params The selector's current constraint parameters.
   * @param selectedAudioLanguage The language of the selected audio track. May be null if the
   *     selected audio track declares no language or no audio track was selected.
   * @return A pair of the selected {@link ExoTrackSelection.Definition} and the corresponding
   *     renderer index, or null if no selection was made.
   * @throws ExoPlaybackException If an error occurs while selecting the tracks.
   */
  @Nullable
  protected Pair<ExoTrackSelection.Definition, Integer> selectTextTrack(
      MappedTrackInfo mappedTrackInfo,
      @Capabilities int[][][] rendererFormatSupports,
      Parameters params,
      @Nullable String selectedAudioLanguage)
      throws ExoPlaybackException {
    if (params.audioOffloadPreferences.audioOffloadMode == AUDIO_OFFLOAD_MODE_REQUIRED) {
      return null;
    }
    @Nullable
    String preferredCaptioningLanguage =
        params.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager
            ? getPreferredLanguageFromCaptioningManager(context)
            : null;
    return selectTracksForType(
        C.TRACK_TYPE_TEXT,
        mappedTrackInfo,
        rendererFormatSupports,
        (int rendererIndex, TrackGroup group, @Capabilities int[] support) ->
            TextTrackInfo.createForTrackGroup(
                rendererIndex,
                group,
                params,
                support,
                selectedAudioLanguage,
                preferredCaptioningLanguage),
        TextTrackInfo::compareSelections);
  }

  // Image track selection implementation.

  /**
   * Called by {@link #selectAllTracks(MappedTrackInfo, int[][][], int[], Parameters)} to create a
   * {@link ExoTrackSelection.Definition} for an image track selection.
   *
   * @param mappedTrackInfo Mapped track information.
   * @param rendererFormatSupports The {@link Capabilities} for each mapped track, indexed by
   *     renderer, track group and track (in that order).
   * @param params The selector's current constraint parameters.
   * @return A pair of the selected {@link ExoTrackSelection.Definition} and the corresponding
   *     renderer index, or null if no selection was made.
   * @throws ExoPlaybackException If an error occurs while selecting the tracks.
   */
  @Nullable
  protected Pair<ExoTrackSelection.Definition, Integer> selectImageTrack(
      MappedTrackInfo mappedTrackInfo,
      @Capabilities int[][][] rendererFormatSupports,
      Parameters params)
      throws ExoPlaybackException {
    if (params.audioOffloadPreferences.audioOffloadMode == AUDIO_OFFLOAD_MODE_REQUIRED) {
      return null;
    }
    return selectTracksForType(
        C.TRACK_TYPE_IMAGE,
        mappedTrackInfo,
        rendererFormatSupports,
        (int rendererIndex, TrackGroup group, @Capabilities int[] support) ->
            ImageTrackInfo.createForTrackGroup(rendererIndex, group, params, support),
        ImageTrackInfo::compareSelections);
  }

  // Generic track selection methods.

  /**
   * Called by {@link #selectAllTracks(MappedTrackInfo, int[][][], int[], Parameters)} to create a
   * {@link ExoTrackSelection} for a renderer whose type is neither video, audio or text.
   *
   * @param trackType The type of the renderer.
   * @param groups The {@link TrackGroupArray} mapped to the renderer.
   * @param formatSupport The {@link Capabilities} for each mapped track, indexed by track group and
   *     track (in that order).
   * @param params The selector's current constraint parameters.
   * @return The {@link ExoTrackSelection} for the renderer, or null if no selection was made.
   * @throws ExoPlaybackException If an error occurs while selecting the tracks.
   */
  @Nullable
  protected ExoTrackSelection.Definition selectOtherTrack(
      int trackType, TrackGroupArray groups, @Capabilities int[][] formatSupport, Parameters params)
      throws ExoPlaybackException {
    if (params.audioOffloadPreferences.audioOffloadMode == AUDIO_OFFLOAD_MODE_REQUIRED) {
      return null;
    }
    @Nullable TrackGroup selectedGroup = null;
    int selectedTrackIndex = 0;
    @Nullable OtherTrackScore selectedTrackScore = null;
    for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
      TrackGroup trackGroup = groups.get(groupIndex);
      @Capabilities int[] trackFormatSupport = formatSupport[groupIndex];
      for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
        if (isFormatSupported(
            trackFormatSupport[trackIndex], params.exceedRendererCapabilitiesIfNecessary)) {
          Format format = trackGroup.getFormat(trackIndex);
          OtherTrackScore trackScore = new OtherTrackScore(format, trackFormatSupport[trackIndex]);
          if (selectedTrackScore == null || trackScore.compareTo(selectedTrackScore) > 0) {
            selectedGroup = trackGroup;
            selectedTrackIndex = trackIndex;
            selectedTrackScore = trackScore;
          }
        }
      }
    }
    return selectedGroup == null
        ? null
        : new ExoTrackSelection.Definition(selectedGroup, selectedTrackIndex);
  }

  @Nullable
  private <T extends TrackInfo<T>> Pair<ExoTrackSelection.Definition, Integer> selectTracksForType(
      @C.TrackType int trackType,
      MappedTrackInfo mappedTrackInfo,
      @Capabilities int[][][] formatSupport,
      TrackInfo.Factory<T> trackInfoFactory,
      Comparator<List<T>> selectionComparator) {
    ArrayList<List<T>> possibleSelections = new ArrayList<>();
    int rendererCount = mappedTrackInfo.getRendererCount();
    for (int rendererIndex = 0; rendererIndex < rendererCount; rendererIndex++) {
      if (trackType == mappedTrackInfo.getRendererType(rendererIndex)) {
        TrackGroupArray groups = mappedTrackInfo.getTrackGroups(rendererIndex);
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
          TrackGroup trackGroup = groups.get(groupIndex);
          @Capabilities int[] groupSupport = formatSupport[rendererIndex][groupIndex];
          List<T> trackInfos = trackInfoFactory.create(rendererIndex, trackGroup, groupSupport);
          boolean[] usedTrackInSelection = new boolean[trackGroup.length];
          for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
            T trackInfo = trackInfos.get(trackIndex);
            @SelectionEligibility int eligibility = trackInfo.getSelectionEligibility();
            if (usedTrackInSelection[trackIndex] || eligibility == SELECTION_ELIGIBILITY_NO) {
              continue;
            }
            List<T> selection;
            if (eligibility == SELECTION_ELIGIBILITY_FIXED) {
              selection = ImmutableList.of(trackInfo);
            } else {
              selection = new ArrayList<>();
              selection.add(trackInfo);
              for (int i = trackIndex + 1; i < trackGroup.length; i++) {
                T otherTrackInfo = trackInfos.get(i);
                if (otherTrackInfo.getSelectionEligibility() == SELECTION_ELIGIBILITY_ADAPTIVE) {
                  if (trackInfo.isCompatibleForAdaptationWith(otherTrackInfo)) {
                    selection.add(otherTrackInfo);
                    usedTrackInSelection[i] = true;
                  }
                }
              }
            }
            possibleSelections.add(selection);
          }
        }
      }
    }
    if (possibleSelections.isEmpty()) {
      return null;
    }
    List<T> bestSelection = max(possibleSelections, selectionComparator);
    int[] trackIndices = new int[bestSelection.size()];
    for (int i = 0; i < bestSelection.size(); i++) {
      trackIndices[i] = bestSelection.get(i).trackIndex;
    }
    T firstTrackInfo = bestSelection.get(0);
    return Pair.create(
        new ExoTrackSelection.Definition(firstTrackInfo.trackGroup, trackIndices),
        firstTrackInfo.rendererIndex);
  }

  private void maybeInvalidateForAudioChannelCountConstraints() {
    boolean shouldInvalidate;
    synchronized (lock) {
      shouldInvalidate =
          parameters.constrainAudioChannelCountToDeviceCapabilities
              && SDK_INT >= 32
              && spatializer != null
              && spatializer.isSpatializationSupported();
    }
    if (shouldInvalidate) {
      invalidate();
    }
  }

  private void maybeInvalidateForRendererCapabilitiesChange(Renderer renderer) {
    boolean shouldInvalidate;
    synchronized (lock) {
      shouldInvalidate = parameters.allowInvalidateSelectionsOnRendererCapabilitiesChange;
    }
    if (shouldInvalidate) {
      invalidateForRendererCapabilitiesChange(renderer);
    }
  }

  // Utility methods.

  private static void applyTrackSelectionOverrides(
      MappedTrackInfo mappedTrackInfo,
      TrackSelectionParameters params,
      ExoTrackSelection.@NullableType Definition[] outDefinitions) {
    int rendererCount = mappedTrackInfo.getRendererCount();

    // Determine overrides to apply.
    HashMap<@C.TrackType Integer, TrackSelectionOverride> overridesByType = new HashMap<>();
    for (int rendererIndex = 0; rendererIndex < rendererCount; rendererIndex++) {
      collectTrackSelectionOverrides(
          mappedTrackInfo.getTrackGroups(rendererIndex), params, overridesByType);
    }
    collectTrackSelectionOverrides(
        mappedTrackInfo.getUnmappedTrackGroups(), params, overridesByType);

    // Apply the overrides.
    for (int rendererIndex = 0; rendererIndex < rendererCount; rendererIndex++) {
      @C.TrackType int trackType = mappedTrackInfo.getRendererType(rendererIndex);
      @Nullable TrackSelectionOverride overrideForType = overridesByType.get(trackType);
      if (overrideForType == null) {
        continue;
      }
      // If the override is non-empty and applies to this renderer, then apply it. Else we don't
      // want the renderer to be enabled at all, so clear any existing selection.
      @Nullable ExoTrackSelection.Definition selection;
      if (!overrideForType.trackIndices.isEmpty()
          && mappedTrackInfo.getTrackGroups(rendererIndex).indexOf(overrideForType.mediaTrackGroup)
              != -1) {
        selection =
            new ExoTrackSelection.Definition(
                overrideForType.mediaTrackGroup, Ints.toArray(overrideForType.trackIndices));
      } else {
        selection = null;
      }
      outDefinitions[rendererIndex] = selection;
    }
  }

  /**
   * Adds {@link TrackSelectionOverride TrackSelectionOverrides} in {@code params} to {@code
   * overridesByType} if they apply to tracks in {@code trackGroups}. If there's an existing
   * override for a track type, it is replaced only if the existing override is empty and the one
   * being considered is not.
   */
  private static void collectTrackSelectionOverrides(
      TrackGroupArray trackGroups,
      TrackSelectionParameters params,
      Map<@C.TrackType Integer, TrackSelectionOverride> overridesByType) {
    for (int trackGroupIndex = 0; trackGroupIndex < trackGroups.length; trackGroupIndex++) {
      TrackGroup trackGroup = trackGroups.get(trackGroupIndex);
      @Nullable TrackSelectionOverride override = params.overrides.get(trackGroup);
      if (override == null) {
        continue;
      }
      @Nullable TrackSelectionOverride existingOverride = overridesByType.get(override.getType());
      // Only replace an existing override if it's empty and the one being considered is not.
      if (existingOverride == null
          || (existingOverride.trackIndices.isEmpty() && !override.trackIndices.isEmpty())) {
        overridesByType.put(override.getType(), override);
      }
    }
  }

  @SuppressWarnings("deprecation") // Calling legacy hasSelectionOverride and getSelectionOverride
  private static void applyLegacyRendererOverrides(
      MappedTrackInfo mappedTrackInfo,
      Parameters params,
      ExoTrackSelection.@NullableType Definition[] outDefinitions) {
    int rendererCount = mappedTrackInfo.getRendererCount();
    for (int rendererIndex = 0; rendererIndex < rendererCount; rendererIndex++) {
      TrackGroupArray trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex);
      if (!params.hasSelectionOverride(rendererIndex, trackGroups)) {
        continue;
      }
      @Nullable
      SelectionOverride override = params.getSelectionOverride(rendererIndex, trackGroups);
      @Nullable ExoTrackSelection.Definition selection;
      if (override != null && override.tracks.length != 0) {
        selection =
            new ExoTrackSelection.Definition(
                trackGroups.get(override.groupIndex), override.tracks, override.type);
      } else {
        selection = null;
      }
      outDefinitions[rendererIndex] = selection;
    }
  }

  /**
   * Determines whether tunneling can be enabled, replacing {@link RendererConfiguration}s in {@code
   * rendererConfigurations} with configurations that enable tunneling on the appropriate renderers
   * if so.
   *
   * @param mappedTrackInfo Mapped track information.
   * @param rendererFormatSupports The {@link Capabilities} for each mapped track, indexed by
   *     renderer, track group and track (in that order).
   * @param rendererConfigurations The renderer configurations. Configurations may be replaced with
   *     ones that enable tunneling as a result of this call.
   * @param trackSelections The renderer track selections.
   */
  private static void maybeConfigureRenderersForTunneling(
      MappedTrackInfo mappedTrackInfo,
      @Capabilities int[][][] rendererFormatSupports,
      @NullableType RendererConfiguration[] rendererConfigurations,
      @NullableType ExoTrackSelection[] trackSelections) {
    // Check whether we can enable tunneling. To enable tunneling we require exactly one audio and
    // one video renderer to support tunneling and have a selection.
    int tunnelingAudioRendererIndex = -1;
    int tunnelingVideoRendererIndex = -1;
    boolean enableTunneling = true;
    for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
      int rendererType = mappedTrackInfo.getRendererType(i);
      ExoTrackSelection trackSelection = trackSelections[i];
      if ((rendererType == C.TRACK_TYPE_AUDIO || rendererType == C.TRACK_TYPE_VIDEO)
          && trackSelection != null) {
        if (rendererSupportsTunneling(
            rendererFormatSupports[i], mappedTrackInfo.getTrackGroups(i), trackSelection)) {
          if (rendererType == C.TRACK_TYPE_AUDIO) {
            if (tunnelingAudioRendererIndex != -1) {
              enableTunneling = false;
              break;
            } else {
              tunnelingAudioRendererIndex = i;
            }
          } else {
            if (tunnelingVideoRendererIndex != -1) {
              enableTunneling = false;
              break;
            } else {
              tunnelingVideoRendererIndex = i;
            }
          }
        }
      }
    }
    enableTunneling &= tunnelingAudioRendererIndex != -1 && tunnelingVideoRendererIndex != -1;
    if (enableTunneling) {
      RendererConfiguration tunnelingRendererConfiguration =
          new RendererConfiguration(AudioSink.OFFLOAD_MODE_DISABLED, /* tunneling= */ true);
      rendererConfigurations[tunnelingAudioRendererIndex] = tunnelingRendererConfiguration;
      rendererConfigurations[tunnelingVideoRendererIndex] = tunnelingRendererConfiguration;
    }
  }

  /**
   * Returns whether a renderer supports tunneling for a {@link ExoTrackSelection}.
   *
   * @param formatSupport The {@link Capabilities} for each track, indexed by group index and track
   *     index (in that order).
   * @param trackGroups The {@link TrackGroupArray}s for the renderer.
   * @param selection The track selection.
   * @return Whether the renderer supports tunneling for the {@link ExoTrackSelection}.
   */
  private static boolean rendererSupportsTunneling(
      @Capabilities int[][] formatSupport,
      TrackGroupArray trackGroups,
      ExoTrackSelection selection) {
    if (selection == null) {
      return false;
    }
    int trackGroupIndex = trackGroups.indexOf(selection.getTrackGroup());
    for (int i = 0; i < selection.length(); i++) {
      @Capabilities
      int trackFormatSupport = formatSupport[trackGroupIndex][selection.getIndexInTrackGroup(i)];
      if (RendererCapabilities.getTunnelingSupport(trackFormatSupport)
          != RendererCapabilities.TUNNELING_SUPPORTED) {
        return false;
      }
    }
    return true;
  }

  /**
   * Determines whether audio offload can be enabled, replacing a {@link RendererConfiguration} in
   * {@code rendererConfigurations} with one that enables audio offload on the appropriate renderer.
   *
   * @param parameters The selection parameters with audio offload mode preferences.
   * @param mappedTrackInfo Mapped track information.
   * @param rendererFormatSupports The {@link Capabilities} for each mapped track, indexed by
   *     renderer, track group and track (in that order).
   * @param rendererConfigurations The renderer configurations. A configuration may be replaced with
   *     one that enables audio offload as a result of this call.
   * @param trackSelections The renderer track selections.
   */
  private static void maybeConfigureRendererForOffload(
      Parameters parameters,
      MappedTrackInfo mappedTrackInfo,
      @Capabilities int[][][] rendererFormatSupports,
      @NullableType RendererConfiguration[] rendererConfigurations,
      @NullableType ExoTrackSelection[] trackSelections) {
    // Check whether we can enable offload. To enable offload we require exactly one audio track
    // and a renderer with support matching the requirements set by
    // setAudioOffloadPreference. There also cannot be other non-audio renderers with their own
    // selected tracks unless the selection parameters say audio-only is not required.
    int audioRendererIndex = C.INDEX_UNSET;
    int audioRenderersSupportingOffload = 0;
    boolean hasNonAudioRendererWithSelectedTracks = false;
    for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
      int rendererType = mappedTrackInfo.getRendererType(i);
      ExoTrackSelection trackSelection = trackSelections[i];
      if (rendererType != C.TRACK_TYPE_AUDIO && trackSelection != null) {
        hasNonAudioRendererWithSelectedTracks = true;
        break;
      }
      if (rendererType == C.TRACK_TYPE_AUDIO
          && trackSelection != null
          && trackSelection.length() == 1) {
        int trackGroupIndex =
            mappedTrackInfo.getTrackGroups(i).indexOf(trackSelection.getTrackGroup());
        @Capabilities
        int trackFormatSupport =
            rendererFormatSupports[i][trackGroupIndex][trackSelection.getIndexInTrackGroup(0)];
        if (rendererSupportsOffload(
            parameters, trackFormatSupport, trackSelection.getSelectedFormat())) {
          audioRendererIndex = i;
          audioRenderersSupportingOffload++;
        }
      }
    }
    if (!hasNonAudioRendererWithSelectedTracks && audioRenderersSupportingOffload == 1) {
      RendererConfiguration offloadRendererConfiguration =
          new RendererConfiguration(
              parameters.audioOffloadPreferences.isGaplessSupportRequired
                  ? AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED
                  : AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED,
              /* tunneling= */ rendererConfigurations[audioRendererIndex] != null
                  && rendererConfigurations[audioRendererIndex].tunneling);
      rendererConfigurations[audioRendererIndex] = offloadRendererConfiguration;
    }
  }

  /**
   * Returns whether a renderer supports offload for a {@link ExoTrackSelection}.
   *
   * @param parameters The selection parameters with audio offload mode preferences.
   * @param formatSupport The {@link Capabilities} for the selected track.
   * @param format The format of the track selection.
   * @return Whether the renderer supports tunneling for the {@link ExoTrackSelection}.
   */
  private static boolean rendererSupportsOffload(
      Parameters parameters, @Capabilities int formatSupport, Format format) {
    if (RendererCapabilities.getAudioOffloadSupport(formatSupport) == AUDIO_OFFLOAD_NOT_SUPPORTED) {
      return false;
    }
    if (parameters.audioOffloadPreferences.isSpeedChangeSupportRequired
        && (RendererCapabilities.getAudioOffloadSupport(formatSupport)
                & AUDIO_OFFLOAD_SPEED_CHANGE_SUPPORTED)
            == 0) {
      return false;
    }
    // TODO(b/235883373): Add check for OPUS where gapless info is in initialization data
    if (parameters.audioOffloadPreferences.isGaplessSupportRequired) {
      boolean isGapless = format.encoderDelay != 0 || format.encoderPadding != 0;
      boolean isGaplessSupported =
          (RendererCapabilities.getAudioOffloadSupport(formatSupport)
                  & AUDIO_OFFLOAD_GAPLESS_SUPPORTED)
              != 0;
      return !isGapless || isGaplessSupported;
    }
    return true;
  }

  /**
   * @deprecated Use {@link RendererCapabilities#isFormatSupported} instead.
   */
  @Deprecated
  protected static boolean isSupported(
      @Capabilities int formatSupport, boolean allowExceedsCapabilities) {
    return isFormatSupported(formatSupport, allowExceedsCapabilities);
  }

  /**
   * Normalizes the input string to null if it does not define a language, or returns it otherwise.
   *
   * @param language The string.
   * @return The string, optionally normalized to null if it does not define a language.
   */
  @Nullable
  protected static String normalizeUndeterminedLanguageToNull(@Nullable String language) {
    return TextUtils.isEmpty(language) || TextUtils.equals(language, C.LANGUAGE_UNDETERMINED)
        ? null
        : language;
  }

  /**
   * Returns a score for how well a language specified in a {@link Format} matches a given language.
   *
   * @param format The {@link Format}.
   * @param language The language, or null.
   * @param allowUndeterminedFormatLanguage Whether matches with an empty or undetermined format
   *     language tag are allowed.
   * @return A score of 4 if the languages match fully, a score of 3 if the languages match partly,
   *     a score of 2 if the languages don't match but belong to the same main language, a score of
   *     1 if the format language is undetermined and such a match is allowed, and a score of 0 if
   *     the languages don't match at all.
   */
  protected static int getFormatLanguageScore(
      Format format, @Nullable String language, boolean allowUndeterminedFormatLanguage) {
    if (!TextUtils.isEmpty(language) && language.equals(format.language)) {
      // Full literal match of non-empty languages, including matches of an explicit "und" query.
      return 4;
    }
    language = normalizeUndeterminedLanguageToNull(language);
    String formatLanguage = normalizeUndeterminedLanguageToNull(format.language);
    if (formatLanguage == null || language == null) {
      // At least one of the languages is undetermined.
      return allowUndeterminedFormatLanguage && formatLanguage == null ? 1 : 0;
    }
    if (formatLanguage.startsWith(language) || language.startsWith(formatLanguage)) {
      // Partial match where one language is a subset of the other (e.g. "zh-hans" and "zh-hans-hk")
      return 3;
    }
    String formatMainLanguage = Util.splitAtFirst(formatLanguage, "-")[0];
    String queryMainLanguage = Util.splitAtFirst(language, "-")[0];
    if (formatMainLanguage.equals(queryMainLanguage)) {
      // Partial match where only the main language tag is the same (e.g. "fr-fr" and "fr-ca")
      return 2;
    }
    return 0;
  }

  private static int getMaxVideoPixelsToRetainForViewport(
      TrackGroup group, int viewportWidth, int viewportHeight, boolean orientationMayChange) {
    if (viewportWidth == Integer.MAX_VALUE || viewportHeight == Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    int maxVideoPixelsToRetain = Integer.MAX_VALUE;
    for (int i = 0; i < group.length; i++) {
      Format format = group.getFormat(i);
      // Keep track of the number of pixels of the selected format whose resolution is the
      // smallest to exceed the maximum size at which it can be displayed within the viewport.
      if (format.width > 0 && format.height > 0) {
        Point maxVideoSizeInViewport =
            TrackSelectionUtil.getMaxVideoSizeInViewport(
                orientationMayChange, viewportWidth, viewportHeight, format.width, format.height);
        int videoPixels = format.width * format.height;
        if (format.width >= (int) (maxVideoSizeInViewport.x * FRACTION_TO_CONSIDER_FULLSCREEN)
            && format.height >= (int) (maxVideoSizeInViewport.y * FRACTION_TO_CONSIDER_FULLSCREEN)
            && videoPixels < maxVideoPixelsToRetain) {
          maxVideoPixelsToRetain = videoPixels;
        }
      }
    }
    return maxVideoPixelsToRetain;
  }

  private static int getRoleFlagMatchScore(int trackRoleFlags, int preferredRoleFlags) {
    if (trackRoleFlags != 0 && trackRoleFlags == preferredRoleFlags) {
      // Prefer perfect match over partial matches.
      return Integer.MAX_VALUE;
    }
    return Integer.bitCount(trackRoleFlags & preferredRoleFlags);
  }

  /**
   * Returns preference score for primary, hardware-accelerated video codecs, with higher score
   * being preferred.
   */
  private static int getVideoCodecPreferenceScore(@Nullable String mimeType) {
    if (mimeType == null) {
      return 0;
    }
    switch (mimeType) {
      case MimeTypes.VIDEO_DOLBY_VISION:
        return 5;
      case MimeTypes.VIDEO_AV1:
        return 4;
      case MimeTypes.VIDEO_H265:
        return 3;
      case MimeTypes.VIDEO_VP9:
        return 2;
      case MimeTypes.VIDEO_H264:
        return 1;
      default:
        return 0;
    }
  }

  private static boolean isObjectBasedAudio(Format format) {
    if (format.sampleMimeType == null) {
      return false;
    }
    switch (format.sampleMimeType) {
      case MimeTypes.AUDIO_E_AC3_JOC:
      case MimeTypes.AUDIO_AC4:
      case MimeTypes.AUDIO_IAMF:
        return true;
      default:
        return false;
    }
  }

  private static boolean isDolbyAudio(Format format) {
    if (format.sampleMimeType == null) {
      return false;
    }
    switch (format.sampleMimeType) {
      case MimeTypes.AUDIO_AC3:
      case MimeTypes.AUDIO_E_AC3:
      case MimeTypes.AUDIO_E_AC3_JOC:
      case MimeTypes.AUDIO_AC4:
        return true;
      default:
        return false;
    }
  }

  @Nullable
  private static String getPreferredLanguageFromCaptioningManager(@Nullable Context context) {
    if (context == null) {
      return null;
    }
    CaptioningManager captioningManager =
        (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);
    if (captioningManager == null || !captioningManager.isEnabled()) {
      return null;
    }
    Locale preferredLocale = captioningManager.getLocale();
    if (preferredLocale == null) {
      return null;
    }
    return Util.getLocaleLanguageTag(preferredLocale);
  }

  /** Base class for track selection information of a {@link Format}. */
  private abstract static class TrackInfo<T extends TrackInfo<T>> {
    /** Factory for {@link TrackInfo} implementations for a given {@link TrackGroup}. */
    public interface Factory<T extends TrackInfo<T>> {
      List<T> create(int rendererIndex, TrackGroup trackGroup, @Capabilities int[] formatSupports);
    }

    public final int rendererIndex;
    public final TrackGroup trackGroup;
    public final int trackIndex;
    public final Format format;

    public TrackInfo(int rendererIndex, TrackGroup trackGroup, int trackIndex) {
      this.rendererIndex = rendererIndex;
      this.trackGroup = trackGroup;
      this.trackIndex = trackIndex;
      this.format = trackGroup.getFormat(trackIndex);
    }

    /** Returns to what extent the track is {@link SelectionEligibility eligible for selection}. */
    public abstract @SelectionEligibility int getSelectionEligibility();

    /**
     * Returns whether this track is compatible for an adaptive selection with the specified other
     * track.
     */
    public abstract boolean isCompatibleForAdaptationWith(T otherTrack);
  }

  private static final class VideoTrackInfo extends TrackInfo<VideoTrackInfo> {

    /**
     * Frame rate below which video playback will definitely not be considered smooth by the human
     * eye.
     */
    private static final float MIN_REASONABLE_FRAME_RATE = 10;

    public static ImmutableList<VideoTrackInfo> createForTrackGroup(
        int rendererIndex,
        TrackGroup trackGroup,
        Parameters params,
        @Capabilities int[] formatSupport,
        @Nullable String selectedAudioLanguage,
        @AdaptiveSupport int mixedMimeTypeAdaptationSupport,
        @Nullable Point viewportSizeFromDisplay) {
      int viewportWidth =
          viewportSizeFromDisplay != null ? viewportSizeFromDisplay.x : params.viewportWidth;
      int viewportHeight =
          viewportSizeFromDisplay != null ? viewportSizeFromDisplay.y : params.viewportHeight;
      int maxPixelsToRetainForViewport =
          getMaxVideoPixelsToRetainForViewport(
              trackGroup, viewportWidth, viewportHeight, params.viewportOrientationMayChange);
      ImmutableList.Builder<VideoTrackInfo> listBuilder = ImmutableList.builder();
      for (int i = 0; i < trackGroup.length; i++) {
        int pixelCount = trackGroup.getFormat(i).getPixelCount();
        boolean isSuitableForViewport =
            maxPixelsToRetainForViewport == Integer.MAX_VALUE
                || (pixelCount != Format.NO_VALUE && pixelCount <= maxPixelsToRetainForViewport);
        listBuilder.add(
            new VideoTrackInfo(
                rendererIndex,
                trackGroup,
                /* trackIndex= */ i,
                params,
                formatSupport[i],
                selectedAudioLanguage,
                mixedMimeTypeAdaptationSupport,
                isSuitableForViewport));
      }
      return listBuilder.build();
    }

    private final boolean isWithinMaxConstraints;
    private final Parameters parameters;
    private final boolean isWithinMinConstraints;
    private final boolean isWithinRendererCapabilities;

    /**
     * True if {@link Format#frameRate} is set and is at least {@link #MIN_REASONABLE_FRAME_RATE}.
     */
    private final boolean hasReasonableFrameRate;

    private final int bitrate;
    private final int pixelCount;
    private final int preferredMimeTypeMatchIndex;
    private final int preferredLanguageIndex;
    private final int preferredLanguageScore;
    private final int preferredRoleFlagsScore;
    private final boolean hasMainOrNoRoleFlag;
    private final int selectedAudioLanguageScore;
    private final boolean allowMixedMimeTypes;
    private final @SelectionEligibility int selectionEligibility;
    private final boolean usesPrimaryDecoder;
    private final boolean usesHardwareAcceleration;
    private final int codecPreferenceScore;

    public VideoTrackInfo(
        int rendererIndex,
        TrackGroup trackGroup,
        int trackIndex,
        Parameters parameters,
        @Capabilities int formatSupport,
        @Nullable String selectedAudioLanguage,
        @AdaptiveSupport int mixedMimeTypeAdaptationSupport,
        boolean isSuitableForViewport) {
      super(rendererIndex, trackGroup, trackIndex);
      this.parameters = parameters;
      @SuppressLint("WrongConstant")
      int requiredAdaptiveSupport =
          parameters.allowVideoNonSeamlessAdaptiveness
              ? (RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
                  | RendererCapabilities.ADAPTIVE_SEAMLESS)
              : RendererCapabilities.ADAPTIVE_SEAMLESS;
      allowMixedMimeTypes =
          parameters.allowVideoMixedMimeTypeAdaptiveness
              && (mixedMimeTypeAdaptationSupport & requiredAdaptiveSupport) != 0;
      isWithinMaxConstraints =
          isSuitableForViewport
              && (format.width == Format.NO_VALUE || format.width <= parameters.maxVideoWidth)
              && (format.height == Format.NO_VALUE || format.height <= parameters.maxVideoHeight)
              && (format.frameRate == Format.NO_VALUE
                  || format.frameRate <= parameters.maxVideoFrameRate)
              && (format.bitrate == Format.NO_VALUE
                  || format.bitrate <= parameters.maxVideoBitrate);
      isWithinMinConstraints =
          isSuitableForViewport
              && (format.width == Format.NO_VALUE || format.width >= parameters.minVideoWidth)
              && (format.height == Format.NO_VALUE || format.height >= parameters.minVideoHeight)
              && (format.frameRate == Format.NO_VALUE
                  || format.frameRate >= parameters.minVideoFrameRate)
              && (format.bitrate == Format.NO_VALUE
                  || format.bitrate >= parameters.minVideoBitrate);
      isWithinRendererCapabilities =
          isFormatSupported(formatSupport, /* allowExceedsCapabilities= */ false);
      hasReasonableFrameRate =
          format.frameRate != Format.NO_VALUE && format.frameRate >= MIN_REASONABLE_FRAME_RATE;
      bitrate = format.bitrate;
      pixelCount = format.getPixelCount();
      int bestLanguageIndex = Integer.MAX_VALUE;
      int bestLanguageScore = 0;
      for (int i = 0; i < parameters.preferredVideoLanguages.size(); i++) {
        int score =
            getFormatLanguageScore(
                format,
                parameters.preferredVideoLanguages.get(i),
                /* allowUndeterminedFormatLanguage= */ false);
        if (score > 0) {
          bestLanguageIndex = i;
          bestLanguageScore = score;
          break;
        }
      }
      preferredLanguageIndex = bestLanguageIndex;
      preferredLanguageScore = bestLanguageScore;
      preferredRoleFlagsScore =
          getRoleFlagMatchScore(format.roleFlags, parameters.preferredVideoRoleFlags);
      hasMainOrNoRoleFlag = format.roleFlags == 0 || (format.roleFlags & C.ROLE_FLAG_MAIN) != 0;
      boolean selectedAudioLanguageUndetermined =
          normalizeUndeterminedLanguageToNull(selectedAudioLanguage) == null;
      selectedAudioLanguageScore =
          getFormatLanguageScore(format, selectedAudioLanguage, selectedAudioLanguageUndetermined);
      int bestMimeTypeMatchIndex = Integer.MAX_VALUE;
      for (int i = 0; i < parameters.preferredVideoMimeTypes.size(); i++) {
        if (format.sampleMimeType != null
            && format.sampleMimeType.equals(parameters.preferredVideoMimeTypes.get(i))) {
          bestMimeTypeMatchIndex = i;
          break;
        }
      }
      preferredMimeTypeMatchIndex = bestMimeTypeMatchIndex;
      usesPrimaryDecoder =
          RendererCapabilities.getDecoderSupport(formatSupport)
              == RendererCapabilities.DECODER_SUPPORT_PRIMARY;
      usesHardwareAcceleration =
          RendererCapabilities.getHardwareAccelerationSupport(formatSupport)
              == RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED;
      codecPreferenceScore = getVideoCodecPreferenceScore(format.sampleMimeType);
      selectionEligibility = evaluateSelectionEligibility(formatSupport, requiredAdaptiveSupport);
    }

    @Override
    public @SelectionEligibility int getSelectionEligibility() {
      return selectionEligibility;
    }

    @Override
    public boolean isCompatibleForAdaptationWith(VideoTrackInfo otherTrack) {
      return (allowMixedMimeTypes
              || Objects.equals(format.sampleMimeType, otherTrack.format.sampleMimeType))
          && (parameters.allowVideoMixedDecoderSupportAdaptiveness
              || (this.usesPrimaryDecoder == otherTrack.usesPrimaryDecoder
                  && this.usesHardwareAcceleration == otherTrack.usesHardwareAcceleration));
    }

    private @SelectionEligibility int evaluateSelectionEligibility(
        @Capabilities int rendererSupport, @AdaptiveSupport int requiredAdaptiveSupport) {
      if ((format.roleFlags & C.ROLE_FLAG_TRICK_PLAY) != 0) {
        // Ignore trick-play tracks for now.
        return SELECTION_ELIGIBILITY_NO;
      }
      if (!isFormatSupported(rendererSupport, parameters.exceedRendererCapabilitiesIfNecessary)) {
        return SELECTION_ELIGIBILITY_NO;
      }
      if (!isWithinMaxConstraints && !parameters.exceedVideoConstraintsIfNecessary) {
        return SELECTION_ELIGIBILITY_NO;
      }
      return isFormatSupported(rendererSupport, /* allowExceedsCapabilities= */ false)
              && isWithinMinConstraints
              && isWithinMaxConstraints
              && format.bitrate != Format.NO_VALUE
              && !parameters.forceHighestSupportedBitrate
              && !parameters.forceLowestBitrate
              && (rendererSupport & requiredAdaptiveSupport) != 0
          ? SELECTION_ELIGIBILITY_ADAPTIVE
          : SELECTION_ELIGIBILITY_FIXED;
    }

    private static int compareNonQualityPreferences(VideoTrackInfo info1, VideoTrackInfo info2) {
      ComparisonChain chain =
          ComparisonChain.start()
              .compareFalseFirst(
                  info1.isWithinRendererCapabilities, info2.isWithinRendererCapabilities)
              // 1. Compare match with specific content preferences set by the parameters.
              .compare(
                  info1.preferredLanguageIndex,
                  info2.preferredLanguageIndex,
                  Ordering.natural().reverse())
              .compare(info1.preferredLanguageScore, info2.preferredLanguageScore)
              .compare(info1.preferredRoleFlagsScore, info2.preferredRoleFlagsScore)
              // 2. Compare match with implicit content preferences set by the media.
              .compareFalseFirst(info1.hasMainOrNoRoleFlag, info2.hasMainOrNoRoleFlag)
              .compare(info1.selectedAudioLanguageScore, info2.selectedAudioLanguageScore)
              // 3. Compare match with 'reasonable' frame rate threshold.
              .compareFalseFirst(info1.hasReasonableFrameRate, info2.hasReasonableFrameRate)
              // 4. Compare match with technical preferences set by the parameters.
              .compareFalseFirst(info1.isWithinMaxConstraints, info2.isWithinMaxConstraints)
              .compareFalseFirst(info1.isWithinMinConstraints, info2.isWithinMinConstraints)
              .compare(
                  info1.preferredMimeTypeMatchIndex,
                  info2.preferredMimeTypeMatchIndex,
                  Ordering.natural().reverse())
              // 5. Compare match with renderer capability preferences.
              .compareFalseFirst(info1.usesPrimaryDecoder, info2.usesPrimaryDecoder)
              .compareFalseFirst(info1.usesHardwareAcceleration, info2.usesHardwareAcceleration);

      if (info1.usesPrimaryDecoder && info1.usesHardwareAcceleration) {
        chain = chain.compare(info1.codecPreferenceScore, info2.codecPreferenceScore);
      }
      return chain.result();
    }

    private static int compareQualityPreferences(VideoTrackInfo info1, VideoTrackInfo info2) {
      // The preferred ordering by video quality depends on the constraints:
      // - Not within renderer capabilities: Prefer lower quality because it's more likely to play.
      // - Within min and max constraints: Prefer higher quality.
      // - Within max constraints only: Prefer higher quality because it gets us closest to
      //   satisfying the violated min constraints.
      // - Within min constraints only: Prefer lower quality because it gets us closest to
      //   satisfying the violated max constraints.
      // - Outside min and max constraints: Arbitrarily prefer lower quality.
      Ordering<Integer> qualityOrdering =
          info1.isWithinMaxConstraints && info1.isWithinRendererCapabilities
              ? FORMAT_VALUE_ORDERING
              : FORMAT_VALUE_ORDERING.reverse();
      ComparisonChain comparisonChain = ComparisonChain.start();
      if (info1.parameters.forceLowestBitrate) {
        comparisonChain =
            comparisonChain.compare(info1.bitrate, info2.bitrate, FORMAT_VALUE_ORDERING.reverse());
      }
      return comparisonChain
          .compare(info1.pixelCount, info2.pixelCount, qualityOrdering)
          .compare(info1.bitrate, info2.bitrate, qualityOrdering)
          .result();
    }

    public static int compareSelections(List<VideoTrackInfo> infos1, List<VideoTrackInfo> infos2) {
      return ComparisonChain.start()
          // Compare non-quality preferences of the best individual track with each other.
          .compare(
              max(infos1, VideoTrackInfo::compareNonQualityPreferences),
              max(infos2, VideoTrackInfo::compareNonQualityPreferences),
              VideoTrackInfo::compareNonQualityPreferences)
          // Prefer selections with more formats (all non-quality preferences being equal).
          .compare(infos1.size(), infos2.size())
          // Prefer selections with the best individual track quality.
          .compare(
              max(infos1, VideoTrackInfo::compareQualityPreferences),
              max(infos2, VideoTrackInfo::compareQualityPreferences),
              VideoTrackInfo::compareQualityPreferences)
          .result();
    }
  }

  private static final class AudioTrackInfo extends TrackInfo<AudioTrackInfo>
      implements Comparable<AudioTrackInfo> {

    public static ImmutableList<AudioTrackInfo> createForTrackGroup(
        int rendererIndex,
        TrackGroup trackGroup,
        Parameters params,
        @Capabilities int[] formatSupport,
        boolean hasMappedVideoTracks,
        Predicate<Format> withinAudioChannelCountConstraints,
        @AdaptiveSupport int mixedMimeTypeAdaptationSupport) {
      ImmutableList.Builder<AudioTrackInfo> listBuilder = ImmutableList.builder();
      for (int i = 0; i < trackGroup.length; i++) {
        listBuilder.add(
            new AudioTrackInfo(
                rendererIndex,
                trackGroup,
                /* trackIndex= */ i,
                params,
                formatSupport[i],
                hasMappedVideoTracks,
                withinAudioChannelCountConstraints,
                mixedMimeTypeAdaptationSupport));
      }
      return listBuilder.build();
    }

    private final @SelectionEligibility int selectionEligibility;
    private final boolean isWithinConstraints;
    @Nullable private final String language;
    private final Parameters parameters;
    private final boolean isWithinRendererCapabilities;
    private final int preferredLanguageScore;
    private final int preferredLanguageIndex;
    private final int preferredRoleFlagsScore;
    private final boolean allowMixedMimeTypes;
    private final boolean hasMainOrNoRoleFlag;
    private final int localeLanguageMatchIndex;
    private final int localeLanguageScore;
    private final boolean isDefaultSelectionFlag;
    private final int channelCount;
    private final int sampleRate;
    private final int bitrate;
    private final int preferredMimeTypeMatchIndex;
    private final boolean usesPrimaryDecoder;
    private final boolean usesHardwareAcceleration;
    private final boolean isObjectBasedAudio;

    public AudioTrackInfo(
        int rendererIndex,
        TrackGroup trackGroup,
        int trackIndex,
        Parameters parameters,
        @Capabilities int formatSupport,
        boolean hasMappedVideoTracks,
        Predicate<Format> withinAudioChannelCountConstraints,
        @AdaptiveSupport int mixedMimeTypeAdaptationSupport) {
      super(rendererIndex, trackGroup, trackIndex);
      this.parameters = parameters;
      @SuppressLint("WrongConstant")
      int requiredAdaptiveSupport =
          parameters.allowAudioNonSeamlessAdaptiveness
              ? (RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
                  | RendererCapabilities.ADAPTIVE_SEAMLESS)
              : RendererCapabilities.ADAPTIVE_SEAMLESS;
      allowMixedMimeTypes =
          parameters.allowAudioMixedMimeTypeAdaptiveness
              && (mixedMimeTypeAdaptationSupport & requiredAdaptiveSupport) != 0;
      this.language = normalizeUndeterminedLanguageToNull(format.language);
      isWithinRendererCapabilities =
          isFormatSupported(formatSupport, /* allowExceedsCapabilities= */ false);
      int bestLanguageScore = 0;
      int bestLanguageIndex = Integer.MAX_VALUE;
      for (int i = 0; i < parameters.preferredAudioLanguages.size(); i++) {
        int score =
            getFormatLanguageScore(
                format,
                parameters.preferredAudioLanguages.get(i),
                /* allowUndeterminedFormatLanguage= */ false);
        if (score > 0) {
          bestLanguageIndex = i;
          bestLanguageScore = score;
          break;
        }
      }
      preferredLanguageIndex = bestLanguageIndex;
      preferredLanguageScore = bestLanguageScore;
      preferredRoleFlagsScore =
          getRoleFlagMatchScore(format.roleFlags, parameters.preferredAudioRoleFlags);
      hasMainOrNoRoleFlag = format.roleFlags == 0 || (format.roleFlags & C.ROLE_FLAG_MAIN) != 0;
      isDefaultSelectionFlag = (format.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0;
      isObjectBasedAudio = isObjectBasedAudio(format);
      channelCount = format.channelCount;
      sampleRate = format.sampleRate;
      bitrate = format.bitrate;
      isWithinConstraints =
          (format.bitrate == Format.NO_VALUE || format.bitrate <= parameters.maxAudioBitrate)
              && (format.channelCount == Format.NO_VALUE
                  || format.channelCount <= parameters.maxAudioChannelCount)
              && withinAudioChannelCountConstraints.apply(format);
      String[] localeLanguages = Util.getSystemLanguageCodes();
      int bestLocaleMatchIndex = Integer.MAX_VALUE;
      int bestLocaleMatchScore = 0;
      for (int i = 0; i < localeLanguages.length; i++) {
        int score =
            getFormatLanguageScore(
                format, localeLanguages[i], /* allowUndeterminedFormatLanguage= */ false);
        if (score > 0) {
          bestLocaleMatchIndex = i;
          bestLocaleMatchScore = score;
          break;
        }
      }
      localeLanguageMatchIndex = bestLocaleMatchIndex;
      localeLanguageScore = bestLocaleMatchScore;
      int bestMimeTypeMatchIndex = Integer.MAX_VALUE;
      for (int i = 0; i < parameters.preferredAudioMimeTypes.size(); i++) {
        if (format.sampleMimeType != null
            && format.sampleMimeType.equals(parameters.preferredAudioMimeTypes.get(i))) {
          bestMimeTypeMatchIndex = i;
          break;
        }
      }
      preferredMimeTypeMatchIndex = bestMimeTypeMatchIndex;
      usesPrimaryDecoder =
          RendererCapabilities.getDecoderSupport(formatSupport)
              == RendererCapabilities.DECODER_SUPPORT_PRIMARY;
      usesHardwareAcceleration =
          RendererCapabilities.getHardwareAccelerationSupport(formatSupport)
              == RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED;
      selectionEligibility =
          evaluateSelectionEligibility(
              formatSupport, hasMappedVideoTracks, requiredAdaptiveSupport);
    }

    @Override
    public @SelectionEligibility int getSelectionEligibility() {
      return selectionEligibility;
    }

    @Override
    public boolean isCompatibleForAdaptationWith(AudioTrackInfo otherTrack) {
      return (parameters.allowAudioMixedChannelCountAdaptiveness
              || (format.channelCount != Format.NO_VALUE
                  && format.channelCount == otherTrack.format.channelCount))
          && (allowMixedMimeTypes
              || (format.sampleMimeType != null
                  && TextUtils.equals(format.sampleMimeType, otherTrack.format.sampleMimeType)))
          && (parameters.allowAudioMixedSampleRateAdaptiveness
              || (format.sampleRate != Format.NO_VALUE
                  && format.sampleRate == otherTrack.format.sampleRate))
          && (parameters.allowAudioMixedDecoderSupportAdaptiveness
              || (this.usesPrimaryDecoder == otherTrack.usesPrimaryDecoder
                  && this.usesHardwareAcceleration == otherTrack.usesHardwareAcceleration));
    }

    @Override
    public int compareTo(AudioTrackInfo other) {
      // If the formats are within constraints and renderer capabilities then prefer higher values
      // of channel count, sample rate and bit rate in that order. Otherwise, prefer lower values.
      Ordering<Integer> qualityOrdering =
          isWithinConstraints && isWithinRendererCapabilities
              ? FORMAT_VALUE_ORDERING
              : FORMAT_VALUE_ORDERING.reverse();
      ComparisonChain comparisonChain =
          ComparisonChain.start()
              .compareFalseFirst(
                  this.isWithinRendererCapabilities, other.isWithinRendererCapabilities)
              // 1. Compare match with specific content preferences set by the parameters.
              .compare(
                  this.preferredLanguageIndex,
                  other.preferredLanguageIndex,
                  Ordering.natural().reverse())
              .compare(this.preferredLanguageScore, other.preferredLanguageScore)
              .compare(this.preferredRoleFlagsScore, other.preferredRoleFlagsScore)
              // 2. Compare match with implicit content preferences set by the media or the system.
              .compareFalseFirst(this.isDefaultSelectionFlag, other.isDefaultSelectionFlag)
              .compareFalseFirst(this.hasMainOrNoRoleFlag, other.hasMainOrNoRoleFlag)
              .compare(
                  this.localeLanguageMatchIndex,
                  other.localeLanguageMatchIndex,
                  Ordering.natural().reverse())
              .compare(this.localeLanguageScore, other.localeLanguageScore)
              // 3. Compare match with technical preferences set by the parameters.
              .compareFalseFirst(this.isWithinConstraints, other.isWithinConstraints)
              .compare(
                  this.preferredMimeTypeMatchIndex,
                  other.preferredMimeTypeMatchIndex,
                  Ordering.natural().reverse());
      if (parameters.forceLowestBitrate) {
        comparisonChain =
            comparisonChain.compare(this.bitrate, other.bitrate, FORMAT_VALUE_ORDERING.reverse());
      }
      // 4. Compare match with renderer capability preferences.
      comparisonChain =
          comparisonChain
              .compareFalseFirst(this.usesPrimaryDecoder, other.usesPrimaryDecoder)
              .compareFalseFirst(this.usesHardwareAcceleration, other.usesHardwareAcceleration)
              // 5. Compare technical quality.
              .compareFalseFirst(this.isObjectBasedAudio, other.isObjectBasedAudio)
              .compare(this.channelCount, other.channelCount, qualityOrdering)
              .compare(this.sampleRate, other.sampleRate, qualityOrdering);
      if (Objects.equals(this.language, other.language)) {
        // Only compare bit rates of tracks with matching language information.
        comparisonChain = comparisonChain.compare(this.bitrate, other.bitrate, qualityOrdering);
      }
      return comparisonChain.result();
    }

    private @SelectionEligibility int evaluateSelectionEligibility(
        @Capabilities int rendererSupport,
        boolean hasMappedVideoTracks,
        @AdaptiveSupport int requiredAdaptiveSupport) {
      if (!isFormatSupported(rendererSupport, parameters.exceedRendererCapabilitiesIfNecessary)) {
        return SELECTION_ELIGIBILITY_NO;
      }
      if (!isWithinConstraints && !parameters.exceedAudioConstraintsIfNecessary) {
        return SELECTION_ELIGIBILITY_NO;
      }
      if (parameters.audioOffloadPreferences.audioOffloadMode == AUDIO_OFFLOAD_MODE_REQUIRED
          && !rendererSupportsOffload(parameters, rendererSupport, format)) {
        return SELECTION_ELIGIBILITY_NO;
      }
      return isFormatSupported(rendererSupport, /* allowExceedsCapabilities= */ false)
              && isWithinConstraints
              && format.bitrate != Format.NO_VALUE
              && !parameters.forceHighestSupportedBitrate
              && !parameters.forceLowestBitrate
              && (parameters.allowMultipleAdaptiveSelections || !hasMappedVideoTracks)
              && parameters.audioOffloadPreferences.audioOffloadMode != AUDIO_OFFLOAD_MODE_REQUIRED
              && (rendererSupport & requiredAdaptiveSupport) != 0
          ? SELECTION_ELIGIBILITY_ADAPTIVE
          : SELECTION_ELIGIBILITY_FIXED;
    }

    public static int compareSelections(List<AudioTrackInfo> infos1, List<AudioTrackInfo> infos2) {
      // Compare best tracks of each selection with each other.
      return max(infos1).compareTo(max(infos2));
    }
  }

  private static final class TextTrackInfo extends TrackInfo<TextTrackInfo>
      implements Comparable<TextTrackInfo> {

    public static ImmutableList<TextTrackInfo> createForTrackGroup(
        int rendererIndex,
        TrackGroup trackGroup,
        Parameters params,
        @Capabilities int[] formatSupport,
        @Nullable String selectedAudioLanguage,
        @Nullable String preferredCaptioningLanguage) {
      ImmutableList.Builder<TextTrackInfo> listBuilder = ImmutableList.builder();
      for (int i = 0; i < trackGroup.length; i++) {
        listBuilder.add(
            new TextTrackInfo(
                rendererIndex,
                trackGroup,
                /* trackIndex= */ i,
                params,
                formatSupport[i],
                selectedAudioLanguage,
                preferredCaptioningLanguage));
      }
      return listBuilder.build();
    }

    private final @SelectionEligibility int selectionEligibility;
    private final boolean isWithinRendererCapabilities;
    private final boolean isDefault;
    private final boolean isForced;
    private final int preferredLanguageIndex;
    private final int preferredLanguageScore;
    private final int preferredRoleFlagsScore;
    private final int selectedAudioLanguageScore;
    private final boolean hasCaptionRoleFlags;

    public TextTrackInfo(
        int rendererIndex,
        TrackGroup trackGroup,
        int trackIndex,
        Parameters parameters,
        @Capabilities int trackFormatSupport,
        @Nullable String selectedAudioLanguage,
        @Nullable String preferredCaptioningLanguage) {
      super(rendererIndex, trackGroup, trackIndex);
      isWithinRendererCapabilities =
          isFormatSupported(trackFormatSupport, /* allowExceedsCapabilities= */ false);
      int maskedSelectionFlags = format.selectionFlags & ~parameters.ignoredTextSelectionFlags;
      isDefault = (maskedSelectionFlags & C.SELECTION_FLAG_DEFAULT) != 0;
      isForced = (maskedSelectionFlags & C.SELECTION_FLAG_FORCED) != 0;
      int bestLanguageIndex = Integer.MAX_VALUE;
      int bestLanguageScore = 0;
      // Compare against empty (unset) language if no preference is given to allow the selection of
      // a text track with undetermined language.
      ImmutableList<String> preferredLanguages =
          preferredCaptioningLanguage != null
              ? ImmutableList.of(preferredCaptioningLanguage)
              : parameters.preferredTextLanguages.isEmpty()
                  ? ImmutableList.of("")
                  : parameters.preferredTextLanguages;
      for (int i = 0; i < preferredLanguages.size(); i++) {
        int score =
            getFormatLanguageScore(
                format, preferredLanguages.get(i), parameters.selectUndeterminedTextLanguage);
        if (score > 0) {
          bestLanguageIndex = i;
          bestLanguageScore = score;
          break;
        }
      }
      preferredLanguageIndex = bestLanguageIndex;
      preferredLanguageScore = bestLanguageScore;
      int preferredRoleFlags =
          preferredCaptioningLanguage != null
              ? C.ROLE_FLAG_CAPTION | C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND
              : parameters.preferredTextRoleFlags;
      preferredRoleFlagsScore = getRoleFlagMatchScore(format.roleFlags, preferredRoleFlags);
      hasCaptionRoleFlags =
          (format.roleFlags & (C.ROLE_FLAG_CAPTION | C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)) != 0;
      boolean selectedAudioLanguageUndetermined =
          normalizeUndeterminedLanguageToNull(selectedAudioLanguage) == null;
      selectedAudioLanguageScore =
          getFormatLanguageScore(format, selectedAudioLanguage, selectedAudioLanguageUndetermined);
      boolean isWithinConstraints =
          preferredLanguageScore > 0
              || (parameters.preferredTextLanguages.isEmpty() && preferredRoleFlagsScore > 0)
              || isDefault
              || (isForced && selectedAudioLanguageScore > 0)
              || parameters.selectTextByDefault;
      selectionEligibility =
          isFormatSupported(trackFormatSupport, parameters.exceedRendererCapabilitiesIfNecessary)
                  && isWithinConstraints
              ? SELECTION_ELIGIBILITY_FIXED
              : SELECTION_ELIGIBILITY_NO;
    }

    @Override
    public @SelectionEligibility int getSelectionEligibility() {
      return selectionEligibility;
    }

    @Override
    public boolean isCompatibleForAdaptationWith(TextTrackInfo otherTrack) {
      return false;
    }

    @Override
    public int compareTo(TextTrackInfo other) {
      ComparisonChain chain =
          ComparisonChain.start()
              .compareFalseFirst(
                  this.isWithinRendererCapabilities, other.isWithinRendererCapabilities)
              // 1. Compare match with specific content preferences set by the parameters.
              .compare(
                  this.preferredLanguageIndex,
                  other.preferredLanguageIndex,
                  Ordering.natural().reverse())
              .compare(this.preferredLanguageScore, other.preferredLanguageScore)
              .compare(this.preferredRoleFlagsScore, other.preferredRoleFlagsScore)
              // 2. Compare match with implicit content preferences set by the media.
              .compareFalseFirst(this.isDefault, other.isDefault)
              .compare(
                  this.isForced,
                  other.isForced,
                  // Prefer non-forced to forced if a preferred text language has been matched.
                  // Where both are provided the non-forced track will usually contain the forced
                  // subtitles as a subset. Otherwise, prefer a forced track.
                  preferredLanguageScore == 0 ? Ordering.natural() : Ordering.natural().reverse())
              .compare(this.selectedAudioLanguageScore, other.selectedAudioLanguageScore);
      if (preferredRoleFlagsScore == 0) {
        chain = chain.compareTrueFirst(this.hasCaptionRoleFlags, other.hasCaptionRoleFlags);
      }
      return chain.result();
    }

    public static int compareSelections(List<TextTrackInfo> infos1, List<TextTrackInfo> infos2) {
      return infos1.get(0).compareTo(infos2.get(0));
    }
  }

  private static final class ImageTrackInfo extends TrackInfo<ImageTrackInfo>
      implements Comparable<ImageTrackInfo> {

    public static ImmutableList<ImageTrackInfo> createForTrackGroup(
        int rendererIndex,
        TrackGroup trackGroup,
        Parameters params,
        @Capabilities int[] formatSupport) {
      ImmutableList.Builder<ImageTrackInfo> imageTracks = ImmutableList.builder();
      for (int i = 0; i < trackGroup.length; i++) {
        imageTracks.add(
            new ImageTrackInfo(
                rendererIndex, trackGroup, /* trackIndex= */ i, params, formatSupport[i]));
      }
      return imageTracks.build();
    }

    private final @SelectionEligibility int selectionEligibility;
    private final int pixelCount;

    public ImageTrackInfo(
        int rendererIndex,
        TrackGroup trackGroup,
        int trackIndex,
        Parameters parameters,
        @Capabilities int trackFormatSupport) {
      super(rendererIndex, trackGroup, trackIndex);
      selectionEligibility =
          isFormatSupported(trackFormatSupport, parameters.exceedRendererCapabilitiesIfNecessary)
              ? SELECTION_ELIGIBILITY_FIXED
              : SELECTION_ELIGIBILITY_NO;
      pixelCount = format.getPixelCount();
    }

    @Override
    public @SelectionEligibility int getSelectionEligibility() {
      return selectionEligibility;
    }

    @Override
    public boolean isCompatibleForAdaptationWith(ImageTrackInfo otherTrack) {
      return false;
    }

    @Override
    public int compareTo(ImageTrackInfo other) {
      return Integer.compare(this.pixelCount, other.pixelCount);
    }

    public static int compareSelections(List<ImageTrackInfo> infos1, List<ImageTrackInfo> infos2) {
      return infos1.get(0).compareTo(infos2.get(0));
    }
  }

  private static final class OtherTrackScore implements Comparable<OtherTrackScore> {

    private final boolean isDefault;
    private final boolean isWithinRendererCapabilities;

    public OtherTrackScore(Format format, @Capabilities int trackFormatSupport) {
      isDefault = (format.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0;
      isWithinRendererCapabilities =
          isFormatSupported(trackFormatSupport, /* allowExceedsCapabilities= */ false);
    }

    @Override
    public int compareTo(OtherTrackScore other) {
      return ComparisonChain.start()
          .compareFalseFirst(this.isWithinRendererCapabilities, other.isWithinRendererCapabilities)
          .compareFalseFirst(this.isDefault, other.isDefault)
          .result();
    }
  }

  /**
   * Wraps the {@link Spatializer} in order to encapsulate its APIs within an inner class, to avoid
   * runtime linking on devices with {@code API < 32}.
   */
  @RequiresApi(32)
  private static class SpatializerWrapperV32 {

    @Nullable private final Spatializer spatializer;
    private final boolean spatializationSupported;
    @Nullable private final Handler handler;
    @Nullable private final Spatializer.OnSpatializerStateChangedListener listener;

    public SpatializerWrapperV32(
        @Nullable Context context,
        DefaultTrackSelector defaultTrackSelector,
        @Nullable Boolean deviceIsTv) {
      @Nullable
      AudioManager audioManager =
          context == null ? null : AudioManagerCompat.getAudioManager(context);
      if (audioManager == null || (deviceIsTv != null && deviceIsTv)) {
        spatializer = null;
        spatializationSupported = false;
        handler = null;
        listener = null;
        return;
      }
      this.spatializer = audioManager.getSpatializer();
      this.spatializationSupported =
          spatializer.getImmersiveAudioLevel() != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
      this.listener =
          new Spatializer.OnSpatializerStateChangedListener() {
            @Override
            public void onSpatializerEnabledChanged(Spatializer spatializer, boolean enabled) {
              defaultTrackSelector.maybeInvalidateForAudioChannelCountConstraints();
            }

            @Override
            public void onSpatializerAvailableChanged(Spatializer spatializer, boolean available) {
              defaultTrackSelector.maybeInvalidateForAudioChannelCountConstraints();
            }
          };
      this.handler = new Handler(checkStateNotNull(Looper.myLooper()));
      spatializer.addOnSpatializerStateChangedListener(handler::post, listener);
    }

    public boolean isSpatializationSupported() {
      return spatializationSupported;
    }

    public boolean isAvailable() {
      return checkNotNull(spatializer).isAvailable();
    }

    public boolean isEnabled() {
      return checkNotNull(spatializer).isEnabled();
    }

    public boolean canBeSpatialized(AudioAttributes audioAttributes, Format format) {
      int linearChannelCount;
      if (Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_E_AC3_JOC)) {
        // For E-AC3 JOC, the format is object based. When the channel count is 16, this maps to 12
        // linear channels and the rest are used for objects. See
        // https://github.com/google/ExoPlayer/pull/10322#discussion_r895265881
        linearChannelCount = format.channelCount == 16 ? 12 : format.channelCount;
      } else if (Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_IAMF)) {
        // IAMF with no channel count specified, assume 5.1 channels. This depends on
        // IamfDecoder.SPATIALIZED_OUTPUT_LAYOUT being set to AudioFormat.CHANNEL_OUT_5POINT1. Any
        // changes to that constant will require updates to this logic.
        linearChannelCount = format.channelCount == Format.NO_VALUE ? 6 : format.channelCount;
      } else if (Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_AC4)) {
        // For AC-4 level 3 or level 4, the format may be object based. When the channel count is
        // 18 (level 3 17.1 OBI) or 21 (level 4 20.1 OBI), it is mapped to 24 linear channels (some
        // channels are used for metadata transfer).
        linearChannelCount =
            (format.channelCount == 18 || format.channelCount == 21) ? 24 : format.channelCount;
      } else {
        linearChannelCount = format.channelCount;
      }

      int channelConfig = Util.getAudioTrackChannelConfig(linearChannelCount);
      if (channelConfig == AudioFormat.CHANNEL_INVALID) {
        return false;
      }
      AudioFormat.Builder builder =
          new AudioFormat.Builder()
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setChannelMask(channelConfig);
      if (format.sampleRate != Format.NO_VALUE) {
        builder.setSampleRate(format.sampleRate);
      }
      return checkNotNull(spatializer)
          .canBeSpatialized(audioAttributes.getPlatformAudioAttributes(), builder.build());
    }

    public void release() {
      if (spatializer == null || listener == null || handler == null) {
        return;
      }
      spatializer.removeOnSpatializerStateChangedListener(listener);
      handler.removeCallbacksAndMessages(/* token= */ null);
    }
  }
}
