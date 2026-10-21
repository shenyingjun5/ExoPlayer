/* Copyright 2026 The Android Open Source Project. Licensed under the Apache License, Version 2.0. */
package com.google.android.exoplayer2.extractor.ts;

import static com.google.common.truth.Truth.assertThat;

import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.testutil.FakeExtractorOutput;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.TimestampAdjuster;
import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Exercises actual AVC reader accounting at PES/start/EOF/seek boundaries. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 22, 23, 29})
public final class PesH264BatchingTest {
  @Test
  public void unboundedBodyIsBoundedAndFinalTailFlushesOnce() throws Exception {
    H264Reader elementary = avc();
    PesReader reader = pes(elementary);
    reader.enableH264BodyBatching();
    startUnbounded(reader);
    for (int i = 0; i < 100; i++) reader.consume(new ParsableByteArray(new byte[184]), 0);
    assertThat(bytes(elementary)).isEqualTo(16384);
    assertThat((Integer) field(reader, "h264BodySize")).isEqualTo(2016);
    reader.flushH264Body();
    assertThat(bytes(elementary)).isEqualTo(18400);
    reader.flushH264Body();
    assertThat(bytes(elementary)).isEqualTo(18400);
  }

  @Test
  public void newPesFlushesOldBodyBeforeStartingPacket() throws Exception {
    H264Reader elementary = avc();
    PesReader reader = pes(elementary);
    reader.enableH264BodyBatching();
    startUnbounded(reader);
    reader.consume(new ParsableByteArray(new byte[37]), 0);
    assertThat(bytes(elementary)).isEqualTo(0);
    startUnbounded(reader);
    assertThat(bytes(elementary)).isEqualTo(37);
    assertThat((Integer) field(reader, "h264BodySize")).isEqualTo(0);
  }

  @Test
  public void seekDiscardsUncommittedTailAndFreshPesRemainsClean() throws Exception {
    H264Reader elementary = avc();
    PesReader reader = pes(elementary);
    reader.enableH264BodyBatching();
    startUnbounded(reader);
    reader.consume(new ParsableByteArray(new byte[91]), 0);
    reader.seek();
    reader.flushH264Body();
    assertThat(bytes(elementary)).isEqualTo(0);
    startUnbounded(reader);
    reader.consume(new ParsableByteArray(new byte[33]), 0);
    reader.flushH264Body();
    assertThat(bytes(elementary)).isEqualTo(33);
  }

  @Test
  public void finitePesAndEverySplitHeaderStayOnOriginalParser() throws Exception {
    for (int split = 1; split < 9; split++) {
      H264Reader elementary = avc();
      PesReader reader = pes(elementary);
      reader.enableH264BodyBatching();
      byte[] header = header(40 + 3);
      reader.consume(new ParsableByteArray(Arrays.copyOfRange(header, 0, split)),
          TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR);
      reader.consume(new ParsableByteArray(Arrays.copyOfRange(header, split, 9)), 0);
      reader.consume(new ParsableByteArray(new byte[17]), 0);
      assertThat(bytes(elementary)).isEqualTo(17);
      reader.consume(new ParsableByteArray(new byte[23]), 0);
      assertThat(bytes(elementary)).isEqualTo(40);
      assertThat((Integer) field(reader, "h264BodySize")).isEqualTo(0);
    }
  }

  @Test
  public void unboundedSplitHeaderKeepsAllBytesAndStartSemantics() throws Exception {
    for (int split = 1; split < 9; split++) {
      H264Reader elementary = avc();
      PesReader reader = pes(elementary);
      reader.enableH264BodyBatching();
      byte[] header = header(0);
      reader.consume(new ParsableByteArray(Arrays.copyOfRange(header, 0, split)),
          TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR);
      reader.consume(new ParsableByteArray(Arrays.copyOfRange(header, split, 9)), 0);
      reader.consume(new ParsableByteArray(new byte[51]), 0);
      reader.flushH264Body();
      assertThat(bytes(elementary)).isEqualTo(51);
    }
  }

  @Test
  public void nonAvcNeverAllocatesOrDelaysBody() throws Exception {
    RecordingReader elementary = new RecordingReader();
    PesReader reader = pes(elementary);
    reader.enableH264BodyBatching();
    assertThat(field(reader, "h264Body")).isNull();
    startUnbounded(reader);
    reader.consume(new ParsableByteArray(new byte[99]), 0);
    assertThat(elementary.bytes).isEqualTo(99);
    assertThat(elementary.starts).isEqualTo(1);
    startUnbounded(reader);
    assertThat(elementary.finished).isEqualTo(1);
    reader.flushH264Body();
    assertThat(elementary.bytes).isEqualTo(99);
  }

  private static H264Reader avc() {
    return new H264Reader(new SeiReader(ImmutableList.of()), false, false);
  }

  private static PesReader pes(ElementaryStreamReader elementary) {
    PesReader reader = new PesReader(elementary);
    reader.init(new TimestampAdjuster(0), new FakeExtractorOutput(),
        new TsPayloadReader.TrackIdGenerator(256, 1));
    return reader;
  }

  private static void startUnbounded(PesReader reader) throws Exception {
    // Include a PTS extension so this call fully parses the header. A header-only nine-byte
    // fragment intentionally leaves extension parsing for the next body-bearing call.
    byte[] header = {0, 0, 1, (byte) 0xe0, 0, 0, (byte) 0x84,
        (byte) 0x80, 5, 0x21, 0, 1, 0, 1};
    reader.consume(new ParsableByteArray(header), TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR);
  }

  private static byte[] header(int packetLength) {
    return new byte[] {0, 0, 1, (byte) 0xe0, (byte) (packetLength >> 8),
        (byte) packetLength, (byte) 0x84, 0, 0};
  }

  private static Object field(Object instance, String name) throws Exception {
    Field f = instance.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(instance);
  }

  private static long bytes(H264Reader reader) throws Exception {
    return (Long) field(reader, "totalBytesWritten");
  }

  private static final class RecordingReader implements ElementaryStreamReader {
    int bytes, starts, finished;
    @Override public void seek() {}
    @Override public void createTracks(ExtractorOutput output, TsPayloadReader.TrackIdGenerator id) {}
    @Override public void packetStarted(long timeUs, int flags) { starts++; }
    @Override public void consume(ParsableByteArray data) {
      bytes += data.bytesLeft();
      data.setPosition(data.limit());
    }
    @Override public void packetFinished() { finished++; }
  }
}
