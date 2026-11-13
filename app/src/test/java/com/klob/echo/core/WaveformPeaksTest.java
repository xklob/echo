package com.klob.echo.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class WaveformPeaksTest {
  @Test
  public void preservesPeaksAcrossUnevenDecoderChunksAndTail() {
    WaveformPeaks waveform = new WaveformPeaks();
    short[] a = new short[79];
    a[20] = -32768;
    short[] b = new short[82];
    b[0] = 30000;
    b[81] = 1234;
    waveform.accept(a, a.length);
    waveform.accept(b, b.length);
    short[][] peaks = waveform.finish();
    assertEquals(161, waveform.samples());
    assertEquals(2, peaks[0].length);
    assertEquals(-32768, peaks[0][0]);
    assertEquals(30000, peaks[1][0]);
    assertEquals(1234, peaks[1][1]);
  }

  @Test
  public void longWaveformCompactsWithoutLosingTimingOrImpulses() {
    WaveformPeaks waveform = new WaveformPeaks();
    short[] pcm = new short[160];
    pcm[1] = 20000;
    for (int i = 0; i < 262145; i++) waveform.accept(pcm, pcm.length);
    short[][] peaks = waveform.finish();
    assertEquals(320, waveform.samplesPerBin());
    assertEquals(131073, peaks[0].length);
    assertEquals(262145L * 160, waveform.samples());
    assertEquals(20000, peaks[1][peaks[1].length - 1]);
  }
}
