package com.klob.echo.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class CameraCommandTest {
  @Test
  public void completePhrasesSelectOnlyOneCamera() {
    assertEquals(
        "back", CameraCommand.match(" ECHO  BACK echo ", CameraCommand.BACK, CameraCommand.FRONT));
    assertEquals(
        "front",
        CameraCommand.match("please echo front echo now", CameraCommand.BACK, CameraCommand.FRONT));
    for (String incomplete :
        new String[] {
          "echo",
          "back",
          "echo back",
          "back echo",
          "echo backpack echo",
          "echo back echo echo front echo"
        })
      assertNull(
          incomplete, CameraCommand.match(incomplete, CameraCommand.BACK, CameraCommand.FRONT));
  }

  @Test
  public void customPhrasesRemainWholeAndValidated() {
    assertTrue(CameraCommand.valid("Record the front camera"));
    assertFalse(CameraCommand.valid(""));
    assertFalse(CameraCommand.valid("\"], [unk]"));
    assertEquals(
        "front",
        CameraCommand.match(
            "record the front camera", "record the back camera", "record the front camera"));
  }
}
