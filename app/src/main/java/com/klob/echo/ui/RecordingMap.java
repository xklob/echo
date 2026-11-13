package com.klob.echo.ui;

import static org.maplibre.android.style.layers.PropertyFactory.*;

import android.content.*;
import android.content.res.Configuration;
import android.net.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.klob.echo.R;
import com.klob.echo.library.*;
import java.util.*;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.*;
import org.maplibre.android.style.layers.*;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.*;

/** Native map with local route overlays and an offline style when the basemap is unavailable. */
final class RecordingMap extends LinearLayout {
  private final RecordingContext context;
  private MapView view;
  private MapLibreMap map;
  private final TextView status;
  private boolean destroyed, fallback, started, resumed;
  private final boolean restoreCamera;
  private boolean fitted;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable mapTimeout = this::showOffline;

  RecordingMap(Context activity, RecordingContext context, Bundle saved) {
    super(activity);
    this.context = context;
    setOrientation(VERTICAL);
    restoreCamera = saved != null && saved.getBoolean("echo_camera_ready");
    fitted = restoreCamera;
    status = Ui.text(this, R.string.map_loading, 14, true);
    status.setId(R.id.clip_location_status);
    try {
      MapLibre.getInstance(activity);
      view = new MapView(activity, new MapLibreMapOptions().textureMode(true));
      view.setId(R.id.clip_map);
      view.setContentDescription(activity.getString(R.string.map_description));
      addView(view, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 280)));
      view.onCreate(saved);
      view.setOnTouchListener(
          (v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN)
              v.getParent().requestDisallowInterceptTouchEvent(true);
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                || event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL)
              v.getParent().requestDisallowInterceptTouchEvent(false);
            return false;
          });
      view.addOnDidFailLoadingMapListener(error -> showOffline());
      view.getMapAsync(
          ready -> {
            if (destroyed) return;
            map = ready;
            map.getUiSettings().setAttributionEnabled(true);
            map.getUiSettings().setCompassEnabled(true);
            ConnectivityManager connectivity = activity.getSystemService(ConnectivityManager.class);
            NetworkCapabilities network =
                connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            if (network == null
                || !network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
              showOffline();
              return;
            }
            boolean dark =
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            main.postDelayed(mapTimeout, 12000);
            map.setStyle(
                new Style.Builder()
                    .fromUri(
                        "https://tiles.openfreemap.org/styles/" + (dark ? "dark" : "positron")),
                this::draw);
          });
      Ui.text(this, R.string.map_help, 13, true);
      Ui.button(this, R.string.map_fit, false, this::fit);
    } catch (RuntimeException | UnsatisfiedLinkError e) {
      main.removeCallbacks(mapTimeout);
      if (view != null) {
        removeView(view);
        try {
          view.onDestroy();
        } catch (RuntimeException ignored) {
        }
        view = null;
      }
      status.setText(R.string.map_unavailable);
    }
    Ui.button(
        this,
        R.string.map_open,
        false,
        () -> {
          LocationPoint point = context.firstLocation;
          try {
            activity.startActivity(
                new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        String.format(
                            Locale.ROOT,
                            "geo:%f,%f?q=%f,%f",
                            point.latitude,
                            point.longitude,
                            point.latitude,
                            point.longitude))));
          } catch (RuntimeException e) {
            android.widget.Toast.makeText(
                    activity, R.string.open_failed, android.widget.Toast.LENGTH_LONG)
                .show();
          }
        });
    TextView attribution = Ui.text(this, R.string.map_attribution, 12, true);
    attribution.setText(
        android.text.Html.fromHtml(
            "© <a href=\"https://openfreemap.org\">OpenFreeMap</a> · <a"
                + " href=\"https://openmaptiles.org\">OpenMapTiles</a> · <a"
                + " href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap"
                + " contributors</a>",
            android.text.Html.FROM_HTML_MODE_LEGACY));
    attribution.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
  }

  private void showOffline() {
    if (destroyed || fallback || map == null) return;
    main.removeCallbacks(mapTimeout);
    status.setText(R.string.map_offline);
    status.setVisibility(VISIBLE);
    fallback = true;
    map.setStyle(new Style.Builder().fromJson(localStyle()), this::draw);
  }

  private String localStyle() {
    String color =
        String.format(
            Locale.ROOT,
            "#%06X",
            Ui.color(getContext(), com.google.android.material.R.attr.colorSurface) & 0xffffff);
    return "{\"version\":8,\"sources\":{},\"layers\":[{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\""
        + color
        + "\"}}]}";
  }

  private void draw(Style style) {
    if (destroyed) return;
    main.removeCallbacks(mapTimeout);
    List<Feature> lines = new ArrayList<>(), points = new ArrayList<>();
    int stride = Math.max(1, (context.locationCount + 9999) / 10000);
    for (List<LocationPoint> segment : context.segments) {
      List<Point> coords = new ArrayList<>();
      for (int i = 0; i < segment.size(); i++)
        if (i % stride == 0 || i == segment.size() - 1) {
          LocationPoint p = segment.get(i);
          Point point = Point.fromLngLat(p.longitude, p.latitude);
          coords.add(point);
          points.add(Feature.fromGeometry(point));
        }
      if (coords.size() > 1) lines.add(Feature.fromGeometry(LineString.fromLngLats(coords)));
    }
    int green = Ui.color(getContext(), androidx.appcompat.R.attr.colorPrimary);
    style.addSource(new GeoJsonSource("echo-route", FeatureCollection.fromFeatures(lines)));
    style.addLayer(
        new LineLayer("echo-route-line", "echo-route")
            .withProperties(
                lineColor(green),
                lineWidth(4f),
                lineOpacity(.9f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)));
    style.addSource(new GeoJsonSource("echo-fixes", FeatureCollection.fromFeatures(points)));
    style.addLayer(
        new CircleLayer("echo-fix-dots", "echo-fixes")
            .withProperties(circleColor(green), circleRadius(2f)));
    marker(style, "echo-start", context.firstLocation, green);
    if (context.locationCount > 1)
      marker(
          style,
          "echo-end",
          context.lastLocation,
          Ui.color(getContext(), com.google.android.material.R.attr.colorOnSurface));
    if (!fallback) {
      status.setText("");
      status.setVisibility(GONE);
    }
    if (!restoreCamera || fallback) view.post(this::fit);
  }

  private void marker(Style style, String id, LocationPoint point, int color) {
    style.addSource(new GeoJsonSource(id, Point.fromLngLat(point.longitude, point.latitude)));
    style.addLayer(
        new CircleLayer(id + "-circle", id)
            .withProperties(
                circleColor(color),
                circleRadius(6f),
                circleStrokeColor(
                    Ui.color(getContext(), com.google.android.material.R.attr.colorSurface)),
                circleStrokeWidth(2f)));
  }

  private static double mercator(double latitude) {
    double sin = Math.sin(Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, latitude))));
    return .5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
  }

  private void fit() {
    if (map == null || view == null || destroyed) return;
    double minY = 1, maxY = 0;
    List<Double> longitudes = new ArrayList<>();
    for (List<LocationPoint> segment : context.segments)
      for (LocationPoint p : segment) {
        double y = mercator(p.latitude);
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
        longitudes.add((p.longitude + 360) % 360);
      }
    if (longitudes.isEmpty()) return;
    Collections.sort(longitudes);
    double gap = -1, west = longitudes.get(0);
    for (int i = 0; i < longitudes.size(); i++) {
      double next = i + 1 < longitudes.size() ? longitudes.get(i + 1) : longitudes.get(0) + 360;
      if (next - longitudes.get(i) > gap) {
        gap = next - longitudes.get(i);
        west = next % 360;
      }
    }
    double span = 360 - gap, lon = (west + span / 2 + 540) % 360 - 180;
    double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (minY + maxY) / 2))));
    double width = Math.max(80, view.getWidth() / getResources().getDisplayMetrics().density - 64),
        height = 216;
    double zx = span <= 0 ? 17 : Math.log(width / (512 * span / 360)) / Math.log(2);
    double zy = maxY - minY <= 0 ? 17 : Math.log(height / (512 * (maxY - minY))) / Math.log(2);
    double zoom = Math.max(0, Math.min(16, Math.min(zx, zy)));
    map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), zoom));
    fitted = true;
  }

  void start() {
    if (view != null && !started && !destroyed) {
      view.onStart();
      started = true;
    }
  }

  void resume() {
    if (view != null && !resumed && !destroyed) {
      start();
      view.onResume();
      resumed = true;
    }
  }

  void pause() {
    if (view != null && resumed) {
      view.onPause();
      resumed = false;
    }
  }

  void stop() {
    pause();
    if (view != null && started) {
      view.onStop();
      started = false;
    }
  }

  Bundle saveState() {
    Bundle b = new Bundle();
    if (view != null && !destroyed) view.onSaveInstanceState(b);
    b.putBoolean("echo_camera_ready", fitted);
    return b;
  }

  void destroy() {
    main.removeCallbacks(mapTimeout);
    stop();
    destroyed = true;
    if (view != null) {
      view.onDestroy();
      view = null;
    }
    map = null;
  }
}
