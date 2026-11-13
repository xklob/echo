package com.klob.echo.library;

public final class LocationPoint {
  public final double latitude, longitude, elevation;
  public final long time;

  public LocationPoint(double latitude, double longitude, double elevation, long time) {
    this.latitude = latitude;
    this.longitude = longitude;
    this.elevation = elevation;
    this.time = time;
  }

  public static boolean valid(double latitude, double longitude, long time) {
    return Double.isFinite(latitude)
        && Math.abs(latitude) <= 90
        && Double.isFinite(longitude)
        && Math.abs(longitude) <= 180
        && time > 0;
  }
}
