package com.klob.echo;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

public final class ToolsActivity extends AppCompatActivity {
  private SaidItService service;
  private TextView status, live;
  private String lastMessage = "";
  private EditText words, note;
  private android.widget.Button gpsButton, cameraButton, keywordButton;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private boolean bound;
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
          service = ((SaidItService.LocalBinder) b).service();
        }

        public void onServiceDisconnected(ComponentName n) {
          service = null;
        }
      };

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout body = Ui.screen(this);
    Ui.text(this, body, "Optional tools", 28);
    status = Ui.text(this, body, "", 14);
    live = Ui.text(this, body, "", 14);
    Ui.text(
        this,
        body,
        "GPS, camera and keyword-alert preferences are saved for future recording sessions. STOP"
            + " pauses the tools without changing your choices. Screen sharing still needs Android"
            + " consent for each session.",
        14);
    gpsButton =
        Ui.button(
            this,
            body,
            "GPS TRACE",
            () ->
                toggleCaptureTool(
                    "gps",
                    31,
                    new String[] {
                      Manifest.permission.ACCESS_FINE_LOCATION,
                      Manifest.permission.ACCESS_COARSE_LOCATION
                    }));
    cameraButton =
        Ui.button(
            this,
            body,
            "SHAKE CAMERA",
            () -> toggleCaptureTool("camera", 32, new String[] {Manifest.permission.CAMERA}));
    Ui.button(
        this,
        body,
        "CAPTURE CAMERA CLIP NOW",
        () -> {
          if (ready()) service.tools.camera.captureNow();
        });
    Ui.text(
        this,
        body,
        "Shake: four swings, then settle back to your prior motion. Rear camera only // silent"
            + " 5-second clips. Camera + microphone support varies by device.",
        14);
    Ui.button(
        this,
        body,
        "TOGGLE SCREENSHOTS",
        () -> {
          if (ready()) {
            if (service.tools.screen.isRunning()) service.tools.stopScreen();
            else
              startActivityForResult(
                  getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 33);
          }
        });
    Ui.button(
        this,
        body,
        "SCREENSHOT NOW",
        () -> {
          if (ready()) service.tools.screen.captureNow();
        });
    Ui.text(this, body, "Audio source", 22);
    Ui.button(this, body, "CAPTURE DEVICE AUDIO", this::requestPlayback);
    Ui.button(
        this,
        body,
        "USE MICROPHONE",
        () -> {
          if (ready())
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("Switch to the microphone and start a new history?")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SWITCH", (d, w) -> service.useMicrophoneCapture())
                .show();
        });
    Ui.text(
        this,
        body,
        "Device audio needs Android screen-capture consent and permission from the playing app."
            + " Switching starts a new history. Screenshot capture must be stopped first.",
        14);
    Ui.text(this, body, "Offline keyword alerts", 22);
    words = new EditText(this);
    words.setHint("Keywords, separated by commas");
    words.setSingleLine(true);
    words.setText(getSharedPreferences(SaidItService.PREFS, 0).getString("keywords", ""));
    body.addView(words);
    Ui.text(
        this,
        body,
        "The optional US-English Vosk model downloads about 40 MB and uses substantial additional"
            + " RAM. Audio recognition stays on the device. Model // Apache-2.0.",
        14);
    Ui.button(
        this,
        body,
        "INSTALL ENGLISH MODEL",
        () -> {
          status.setText("Downloading and installing speech model…");
          Context app = getApplicationContext();
          io.execute(
              () -> {
                try {
                  ModelInstaller.install(app);
                  runOnUiThread(
                      () -> {
                        status.setText("Speech model installed.");
                        if (service != null) service.tools.restoreConfigured();
                      });
                } catch (IOException e) {
                  runOnUiThread(
                      () ->
                          status.setText(
                              "Model installation failed; audio recording is unaffected."));
                }
              });
        });
    keywordButton =
        Ui.button(
            this,
            body,
            "KEYWORD ALERTS",
            () -> {
              if (service == null) return;
              try {
                service.tools.setEnabled(
                    "keywords",
                    !service.tools.wanted("keywords"),
                    words.getText().toString().trim());
              } catch (IllegalArgumentException e) {
                words.setError(e.getMessage());
                words.requestFocus();
              }
            });
    Ui.text(this, body, "Notes and surveys", 22);
    note = new EditText(this);
    note.setHint("Write a note");
    note.setMaxLines(6);
    body.addView(note);
    Ui.button(
        this,
        body,
        "SAVE NOTE",
        () -> {
          if (service != null && !note.getText().toString().trim().isEmpty()) {
            service.tools.note(note.getText().toString());
            note.setText("");
          }
        });
    Ui.button(
        this,
        body,
        "IMPORT SURVEY JSON",
        () ->
            startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE),
                34));
    Ui.button(
        this, body, "FILL SURVEY", () -> startActivity(new Intent(this, SurveyActivity.class)));
    Ui.button(
        this,
        body,
        "RECORDINGS AND TRACES",
        () -> startActivity(new Intent(this, RecordingsActivity.class)));
    Ui.text(this, body, "Optional server sync", 22);
    EditText destination = new EditText(this);
    destination.setHint("HTTPS server base URL");
    destination.setSingleLine(true);
    destination.setText(getSharedPreferences(SaidItService.PREFS, 0).getString("sync_url", ""));
    body.addView(destination);
    EditText token = new EditText(this);
    token.setHint("Server token // leave blank to keep saved token");
    token.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    body.addView(token);
    com.google.android.material.switchmaterial.SwitchMaterial automatic =
        new com.google.android.material.switchmaterial.SwitchMaterial(this);
    automatic.setText("Sync automatically while listening");
    automatic.setChecked(getSharedPreferences(SaidItService.PREFS, 0).getBoolean("sync", false));
    body.addView(automatic);
    Ui.text(
        this,
        body,
        "Sends saved recordings, traces and filename metadata to your server. Local originals stay"
            + " on this device. Retries run every 15 minutes while listening.",
        14);
    Ui.button(
        this,
        body,
        "SAVE SYNC SETTINGS",
        () -> {
          try {
            String base = TraceSync.endpoint(destination.getText().toString());
            String value = token.getText().toString();
            if (!value.isEmpty()) SyncCredentials.set(this, value);
            token.setText("");
            getSharedPreferences(SaidItService.PREFS, 0)
                .edit()
                .putString("sync_url", base)
                .putBoolean("sync", automatic.isChecked())
                .apply();
            status.setText("Sync settings saved.");
          } catch (Exception e) {
            status.setText("Use an HTTPS base URL and a token of at least 32 characters.");
          }
        });
    Ui.button(
        this,
        body,
        "SYNC NOW",
        () -> {
          if (service != null) service.tools.sync.sync();
        });
    Ui.button(
        this,
        body,
        "SHARE DIAGNOSTICS",
        () -> {
          if (service != null)
            startActivity(
                Intent.createChooser(
                    new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, service.diagnosticReport()),
                    "Share diagnostics"));
        });
    Ui.button(
        this,
        body,
        "LICENSES",
        () -> {
          try (InputStream in = getAssets().open("licenses/NOTICE.txt")) {
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            TextView text = new TextView(this);
            text.setPadding(24, 16, 24, 16);
            text.setText(
                new String(Io.readBytes(in, 131072), java.nio.charset.StandardCharsets.UTF_8));
            text.setTextIsSelectable(true);
            scroll.addView(text);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sources and licenses")
                .setView(scroll)
                .setPositiveButton("CLOSE", null)
                .show();
          } catch (IOException e) {
            status.setText("License information unavailable.");
          }
        });
    Ui.button(this, body, "BACK", this::finish);
  }

  void requestPlayback() {
    if (service != null && service.isPlaybackCapture()) {
      status.setText("Device audio is already selected.");
      return;
    }
    if (ready())
      new androidx.appcompat.app.AlertDialog.Builder(this)
          .setMessage(
              "Start a new history using device audio? Only apps that allow playback capture can be"
                  + " recorded.")
          .setNegativeButton("CANCEL", null)
          .setPositiveButton(
              "CONTINUE",
              (d, w) ->
                  startActivityForResult(
                      getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),
                      35))
          .show();
  }

  private void toggleCaptureTool(String tool, int request, String[] permissions) {
    if (service == null) return;
    boolean enabled = !service.tools.wanted(tool);
    service.tools.setEnabled(tool, enabled, "");
    if (enabled && checkSelfPermission(permissions[0]) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(permissions, request);
  }

  private boolean ready() {
    if (service == null || !service.isSessionActive()) {
      status.setText("Start listening on the main screen first.");
      return false;
    }
    return true;
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(code, permissions, grants);
    if (service == null || grants.length == 0 || grants[0] != PackageManager.PERMISSION_GRANTED)
      return;
    if (code == 31 || code == 32) service.tools.restoreConfigured();
  }

  @Override
  protected void onActivityResult(int code, int result, Intent data) {
    super.onActivityResult(code, result, data);
    if (result != RESULT_OK || data == null) return;
    if (code == 35 && service != null) service.usePlaybackCapture(result, data);
    if (code == 33 && service != null) service.tools.screen(result, data);
    if (code == 34 && data.getData() != null) {
      android.net.Uri uri = data.getData();
      io.execute(
          () -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
              if (in == null) throw new IOException();
              byte[] bytes = Io.readBytes(in, 65536);
              if (bytes.length > 65536) throw new IOException();
              String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
              Survey.parse(json);
              File partial = new File(getFilesDir(), "survey.json.part");
              Io.write(partial.toPath(), json);
              Files.move(
                  partial.toPath(),
                  new File(getFilesDir(), "survey.json").toPath(),
                  StandardCopyOption.ATOMIC_MOVE,
                  StandardCopyOption.REPLACE_EXISTING);
              runOnUiThread(() -> status.setText("Survey imported."));
            } catch (Exception e) {
              runOnUiThread(
                  () ->
                      status.setText(
                          "Survey must be valid JSON, under 64 KB, with valid question branches."));
            }
          });
    }
  }

  private final Runnable update =
      new Runnable() {
        public void run() {
          if (service != null) {
            Ui.setText(gpsButton, "GPS TRACE // " + (service.tools.wanted("gps") ? "ON" : "OFF"));
            Ui.setText(
                cameraButton, "SHAKE CAMERA // " + (service.tools.wanted("camera") ? "ON" : "OFF"));
            Ui.setText(
                keywordButton,
                "KEYWORD ALERTS // " + (service.tools.wanted("keywords") ? "ON" : "OFF"));
            Ui.setText(
                live,
                service.captureState
                    + " // "
                    + service.tools.summary()
                    + "\nKeyword model // "
                    + service.tools.keywords.getStatus());
            if (!service.message.equals(lastMessage)) {
              lastMessage = service.message;
              status.setText(lastMessage);
            }
          }
          main.postDelayed(this, 1000);
        }
      };

  @Override
  protected void onStart() {
    super.onStart();
    bound = bindService(new Intent(this, SaidItService.class), connection, BIND_AUTO_CREATE);
    main.post(update);
  }

  @Override
  protected void onStop() {
    main.removeCallbacks(update);
    if (bound) unbindService(connection);
    service = null;
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    io.shutdown();
    super.onDestroy();
  }
}
