package com.klob.echo.core;

import java.util.Locale;

/** Whole spoken phrases, never independent keyword matches. */
public final class CameraCommand {
  public static final String BACK = "echo back echo";
  public static final String FRONT = "echo front echo";

  public static String normalize(String text) {
    return text == null ? "" : text.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
  }

  public static boolean valid(String phrase) {
    String p = normalize(phrase);
    return p.length() >= 3 && p.length() <= 100 && p.matches("[a-z]+(?:[ '-][a-z]+)*");
  }

  public static String match(String text, String back, String front) {
    String spoken = " " + normalize(text) + " ";
    boolean b = spoken.contains(" " + normalize(back) + " ");
    boolean f = spoken.contains(" " + normalize(front) + " ");
    // Ambiguous utterances must not choose a camera arbitrarily.
    return b == f ? null : b ? "back" : "front";
  }
}
