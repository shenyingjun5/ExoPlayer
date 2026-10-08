/*
 * Copyright 2026 The Android Open Source Project
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
package com.google.android.exoplayer2.source.rtsp;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.testutil.FakeExtractorInput;
import com.google.android.exoplayer2.testutil.FakeExtractorOutput;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises H.264/AAC timeline mapping through the actual extractor dispatch paths. */
@RunWith(AndroidJUnit4.class)
public final class RtpAvTimestampMappingTest {

  @Test
  public void rtpInfo_twoSecondStartupGap_withoutDiagnostics_keepsSharedTime() throws Exception {
    Tracks tracks = new Tracks(0, 0, /* diagnostics= */ false);
    tracks.read(180_000, 96_000, 1);
    tracks.assertTimes(0, 2_000_000, 2_000_000);
  }

  @Test
  public void rtpInfo_twoSecondStartupGap_withDiagnostics_keepsSharedTime() throws Exception {
    Tracks tracks = new Tracks(0, 0, /* diagnostics= */ true);
    tracks.read(180_000, 96_000, 1);
    tracks.assertTimes(0, 2_000_000, 2_000_000);
  }

  @Test
  public void rtpInfo_independentNonzeroOrigins_preservesRealAudioOffset() throws Exception {
    Tracks tracks = new Tracks(9_000_000, 7_000_000, /* diagnostics= */ true);
    tracks.read(9_180_000, 7_097_920, 1);
    tracks.assertTimes(0, 2_000_000, 2_040_000);
  }

  @Test
  public void rtpInfo_firstPacketsAtOrigins_startsBothTracksAtZero() throws Exception {
    Tracks tracks = new Tracks(9_000_000, 7_000_000, /* diagnostics= */ false);
    tracks.read(9_000_000, 7_000_000, 1);
    tracks.assertTimes(0, 0, 0);
  }

  @Test
  public void missingRtpInfo_usesEachTracksFirstPacketAndRetainsProgression() throws Exception {
    for (boolean diagnostics : new boolean[] {false, true}) {
      Tracks tracks = new Tracks(C.TIME_UNSET, C.TIME_UNSET, diagnostics);
      tracks.read(9_180_000, 7_096_000, 1);
      tracks.assertTimes(0, 0, 0);
      tracks.read(9_270_000, 7_144_000, 2);
      tracks.assertTimes(1, 1_000_000, 1_000_000);
    }
  }

  @Test
  public void rtpInfo_timestampWrap_keepsSharedTime() throws Exception {
    long videoOrigin = 0xFFFFFFFFL - 90_000;
    long audioOrigin = 0xFFFFFFFFL - 48_000;
    Tracks tracks = new Tracks(videoOrigin, audioOrigin, /* diagnostics= */ true);
    tracks.read((videoOrigin + 180_000) & 0xFFFFFFFFL,
        (audioOrigin + 96_000) & 0xFFFFFFFFL, 1);
    tracks.assertTimes(0, 2_000_000, 2_000_000);
  }

  @Test
  public void seek_replacesBothOriginsAndAppliesNonzeroNpt() throws Exception {
    Tracks tracks = new Tracks(0, 0, /* diagnostics= */ true);
    tracks.read(180_000, 96_000, 1);
    tracks.assertTimes(0, 2_000_000, 2_000_000);
    tracks.video.preSeek();
    tracks.audio.preSeek();
    tracks.video.seek(9_000_000, 10_000_000);
    tracks.audio.seek(7_000_000, 10_000_000);
    // The first read applies the pending seek and intentionally discards that packet.
    tracks.read(9_000_000, 7_000_000, 2);
    tracks.read(9_180_000, 7_096_000, 3);
    tracks.assertTimes(1, 12_000_000, 12_000_000);
  }

  @Test
  public void nonzeroInitialNpt_usesSeekMappingAfterFirstPacketNotification() throws Exception {
    Tracks tracks = new Tracks(0, 0, /* diagnostics= */ true);
    tracks.video.seek(9_000_000, 10_000_000);
    tracks.audio.seek(7_000_000, 10_000_000);
    tracks.read(9_000_000, 7_000_000, 1);
    tracks.read(9_180_000, 7_096_000, 2);
    tracks.assertTimes(0, 12_000_000, 12_000_000);
  }

  @Test
  public void rebuiltSession_usesNewTrackOriginsWithoutCarryingOldTime() throws Exception {
    Tracks oldSession = new Tracks(0, 0, /* diagnostics= */ true);
    oldSession.read(180_000, 96_000, 1);
    oldSession.assertTimes(0, 2_000_000, 2_000_000);
    oldSession.video.release();
    oldSession.audio.release();
    Tracks newSession = new Tracks(9_000_000, 7_000_000, /* diagnostics= */ true);
    newSession.read(9_045_000, 7_024_000, 1);
    newSession.assertTimes(0, 500_000, 500_000);
  }

  private static final class Tracks {
    final FakeExtractorOutput videoOutput = new FakeExtractorOutput();
    final FakeExtractorOutput audioOutput = new FakeExtractorOutput();
    final RtpExtractor video;
    final RtpExtractor audio;

    Tracks(long videoOrigin, long audioOrigin, boolean diagnostics) {
      @Nullable RtspDiagnosticsListener listener =
          diagnostics ? new RtspDiagnosticsListener() {} : null;
      RtpPayloadFormat videoFormat = new RtpPayloadFormat(
          new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
              .setInitializationData(ImmutableList.of(
                  new byte[] {0x67, 0x42, 0, 0x1E},
                  new byte[] {0x68, (byte) 0xCE, 0x06, (byte) 0xE2})).build(),
          96, 90_000, ImmutableMap.of(), RtpPayloadFormat.RTP_MEDIA_H264);
      RtpPayloadFormat audioFormat = new RtpPayloadFormat(
          new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
              .setSampleRate(48_000).setChannelCount(2).build(),
          97, 48_000, ImmutableMap.of("mode", "AAC-hbr"),
          RtpPayloadFormat.RTP_MEDIA_MPEG4_GENERIC);
      video = createExtractor(videoFormat, 0, listener);
      audio = createExtractor(audioFormat, 1, listener);
      video.init(videoOutput);
      audio.init(audioOutput);
      if (videoOrigin != C.TIME_UNSET) {
        video.setFirstTimestamp(videoOrigin);
      }
      if (audioOrigin != C.TIME_UNSET) {
        audio.setFirstTimestamp(audioOrigin);
      }
    }

    void read(long videoTimestamp, long audioTimestamp, int sequence) throws Exception {
      readPacket(video, 96, videoTimestamp, sequence, new byte[] {0x65, 0x05, 0x06});
      // AU-headers-length=16 bits; one AAC-hbr AU, size=1/index=0, one payload byte.
      readPacket(audio, 97, audioTimestamp, sequence, new byte[] {0, 16, 0, 8, (byte) 0xAA});
    }

    void assertTimes(int sampleIndex, long videoUs, long audioUs) {
      assertThat(videoOutput.trackOutputs.get(0).getSampleCount()).isEqualTo(sampleIndex + 1);
      assertThat(audioOutput.trackOutputs.get(1).getSampleCount()).isEqualTo(sampleIndex + 1);
      assertThat(videoOutput.trackOutputs.get(0).getSampleTimeUs(sampleIndex)).isEqualTo(videoUs);
      assertThat(audioOutput.trackOutputs.get(1).getSampleTimeUs(sampleIndex)).isEqualTo(audioUs);
    }
  }

  private static RtpExtractor createExtractor(
      RtpPayloadFormat format, int track, @Nullable RtspDiagnosticsListener listener) {
    return new RtpExtractor(format, track, RtspTransportMode.TCP_INTERLEAVED, listener,
        /* rtcpFeedbackRequester= */ null, RtcpFeedbackPolicy.DEFAULT,
        RtspBacklogRecoveryPolicy.DISABLED, /* rtspPacketDiagnosticsEnabled= */ false);
  }

  private static void readPacket(
      RtpExtractor extractor, int payloadType, long timestamp, int sequence, byte[] payload)
      throws Exception {
    RtpPacket packet = new RtpPacket.Builder().setMarker(true)
        .setPayloadType((byte) payloadType).setSequenceNumber(sequence).setTimestamp(timestamp)
        .setSsrc(0x12345678).setPayloadData(payload).build();
    byte[] data = new byte[12 + payload.length];
    assertThat(packet.writeToBuffer(data, 0, data.length)).isEqualTo(data.length);
    extractor.read(new FakeExtractorInput.Builder().setData(data).build(), new PositionHolder());
  }
}
