/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runLooperUntil;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.transformer.AssetLoader.CompositionSettings;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowSystemClock;

/** Unit tests for {@link ImageAssetLoader}. */
@RunWith(AndroidJUnit4.class)
public class ImageAssetLoaderTest {

  @Test
  public void imageAssetLoader_callsListenerCallbacksInRightOrder() throws Exception {
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();
    AtomicBoolean isOutputFormatSet = new AtomicBoolean();
    AssetLoader.Listener listener =
        new AssetLoader.Listener() {

          private volatile boolean isDurationSet;
          private volatile boolean isTrackCountSet;
          private volatile boolean isTrackAdded;

          @Override
          public void onDurationUs(long durationUs) {
            // Sleep to increase the chances of the test failing.
            sleep();
            isDurationSet = true;
          }

          @Override
          public void onTrackCount(int trackCount) {
            // Sleep to increase the chances of the test failing.
            sleep();
            isTrackCountSet = true;
          }

          @Override
          public boolean onTrackAdded(
              Format inputFormat, @AssetLoader.SupportedOutputTypes int supportedOutputTypes) {
            if (!isDurationSet) {
              exceptionRef.set(
                  new IllegalStateException("onTrackAdded() called before onDurationUs()"));
            } else if (!isTrackCountSet) {
              exceptionRef.set(
                  new IllegalStateException("onTrackAdded() called before onTrackCount()"));
            }
            sleep();
            isTrackAdded = true;
            return false;
          }

          @Override
          public SampleConsumer onOutputFormat(Format format) {
            if (!isTrackAdded) {
              exceptionRef.set(
                  new IllegalStateException("onOutputFormat() called before onTrackAdded()"));
            }
            isOutputFormatSet.set(true);
            return new FakeSampleConsumer();
          }

          @Override
          public void onError(ExportException e) {
            exceptionRef.set(e);
          }

          private void sleep() {
            try {
              Thread.sleep(10);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              exceptionRef.set(e);
            }
          }
        };
    AssetLoader assetLoader = getAssetLoader(listener, "asset:///media/png/media3test.png");

    assetLoader.start();
    runLooperUntil(
        Looper.myLooper(),
        () -> {
          ShadowSystemClock.advanceBy(Duration.ofMillis(10));
          return isOutputFormatSet.get() || exceptionRef.get() != null;
        });

    assertThat(exceptionRef.get()).isNull();
  }

  @Test
  public void imageAssetLoader_onUnsupportedMimeType_callsListener() throws Exception {
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();
    AssetLoader.Listener listener =
        new AssetLoader.Listener() {

          @Override
          public void onDurationUs(long durationUs) {}

          @Override
          public void onTrackCount(int trackCount) {}

          @Override
          public boolean onTrackAdded(
              Format inputFormat, @AssetLoader.SupportedOutputTypes int supportedOutputTypes) {
            return false;
          }

          @Override
          public SampleConsumer onOutputFormat(Format format) {
            return new FakeSampleConsumer();
          }

          @Override
          public void onError(ExportException e) {
            exceptionRef.set(e);
          }
        };
    AssetLoader assetLoader = getAssetLoader(listener, "asset:///media3test.gif");

    assetLoader.start();
    runLooperUntil(
        Looper.myLooper(),
        () -> {
          ShadowSystemClock.advanceBy(Duration.ofMillis(10));
          return exceptionRef.get() != null;
        });
    ParserException parserException = (ParserException) exceptionRef.get().getCause();

    assertThat(parserException)
        .hasMessageThat()
        .contains("Attempted to load a Bitmap from unsupported MIME type: image/gif");
    assertThat(parserException.contentIsMalformed).isFalse();
    assertThat(parserException.dataType).isEqualTo(C.DATA_TYPE_MEDIA);
  }

  private static AssetLoader getAssetLoader(AssetLoader.Listener listener, String uri) {
    Context context = ApplicationProvider.getApplicationContext();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder().setUri(uri).setImageDurationMs(1000).build())
            .setFrameRate(30)
            .build();
    return new ImageAssetLoader.Factory(
            context, new DataSourceBitmapLoader(ApplicationProvider.getApplicationContext()))
        .createAssetLoader(
            editedMediaItem,
            Looper.myLooper(),
            listener,
            new CompositionSettings(
                Composition.HDR_MODE_KEEP_HDR, /* retainHdrFromUltraHdrImage= */ false));
  }

  private static final class FakeSampleConsumer implements SampleConsumer {

    @Override
    public @InputResult int queueInputBitmap(
        Bitmap inputBitmap, TimestampIterator inStreamOffsetsUs) {
      return INPUT_RESULT_SUCCESS;
    }

    @Override
    public void signalEndOfVideoInput() {}
  }
}
