package com.google.android.exoplayer2.source.rtsp.reader;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.source.rtsp.RtpPayloadFormat;
import com.google.android.exoplayer2.testutil.FakeTrackOutput;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class RtpAacReaderTest {
  private final FakeTrackOutput output = new FakeTrackOutput(true);
  private RtpAacReader reader(long origin) {
    return reader(origin, ImmutableMap.of("mode", "AAC-hbr", "config", "1190"));
  }
  private RtpAacReader reader(long origin, ImmutableMap<String, String> parameters) {
    RtpPayloadFormat format = new RtpPayloadFormat(new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC).setSampleRate(48000).setChannelCount(2).build(),
        97, 48000, parameters, "MPEG4-GENERIC");
    RtpAacReader reader = new RtpAacReader(format);
    ExtractorOutput extractor = mock(ExtractorOutput.class);
    when(extractor.track(anyInt(), anyInt())).thenReturn(output);
    reader.createTracks(extractor, 0);
    reader.onReceivingFirstPacket(origin, 1);
    return reader;
  }
  private static ParsableByteArray payload(int count) {
    byte[] data = new byte[2 + count * 3];
    int bits = count * 16; data[0] = (byte)(bits >> 8); data[1] = (byte)bits;
    for (int i = 0; i < count; i++) { data[2+i*2+1] = 8; data[2+count*2+i] = (byte)i; }
    return new ParsableByteArray(data);
  }
  @Test public void aggregated33LcFrames_use1024SamplesPerAccessUnit() {
    RtpAacReader reader = reader(0);
    reader.consume(payload(33), 0, 1, true);
    assertThat(output.getSampleCount()).isEqualTo(33);
    assertThat(output.getSampleTimeUs(1)).isEqualTo(21333);
    assertThat(output.getSampleTimeUs(32)).isEqualTo(682666);
  }
  @Test public void firstPacketBeforePlayAnchor_preservesNegativePreroll() {
    RtpAacReader reader = reader(2930540933L);
    reader.consume(payload(1), 2930538363L, 1, true);
    assertThat(output.getSampleTimeUs(0)).isEqualTo(-53541);
  }
  @Test public void singleAccessUnitsAndSeek_keepAnnouncedMapping() {
    RtpAacReader reader = reader(10000);
    reader.consume(payload(1), 11024, 1, true);
    reader.consume(payload(1), 12048, 2, true);
    assertThat(output.getSampleTimeUs(0)).isEqualTo(21333);
    assertThat(output.getSampleTimeUs(1)).isEqualTo(42666);
    reader.seek(20000, 5000000);
    reader.consume(payload(1), 20000, 3, true);
    assertThat(output.getSampleTimeUs(2)).isEqualTo(5000000);
  }
  @Test public void explicitConstantDuration_usesRtpTicks() {
    RtpAacReader reader = reader(0, ImmutableMap.of("mode", "AAC-hbr", "constantDuration", "960"));
    reader.consume(payload(33), 0, 1, true);
    assertThat(output.getSampleTimeUs(1)).isEqualTo(20000);
    assertThat(output.getSampleTimeUs(32)).isEqualTo(640000);
  }
  @Test public void aggregatedPackets_remainContinuousAcrossPacketBoundary() {
    RtpAacReader reader = reader(0);
    reader.consume(payload(33), 0, 1, true);
    reader.consume(payload(33), 33792, 2, true);
    assertThat(output.getSampleTimeUs(33)).isEqualTo(704000);
    assertThat(output.getSampleTimeUs(65)).isEqualTo(1386666);
  }

}
