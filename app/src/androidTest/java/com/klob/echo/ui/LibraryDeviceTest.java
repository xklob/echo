package com.klob.echo.ui;

import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.navigation.NavigationBarView;
import com.klob.echo.R;
import com.klob.echo.library.*;
import com.klob.echo.storage.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LibraryDeviceTest {
  private Context app, isolated;
  private File sandbox;
  private final List<File> owned = new ArrayList<>();
  private static final long START = 1794506400123L;

  @Before
  public void setup() throws Exception {
    app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    sandbox = Files.createTempDirectory(app.getCacheDir().toPath(), "library-test-").toFile();
    isolated =
        new ContextWrapper(app) {
          @Override
          public File getFilesDir() {
            return sandbox;
          }

          @Override
          public Context getApplicationContext() {
            return this;
          }
        };
  }

  @After
  public void cleanup() throws Exception {
    for (File f : owned) {
      f.delete();
      new File(f.getPath() + ".json").delete();
      TraceItem.metadata(f).delete();
    }
    try (java.util.stream.Stream<Path> paths = Files.walk(sandbox.toPath())) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  private File clip(Context context, String name, long start) throws Exception {
    File directory = new File(new ClipStore(context).root, "manual");
    directory.mkdirs();
    File f = new File(directory, name + ".wav");
    int samples = 48000 * 10;
    ByteBuffer wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
    wav.put("RIFF".getBytes())
        .putInt(36 + samples * 2)
        .put("WAVEfmt ".getBytes())
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(48000)
        .putInt(96000)
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes())
        .putInt(samples * 2);
    for (int i = 0; i < samples; i++)
      wav.putShort((short) (500 * Math.sin(i * 440 * 2 * Math.PI / 48000)));
    Files.write(f.toPath(), wav.array());
    Io.write(
        new File(f.getPath() + ".json").toPath(),
        new JSONObject()
            .put("start_time_ms", start)
            .put("captured_samples", samples)
            .put("sample_rate", 48000)
            .put("source", "microphone")
            .put("kind", "manual")
            .toString());
    owned.add(f);
    return f;
  }

  private File trace(Context context, long time, String suffix, String text) throws Exception {
    File f = new File(new TraceStore(context).directory(), TraceStore.timestampName(time) + suffix);
    Io.write(f.toPath(), text);
    owned.add(f);
    return f;
  }

  private String point(long time, double lat, double lon) {
    return "<trkpt lat=\""
        + lat
        + "\" lon=\""
        + lon
        + "\"><time>"
        + Instant.ofEpochMilli(time)
        + "</time></trkpt>";
  }

  private File track(Context context, String points) throws Exception {
    return trace(
        context,
        START,
        "_route.gpx",
        "<?xml version=\"1.0\"?><gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg>"
            + points
            + "</trkseg></trk></gpx>");
  }

  private void await(java.util.function.BooleanSupplier check) throws Exception {
    long end = SystemClock.elapsedRealtime() + 15000;
    while (!check.getAsBoolean() && SystemClock.elapsedRealtime() < end) Thread.sleep(40);
    assertTrue(check.getAsBoolean());
  }

  @Test
  public void audioOnlyLibraryJoinsExactIntervalsAndPreservesSharedTraces() throws Exception {
    File first = clip(isolated, "First", START), second = clip(isolated, "Second", START + 5000);
    File inside = trace(isolated, START + 6000, "_note.txt", "Shared note");
    trace(isolated, START + 10000, "_note.txt", "At end of first clip");
    trace(isolated, START - 1, "_note.txt", "Before both clips");
    trace(isolated, START, "_survey.json", "{}");
    File route =
        track(
            isolated,
            point(START - 1, 37.5, -77.4)
                + point(START, 37.501, -77.4)
                + point(START + 6000, 37.502, -77.4)
                + point(START + 10000, 37.503, -77.4));
    RecordingRepository repository = new RecordingRepository(isolated);
    List<RecordingContext> rows = repository.recordings();
    assertEquals(2, rows.size());
    assertEquals(second, rows.get(0).recording.file);
    RecordingContext context = repository.recording(first);
    assertEquals(2, context.locationCount);
    assertEquals(START, context.firstLocation.time);
    assertEquals(START + 6000, context.lastLocation.time);
    assertEquals(2, context.attachments.size());
    assertEquals(2, rows.get(0).attachments.size());
    assertTrue(context.attachments.stream().anyMatch(t -> t.file.equals(inside)));
    assertTrue(first.delete());
    assertEquals(1, repository.recordings().size());
    assertTrue(inside.isFile());
    assertTrue(route.isFile());
  }

  @Test
  public void missingMetadataDoesNotInventLocationOrEventLinks() throws Exception {
    File audio = clip(isolated, "Without metadata", START);
    new File(audio.getPath() + ".json").delete();
    audio.setLastModified(START);
    trace(isolated, START + 1000, "_note.txt", "Unrelated until timing is known");
    track(isolated, point(START + 1000, 37.5, -77.4));
    RecordingContext context = new RecordingRepository(isolated).recording(audio);
    assertNull(context.recording.window);
    assertTrue(context.recording.duration > 0);
    assertEquals(0, context.locationCount);
    assertTrue(context.attachments.isEmpty());
    assertNull(new RecordingRepository(isolated).recording(new File(sandbox, "../outside.wav")));
  }

  @Test
  public void gpsKeepsMillisecondTimesAndDoesNotBridgeMissingFixes() throws Exception {
    File route =
        track(
            isolated,
            point(START, 37.5, -77.4)
                + point(START + 1000, 37.6, -77.4)
                + point(START + 2000, 91, -77.4)
                + point(START + 3000, 37.7, -77.4)
                + "</trkseg><trkseg>"
                + point(START + 4000, 37.8, -77.4)
                + point(START + 65001, 37.9, -77.4));
    List<List<LocationPoint>> segments = GpxReader.read(route);
    assertEquals(4, segments.size());
    assertEquals(2, segments.get(0).size());
    assertEquals(START, segments.get(0).get(0).time);
    assertEquals(START + 3000, segments.get(1).get(0).time);
    assertEquals(START + 65001, segments.get(3).get(0).time);
  }

  @Test
  public void unsafeAndMalformedGpxFailWithoutHidingAudio() throws Exception {
    File audio = clip(isolated, "Readable audio", START);
    File route = track(isolated, point(START + 1000, 37.5, -77.4));
    Io.write(
        route.toPath(),
        "<!DOCTYPE gpx [<!ENTITY secret SYSTEM 'file:///etc/hosts'>]><gpx>&secret;</gpx>");
    assertThrows(IOException.class, () -> GpxReader.read(route));
    RecordingContext data = new RecordingRepository(isolated).recording(audio);
    assertTrue(data.unreadableLocations);
    assertEquals(audio, data.recording.file);
    Io.write(route.toPath(), "<gpx><trk>");
    assertThrows(IOException.class, () -> GpxReader.read(route));
  }

  @Test
  public void renamedTraceKeepsOriginalTimeAndTypeAndMetadataIsHidden() throws Exception {
    File note = trace(app, START + 6000, "_keyword-fixture.txt", "12:00 // remember");
    File renamed = new File(note.getParentFile(), "Renamed keyword fixture.txt");
    owned.add(renamed);
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      activity.onActivity(
          a ->
              new ViewModelProvider(a)
                  .get(LibraryModel.class)
                  .rename(note, "Renamed keyword fixture"));
      await(() -> renamed.isFile() && TraceItem.metadata(renamed).isFile());
      assertFalse(note.exists());
      TraceItem saved = new TraceItem(renamed);
      assertEquals(START + 6000, saved.time);
      assertEquals("keyword", saved.type);
      assertEquals("remember", saved.preview);
      assertFalse(new TraceStore(app).list().contains(TraceItem.metadata(renamed)));
    }
  }

  @Test
  public void libraryCardsOpenDetailWithPlayerMapAndContextAfterRecreation() throws Exception {
    File audio = clip(app, "Library UI fixture", START);
    trace(app, START + 1000, "_note.txt", "Library detail fixture note");
    track(app, point(START + 1000, 37.54, -77.44) + point(START + 3000, 37.541, -77.439));
    try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
      main.onActivity(
          a ->
              ((NavigationBarView) a.findViewById(R.id.navigation))
                  .setSelectedItemId(R.id.nav_library));
      AtomicBoolean loaded = new AtomicBoolean();
      await(
          () -> {
            main.onActivity(
                a -> {
                  LibraryModel model = new ViewModelProvider(a).get(LibraryModel.class);
                  List<LibraryModel.Item> rows = model.items.getValue();
                  loaded.set(rows != null && rows.stream().anyMatch(i -> i.file.equals(audio)));
                  if (loaded.get()) assertTrue(rows.stream().allMatch(i -> i.audio));
                });
            return loaded.get();
          });
      main.onActivity(
          a -> {
            RecyclerView list = a.findViewById(R.id.library_list);
            LibraryModel model = new ViewModelProvider(a).get(LibraryModel.class);
            List<LibraryModel.Item> rows = model.items.getValue();
            for (int i = 0; i < rows.size(); i++)
              if (rows.get(i).file.equals(audio)) {
                assertTrue(rows.get(i).contextText.contains("GPS"));
                assertTrue(rows.get(i).preview.contains("Library detail fixture note"));
                list.scrollToPosition(i);
              }
          });
    }
    Intent intent =
        DetailActivity.intent(app, "clip", R.string.clip_details).putExtra("file", audio.getPath());
    try (ActivityScenario<DetailActivity> detail = ActivityScenario.launch(intent)) {
      AtomicBoolean ready = new AtomicBoolean();
      await(
          () -> {
            detail.onActivity(
                a -> {
                  View play = a.findViewById(R.id.clip_play_pause);
                  ready.set(play != null && play.isEnabled());
                });
            return ready.get();
          });
      detail.onActivity(
          a -> {
            assertNotNull(a.findViewById(R.id.clip_map));
            assertNotNull(a.findViewById(R.id.clip_context));
            a.findViewById(R.id.clip_play_pause).performClick();
          });
      detail.recreate();
      ready.set(false);
      await(
          () -> {
            detail.onActivity(
                a ->
                    ready.set(
                        a.findViewById(R.id.clip_map) != null
                            && a.findViewById(R.id.clip_player) != null));
            return ready.get();
          });
      // Renaming the open recording updates both the visible detail and restored path.
      File renamed = new File(audio.getParentFile(), "Renamed audio fixture.wav");
      owned.add(renamed);
      detail.onActivity(
          a ->
              new ViewModelProvider(a)
                  .get(LibraryModel.class)
                  .rename(audio, "Renamed audio fixture"));
      await(() -> renamed.isFile() && new File(renamed.getPath() + ".json").isFile());
      detail.recreate();
      ready.set(false);
      await(
          () -> {
            detail.onActivity(
                a -> {
                  LibraryModel.Item shown =
                      new ViewModelProvider(a).get(LibraryModel.class).detail.getValue();
                  ready.set(shown != null && shown.file.equals(renamed));
                });
            return ready.get();
          });
      detail.onActivity(a -> new ViewModelProvider(a).get(LibraryModel.class).delete(renamed));
      await(() -> !renamed.exists());
      assertTrue(
          new TraceStore(app).list().stream().anyMatch(f -> f.getName().endsWith("_route.gpx")));
    }
  }
}
