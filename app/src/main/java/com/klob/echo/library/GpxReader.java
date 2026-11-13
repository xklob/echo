package com.klob.echo.library;

import android.util.Xml;
import java.io.*;
import java.time.Instant;
import java.util.*;
import org.xmlpull.v1.XmlPullParser;

/** Reads saved fixes only. XML declarations/entities cannot trigger external resource requests. */
public final class GpxReader {
  public static final long MAX_BYTES = 16 * 1024 * 1024;
  public static final int MAX_POINTS = 60000;

  public static List<List<LocationPoint>> read(File file) throws IOException {
    if (file.length() > MAX_BYTES) throw new IOException("Location file is too large");
    List<List<LocationPoint>> segments = new ArrayList<>();
    List<LocationPoint> segment = null;
    try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
      XmlPullParser p = Xml.newPullParser();
      p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
      p.setInput(in, "UTF-8");
      boolean point = false;
      double lat = Double.NaN, lon = Double.NaN, ele = Double.NaN;
      long time = 0;
      int count = 0;
      int depth = 0;
      boolean root = false;
      for (int event = p.nextToken(); event != XmlPullParser.END_DOCUMENT; event = p.nextToken()) {
        if (event == XmlPullParser.DOCDECL || event == XmlPullParser.ENTITY_REF)
          throw new IOException("Location XML entities are unsupported");
        if (event == XmlPullParser.START_TAG) {
          depth++;
          if (depth == 1) {
            if (root || !p.getName().equals("gpx")) throw new IOException("Invalid GPX root");
            root = true;
          }
          String name = p.getName();
          if (name.equals("trkseg")) segment = null;
          else if (name.equals("trkpt")) {
            point = true;
            lat = number(p.getAttributeValue(null, "lat"));
            lon = number(p.getAttributeValue(null, "lon"));
            ele = Double.NaN;
            time = 0;
          } else if (point && name.equals("time")) {
            depth--; // nextText consumes this element's end tag.
            try {
              time = Instant.parse(p.nextText().trim()).toEpochMilli();
            } catch (RuntimeException ignored) {
              time = 0;
            }
          } else if (point && name.equals("ele")) {
            depth--;
            ele = number(p.nextText().trim());
          }
        } else if (event == XmlPullParser.END_TAG) {
          depth--;
          if (p.getName().equals("trkpt")) {
            point = false;
            if (++count > MAX_POINTS) throw new IOException("Too many location fixes");
            if (!LocationPoint.valid(lat, lon, time)) {
              segment = null;
              continue;
            }
            if (segment != null && !segment.isEmpty()) {
              long previous = segment.get(segment.size() - 1).time;
              if (time <= previous || time - previous > 60000) segment = null;
            }
            if (segment == null) {
              segment = new ArrayList<>();
              segments.add(segment);
            }
            segment.add(new LocationPoint(lat, lon, Double.isFinite(ele) ? ele : Double.NaN, time));
          } else if (p.getName().equals("trkseg")) segment = null;
        }
      }
      if (!root || depth != 0) throw new IOException("Incomplete location data");
    } catch (org.xmlpull.v1.XmlPullParserException e) {
      throw new IOException("Unreadable location data", e);
    }
    return segments;
  }

  private static double number(String value) {
    try {
      return Double.parseDouble(value);
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }
}
