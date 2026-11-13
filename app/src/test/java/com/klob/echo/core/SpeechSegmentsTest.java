package com.klob.echo.core;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class SpeechSegmentsTest {
  @Test
  public void shortBurstsAndNoiseAreNotSpeech() {
    SpeechSegments segments = new SpeechSegments();
    for (int i = 0; i < 50; i++) segments.accept(i == 10 ? .9f : .05f, i * 32, (i + 1) * 32);
    assertTrue(segments.finish().isEmpty());
  }

  @Test
  public void speechSurvivesShortPauseButLongPauseSeparatesIt() {
    SpeechSegments segments = new SpeechSegments();
    for (int i = 0; i < 100; i++) {
      boolean speech = i >= 10 && i < 30 || i >= 35 && i < 50 || i >= 70 && i < 90;
      segments.accept(speech ? .9f : .05f, i * 32, (i + 1) * 32);
    }
    List<long[]> result = segments.finish();
    assertEquals(2, result.size());
    assertArrayEquals(new long[] {220, 1700}, result.get(0));
    assertArrayEquals(new long[] {2140, 2980}, result.get(1));
  }

  @Test
  public void speechAtEndIsClampedToCapturedDuration() {
    SpeechSegments segments = new SpeechSegments();
    for (int i = 0; i < 10; i++) segments.accept(.9f, i * 32, (i + 1) * 32);
    assertArrayEquals(new long[] {0, 320}, segments.finish().get(0));
  }
}
