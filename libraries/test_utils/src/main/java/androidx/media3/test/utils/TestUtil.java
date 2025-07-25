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
package androidx.media3.test.utils;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.putInt24;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.StreamKey;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.DefaultDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.MetadataRetriever;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.metadata.MetadataInputBuffer;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.base.Function;
import com.google.common.collect.BoundType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Range;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.UnsignedBytes;
import com.google.common.truth.Correspondence;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.function.ThrowingRunnable;
import org.mockito.Mockito;

/** Utility methods for tests. */
@UnstableApi
public class TestUtil {
  /**
   * Luma PSNR values between 30 and 50 are considered good for lossy compression (See <a
   * href="https://en.wikipedia.org/wiki/Peak_signal-to-noise_ratio#Quality_estimation_with_PSNR">Quality
   * estimation with PSNR</a> ).
   */
  public static final float PSNR_THRESHOLD = 35f;

  private TestUtil() {}

  /**
   * Equivalent to {@code buildTestData(length, length)}.
   *
   * @param length The length of the array.
   * @return The generated array.
   */
  public static byte[] buildTestData(int length) {
    return buildTestData(length, length);
  }

  /**
   * Generates an array of random bytes with the specified length.
   *
   * @param length The length of the array.
   * @param seed A seed for an internally created {@link Random source of randomness}.
   * @return The generated array.
   */
  public static byte[] buildTestData(int length, int seed) {
    return buildTestData(length, new Random(seed));
  }

  /**
   * Generates an array of random bytes with the specified length.
   *
   * @param length The length of the array.
   * @param random A source of randomness.
   * @return The generated array.
   */
  public static byte[] buildTestData(int length, Random random) {
    byte[] source = new byte[length];
    random.nextBytes(source);
    return source;
  }

  /**
   * Generates a random string with the specified length.
   *
   * @param length The length of the string.
   * @param random A source of randomness.
   * @return The generated string.
   */
  public static String buildTestString(int length, Random random) {
    char[] chars = new char[length];
    for (int i = 0; i < length; i++) {
      chars[i] = (char) random.nextInt();
    }
    return new String(chars);
  }

  /**
   * Converts an array of integers in the range [0, 255] into an equivalent byte array.
   *
   * @param bytes An array of integers, all of which must be in the range [0, 255].
   * @return The equivalent byte array.
   */
  public static byte[] createByteArray(int... bytes) {
    byte[] array = new byte[bytes.length];
    for (int i = 0; i < array.length; i++) {
      array[i] = UnsignedBytes.checkedCast(bytes[i]);
    }
    return array;
  }

  /** Gets the underlying data of the {@link ByteBuffer} as a {@code byte[]}. */
  public static byte[] createByteArray(ByteBuffer byteBuffer) {
    byte[] content = new byte[byteBuffer.remaining()];
    byteBuffer.get(content);
    return content;
  }

  /** Gets the underlying data of the {@link ByteBuffer} as a {@code float[]}. */
  public static float[] createFloatArray(ByteBuffer byteBuffer) {
    FloatBuffer buffer = byteBuffer.asFloatBuffer();
    float[] content = new float[buffer.remaining()];
    buffer.get(content);
    return content;
  }

  /** Gets the underlying data of the {@link ByteBuffer} as a {@code int[]}. */
  public static int[] createIntArray(ByteBuffer byteBuffer) {
    IntBuffer buffer = byteBuffer.asIntBuffer();
    int[] content = new int[buffer.remaining()];
    buffer.get(content);
    return content;
  }

  /**
   * Gets the underlying data of the {@link ByteBuffer} as 24-bit integer values in {@code int[]}.
   */
  public static int[] createInt24Array(ByteBuffer byteBuffer) {
    int[] content = new int[byteBuffer.remaining() / 3];
    for (int i = 0; i < content.length; i++) {
      content[i] = Util.getInt24(byteBuffer, byteBuffer.position() + i * 3);
    }
    return content;
  }

  /** Gets the underlying data of the {@link ByteBuffer} as a {@code short[]}. */
  public static short[] createShortArray(ByteBuffer byteBuffer) {
    ShortBuffer buffer = byteBuffer.asShortBuffer();
    short[] content = new short[buffer.remaining()];
    buffer.get(content);
    return content;
  }

  /** Creates a {@link ByteBuffer} containing the {@code data}. */
  public static ByteBuffer createByteBuffer(float[] data) {
    ByteBuffer buffer =
        ByteBuffer.allocateDirect(data.length * C.BYTES_PER_FLOAT).order(ByteOrder.nativeOrder());
    buffer.asFloatBuffer().put(data);
    return buffer;
  }

  /** Creates a {@link ByteBuffer} containing the {@code data}. */
  public static ByteBuffer createByteBuffer(int[] data) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder());
    buffer.asIntBuffer().put(data);
    return buffer;
  }

  /** Creates a {@link ByteBuffer} containing the {@code data}. */
  public static ByteBuffer createByteBuffer(short[] data) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(data.length * 2).order(ByteOrder.nativeOrder());
    buffer.asShortBuffer().put(data);
    return buffer;
  }

  /** Creates a {@link ByteBuffer} containing the {@code data}. */
  public static ByteBuffer createByteBuffer(byte[] data) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
    buffer.put(data);
    buffer.rewind();
    return buffer;
  }

  /** Creates a {@link ByteBuffer} with the contents of {@code data} as 24-bit integers. */
  public static ByteBuffer createInt24ByteBuffer(int[] data) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(data.length * 3).order(ByteOrder.nativeOrder());
    for (int i : data) {
      putInt24(buffer, i);
    }
    buffer.rewind();
    return buffer;
  }

  /**
   * Converts an array of integers in the range [0, 255] into an equivalent byte list.
   *
   * @param bytes An array of integers, all of which must be in the range [0, 255].
   * @return The equivalent byte list.
   */
  public static ImmutableList<Byte> createByteList(int... bytes) {
    return ImmutableList.copyOf(Bytes.asList(createByteArray(bytes)));
  }

  /** Writes one byte long test data to the file and returns it. */
  public static File createTestFile(File directory, String name) throws IOException {
    return createTestFile(directory, name, /* length= */ 1);
  }

  /** Writes test data with the specified length to the file and returns it. */
  public static File createTestFile(File directory, String name, long length) throws IOException {
    return createTestFile(new File(directory, name), length);
  }

  /** Writes test data with the specified length to the file and returns it. */
  public static File createTestFile(File file, long length) throws IOException {
    try (FileOutputStream output = new FileOutputStream(file)) {
      for (long i = 0; i < length; i++) {
        output.write((int) i);
      }
    }
    return file;
  }

  /** Returns the bytes of an asset file. */
  public static byte[] getByteArray(Context context, String fileName) throws IOException {
    try (InputStream inputStream = getInputStream(context, fileName)) {
      return ByteStreams.toByteArray(inputStream);
    }
  }

  /** Returns the bytes of a file using its file path. */
  public static byte[] getByteArrayFromFilePath(String filePath) throws IOException {
    try (InputStream inputStream = new FileInputStream(filePath)) {
      return ByteStreams.toByteArray(inputStream);
    }
  }

  /** Returns an {@link InputStream} for reading from an asset file. */
  public static InputStream getInputStream(Context context, String fileName) throws IOException {
    return context.getResources().getAssets().open(fileName);
  }

  /** Returns a {@link String} read from an asset file. */
  public static String getString(Context context, String fileName) throws IOException {
    return Util.fromUtf8Bytes(getByteArray(context, fileName));
  }

  /** Returns a {@link DatabaseProvider} that provides an in-memory database. */
  public static DatabaseProvider getInMemoryDatabaseProvider() {
    return new DefaultDatabaseProvider(
        new SQLiteOpenHelper(
            /* context= */ null, /* name= */ null, /* factory= */ null, /* version= */ 1) {
          @Override
          public void onCreate(SQLiteDatabase db) {
            // Do nothing.
          }

          @Override
          public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Do nothing.
          }
        });
  }

  /**
   * Asserts that the actual timelines are the same to the expected timelines. This assert differs
   * from testing equality by not comparing:
   *
   * <ul>
   *   <li>Period IDs, which may be different due to ID mapping of child source period IDs.
   *   <li>Shuffle order, which by default is random and non-deterministic.
   * </ul>
   *
   * @param actualTimelines A list of actual {@link Timeline timelines}.
   * @param expectedTimelines A list of expected {@link Timeline timelines}.
   */
  public static void assertTimelinesSame(
      List<Timeline> actualTimelines, List<Timeline> expectedTimelines) {
    assertThat(actualTimelines)
        .comparingElementsUsing(
            Correspondence.from(
                TestUtil::timelinesAreSame, "is equal to (ignoring Window.uid and Period.uid)"))
        .containsExactlyElementsIn(expectedTimelines)
        .inOrder();
  }

  /**
   * Returns true if {@code thisTimeline} is equal to {@code thatTimeline}, ignoring {@link
   * Timeline.Window#uid} and {@link Timeline.Period#uid} values, and shuffle order.
   */
  public static boolean timelinesAreSame(Timeline thisTimeline, Timeline thatTimeline) {
    return new NoUidOrShufflingTimeline(thisTimeline)
        .equals(new NoUidOrShufflingTimeline(thatTimeline));
  }

  /**
   * Asserts that data read from a {@link DataSource} matches {@code expected}.
   *
   * @param dataSource The {@link DataSource} through which to read.
   * @param dataSpec The {@link DataSpec} to use when opening the {@link DataSource}.
   * @param expectedData The expected data.
   * @param expectKnownLength Whether to assert that {@link DataSource#open} returns the expected
   *     data length. If false then it's asserted that {@link C#LENGTH_UNSET} is returned.
   * @throws IOException If an error occurs reading fom the {@link DataSource}.
   */
  public static void assertDataSourceContent(
      DataSource dataSource, DataSpec dataSpec, byte[] expectedData, boolean expectKnownLength)
      throws IOException {
    try {
      long length = dataSource.open(dataSpec);
      assertThat(length).isEqualTo(expectKnownLength ? expectedData.length : C.LENGTH_UNSET);
      byte[] readData = DataSourceUtil.readToEnd(dataSource);
      assertThat(readData).isEqualTo(expectedData);
    } finally {
      dataSource.close();
    }
  }

  /** Returns whether two {@link android.media.MediaCodec.BufferInfo BufferInfos} are equal. */
  public static void assertBufferInfosEqual(
      MediaCodec.BufferInfo expected, MediaCodec.BufferInfo actual) {
    assertThat(actual.flags).isEqualTo(expected.flags);
    assertThat(actual.offset).isEqualTo(expected.offset);
    assertThat(actual.presentationTimeUs).isEqualTo(expected.presentationTimeUs);
    assertThat(actual.size).isEqualTo(expected.size);
  }

  /**
   * Asserts whether actual bitmap is very similar to the expected bitmap at some quality level.
   *
   * <p>This is defined as their PSNR value is greater than or equal to the threshold. The higher
   * the threshold, the more similar they are.
   *
   * @param expectedBitmap The expected bitmap.
   * @param actualBitmap The actual bitmap.
   * @param psnrThresholdDb The PSNR threshold (in dB), at or above which bitmaps are considered
   *     very similar.
   */
  public static void assertBitmapsAreSimilar(
      Bitmap expectedBitmap, Bitmap actualBitmap, double psnrThresholdDb) {
    assertThat(getPsnr(expectedBitmap, actualBitmap)).isAtLeast(psnrThresholdDb);
  }

  /**
   * Calculates the Peak-Signal-to-Noise-Ratio value for 2 bitmaps.
   *
   * <p>This is the logarithmic decibel(dB) value of the average mean-squared-error of normalized
   * (0.0-1.0) R/G/B values from the two bitmaps. The higher the value, the more similar they are.
   *
   * @param firstBitmap The first bitmap.
   * @param secondBitmap The second bitmap.
   * @return The PSNR value calculated from these 2 bitmaps.
   */
  private static double getPsnr(Bitmap firstBitmap, Bitmap secondBitmap) {
    assertThat(firstBitmap.getWidth()).isEqualTo(secondBitmap.getWidth());
    assertThat(firstBitmap.getHeight()).isEqualTo(secondBitmap.getHeight());
    long mse = 0;
    for (int i = 0; i < firstBitmap.getWidth(); i++) {
      for (int j = 0; j < firstBitmap.getHeight(); j++) {
        int firstColorInt = firstBitmap.getPixel(i, j);
        int firstRed = Color.red(firstColorInt);
        int firstGreen = Color.green(firstColorInt);
        int firstBlue = Color.blue(firstColorInt);
        int secondColorInt = secondBitmap.getPixel(i, j);
        int secondRed = Color.red(secondColorInt);
        int secondGreen = Color.green(secondColorInt);
        int secondBlue = Color.blue(secondColorInt);
        mse +=
            ((firstRed - secondRed) * (firstRed - secondRed)
                + (firstGreen - secondGreen) * (firstGreen - secondGreen)
                + (firstBlue - secondBlue) * (firstBlue - secondBlue));
      }
    }
    double normalizedMse =
        mse / (255.0 * 255.0 * 3.0 * firstBitmap.getWidth() * firstBitmap.getHeight());
    return 10 * Math.log10(1.0 / normalizedMse);
  }

  /** Returns the {@link Uri} for the given asset path. */
  public static Uri buildAssetUri(String assetPath) {
    return Uri.parse("asset:///" + assetPath);
  }

  /**
   * Returns the {@link Format} for a given {@link C.TrackType} from a media file.
   *
   * <p>If more than one track is present for the given {@link C.TrackType} then only one track's
   * {@link Format} is returned.
   *
   * @param context The {@link Context};
   * @param fileUri The media file uri.
   * @param trackType The {@link C.TrackType}.
   * @return The {@link Format} for the given {@link C.TrackType}.
   * @throws ExecutionException If an error occurred while retrieving file's metadata.
   * @throws InterruptedException If interrupted while retrieving file's metadata.
   */
  public static Format retrieveTrackFormat(
      Context context, String fileUri, @C.TrackType int trackType)
      throws ExecutionException, InterruptedException {
    checkState(new File(fileUri).length() > 0);

    try (MetadataRetriever retriever =
        new MetadataRetriever.Builder(context, MediaItem.fromUri(fileUri)).build()) {
      TrackGroupArray trackGroups = retriever.retrieveTrackGroups().get();
      for (int i = 0; i < trackGroups.length; i++) {
        TrackGroup trackGroup = trackGroups.get(i);
        if (trackGroup.type == trackType) {
          checkState(trackGroup.length == 1);
          return trackGroup.getFormat(0);
        }
      }
    }
    throw new IllegalStateException("Couldn't find track");
  }

  /**
   * Reads from the given input using the given {@link Extractor}, until it can produce the {@link
   * SeekMap} and all of the track formats have been identified, or until the extractor encounters
   * EOF.
   *
   * @param extractor The {@link Extractor} to extractor from input.
   * @param output The {@link FakeTrackOutput} to store the extracted {@link SeekMap} and track.
   * @param dataSource The {@link DataSource} that will be used to read from the input.
   * @param uri The Uri of the input.
   * @return The extracted {@link SeekMap}.
   * @throws IOException If an error occurred reading from the input, or if the extractor finishes
   *     reading from input without extracting any {@link SeekMap}.
   */
  public static SeekMap extractSeekMap(
      Extractor extractor, FakeExtractorOutput output, DataSource dataSource, Uri uri)
      throws IOException {
    ExtractorInput input = getExtractorInputFromPosition(dataSource, /* position= */ 0, uri);
    extractor.init(output);
    PositionHolder positionHolder = new PositionHolder();
    int readResult = Extractor.RESULT_CONTINUE;
    while (true) {
      try {
        // Keep reading until we get the seek map and the track information.
        while (readResult == Extractor.RESULT_CONTINUE
            && (output.seekMap == null || !output.tracksEnded)) {
          readResult = extractor.read(input, positionHolder);
        }
        for (int i = 0; i < output.trackOutputs.size(); i++) {
          int trackId = output.trackOutputs.keyAt(i);
          while (readResult == Extractor.RESULT_CONTINUE
              && output.trackOutputs.get(trackId).lastFormat == null) {
            readResult = extractor.read(input, positionHolder);
          }
        }
      } finally {
        DataSourceUtil.closeQuietly(dataSource);
      }

      if (readResult == Extractor.RESULT_SEEK) {
        input = getExtractorInputFromPosition(dataSource, positionHolder.position, uri);
        readResult = Extractor.RESULT_CONTINUE;
      } else if (readResult == Extractor.RESULT_END_OF_INPUT) {
        throw new IOException("EOF encountered without seekmap");
      }
      if (output.seekMap != null) {
        return output.seekMap;
      }
    }
  }

  /**
   * Extracts all samples from the given file into a {@link FakeTrackOutput}.
   *
   * @param extractor The {@link Extractor} to be used.
   * @param context A {@link Context}.
   * @param fileName The name of the input file.
   * @return The {@link FakeTrackOutput} containing the extracted samples.
   * @throws IOException If an error occurred reading from the input, or if the extractor finishes
   *     reading from input without extracting any {@link SeekMap}.
   */
  public static FakeExtractorOutput extractAllSamplesFromFile(
      Extractor extractor, Context context, String fileName) throws IOException {
    byte[] data = TestUtil.getByteArray(context, fileName);
    return extractAllSamplesFromByteArray(extractor, data);
  }

  /**
   * Extracts all samples from the given file into a {@link FakeTrackOutput}.
   *
   * @param extractor The {@link Extractor} to be used.
   * @param filePath The file path.
   * @return The {@link FakeTrackOutput} containing the extracted samples.
   * @throws IOException If an error occurred reading from the input, or if the extractor finishes
   *     reading from input without extracting any {@link SeekMap}.
   */
  public static FakeExtractorOutput extractAllSamplesFromFilePath(
      Extractor extractor, String filePath) throws IOException {
    byte[] data = getByteArrayFromFilePath(filePath);
    return extractAllSamplesFromByteArray(extractor, data);
  }

  /**
   * Extracts all samples from the given byte array into a {@link FakeTrackOutput}.
   *
   * @param extractor The {@link Extractor} to be used.
   * @param data The byte array data.
   * @return The {@link FakeTrackOutput} containing the extracted samples.
   * @throws IOException If an error occurred reading from the input, or if the extractor finishes
   *     reading from input without extracting any {@link SeekMap}.
   */
  public static FakeExtractorOutput extractAllSamplesFromByteArray(Extractor extractor, byte[] data)
      throws IOException {
    FakeExtractorOutput expectedOutput = new FakeExtractorOutput();
    extractor.init(expectedOutput);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();

    PositionHolder positionHolder = new PositionHolder();
    int readResult = Extractor.RESULT_CONTINUE;
    while (readResult != Extractor.RESULT_END_OF_INPUT) {
      while (readResult == Extractor.RESULT_CONTINUE) {
        readResult = extractor.read(input, positionHolder);
      }
      if (readResult == Extractor.RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
        readResult = Extractor.RESULT_CONTINUE;
      }
    }
    return expectedOutput;
  }

  /**
   * Seeks to the given seek time of the stream from the given input, and keeps reading from the
   * input until we can extract at least one sample following the seek position, or until
   * end-of-input is reached.
   *
   * @param extractor The {@link Extractor} to extract from input.
   * @param seekMap The {@link SeekMap} of the stream from the given input.
   * @param seekTimeUs The seek time, in micro-seconds.
   * @param trackOutput The {@link FakeTrackOutput} to store the extracted samples.
   * @param dataSource The {@link DataSource} that will be used to read from the input.
   * @param uri The Uri of the input.
   * @return The index of the first extracted sample written to the given {@code trackOutput} after
   *     the seek is completed, or {@link C#INDEX_UNSET} if the seek is completed without any
   *     extracted sample.
   */
  public static int seekToTimeUs(
      Extractor extractor,
      SeekMap seekMap,
      long seekTimeUs,
      DataSource dataSource,
      FakeTrackOutput trackOutput,
      Uri uri)
      throws IOException {
    int numSampleBeforeSeek = trackOutput.getSampleCount();
    SeekMap.SeekPoints seekPoints = seekMap.getSeekPoints(seekTimeUs);

    long initialSeekLoadPosition = seekPoints.first.position;
    extractor.seek(initialSeekLoadPosition, seekTimeUs);

    PositionHolder positionHolder = new PositionHolder();
    positionHolder.position = C.INDEX_UNSET;
    ExtractorInput extractorInput =
        TestUtil.getExtractorInputFromPosition(dataSource, initialSeekLoadPosition, uri);
    int extractorReadResult = Extractor.RESULT_CONTINUE;
    while (true) {
      try {
        // Keep reading until we can read at least one sample after seek
        while (extractorReadResult == Extractor.RESULT_CONTINUE
            && trackOutput.getSampleCount() == numSampleBeforeSeek) {
          extractorReadResult = extractor.read(extractorInput, positionHolder);
        }
      } finally {
        DataSourceUtil.closeQuietly(dataSource);
      }

      if (extractorReadResult == Extractor.RESULT_SEEK) {
        extractorInput =
            TestUtil.getExtractorInputFromPosition(dataSource, positionHolder.position, uri);
        extractorReadResult = Extractor.RESULT_CONTINUE;
      } else if (extractorReadResult == Extractor.RESULT_END_OF_INPUT
          && trackOutput.getSampleCount() == numSampleBeforeSeek) {
        return C.INDEX_UNSET;
      } else if (trackOutput.getSampleCount() > numSampleBeforeSeek) {
        // First index after seek = num sample before seek.
        return numSampleBeforeSeek;
      }
    }
  }

  /** Returns an {@link ExtractorInput} to read from the given input at given position. */
  public static ExtractorInput getExtractorInputFromPosition(
      DataSource dataSource, long position, Uri uri) throws IOException {
    DataSpec dataSpec = new DataSpec(uri, position, C.LENGTH_UNSET);
    long length = dataSource.open(dataSpec);
    if (length != C.LENGTH_UNSET) {
      length += position;
    }
    return new DefaultExtractorInput(dataSource, position, length);
  }

  /**
   * Create a new {@link MetadataInputBuffer} and copy {@code data} into the backing {@link
   * ByteBuffer}.
   */
  public static MetadataInputBuffer createMetadataInputBuffer(byte[] data) {
    MetadataInputBuffer buffer = new MetadataInputBuffer();
    buffer.data = ByteBuffer.allocate(data.length).put(data);
    buffer.data.flip();
    return buffer;
  }

  /**
   * Returns all the public overridable methods of a Java class (except those defined by {@link
   * Object}).
   */
  public static Iterable<Method> getPublicOverridableMethods(Class<?> clazz) {
    return Iterables.filter(
        getPublicMethods(clazz),
        method ->
            !Modifier.isFinal(method.getModifiers()) && !Modifier.isStatic(method.getModifiers()));
  }

  /** Returns all the public methods of a Java class (except those defined by {@link Object}). */
  public static List<Method> getPublicMethods(Class<?> clazz) {
    // Run a BFS over all extended types to inspect them all.
    Queue<Class<?>> supertypeQueue = new ArrayDeque<>();
    supertypeQueue.add(clazz);
    Set<Class<?>> supertypes = new HashSet<>();
    Object object = new Object();
    while (!supertypeQueue.isEmpty()) {
      Class<?> currentSupertype = supertypeQueue.remove();
      if (supertypes.add(currentSupertype)) {
        @Nullable Class<?> superclass = currentSupertype.getSuperclass();
        if (superclass != null && !superclass.isInstance(object)) {
          supertypeQueue.add(superclass);
        }

        Collections.addAll(supertypeQueue, currentSupertype.getInterfaces());
      }
    }

    List<Method> list = new ArrayList<>();
    for (Class<?> supertype : supertypes) {
      for (Method method : supertype.getDeclaredMethods()) {
        if (Modifier.isPublic(method.getModifiers())) {
          list.add(method);
        }
      }
    }

    return list;
  }

  /**
   * Use reflection to assert that every non-final method declared on {@code superType} is
   * overridden by {@code subType}.
   */
  public static <T> void assertSubclassOverridesAllMethods(
      Class<T> superType, Class<? extends T> subType) throws NoSuchMethodException {
    assertSubclassOverridesAllMethodsExcept(superType, subType, ImmutableSet.of());
  }

  /**
   * Use reflection to assert that every non-final, non-excluded method declared on {@code
   * superType} is overridden by {@code subType}.
   */
  public static <T> void assertSubclassOverridesAllMethodsExcept(
      Class<T> superType, Class<? extends T> subType, Set<String> excludedMethods)
      throws NoSuchMethodException {
    for (Method method : TestUtil.getPublicOverridableMethods(superType)) {
      if (excludedMethods.contains(method.getName())) {
        continue;
      }
      assertThat(
              subType
                  .getDeclaredMethod(method.getName(), method.getParameterTypes())
                  .getDeclaringClass())
          .isEqualTo(subType);
    }
  }

  /**
   * Use reflection to assert that calling every non-final method declared on {@code superType} on
   * an instance of {@code forwardingType} results in the call being forwarded to the {@code
   * superType} delegate.
   */
  public static <T extends @NonNull Object, F extends T>
      void assertForwardingClassForwardsAllMethods(
          Class<T> superType, Function<T, F> forwardingInstanceFactory)
          throws InvocationTargetException, IllegalAccessException {
    assertForwardingClassForwardsAllMethodsExcept(
        superType, forwardingInstanceFactory, /* excludedMethods= */ ImmutableSet.of());
  }

  /**
   * Use reflection to assert that calling every non-final, non-excluded method declared on {@code
   * superType} on an instance of {@code forwardingType} results in the call being forwarded to the
   * {@code superType} delegate.
   */
  // The nullness checker is deliberately over-conservative and doesn't permit passing a null
  // parameter to method.invoke(), even if the real method does accept null. Regardless, we expect
  // the null to be passed straight to our mocked delegate, so it's OK to pass null even for
  // non-null parameters. See also
  // https://github.com/typetools/checker-framework/blob/c26bb695ebc572fac1e9cd2e331fc5b9d3953ec0/checker/jdk/nullness/src/java/lang/reflect/Method.java#L109
  @SuppressWarnings("nullness:argument.type.incompatible")
  public static <T extends @NonNull Object, F extends T>
      void assertForwardingClassForwardsAllMethodsExcept(
          Class<T> superType, Function<T, F> forwardingInstanceFactory, Set<String> excludedMethods)
          throws InvocationTargetException, IllegalAccessException {
    for (Method method : getPublicOverridableMethods(superType)) {
      if (excludedMethods.contains(method.getName())) {
        continue;
      }
      T delegate = mock(superType);
      F forwardingInstance = forwardingInstanceFactory.apply(delegate);
      @NullableType Object[] parameters = new Object[method.getParameterCount()];
      for (int i = 0; i < method.getParameterCount(); i++) {
        parameters[i] = tryCreateMockInstance(method.getParameterTypes()[i]);
      }
      method.invoke(forwardingInstance, parameters);

      // Reflective version of verify(delegate).method(parameters), to assert the expected method
      // was invoked on the delegate instance.
      method.invoke(verify(delegate), parameters);
    }
  }

  /**
   * Create an instance of {@code clazz}, using {@link Mockito#mock} if possible.
   *
   * <p>For final types, where mocking is not possible, {@link #tryCreateInstance(Class)} is used
   * instead.
   */
  @SuppressWarnings("unchecked")
  @Nullable
  private static <T> T tryCreateMockInstance(Class<T> clazz) {
    if (clazz.isPrimitive()) {
      // Get a default value of the right primitive type by creating a single-element array.
      return (T) Array.get(Array.newInstance(clazz, 1), 0);
    } else if (clazz.isArray()) {
      return (T) Array.newInstance(clazz.getComponentType(), 0);
    } else if (!Modifier.isFinal(clazz.getModifiers())) {
      try {
        return mock(clazz);
      } catch (RuntimeException e) {
        // continue
      }
    }
    // clazz is a final type, or couldn't otherwise be mocked, so we try and instantiate it via a
    // public constructor instead.
    return tryCreateInstance(clazz);
  }

  /**
   * Creates an instance of {@code clazz} by passing mock parameter values to its public
   * constructor. If the type has no public constructor, we look for a nested {@code Builder} type
   * and try and use that instead. If all the constructors and builders fail, return null.
   */
  // The nullness checker is deliberately over-conservative and doesn't permit passing a null
  // parameter to Constructor.newInstance(), even if the real constructor does accept null.
  @SuppressWarnings({"unchecked", "nullness:argument.type.incompatible"})
  @Nullable
  private static <T> T tryCreateInstance(Class<T> clazz) {
    Constructor<T>[] constructors = (Constructor<T>[]) clazz.getConstructors();
    // Start with the constructor with fewest parameters.
    Arrays.sort(constructors, Ordering.natural().onResultOf(c -> c.getParameterTypes().length));
    for (Constructor<T> constructor : constructors) {
      try {
        return constructor.newInstance(createParameters(constructor.getParameterTypes()));
      } catch (ReflectiveOperationException e) {
        // continue
      }
    }

    // We didn't find a usable constructor, so look for a static factory method instead.
    Method[] methods = clazz.getMethods();
    // Start with the method with fewest parameters.
    Arrays.sort(methods, Ordering.natural().onResultOf(m -> m.getParameterTypes().length));
    for (Method method : methods) {
      if (!Modifier.isStatic(method.getModifiers())
          || !clazz.isAssignableFrom(method.getReturnType())) {
        // Skip non-static methods or those that don't return an instance of clazz
        continue;
      }
      try {
        return (T) method.invoke(null, createParameters(method.getParameterTypes()));
      } catch (ReflectiveOperationException e) {
        // continue
      }
    }

    // Try and instantiate via a builder instead
    @Nullable Class<?> builderClazz = getInnerClass(clazz, "Builder");
    if (builderClazz != null) {
      Object builder = tryCreateInstance(builderClazz);
      if (builder != null) {
        try {
          return (T) builderClazz.getMethod("build").invoke(builder);
        } catch (ReflectiveOperationException e) {
          // continue
        }
      }
    }
    return null;
  }

  private static @NullableType Object[] createParameters(Class<?>[] parameterTypes) {
    @NullableType Object[] parameters = new Object[parameterTypes.length];
    for (int i = 0; i < parameters.length; i++) {
      parameters[i] = tryCreateMockInstance(parameterTypes[i]);
    }
    return parameters;
  }

  /**
   * Returns an inner class of {@code clazz} called {@code className} if it exists, otherwise null.
   */
  @Nullable
  public static Class<?> getInnerClass(Class<?> clazz, String className) {
    for (Class<?> innerClass : clazz.getDeclaredClasses()) {
      if (innerClass.getSimpleName().equals(className)) {
        return innerClass;
      }
    }
    return null;
  }

  /** Returns a {@link MediaItem} that has all fields set to non-default values. */
  public static MediaItem buildFullyCustomizedMediaItem() {
    return new MediaItem.Builder()
        .setUri("http://custom.uri.test")
        .setCustomCacheKey("custom.cache")
        .setMediaId("custom.id")
        .setMediaMetadata(new MediaMetadata.Builder().setTitle("custom.title").build())
        .setClippingConfiguration(
            new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(123).build())
        .setAdsConfiguration(
            new MediaItem.AdsConfiguration.Builder(Uri.parse("http:://custom.ad.test")).build())
        .setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(UUID.randomUUID()).build())
        .setLiveConfiguration(
            new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(234).build())
        .setMimeType("mime")
        .setRequestMetadata(
            new MediaItem.RequestMetadata.Builder().setSearchQuery("custom.query").build())
        .setStreamKeys(ImmutableList.of(new StreamKey(/* groupIndex= */ 0, /* streamIndex= */ 0)))
        .setTag("tag")
        .setSubtitleConfigurations(
            ImmutableList.of(
                new MediaItem.SubtitleConfiguration.Builder(
                        Uri.parse("http://custom.subtitle.test"))
                    .build()))
        .build();
  }

  /** Returns a {@link Bundle} that will throw an exception at the first attempt to read a value. */
  public static Bundle getThrowingBundle() {
    // Create Bundle containing a Parcelable class that will require a ClassLoader.
    Bundle bundle = new Bundle();
    bundle.putParcelable("0", new StreamKey(0, 0));
    // Serialize this Bundle to a Parcel to remove the direct object reference.
    Parcel parcel = Parcel.obtain();
    parcel.writeBundle(bundle);
    // Read the same Bundle from the Parcel again, but with a ClassLoader that can't load the class.
    parcel.setDataPosition(0);
    ClassLoader throwingClassLoader =
        new ClassLoader() {
          @Override
          public Class<?> loadClass(String name) throws ClassNotFoundException {
            throw new ClassNotFoundException();
          }
        };
    return checkNotNull(parcel.readBundle(throwingClassLoader));
  }

  /**
   * Returns a randomly generated float within the specified range, using {@code random} as random
   * number generator.
   *
   * <p>{@code range} must be a bounded range.
   */
  public static float generateFloatInRange(Random random, Range<Float> range) {
    float bottom =
        range.lowerBoundType() == BoundType.OPEN
            ? Math.nextUp(range.lowerEndpoint())
            : range.lowerEndpoint();
    float top =
        range.upperBoundType() == BoundType.OPEN
            ? Math.nextDown(range.upperEndpoint())
            : range.upperEndpoint();

    return bottom + random.nextFloat() * (top - bottom);
  }

  /**
   * Returns a long between {@code origin} (inclusive) and {@code bound} (exclusive), given {@code
   * random}.
   */
  public static long generateLong(Random random, long origin, long bound) {
    return (long) (origin + random.nextFloat() * (bound - origin));
  }

  /**
   * Returns a non-random {@link ByteBuffer} filled with {@code frameCount * bytesPerFrame} bytes.
   */
  public static ByteBuffer getNonRandomByteBuffer(int frameCount, int bytesPerFrame) {
    int bufferSize = frameCount * bytesPerFrame;
    ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder());
    for (int i = 0; i < bufferSize; i++) {
      buffer.put((byte) i);
    }
    buffer.rewind();
    return buffer;
  }

  /**
   * Returns a {@link ByteBuffer} filled with alternating 16-bit PCM samples as per the provided
   * period length.
   *
   * <p>The generated samples alternate between {@link Short#MAX_VALUE} and {@link Short#MIN_VALUE}
   * every {@code period / 2} samples.
   *
   * @param sampleCount Number of total PCM samples (not frames) to generate.
   * @param period Length in PCM samples of one full cycle.
   */
  public static ByteBuffer getPeriodicSamplesBuffer(int sampleCount, int period) {
    int halfPeriod = period / 2;
    ByteBuffer buffer = ByteBuffer.allocateDirect(sampleCount * 2).order(ByteOrder.nativeOrder());
    boolean isHigh = false;
    int counter = 0;
    while (counter < sampleCount) {
      short sample = isHigh ? Short.MAX_VALUE : Short.MIN_VALUE;
      for (int i = 0; i < halfPeriod && counter < sampleCount; i++) {
        buffer.putShort(sample);
        counter++;
      }
      isHigh = !isHigh;
    }
    buffer.rewind();
    return buffer;
  }

  /**
   * Creates a {@link SurfaceView} for tests where the creation is moved to the main thread if run
   * on a non-Looper thread. This is needed on API &lt; 26 where {@link SurfaceView} cannot be
   * created on a non-Looper thread.
   */
  public static SurfaceView createSurfaceView(Context context) {
    if (SDK_INT >= 26 || Looper.myLooper() != null) {
      return new SurfaceView(context);
    }
    AtomicReference<SurfaceView> surfaceView = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> surfaceView.set(new SurfaceView(context)));
    return surfaceView.get();
  }

  /**
   * Repeats the potentially flaky test multiple times if needed.
   *
   * <p>Only use this method for tests with inherent randomness or systems-under-test that are not
   * fully controllable, and where the reason for the the flakiness can be explained. Don't use this
   * method if the test should always pass reliably.
   *
   * @param maxRepetitions The maximum number of repetitions before failing the test.
   * @param testImpl The test implementation.
   */
  public static void repeatFlakyTest(int maxRepetitions, ThrowingRunnable testImpl) {
    for (int i = 0; i < maxRepetitions; i++) {
      try {
        testImpl.run();
        break;
      } catch (Throwable e) {
        if (i == maxRepetitions - 1) {
          Util.sneakyThrow(e);
        }
      }
    }
  }

  private static final class NoUidOrShufflingTimeline extends Timeline {

    private final Timeline delegate;

    public NoUidOrShufflingTimeline(Timeline timeline) {
      this.delegate = timeline;
    }

    @Override
    public int getWindowCount() {
      return delegate.getWindowCount();
    }

    @Override
    public int getNextWindowIndex(int windowIndex, int repeatMode, boolean shuffleModeEnabled) {
      return delegate.getNextWindowIndex(windowIndex, repeatMode, /* shuffleModeEnabled= */ false);
    }

    @Override
    public int getPreviousWindowIndex(int windowIndex, int repeatMode, boolean shuffleModeEnabled) {
      return delegate.getPreviousWindowIndex(
          windowIndex, repeatMode, /* shuffleModeEnabled= */ false);
    }

    @Override
    public int getLastWindowIndex(boolean shuffleModeEnabled) {
      return delegate.getLastWindowIndex(/* shuffleModeEnabled= */ false);
    }

    @Override
    public int getFirstWindowIndex(boolean shuffleModeEnabled) {
      return delegate.getFirstWindowIndex(/* shuffleModeEnabled= */ false);
    }

    @Override
    public Window getWindow(int windowIndex, Window window, long defaultPositionProjectionUs) {
      delegate.getWindow(windowIndex, window, defaultPositionProjectionUs);
      window.uid = 0;
      return window;
    }

    @Override
    public int getPeriodCount() {
      return delegate.getPeriodCount();
    }

    @Override
    public Period getPeriod(int periodIndex, Period period, boolean setIds) {
      delegate.getPeriod(periodIndex, period, setIds);
      period.uid = 0;
      return period;
    }

    @Override
    public int getIndexOfPeriod(Object uid) {
      return delegate.getIndexOfPeriod(uid);
    }

    @Override
    public Object getUidOfPeriod(int periodIndex) {
      return 0;
    }
  }
}
