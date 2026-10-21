/* Copyright 2026 The Android Open Source Project. Licensed under the Apache License, Version 2.0. */
package com.google.android.exoplayer2.extractor.ts;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.util.SparseArray;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ForwardingExtractorInput;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.testutil.FakeExtractorInput;
import com.google.android.exoplayer2.testutil.FakeExtractorOutput;
import com.google.android.exoplayer2.testutil.FakeTrackOutput;
import com.google.android.exoplayer2.testutil.TestUtil;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.TimestampAdjuster;
import com.google.android.exoplayer2.util.Util;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Byte/sample equivalence and bounded cancellation boundaries for the old-API HLS fast path. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 22, 23, 29})
public final class TsHlsBatchingTest {
  @Test
  public void apiAndModeGuard() throws Exception {
    assertThat(field(newExtractor(false), "hlsPayloadBuffer") != null)
        .isEqualTo(Util.SDK_INT <= 22);
    for (int mode : new int[] {TsExtractor.MODE_SINGLE_PMT, TsExtractor.MODE_MULTI_PMT}) {
      assertThat(field(new TsExtractor(mode, new TimestampAdjuster(0),
          new DefaultTsPayloadReaderFactory()), "hlsPayloadBuffer")).isNull();
    }
  }

  @Test
  public void realFixturesMatchNormalParserAtReadBoundaries() throws Exception {
    for (String asset : new String[] {
        "sample_h264_mpeg_audio.ts", "sample_h264_dts_audio.ts",
        "sample_h264_no_access_unit_delimiters.ts", "sample_h265.ts",
        "sample_h262_mpeg_audio.ts", "sample_ac3.ts", "sample_latm.ts"}) {
      byte[] bytes = TestUtil.getByteArray(ApplicationProvider.getApplicationContext(),
          "media/ts/" + asset);
      FakeExtractorOutput expected = extract(bytes, 9400, true);
      for (int chunk : new int[] {188, 257, 9400}) {
        assertOutputsEqual(expected, extract(bytes, chunk, false));
      }
    }
  }

  @Test
  public void truncatedSegmentsAndJunkMatchNormalParser() throws Exception {
    byte[] original = TestUtil.getByteArray(ApplicationProvider.getApplicationContext(),
        "media/ts/sample_h264_mpeg_audio.ts");
    for (int tail : new int[] {1, 7, 187, 188, 189}) {
      byte[] bytes = Arrays.copyOf(original, original.length - tail);
      assertOutputsEqual(extract(bytes, 188, true), extract(bytes, 257, false));
    }
    byte[] junkPrefix = new byte[original.length + 5];
    System.arraycopy(original, 0, junkPrefix, 5, original.length);
    assertOutputsEqual(extract(junkPrefix, 188, true), extract(junkPrefix, 9400, false));
  }

  @Test
  public void readReturnsAfterAtMost32BufferedPackets() throws Exception {
    CountingReader reader = new CountingReader();
    TsExtractor extractor = countingExtractor(reader);
    FakeExtractorInput input = input(packets(60, 0x10));
    PositionHolder seek = new PositionHolder();
    assertThat(extractor.read(input, seek)).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(reader.calls).isEqualTo(Util.SDK_INT <= 22 ? 32 : 1);
    // Loader cancellation can occur after this return; another read alone consumes more packets.
    int consumed = reader.calls;
    assertThat(extractor.read(input, seek)).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(reader.calls - consumed).isAtMost(32);
  }

  @Test
  public void adaptationFlagsAndEmptyAdaptationArePreserved() throws Exception {
    byte[] bytes = packets(2, 0x30);
    bytes[4] = 0; // Empty adaptation field must not consume the first payload byte as flags.
    bytes[5] = 0x55;
    bytes[188 + 4] = 1;
    bytes[188 + 5] = 0x40;
    CountingReader reader = new CountingReader();
    TsExtractor extractor = countingExtractor(reader);
    FakeExtractorInput input = input(bytes);
    PositionHolder seek = new PositionHolder();
    while (extractor.read(input, seek) != Extractor.RESULT_END_OF_INPUT) {}
    assertThat(reader.calls).isEqualTo(2);
    assertThat(reader.firstByte).isEqualTo(0x55);
    assertThat(reader.lastFlags & TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR).isNotEqualTo(0);
  }

  @Test
  public void transportErrorsAndMissingPayloadDoNotReachReader() throws Exception {
    byte[] bytes = packets(3, 0x10);
    bytes[1] |= (byte) 0x80;
    bytes[188 + 3] = 0x20;
    CountingReader reader = new CountingReader();
    TsExtractor extractor = countingExtractor(reader);
    FakeExtractorInput input = input(bytes);
    PositionHolder seek = new PositionHolder();
    while (extractor.read(input, seek) != Extractor.RESULT_END_OF_INPUT) {}
    assertThat(reader.calls).isEqualTo(1);
  }

  @Test
  public void oldApiMalformedAdaptationFailsWithinPacket() throws Exception {
    if (Util.SDK_INT > 22) return;
    byte[] bytes = packets(1, 0x30);
    bytes[4] = (byte) 255;
    assertThrows(ParserException.class,
        () -> countingExtractor(new CountingReader()).read(input(bytes), new PositionHolder()));
  }

  @Test
  public void oldApiMissingSyncIsRejected() throws Exception {
    if (Util.SDK_INT > 22) return;
    TsExtractor extractor = newExtractor(false);
    assertThrows(ParserException.class,
        () -> extractor.read(input(new byte[188 * 4]), new PositionHolder()));
  }

  private static TsExtractor newExtractor(boolean normalParser) throws Exception {
    TsExtractor extractor = new TsExtractor(TsExtractor.MODE_HLS, new TimestampAdjuster(0),
        new DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS));
    if (normalParser) {
      Field f = TsExtractor.class.getDeclaredField("hlsPayloadBuffer");
      f.setAccessible(true);
      f.set(extractor, null);
    }
    extractor.init(new FakeExtractorOutput());
    return extractor;
  }

  private static FakeExtractorOutput extract(byte[] bytes, int chunk, boolean normal)
      throws Exception {
    TsExtractor extractor = newExtractor(normal);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput raw = input(bytes);
    ForwardingExtractorInput input = new ForwardingExtractorInput(raw) {
      @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        return super.read(buffer, offset, Math.min(chunk, length));
      }
    };
    PositionHolder seek = new PositionHolder();
    int steps = 0;
    while (extractor.read(input, seek) != Extractor.RESULT_END_OF_INPUT) {
      assertThat(++steps).isAtMost(bytes.length * 2 + 1);
    }
    extractor.release();
    return output;
  }

  private static void assertOutputsEqual(FakeExtractorOutput expected, FakeExtractorOutput actual) {
    assertThat(actual.numberOfTracks).isEqualTo(expected.numberOfTracks);
    assertThat(actual.tracksEnded).isEqualTo(expected.tracksEnded);
    assertThat(actual.seekMap == null).isEqualTo(expected.seekMap == null);
    for (int i = 0; i < expected.trackOutputs.size(); i++) {
      int id = expected.trackOutputs.keyAt(i);
      FakeTrackOutput a = actual.trackOutputs.get(id), e = expected.trackOutputs.valueAt(i);
      assertThat(a).isNotNull();
      assertThat(a.lastFormat).isEqualTo(e.lastFormat);
      assertThat(a.getSampleCount()).isEqualTo(e.getSampleCount());
      for (int sample = 0; sample < e.getSampleCount(); sample++) {
        a.assertSample(sample, e.getSampleData(sample), e.getSampleTimeUs(sample),
            e.getSampleFlags(sample), e.getSampleCryptoData(sample));
      }
    }
  }

  private static FakeExtractorInput input(byte[] bytes) {
    return new FakeExtractorInput.Builder().setData(bytes).setSimulateIOErrors(false)
        .setSimulatePartialReads(false).setSimulateUnknownLength(false).build();
  }

  private static Object field(Object instance, String name) throws Exception {
    Field field = instance.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(instance);
  }

  @SuppressWarnings("unchecked")
  private static TsExtractor countingExtractor(CountingReader reader) throws Exception {
    TsExtractor extractor = newExtractor(false);
    ((SparseArray<TsPayloadReader>) field(extractor, "tsPayloadReaders")).put(256, reader);
    return extractor;
  }

  private static byte[] packets(int count, int control) {
    byte[] bytes = new byte[count * 188];
    for (int i = 0; i < count; i++) {
      int offset = i * 188;
      bytes[offset] = 0x47;
      bytes[offset + 1] = 1;
      bytes[offset + 3] = (byte) (control | i % 16);
    }
    return bytes;
  }

  private static final class CountingReader implements TsPayloadReader {
    int calls, firstByte = -1, lastFlags;
    @Override public void init(TimestampAdjuster adjuster,
        com.google.android.exoplayer2.extractor.ExtractorOutput output, TrackIdGenerator id) {}
    @Override public void seek() {}
    @Override public void consume(ParsableByteArray data, int flags) {
      if (calls++ == 0 && data.bytesLeft() > 0) firstByte = data.peekUnsignedByte();
      lastFlags = flags;
      data.setPosition(data.limit());
    }
  }
}
